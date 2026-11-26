import http from 'node:http';
import fs from 'node:fs/promises';
import { createReadStream } from 'node:fs';
import { gzipSync } from 'node:zlib';
import path from 'node:path';
import os from 'node:os';
import { execFile, spawn } from 'node:child_process';
import { promisify } from 'node:util';
import { createInterface } from 'node:readline';
import { createHmac, randomUUID, timingSafeEqual } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const appDir = path.dirname(fileURLToPath(import.meta.url));
const stateDir = process.env.CODEX_MOBILE_STATE || path.join(os.homedir(), '.local/state/codex-mobile');
const workspace = process.env.CODEX_MOBILE_WORKSPACE || '/sdcard/codex-android/arquivos do chat';
const internalWorkspace = process.env.CODEX_MOBILE_INTERNAL_WORKSPACE || workspace;
const port = Number(process.env.CODEX_MOBILE_PORT || 41739);
await fs.mkdir(stateDir, { recursive: true, mode: 0o700 });
const embedded = process.env.CODEX_MOBILE_EMBEDDED === '1';
const deviceControlEnabled = embedded && process.env.CODEX_MOBILE_DEVICE_CONTROL !== '0';
const token = (process.env.CODEX_MOBILE_TOKEN || await fs.readFile(path.join(stateDir, 'token'), 'utf8')).trim();
delete process.env.CODEX_MOBILE_TOKEN;
if (!/^[a-f\d]{64}$/.test(token)) throw new Error('Token local inválido.');
const store = path.join(stateDir, 'chats.json');
const segmentedStore = process.env.CODEX_MOBILE_CHATSTORE;
const chatDirectory = path.join(stateDir, 'chats-v2');
const chats = new Map();
const summaries = new Map();
const loadingChats = new Map();
const itemVersions = new WeakMap();
let version = 0;
function restore(chat) {
  if (chat.deleted) {
    chats.delete(chat.id);
    return;
  }
  chat.running = false;
  if (chat.inFlight || chat.turn) {
    chat.turn = null;
    delete chat.inFlight;
    delete chat.turnStartedMs;
    item(chat, randomUUID(), 'notice', { text: 'O Ubuntu reiniciou durante a resposta.' });
  }
  chats.set(chat.id, chat);
}
async function readLegacy() {
  try { return JSON.parse(await fs.readFile(store, 'utf8')); }
  catch (error) {
    if (error.code !== 'ENOENT') throw error;
    return [];
  }
}
if (segmentedStore) {
  const { stdout } = await promisify(execFile)(segmentedStore, ['--index', chatDirectory], {
    maxBuffer: 32 * 1024 * 1024,
  });
  for (const row of JSON.parse(stdout)) {
    if (!row.deleted) summaries.set(row.id, row);
  }
} else {
  for (const chat of await readLegacy()) {
    restore(chat);
    if (!chat.deleted) summaries.set(chat.id, chat);
  }
}

function updateSummary(chat) {
  if (chat.deleted) {
    summaries.delete(chat.id);
  } else {
    const { id, title, cwd, createdAt, pinned, archived } = chat;
    summaries.set(id, { id, title, cwd, createdAt, pinned, archived });
  }
}

const stored = (key, value) => key === 'running' ? undefined : value;
let writeQueue = Promise.resolve();
let writer;
function storageWorker() {
  if (writer?.stdin.writable && writer.exitCode === null) return writer;
  const process = spawn(segmentedStore, [chatDirectory], { stdio: ['pipe', 'pipe', 'ignore'] });
  process.stdin.on('error', () => {});
  process.on('error', () => {});
  return (writer = process);
}
function writeDocument(id, body) {
  const process = storageWorker();
  return new Promise((resolve, reject) => {
    let response = '';
    const halt = () => settle(new Error('Falha ao gravar conversa no armazenamento local.'));
    const read = chunk => {
      response += chunk.toString();
      if (response.includes('\n')) settle(response.trim() === 'ok' ? null :
        new Error('Falha ao gravar conversa no armazenamento local.'));
    };
    function settle(error) {
      process.stdout.off('data', read);
      process.off('exit', halt);
      process.off('error', halt);
      if (error) reject(error);
      else resolve();
    }
    process.stdout.on('data', read);
    process.once('exit', halt);
    process.once('error', halt);
    process.stdin.write(id + '\t' + body + '\n', error => { if (error) halt(); });
  });
}
function save(changed) {
  const records = segmentedStore ? (Array.isArray(changed) ? changed : [changed]).map(chat =>
    [chat.id, JSON.stringify(chat, stored)]) : null;
  const legacy = records ? null : JSON.stringify([...chats.values()], stored);
  writeQueue = writeQueue.catch(() => {}).then(async () => {
    if (records) {
      for (const [id, body] of records) await writeDocument(id, body);
    } else {
      await fs.writeFile(store + '.tmp', legacy, { mode: 0o600 });
      await fs.rename(store + '.tmp', store);
    }
    for (const chat of Array.isArray(changed) ? changed : [changed]) updateSummary(chat);
  });
  return writeQueue;
}

let child, connecting, sequence = 0;
const pending = new Map();
const loaded = new Set();
const approvals = new Map();
const buffer = new Map();
const androidTools = embedded ? [
  { type: 'function', name: 'credential_shell', description: 'Use a named credential. For an HTTPS GET, set command to the HTTPS URL. When the user explicitly asks to save the secret in a file, set command to file: followed by a relative path inside the project, for example file:token.txt. The app requests password approval for the exact destination and creates a new file without overwriting. Never ask to reveal the secret in chat.', inputSchema: {
    type: 'object', properties: { credential: { type: 'string' }, command: { type: 'string' } }, required: ['credential','command'], additionalProperties: false
  } },
  { type: 'function', name: 'termux_shell', description: 'Run Bash in the separately installed Android Termux app only when explicitly needed. Ubuntu is the default for ordinary work. In YOLO mode, approved read-only diagnostics run directly; other commands require confirmation.', inputSchema: {
    type: 'object', properties: { command: { type: 'string' }, cwd: { type: 'string', description: 'Optional absolute Termux working directory; defaults to its home.' } }, required: ['command'], additionalProperties: false
  } },
  { type: 'function', name: 'device_control', description: 'Control the Android screen only after the user authorizes this chat. Request screenshot to see the current screen; use tap, long_press, swipe, back, home or open_app to interact. Coordinates use screenshot pixels. Take a fresh screenshot after actions.', inputSchema: {
    type: 'object', properties: { action: { type: 'string', enum: ['screenshot','tap','long_press','swipe','back','home','open_app'] }, x: { type: 'integer' }, y: { type: 'integer' }, x2: { type: 'integer' }, y2: { type: 'integer' }, duration: { type: 'integer' }, package: { type: 'string' } }, required: ['action'], additionalProperties: false
  } },
  { type: 'function', name: 'adb_shell', description: 'Run a command in Android adb shell through Shizuku (shell UID 2000). Use the shell command itself, without the adb shell prefix. In YOLO mode, approved read-only diagnostics run directly; other commands require confirmation.', inputSchema: {
    type: 'object', properties: { command: { type: 'string' } }, required: ['command'], additionalProperties: false
  } }
] : [];
if (embedded && !deviceControlEnabled) androidTools.splice(androidTools.findIndex(tool => tool.name === 'device_control'), 1);
const bridgeCalls = new Map();
const yoloTurns = new Set();
const deletionConsent = new Map();

function exactDeletionTargets(text) {
  const request = text.match(/^(?:por favor,? +)?(?:apague|apaga|exclua|exclui|remova|remove|delete) +(.+)$/iu);
  if (!request) return [];
  const single = request[1].match(/^(?:o arquivo |a pasta )?([A-Za-z0-9_.-]+)$/u);
  if (single) return single[1] === '.' || single[1] === '..' || single[1].startsWith('-') ? [] : [single[1]];
  const names = [...request[1].matchAll(/`([^`]+)`/g)].map(match => match[1]);
  const outside = request[1].replace(/`[^`]+`/g, '').replace(/\b(os|as|o|a|arquivos|arquivo|pasta|pastas|e)\b/gi, '');
  if (outside.replace(/[,. \t]/g, '')) return [];
  return names.filter(name => /^(?!-)(?!\.{1,2}$)[\p{L}\p{N}_. -]+$/u.test(name));
}

function previouslyAuthorizedDeletion(chat, params) {
  const targets = deletionConsent.get(chat.id);
  if (!targets?.length || params.cwd !== chat.cwd || typeof params.command !== 'string' ||
    params.additionalPermissions || params.networkApprovalContext) return false;
  const wrapped = params.command.match(/^(?:(?:\/usr)?\/bin\/)?bash -lc "([^"\\$`]+)"$/u);
  const command = wrapped?.[1] ?? params.command;
  const match = command.match(/^rm(?: +(?:-[rfRF]+|--recursive|--force))* +-- +(.+)$/u);
  if (!match) return false;
  const args = match[1].match(/'[^']+'|[^ ]+/g) || [];
  if (!args.length || args.join(' ') !== match[1]) return false;
  return args.every(arg => {
    const name = arg.startsWith("'") && arg.endsWith("'") ? arg.slice(1, -1) : arg;
    return targets.includes(name);
  });
}
const diagnostics = new Set([
  'id', 'pwd', 'uname', 'uname -a', 'uname -m', 'df -h', 'df -h /sdcard', 'df -h /data',
  'getprop ro.product.model', 'getprop ro.product.manufacturer', 'getprop ro.build.version.release',
  'getprop ro.build.version.sdk', 'getprop ro.product.cpu.abi', 'dumpsys battery'
]);

function routineAndroidQuery(command, cwd, target) {
  if (cwd != null || /[\r\n]/.test(command)) return null;
  const queries = command.split('&&').map(part => part.trim().replace(/[ \t]+/g, ' '));
  const allowed = queries.length <= 5 &&
    queries.every(query => diagnostics.has(query) || (target === 'Termux' && query === 'termux-battery-status'));
  return allowed ? queries.join(' && ') : null;
}

function dynamicResult(id, success, text, image) {
  send({ id, result: { success, contentItems: [{ type: 'inputText', text }, ...(image ? [{type: 'inputImage', imageUrl: image}] : [])] } });
}

function finishBridge(key, result) {
  const call = bridgeCalls.get(key);
  if (!call) return;
  const { request, chat } = call;
  clearTimeout(call.timer);
  call.abort?.abort();
  bridgeCalls.delete(key);
  approvals.delete(key);
  const output = [result.stdout, result.stderr, result.error].filter(value => typeof value === 'string' && value).join('\n');
  if (call.running) item(chat, key, 'command', {
    durationMs: Math.max(0, Date.now() - call.startedAt),
    environment: call.target, status: result.interrupted ? 'interrupted' : result.exit === 0 && !result.error ? 'completed' : 'failed',
    exit: result.exit, output: output.slice(-24000), truncated: output.length > 24000 || result.truncated === true
  });
  dynamicResult(request, result.exit === 0 && !result.error,
    [result.exit == null ? null : 'Código de saída: ' + result.exit, output || '(sem saída)'].filter(Boolean).join('\n').slice(0, 105000),
    call.target === 'Controle do dispositivo' && typeof result.image === 'string' && /^data:image\/jpeg;base64,[A-Za-z0-9+/=]+$/.test(result.image) ? result.image : null);
  touch();
}
let login = null;
let loginOutcome = null;
const touch = () => { version++; };
function send(message) {
  if (!child?.stdin.writable) throw new Error('Codex desconectado.');
  child.stdin.write(JSON.stringify(message) + '\n');
}
function rpc(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++sequence;
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error('O Codex demorou para responder: ' + method));
    }, 60000);
    timer.unref();
    pending.set(id, (error, result) => {
      clearTimeout(timer);
      if (error) reject(Object.assign(new Error(error.message), { status: error.status }));
      else resolve(result);
    });
    try { send({ id, method, params }); }
    catch (error) {
      pending.delete(id);
      clearTimeout(timer);
      reject(error);
    }
  });
}
function item(chat, id, type, values) {
  let message = chat.messages.find(message => message.id === id);
  if (!message) {
    message = { id, type };
    chat.messages.push(message);
  }
  Object.assign(message, values);
  itemVersions.set(message, version + 1);
  return message;
}
function notify({ method, params: p = {}, id }) {
  if (method === 'account/login/completed') {
    if (login?.loginId === p.loginId || !login) {
      login = null;
      loginOutcome = { success: p.success, error: p.error };
      touch();
    }
    return;
  }
  const chat = [...chats.values()].find(chat => chat.thread === p.threadId);
  if (id !== undefined) {
    if (method === 'item/tool/call') {
      const credentialCall = p.tool === 'credential_shell';
      const deviceCall = p.tool === 'device_control' || (p.tool === 'adb_shell' &&
        typeof p.arguments?.command === 'string' && p.arguments.command.startsWith('codex-device:'));
      if (deviceCall && !deviceControlEnabled) { dynamicResult(id, false, 'Controle do dispositivo não está disponível nesta edição.'); return; }
      const target = deviceCall ? 'Controle do dispositivo' : credentialCall ? 'Ubuntu · Credencial' : p.tool === 'adb_shell' ? 'ADB shell' : p.tool === 'termux_shell' ? 'Termux' : null;
      const { cwd, credential } = p.arguments || {};
      const command = p.tool === 'device_control' ? JSON.stringify(p.arguments || {}) :
        deviceCall ? p.arguments.command.slice('codex-device:'.length) : p.arguments?.command;
      const alias = typeof credential === 'string' ? credential.trim().replace(/^\+/, '').toLowerCase() : '';
      if (!chat?.running || !target || !androidTools.length || typeof command !== 'string' ||
        !command.trim() || command.length > 12000 || command.includes('\0') ||
        (credentialCall && !/^[a-z0-9][a-z0-9_.-]{0,39}$/.test(alias)) ||
        (cwd != null && (target !== 'Termux' || typeof cwd !== 'string' || !cwd.startsWith('/') || cwd.length > 1024 || cwd.includes('\0')))) {
        dynamicResult(id, false, 'Comando ou destino inválido.');
        return;
      }
      let credentialUrl;
      let credentialFile;
      if (credentialCall && command.startsWith('file:')) {
        const relative = command.slice(5);
        if (!relative || path.isAbsolute(relative) || relative.includes('\0') ||
            relative.split('/').some(part => !part || part === '.' || part === '..')) {
          dynamicResult(id, false, 'Use file: com um caminho relativo válido dentro do projeto.');
          return;
        }
        credentialFile = relative;
      } else if (credentialCall) {
        try {
          credentialUrl = new URL(command);
          if (credentialUrl.protocol !== 'https:' || credentialUrl.username || credentialUrl.password ||
            credentialUrl.hash || command !== command.trim() || /\s/.test(command)) throw Error();
        } catch {
          dynamicResult(id, false, 'Use uma URL HTTPS completa no argumento command. O cofre usa uma solicitação autenticada.');
          return;
        }
      }
      const key = 'android-' + randomUUID();
      const timer = setTimeout(() => finishBridge(key, { error: 'Execução Android expirou.' }), 150000);
      timer.unref();
      const routine = !credentialCall && !deviceCall && yoloTurns.has(chat.id) ? routineAndroidQuery(command, cwd, target) : null;
      bridgeCalls.set(key, { request: id, chat, target, command: routine ?? command, cwd, timer, alias: credentialCall ? alias : null, credentialUrl, credentialFile });
      const automatic = routine !== null;
      approvals.set(key, { id: key, chatId: chat.id, android: !credentialCall, credential: credentialCall, automatic,
        params: { command, cwd, target, ...(credentialCall ? { name: alias } : {}) } });
      touch();
      return;
    }
    const supported = ['item/commandExecution/requestApproval', 'item/fileChange/requestApproval', 'item/permissions/requestApproval'];
    if (chat && method === 'item/commandExecution/requestApproval' && previouslyAuthorizedDeletion(chat, p)) {
      send({ id, result: { decision: 'accept' } });
      return;
    }
    if (chat && supported.includes(method)) {
      approvals.set(String(id), { id, method, chatId: chat.id, params: p });
      touch();
    } else {
      send({ id, error: { code: -32601, message: 'Solicitação indisponível nesta interface.' } });
    }
    return;
  }
  if (!chat) return;
  const entry = p.item;
  switch (method) {
    case 'turn/started':
      chat.turn = p.turn.id;
      chat.turnStartedMs = Number.isFinite(p.turn.startedAt) ? p.turn.startedAt * 1000 : Date.now();
      break;
    case 'item/agentMessage/delta':
      buffer.set(p.itemId, (buffer.get(p.itemId) || '') + p.delta);
      item(chat, p.itemId, 'agent', { text: buffer.get(p.itemId) });
      break;
    case 'item/commandExecution/outputDelta': {
      const current = chat.messages.find(message => message.id === p.itemId);
      const output = (current?.output || '') + p.delta;
      item(chat, p.itemId, 'command', { output: output.slice(-24000), truncated: Boolean(current?.truncated || output.length > 24000) });
      break;
    }
    case 'item/started':
    case 'item/completed':
      if (entry.type === 'agentMessage') {
        if (entry.text || method === 'item/completed') item(chat, entry.id, 'agent', { text: entry.text });
        if (method === 'item/completed') buffer.delete(entry.id);
      }
      if (entry.type === 'commandExecution') {
        const previous = chat.messages.find(message => message.id === entry.id);
        const started = entry.startedAtMs ?? previous?.startedAt ?? (method === 'item/started' ? Date.now() : null);
        item(chat, entry.id, 'command', { text: entry.command, status: entry.status, exit: entry.exitCode,
          startedAt: started,
          durationMs: entry.durationMs ?? (method === 'item/completed' && started !== null ? Math.max(0, Date.now() - started) : null),
          ...(entry.aggregatedOutput == null ? {} : { output: entry.aggregatedOutput.slice(-24000), truncated: entry.aggregatedOutput.length > 24000 }) });
      }
      if (entry.type === 'fileChange') {
        item(chat, entry.id, 'change', { text: entry.changes.map(change => change.path).join('\n'), status: entry.status });
      }
      break;
    case 'turn/diff/updated': {
      if (typeof p.diff === 'string' && p.diff) {
        item(chat, 'diff-' + p.turnId, 'diff', { text: p.diff.slice(0, 120000), truncated: p.diff.length > 120000 });
      }
      break;
    }
    case 'thread/tokenUsage/updated':
      chat.usage = p.tokenUsage;
      break;
    case 'turn/completed': {
      const finishedAt = Number.isFinite(p.turn.completedAt) ? p.turn.completedAt * 1000 : Date.now();
      const durationMs = Number.isFinite(p.turn.durationMs) ? p.turn.durationMs :
        Number.isFinite(chat.turnStartedMs) ? Math.max(0, finishedAt - chat.turnStartedMs) : null;
      if (durationMs !== null && durationMs >= 0) {
        const id = p.turn.id || chat.turn || chat.inFlight || randomUUID();
        item(chat, 'worked-' + id, 'worked', { durationMs, finishedAt });
      }
      delete chat.turnStartedMs;
      yoloTurns.delete(chat.id);
      deletionConsent.delete(chat.id);
      chat.running = false;
      delete chat.inFlight;
      chat.turn = null;
      for (const entry of chat.messages) {
        if (entry.type === 'command' && ['inProgress', 'in_progress'].includes(entry.status)) {
          item(chat, entry.id, 'command', { status: 'interrupted',
            durationMs: Number.isFinite(entry.startedAt) ? Math.max(0, finishedAt - entry.startedAt) : null });
        }
      }
      for (const [key, call] of bridgeCalls) if (call.chat === chat) finishBridge(key, { error: 'Turno encerrado.' });
      for (const [key, request] of approvals) if (request.chatId === chat.id) approvals.delete(key);
      if (p.turn.status !== 'completed') {
        item(chat, randomUUID(), 'notice', { text: p.turn.error?.message || (p.turn.status === 'interrupted' ? 'Resposta interrompida.' : 'O Codex não concluiu a resposta.') });
      }
      save(chat).catch(console.error);
      break;
    }
    default:
      return;
  }
  touch();
}
function connect() {
  if (connecting) return connecting;
  const config = embedded
    ? ['-c', 'sandbox_mode="danger-full-access"', '-c', 'approval_policy="on-request"'] : [];
  const relay = process.env.CODEX_MOBILE_RELAY;
  const args = ['app-server', '--listen', 'stdio://', ...config];
  const subprocess = spawn(relay || 'codex', relay ? ['--relay', 'codex', ...args] : args,
    { stdio: ['pipe', 'pipe', 'inherit'] });
  child = subprocess;
  subprocess.stdin.on('error', () => {});
  const disconnect = error => {
    if (child !== subprocess) return;
    child = null;
    connecting = null;
    loaded.clear();
    approvals.clear();
    yoloTurns.clear();
    deletionConsent.clear();
    for (const call of bridgeCalls.values()) {
      clearTimeout(call.timer);
      call.abort?.abort();
    }
    bridgeCalls.clear();
    buffer.clear();
    for (const [id, finish] of pending) {
      pending.delete(id);
      finish({ message: error?.message || 'O serviço Codex foi encerrado.', status: 503 });
    }
    const interrupted = [];
    for (const chat of chats.values()) {
      if (!chat.running) continue;
      interrupted.push(chat);
      chat.running = false;
      chat.turn = null;
      delete chat.inFlight;
      delete chat.turnStartedMs;
      item(chat, randomUUID(), 'notice', { text: 'Serviço Codex desconectado.' });
    }
    touch();
    if (interrupted.length) save(interrupted).catch(console.error);
  };
  subprocess.on('error', disconnect);
  subprocess.on('close', () => disconnect());
  createInterface({ input: subprocess.stdout }).on('line', line => {
    try {
      const message = JSON.parse(line);
      if (message.method) notify(message);
      else {
        const finish = pending.get(message.id);
        pending.delete(message.id);
        finish?.(message.error, message.result);
      }
    } catch (error) { console.error(error.message); }
  });
  connecting = rpc('initialize', { clientInfo: { name: 'codex_mobile_local', title: 'Codex Mobile', version: '2.0.1' }, ...(androidTools.length ? { capabilities: { experimentalApi: true, requestAttestation: false } } : {}) })
    .then(result => {
      send({ method: 'initialized' });
      return result;
    })
    .catch(error => {
      subprocess.kill();
      disconnect(error);
      throw error;
    });
  return connecting;
}
async function bytes(request, maximum) {
  const chunks = [];
  let length = 0;
  for await (const chunk of request) {
    length += chunk.length;
    if (length > maximum) throw Object.assign(new Error('Arquivo ou mensagem grande demais.'), { status: 413 });
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}
async function directory(value) {
  const result = await fs.realpath(value);
  if (!(await fs.stat(result)).isDirectory()) throw new Error('Selecione uma pasta.');
  return result;
}
async function browseRoots(artifacts = false) {
  const candidates = [internalWorkspace, workspace, '/sdcard', '/storage'];
  if (artifacts) candidates.push('/mnt/data', path.join(os.homedir(), '.codex/generated_images'));
  const roots = await Promise.all(candidates.map(value => fs.realpath(value).catch(() => null)));
  return roots.filter(Boolean);
}
async function selectable(value, artifacts = false) {
  const item = await fs.realpath(value);
  const roots = await browseRoots(artifacts);
  if (!roots.some(root => item === root || item.startsWith(root + '/'))) {
    throw new Error('Escolha arquivos no armazenamento ou no espaço de projetos.');
  }
  return item;
}
const validId = value => typeof value === 'string' && /^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/.test(value);
const creatingChats = new Map();
async function createChat(id, requestedDirectory) {
  let cwd = requestedDirectory ? await directory(requestedDirectory) : path.join(workspace, id);
  if (!requestedDirectory) {
    try { await fs.mkdir(cwd, { recursive: true }); }
    catch (error) {
      if (workspace === internalWorkspace || !['ENOENT', 'EACCES', 'EPERM', 'EROFS'].includes(error.code)) throw error;
      cwd = path.join(internalWorkspace, id);
      await fs.mkdir(cwd, { recursive: true });
    }
  }
  let storage = workspace;
  try { await fs.mkdir(path.join(storage, id), { recursive: true }); }
  catch (error) {
    if (storage === internalWorkspace || !['ENOENT', 'EACCES', 'EPERM', 'EROFS'].includes(error.code)) throw error;
    storage = internalWorkspace;
    await fs.mkdir(path.join(storage, id), { recursive: true });
  }
  const chat = { id, cwd, storage, title: 'Nova conversa', createdAt: Date.now(), messages: [] };
  chats.set(id, chat);
  try { await save(chat); }
  catch (error) { chats.delete(id); throw error; }
  touch();
  return chat;
}

const imagePath = file => /\.(png|jpe?g|webp|gif)$/i.test(file);
async function getChat(id) {
  if (chats.has(id)) return chats.get(id);
  if (!segmentedStore || !summaries.has(id)) {
    throw Object.assign(new Error('Conversa não encontrada.'), { status: 404 });
  }
  let loading = loadingChats.get(id);
  if (!loading) {
    loading = fs.readFile(path.join(chatDirectory, id + '.json'), 'utf8').then(text => {
      const chat = JSON.parse(text);
      if (chat.id !== id || chat.deleted) {
        throw Object.assign(new Error('Conversa não encontrada.'), { status: 404 });
      }
      restore(chat);
      return chat;
    }).finally(() => loadingChats.delete(id));
    loadingChats.set(id, loading);
  }
  return loading;
}
function idle(chat) {
  if (chat.running) throw Object.assign(new Error('A conversa está em andamento.'), { status: 409 });
}
const files = {
  '': ['index.html', 'text/html; charset=utf-8'],
  'style.css': ['style.css', 'text/css'],
  'app.js': ['app.js', 'text/javascript'],
  'markdown-it.min.js': ['markdown-it.min.js', 'text/javascript']
};

http.createServer(async (request, response) => {
  function reply(data, status = 200, type = 'application/json; charset=utf-8') {
    const payload = Buffer.isBuffer(data) ? data : Buffer.from(JSON.stringify(data));
    const compressed = type.startsWith('application/json') && payload.length > 16384 &&
      /(?:^|[,\s])gzip(?:$|[,\s])/.test(request.headers['accept-encoding'] || '');
    const body = compressed ? gzipSync(payload) : payload;
    response.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store',
      'Content-Length': body.length, ...(compressed ? { 'Content-Encoding': 'gzip', Vary: 'Accept-Encoding' } : {}),
      'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer',
      'Content-Security-Policy': "default-src 'self'; img-src 'none'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'" });
    response.end(body);
  }
  try {
    const origin = 'http://127.0.0.1:' + port;
    if (request.headers.host !== '127.0.0.1:' + port || (request.headers.origin && request.headers.origin !== origin)) return reply({}, 403);
    const url = new URL(request.url, origin);
    const [, credential, ...parts] = url.pathname.split('/');
    const route = parts.join('/');
    const equalsToken = expected => credential?.length === expected.length && /^[a-f\d]+$/.test(credential) &&
      timingSafeEqual(Buffer.from(credential), Buffer.from(expected));
    if (!equalsToken(token)) {
      const expires = url.searchParams.get('expires');
      const now = Math.floor(Date.now() / 1000);
      if (route !== 'api/media' || request.method !== 'GET' || !/^\d{10}$/.test(expires || '') ||
        Number(expires) < now || Number(expires) > now + 28800) return reply({}, 404);
      const scoped = createHmac('sha256', token).update('video\n' + expires + '\n' + (url.searchParams.get('path') || '')).digest('hex');
      if (!equalsToken(scoped)) return reply({}, 404);
    }
    if (request.method === 'GET' && Object.hasOwn(files, route)) {
      const [file, type] = files[route];
      return reply(await fs.readFile(path.join(appDir, 'web', file)), 200, type);
    }
    if (request.method === 'GET') {
      if (route === 'api/health') return reply({});
      if (route === 'api/search') {
        const query = (url.searchParams.get('q') || '').trim().slice(0, 200).toLocaleLowerCase();
        if (!query) return reply({ results: [] });
        const results = [];
        for (const row of [...summaries.values()].reverse().sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0))) {
          if (row.archived || row.deleted) continue;
          let chat = chats.get(row.id);
          if (!chat && segmentedStore) {
            try { chat = JSON.parse(await fs.readFile(path.join(chatDirectory, row.id + '.json'), 'utf8')); }
            catch { continue; }
          }
          const title = row.title || 'Nova conversa';
          let snippet = '';
          for (const message of chat?.messages || []) {
            if (typeof message.text !== 'string') continue;
            const index = message.text.toLocaleLowerCase().indexOf(query);
            if (index >= 0) {
              snippet = message.text.slice(Math.max(0, index - 50), index + query.length + 100).replace(/\s+/g, ' ');
              break;
            }
          }
          if (snippet || title.toLocaleLowerCase().includes(query)) results.push({ id: row.id, title, snippet });
          if (results.length >= 100) break;
        }
        return reply({ results });
      }
      if (route === 'api/state') {
        const id = url.searchParams.get('chat');
        const cached = chats.has(id);
        const selected = (cached || summaries.has(id)) ? await getChat(id) : undefined;
        const requests = [...approvals.values()];
        const since = id && !cached ? null : url.searchParams.get('since');
        if (since !== null && Number(since) === version) return reply({ version, unchanged: true,
          automatic: requests.filter(a => a.automatic) });
        const messagesDelta = selected && since !== null && /^\d+$/.test(since) && Number(since) <= version;
        const chat = messagesDelta ? { ...selected, messages: selected.messages.filter(entry =>
          (itemVersions.get(entry) || 0) > Number(since)) } : selected;
        return reply({ version, chat, messagesDelta: Boolean(messagesDelta), approvals: requests.filter(a => a.chatId === id && !a.automatic),
          automatic: requests.filter(a => a.automatic),
          chats: [...summaries.values()].reverse().sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0))
            .map(({ id, title, pinned, archived }) => ({ id, title, running: chats.get(id)?.running === true, pinned, archived })) });
      }
      if (route === 'api/media') {
        const file = await selectable(url.searchParams.get('path') || '', true);
        const types = { '.mp4': 'video/mp4', '.m4v': 'video/mp4', '.webm': 'video/webm', '.mkv': 'video/x-matroska',
          '.mov': 'video/quicktime', '.3gp': 'video/3gpp' };
        const type = types[path.extname(file).toLowerCase()];
        const stats = await fs.stat(file);
        if (!type || !stats.isFile() || !stats.size) return reply({ error: 'Vídeo indisponível.' }, 404);
        const range = request.headers.range?.match(/^bytes=(\d*)-(\d*)$/);
        const start = range ? (range[1] ? Number(range[1]) : Math.max(0, stats.size - Number(range[2]))) : 0;
        const end = range?.[1] && range[2] ? Math.min(Number(range[2]), stats.size - 1) : stats.size - 1;
        if (request.headers.range && (!range || (!range[1] && !range[2]) || !Number.isSafeInteger(start) ||
          !Number.isSafeInteger(end) || start >= stats.size || start > end)) {
          response.writeHead(416, { 'Content-Range': 'bytes */' + stats.size });
          return response.end();
        }
        response.writeHead(range ? 206 : 200, { 'Content-Type': type, 'Accept-Ranges': 'bytes', 'Cache-Control': 'no-store',
          'Content-Length': stats.size ? end - start + 1 : 0,
          ...(range ? { 'Content-Range': 'bytes ' + start + '-' + end + '/' + stats.size } : {}) });
        if (!stats.size) return response.end();
        const stream = createReadStream(file, { start, end });
        stream.on('error', error => response.destroy(error));
        response.once('close', () => stream.destroy());
        stream.pipe(response);
        return;
      }
      if (route === 'api/file') {
        const file = await selectable(url.searchParams.get('path') || '', true);
        const stats = await fs.stat(file);
        if (stats.isDirectory()) return reply({ path: file, name: path.basename(file), directory: true });
        if (url.searchParams.get('metadata') === 'true' && stats.isFile()) {
          return reply({ name: path.basename(file), image: imagePath(file), bytes: stats.size });
        }
        if (!stats.isFile()) return reply({ error: 'Escolha um arquivo ou pasta.' }, 400);
        const handle = await fs.open(file, 'r');
        try {
          const image = imagePath(file);
          const maximum = url.searchParams.get('export') === 'true' ? 20 * 1024 * 1024 : image ? 10 * 1024 * 1024 : 64 * 1024;
          if (stats.size > maximum && (image || url.searchParams.get('export') === 'true')) {
            return reply({ error: 'Arquivo grande demais para abrir nesta ação.' }, 413);
          }
          const content = Buffer.alloc(Math.min(stats.size, maximum));
          const { bytesRead } = await handle.read(content, 0, content.length, 0);
          return reply({ name: path.basename(file), image, truncated: stats.size > bytesRead,
            content: content.subarray(0, bytesRead).toString('base64') });
        } finally { await handle.close(); }
      }
      if (route === 'api/folders') {
        const mode = url.searchParams.get('mode');
        const requested = url.searchParams.get('path') || '/sdcard';
        const browse = mode === 'browse';
        const location = await directory(mode ? await selectable(requested, browse) : requested);
        const hidden = url.searchParams.get('hidden') === 'true';
        const entries = await fs.readdir(location, { withFileTypes: true });
        const folders = [];
        const listedFiles = [];
        for (const entry of entries) {
          if (!hidden && entry.name.startsWith('.')) continue;
          const file = path.join(location, entry.name);
          if (entry.isDirectory() || (entry.isSymbolicLink() &&
            await (mode ? selectable(file, browse) : Promise.resolve(file)).then(directory).then(() => true, () => false))) {
            folders.push(entry.name);
          } else if (entry.isFile() && (browse || mode === 'files' || (mode === 'images' && imagePath(file)))) {
            listedFiles.push(entry.name);
          }
        }
        const sort = (a, b) => a.localeCompare(b, 'pt-BR', { numeric: true });
        let parent = path.dirname(location);
        if (mode && !await selectable(parent, browse).then(() => true, () => false)) parent = location;
        return reply({ location, parent, folders: folders.sort(sort), files: listedFiles.sort(sort) });
      }
      if (route === 'api/account') {
        await connect();
        const result = await rpc('account/read', { refreshToken: false });
        return reply({ ...result, login, loginOutcome });
      }
      if (route === 'api/rate-limits') {
        await connect();
        return reply(await rpc('account/rateLimits/read'));
      }
      if (route === 'api/status') {
        const info = await connect();
        const [account, limits] = await Promise.all([
          rpc('account/read').catch(() => ({})), rpc('account/rateLimits/read').catch(() => ({}))
        ]);
        return reply({ info, account, limits, workspace });
      }
      if (route === 'api/models') {
        await connect();
        const models = [];
        let cursor;
        do {
          const page = await rpc('model/list', { cursor });
          models.push(...page.data);
          cursor = page.nextCursor;
        } while (cursor);
        return reply(models);
      }
    }
    if (request.method !== 'POST') return reply({}, 404);
    if (route === 'api/attach') {
      const id = url.searchParams.get('chat');
      const rawName = url.searchParams.get('name') || 'arquivo';
      const name = path.basename(rawName).replace(/[^\p{L}\p{N}._ -]/gu, '_').slice(-110);
      const chat = await getChat(id);
      const folder = path.join(chat.storage || workspace, id, 'Anexos');
      const content = await bytes(request, 25 * 1024 * 1024);
      await fs.mkdir(folder, { recursive: true });
      const file = path.join(folder, randomUUID() + '-' + name);
      await fs.writeFile(file, content, { flag: 'wx' });
      return reply({ id: path.basename(file), name, image: /^image\/(png|jpeg|webp|gif)$/.test(request.headers['content-type'] || '') });
    }
    const mediaType = request.headers['content-type']?.split(';', 1)[0].trim().toLowerCase();
    if (mediaType !== 'application/json') return reply({ error: 'O serviço espera JSON neste pedido.' }, 415);
    const data = JSON.parse((await bytes(request, 2 * 1024 * 1024)).toString());
    if (route === 'api/login') {
      await connect();
      if (login) return reply(login);
      loginOutcome = null;
      login = await rpc('account/login/start', data.device
        ? { type: 'chatgptDeviceCode' }
        : { type: 'chatgpt', useHostedLoginSuccessPage: true });
      return reply(login);
    }
    if (route === 'api/login/cancel') {
      if (login) await rpc('account/login/cancel', { loginId: login.loginId });
      login = null;
      loginOutcome = null;
      return reply({});
    }
    if (route === 'api/logout') {
      await connect();
      if ([...chats.values()].some(chat => chat.running)) throw new Error('Encerre as respostas em andamento antes de sair.');
      await rpc('account/logout');
      login = null;
      loginOutcome = null;
      return reply({});
    }
    if (route === 'api/mkdir') {
      const name = String(data.name || '').trim();
      if (!name || name === '.' || name === '..' || /[/\\\x00-\x1f]/.test(name)) {
        return reply({ error: 'Informe um nome de pasta válido.' }, 400);
      }
      const parent = await directory(await selectable(data.path));
      const created = path.join(parent, name);
      try { await fs.mkdir(created); }
      catch (error) {
        if (error.code === 'EEXIST') return reply({ error: 'Já existe um arquivo ou pasta com esse nome.' }, 409);
        throw error;
      }
      return reply({ path: created }, 201);
    }
    if (route === 'api/new') {
      if (data.id != null && !validId(data.id)) return reply({ error: 'Conversa inválida.' }, 400);
      const id = data.id || randomUUID();
      let creation = creatingChats.get(id);
      if (!creation) {
        if (chats.has(id) || summaries.has(id)) return reply(await getChat(id));
        creation = createChat(id, data.cwd).finally(() => creatingChats.delete(id));
        creatingChats.set(id, creation);
      }
      return reply(await creation, 201);
    }
    const chat = await getChat(data.chat);
    if (route === 'api/attach-local') {
      idle(chat);
      if (!Array.isArray(data.paths) || !data.paths.length || data.paths.length > 20) {
        throw new Error('Selecione entre 1 e 20 arquivos.');
      }
      const files = await Promise.all(data.paths.map(async value => {
        if (typeof value !== 'string') throw new Error('Arquivo inválido.');
        const source = await selectable(value);
        const stats = await fs.stat(source);
        if (!stats.isFile() || stats.size > 25 * 1024 * 1024) {
          throw new Error('Selecione arquivos com até 25 MB cada.');
        }
        return {
          source, name: path.basename(source).replace(/[^\p{L}\p{N}._ -]/gu, '_').slice(-110)
        };
      }));
      const target = path.join(chat.storage || workspace, chat.id, 'Anexos');
      await fs.mkdir(target, { recursive: true });
      const attachments = [];
      try {
        for (const { source, name } of files) {
          const id = randomUUID() + '-' + name;
          await fs.copyFile(source, path.join(target, id));
          attachments.push({ id, name, image: imagePath(source) });
        }
      } catch (error) {
        await Promise.allSettled(attachments.map(attachment => fs.unlink(path.join(target, attachment.id))));
        throw error;
      }
      return reply({ attachments });
    }
    if (route === 'api/conversation') {
      if (data.action === 'delete') {
        idle(chat);
        if (chat.thread) {
          await connect();
          await rpc('thread/delete', { threadId: chat.thread });
          loaded.delete(chat.thread);
        }
        chats.delete(chat.id);
        try {
          await save({ id: chat.id, deleted: true });
          if (segmentedStore) {
            writeQueue = writeQueue.then(async () => {
              const legacy = await readLegacy();
              if (!legacy.some(entry => entry.id === chat.id)) return;
              const keep = legacy.filter(entry => entry.id !== chat.id);
              await fs.writeFile(store + '.tmp', JSON.stringify(keep, stored), { mode: 0o600 });
              await fs.rename(store + '.tmp', store);
            });
            await writeQueue;
          }
        } finally { touch(); }
        return reply({});
      }
      if (data.action === 'rename') {
        const title = typeof data.title === 'string' ? data.title.trim() : '';
        if (!title || title.length > 120) return reply({ error: 'Informe um título de até 120 caracteres.' }, 400);
        chat.title = title;
      } else if (data.action === 'pin') {
        chat.pinned = data.value === true;
      } else if (data.action === 'archive') {
        chat.archived = data.value === true;
      } else return reply({ error: 'Ação desconhecida.' }, 400);
      await save(chat);
      touch();
      return reply({});
    }
    if (route === 'api/settings') {
      idle(chat);
      if (data.cwd) chat.cwd = await directory(data.cwd);
      if (typeof data.model === 'string') chat.model = data.model || null;
      if (typeof data.effort === 'string') chat.effort = data.effort || null;
      touch();
      await save(chat);
      return reply(chat);
    }
    if (route === 'api/stop') {
      if (!chat.turn) throw new Error('Aguarde o início da resposta.');
      return reply(await rpc('turn/interrupt', { threadId: chat.thread, turnId: chat.turn }));
    }
    if (route === 'api/credential/run') {
      const key = String(data.id);
      const call = bridgeCalls.get(key);
      const request = approvals.get(key);
      const credential = data.credential;
      if (!request?.credential || !call || call.chat !== chat || credential?.name !== call.alias) {
        throw new Error('Solicitação de credencial inválida ou expirada.');
      }
      if (call.credentialFile) {
        if (credential.type !== 'TOKEN' || typeof credential.secret !== 'string' || !credential.secret ||
            credential.secret.length > 20000) throw new Error('A exportação aceita uma credencial do tipo Token.');
        const root = await directory(chat.cwd);
        const parent = await directory(path.join(root, path.dirname(call.credentialFile)));
        if (parent !== root && !parent.startsWith(root + '/')) throw new Error('Destino fora da pasta do projeto.');
        const file = path.join(parent, path.basename(call.credentialFile));
        const handle = await fs.open(file, 'wx', 0o600);
        try { await handle.writeFile(credential.secret, 'utf8'); }
        catch (error) { await handle.close(); await fs.unlink(file).catch(() => {}); throw error; }
        await handle.close();
        finishBridge(key, { exit: 0, stdout: 'Arquivo criado: ' + call.credentialFile });
        return reply({});
      }
      const { type, secret, username } = credential;
      const field = (value, max) => typeof value === 'string' && value.length > 0 && value.length <= max && !/[\x00-\x1f\x7f]/.test(value);
      if (!['TOKEN', 'LOGIN'].includes(type) || !field(secret, 20000) ||
        (type === 'LOGIN' && (!field(username, 256) || username.includes(':')))) {
        throw new Error('Esta operação requer uma credencial Token ou Login válida para HTTPS.');
      }
      const authorization = type === 'TOKEN' ? 'Bearer ' + secret :
        'Basic ' + Buffer.from(username + ':' + secret).toString('base64');
      approvals.delete(key);
      clearTimeout(call.timer);
      call.running = true;
      call.startedAt = Date.now();
      call.abort = new AbortController();
      item(chat, key, 'command', { text: 'GET ' + call.credentialUrl.origin, status: 'inProgress',
        environment: call.target, startedAt: call.startedAt });
      call.timer = setTimeout(() => finishBridge(key, { error: 'Solicitação autenticada expirou.' }), 30000);
      call.timer.unref();
      fetch(call.credentialUrl, {
        headers: { authorization, 'user-agent': 'Codex-Mobile' },
        redirect: 'manual', signal: call.abort.signal
      }).then(async response => {
        await response.body?.cancel();
        finishBridge(key, { exit: response.ok ? 0 : 1, stdout: 'HTTP ' + response.status });
      }).catch(() => finishBridge(key, { error: 'A solicitação HTTPS autenticada falhou.' }));
      touch();
      return reply({}, 202);
    }
    if (route === 'api/android/start') {
      const request = approvals.get(String(data.id));
      const call = bridgeCalls.get(String(data.id));
      if (!request?.android || !call || call.chat !== chat || (data.automatic === true && !request.automatic)) {
        throw new Error('A solicitação expirou ou requer aprovação.');
      }
      approvals.delete(String(data.id));
      clearTimeout(call.timer);
      call.timer = setTimeout(() => finishBridge(String(data.id), { error: 'Execução Android expirou.' }), 125000);
      call.timer.unref();
      call.running = true;
      call.startedAt = Date.now();
      item(chat, String(data.id), 'command', { text: call.command, status: 'inProgress', environment: call.target, startedAt: call.startedAt });
      touch();
      return reply({ command: call.command, cwd: call.cwd, target: call.target, automatic: request.automatic === true });
    }
    if (route === 'api/tool/stop') {
      const key = String(data.id);
      const call = bridgeCalls.get(key);
      if (!call?.running || call.chat !== chat || call.target === 'Termux') {
        return reply({ error: 'Esta execução não tem interrupção individual disponível.' }, 409);
      }
      finishBridge(key, { error: 'Interrompido pelo aplicativo.', interrupted: true });
      return reply({});
    }
    if (route === 'api/android/result') {
      const call = bridgeCalls.get(String(data.id));
      if (!call?.running || call.chat !== chat) throw Object.assign(new Error('A execução expirou.'), { status: 409 });
      finishBridge(String(data.id), data);
      return reply({});
    }
    if (route === 'api/approval') {
      const request = approvals.get(String(data.id));
      if (request?.chatId !== chat.id) throw new Error('A aprovação expirou.');
      if (request.android || request.credential) {
        if (data.allow) throw new Error('Aprovação inválida para esta ferramenta.');
        finishBridge(String(data.id), { error: 'Comando recusado.' });
        return reply({});
      }
      const result = request.method === 'item/permissions/requestApproval'
        ? { permissions: data.allow ? request.params.permissions : {}, scope: 'turn' }
        : { decision: data.allow ? 'accept' : 'decline' };
      send({ id: request.id, result });
      approvals.delete(String(data.id));
      touch();
      return reply({});
    }
    if (route === 'api/send') {
      if (data.requestId != null && !validId(data.requestId)) return reply({ error: 'Envio inválido.' }, 400);
      const requestId = data.requestId || randomUUID();
      const previous = chat.messages.find(message => message.id === requestId && message.type === 'user');
      if (previous?.delivery === 'accepted') return reply({}, 202);
      idle(chat);
      if (previous) return reply({ error: previous.deliveryError || 'O envio anterior foi interrompido. Confira o histórico antes de reenviar.' }, 422);
      const text = String(data.text || '').trim();
      const attachments = Array.isArray(data.attachments) ? data.attachments : [];
      if (!text && !attachments.length) throw new Error('Escreva uma mensagem ou escolha um arquivo.');
      chat.running = true;
      chat.turn = null;
      touch();
      try {
        const paths = [];
        for (const attachment of attachments) {
          if (typeof attachment.id !== 'string' || !/^[a-f\d-]{36}-[^/]+$/u.test(attachment.id)) throw new Error('Anexo inválido.');
          const file = path.join(chat.storage || workspace, chat.id, 'Anexos', attachment.id);
          if (!(await fs.stat(file)).isFile()) throw new Error('Anexo indisponível.');
          paths.push({ file, image: attachment.image === true });
        }
        await connect();
        if (!chat.thread) {
          const result = await rpc('thread/start', { cwd: chat.cwd, model: chat.model, ...(androidTools.length ? { dynamicTools: androidTools } : {}) });
          chat.thread = result.thread.id;
          chat.model ||= result.model;
          loaded.add(chat.thread);
        } else if (!loaded.has(chat.thread)) {
          await rpc('thread/resume', { threadId: chat.thread, cwd: chat.cwd, excludeTurns: true });
          loaded.add(chat.thread);
        }
        const context = paths.length ? '\n\nArquivos selecionados no Android (caminhos no Ubuntu):\n' +
          paths.map(({ file }) => '- ' + file).join('\n') : '';
        const input = [{ type: 'text', text: text + context, text_elements: [] }];
        const yolo = embedded && data.yolo === true;
        if (embedded) {
          const credentials = Array.isArray(data.credentialMetadata) ? data.credentialMetadata.slice(0, 20)
            .filter(item => item && typeof item.name === 'string' &&
              [...text.matchAll(/(?:^|[^\w+])\+([a-z0-9][a-z0-9_.-]{0,39})/gi)].some(match =>
                match[1].replace(/\.$/, '').toLowerCase() === item.name))
            .map(item => ({
              name: item.name, description: String(item.description || '').slice(0, 1000),
              type: String(item.type || '').slice(0, 12), instructions: String(item.instructions || '').slice(0, 1000)
            })) : [];
          if (credentials.length) input.push({ type: 'text', text: 'Metadata of mentioned credentials (no secret): ' +
            JSON.stringify(credentials), text_elements: [] });
          const ordinal = chat.messages.filter(message => message.type === 'user').length + 1;
          input.push({ type: 'text', text: [
            'Codex Mobile UI metadata. This is user message #' + ordinal + ' in this chat.',
            'When referencing a user message, use [mensagem](message:N) with its 1-based user message number.',
            'Use Markdown links to show existing paths, e.g. [arquivo](./relatorio.md) and [pasta](./Anexos/).',
            'Use Markdown images for local images, e.g. ![prévia](./figura.png). Percent-encode spaces in paths.',
            ...(deviceControlEnabled ? ['Use device_control to inspect or interact with the Android screen when requested. If device_control is unavailable in an older thread, use adb_shell with command codex-device: followed by JSON, for example codex-device:{\"action\":\"screenshot\"}. Supported actions are screenshot, tap, long_press, swipe, back, home and open_app. Gestures use integer x, y, optional x2, y2 and duration; open_app uses package. A password-protected session is required. Ask for explicit confirmation before irreversible actions such as sending, purchasing or deleting. Request screenshot for current visual context before and after actions.'] : []),
            'Reference a credential by known alias only, e.g. [+alias](credential:alias). Authorization is a separate app action. When explicitly asked to write a token to a file, call credential_shell with command file:relative-path; the app approves the destination and writes it without disclosing the secret to you.',
            'Tools run in the integrated Ubuntu environment with Bash and network access when connected. If an ordinary tool is missing, try installing it from its official package registry, e.g. apt-get update followed by apt-get install -y PACKAGE, or use a project-local package manager. Do not stop at a missing dependency declaration. Verify the installation and continue; report concrete errors if the attempts fail.',
            'Routine builds, downloads, installs, and reversible local edits do not require a chat confirmation. An explicit user instruction naming exactly what to delete is consent to prepare that command. Use a standalone rm -- command with exactly those names when deleting named entries in the current project. If the tool requires approval, submit it to the app approval UI directly; do not first ask in chat and then prompt again. When the target or scope is unclear, clarify before preparing it. Keep tool approval for risky operations and Android modifications or credential use.',
            yolo ? 'YOLO is active: make routine implementation and dependency choices independently and finish the requested work. Read-only Android diagnostics such as id, getprop ro.product.model and dumpsys battery may run directly after the app has been connected. Preserve the confirmations for important actions.' : 'Standard mode is active: respect tool approval prompts and ask where important choices need confirmation.'
          ].join('\n'), text_elements: [] });
        }
        for (const { file, image } of paths) if (image) input.push({ type: 'localImage', path: file });
        if (!chat.messages.length && chat.title === 'Nova conversa') chat.title = (text || attachments[0].name || 'Anexos').replace(/\s+/g, ' ').slice(0, 64);
        chat.inFlight = requestId;
        chat.turnStartedMs = Date.now();
        const message = item(chat, requestId, 'user', { text, files: attachments.map(a => String(a.name || 'Arquivo')), delivery: 'starting' });
        await save(chat);
        touch();
        if (yolo) {
          yoloTurns.add(chat.id);
          deletionConsent.set(chat.id, exactDeletionTargets(text));
        }
        await rpc('turn/start', { threadId: chat.thread, cwd: chat.cwd, model: chat.model, effort: chat.effort, input,
          ...(embedded ? { approvalPolicy: yolo ? 'on-request' : 'untrusted' } : {}) });
        message.delivery = 'accepted';
        await save(chat);
        return reply({}, 202);
      } catch (error) {
        yoloTurns.delete(chat.id);
        deletionConsent.delete(chat.id);
        const message = chat.messages.find(item => item.id === requestId && item.delivery === 'starting');
        if (chat.inFlight === requestId) {
          delete chat.inFlight;
          delete chat.turnStartedMs;
        }
        if (message) {
          message.deliveryError = error.message;
          await save(chat).catch(console.error);
        }
        chat.running = false;
        touch();
        throw error;
      }
    }
    return reply({}, 404);
  } catch (error) {
    if (!response.headersSent && !response.destroyed) reply({ error: error.message }, error.status || 400);
  }
}).listen(port, '127.0.0.1', () => console.log('Codex Mobile · Ubuntu · porta ' + port));
