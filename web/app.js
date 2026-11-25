'use strict';

const $ = id => document.getElementById(id);
const markdown = window.markdownit({ html: false, linkify: true, breaks: true });
markdown.validateLink = url => /^https?:\/\//i.test(url);
markdown.renderer.rules.image = (tokens, index) => markdown.utils.escapeHtml(tokens[index].content);
markdown.renderer.rules.fence = (tokens, index) => codeBlock(tokens[index].content, tokens[index].info || 'Código');
markdown.renderer.rules.code_block = (tokens, index) => codeBlock(tokens[index].content, 'Código');

function codeBlock(text, label) {
  const escape = markdown.utils.escapeHtml;
  return '<div class="codebox"><div class="codebar"><span>' + escape(label) +
    '</span><button class="copy" type="button">Copiar</button></div><pre><code>' +
    escape(text) + '</code></pre></div>';
}

let active = localStorage.getItem('chat'), state = {}, models, serial = 0;
let attachments = [], uploads = 0, busy = false, newChat, messageSignature = '', listSignature = '', approvalSignature = '';
let folderRequest = 0;
const drafts = new Map();

async function api(route, data) {
  const options = data === undefined ? {} : {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data)
  };
  const response = await fetch('api/' + route, options);
  const body = await response.json();
  if (!response.ok) throw new Error(body.error || 'Falha local (' + response.status + ')');
  return body;
}

function el(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  return node;
}
function button(text, action, className) {
  const node = el('button', text, className);
  node.type = 'button';
  node.onclick = () => Promise.resolve().then(action).catch(error => feedback(error.message));
  return node;
}
function feedback(text = '') { $('feedback').textContent = text; }
function menu(open) {
  $('sidebar').classList.toggle('open', open);
  $('scrim').hidden = !open;
}
function closeDialog() {
  folderRequest++;
  $('dialog').close();
}
function dialog(title, ...nodes) {
  $('dialog-title').textContent = title;
  $('dialog-body').replaceChildren(...nodes);
  if (!$('dialog').open) $('dialog').showModal();
}
function basename(value) { return value?.replace(/\/$/, '').split('/').pop() || '/'; }
function renderList() {
  const filter = $('search').value.trim().toLocaleLowerCase();
  const entries = (state.chats || []).filter(chat => chat.title.toLocaleLowerCase().includes(filter));
  const signature = JSON.stringify([active, entries]);
  if (signature === listSignature) return;
  listSignature = signature;
  $('chats').replaceChildren(...entries.map(chat => {
    const entry = button((chat.running ? '◌  ' : '') + chat.title, () => select(chat.id),
      chat.id === active ? 'active' : '');
    entry.title = chat.cwd;
    if (chat.id === active) entry.setAttribute('aria-current', 'true');
    return entry;
  }));
}
function renderMessages(chat) {
  const messages = chat?.messages || [];
  const signature = JSON.stringify([active, messages]);
  if (signature === messageSignature) return;
  messageSignature = signature;
  const scroll = $('scroll');
  const sticky = scroll.scrollHeight - scroll.scrollTop - scroll.clientHeight < 100;
  const existing = new Map(Array.from($('messages').children, node => [node.dataset.id, node]));
  const nodes = messages.map(message => {
    let node = existing.get(message.id);
    const snapshot = JSON.stringify(message);
    if (node?._snapshot === snapshot) return node;
    if (!node) node = el('article', undefined, 'message ' + message.type);
    node.dataset.id = message.id;
    node._snapshot = snapshot;
    if (message.type === 'agent') node.innerHTML = markdown.render(message.text || '');
    else if (message.type === 'command') {
      const wasOpen = node.firstElementChild?.open;
      const details = el('details');
      details.open = wasOpen ?? false;
      details.append(el('summary', 'Bash · ' + ({
        inProgress: 'executando', completed: 'concluído', failed: 'falhou', declined: 'recusado'
      }[message.status] || 'comando') + (message.exit != null ? ' (' + message.exit + ')' : '')));
      if (message.text) details.insertAdjacentHTML('beforeend', codeBlock(message.text, 'Comando'));
      if (message.output) details.insertAdjacentHTML('beforeend', codeBlock(message.output, 'Saída'));
      node.replaceChildren(details);
    } else {
      node.textContent = (message.type === 'change' ? 'Arquivos alterados\n' : '') + (message.text || '');
      if (message.files?.length) node.append(el('span', message.files.join(' · '), 'file-list'));
    }
    return node;
  });
  const container = $('messages');
  nodes.forEach((node, index) => {
    if (container.children[index] !== node) container.insertBefore(node, container.children[index] || null);
  });
  while (container.children.length > nodes.length) container.lastElementChild.remove();
  $('empty').hidden = nodes.length > 0;
  if (sticky) scroll.scrollTop = scroll.scrollHeight;
}
function renderApprovals() {
  const requests = state.approvals || [];
  const signature = JSON.stringify([active, requests]);
  if (approvalSignature === signature) return;
  approvalSignature = signature;
  $('approvals').replaceChildren(...requests.map(request => {
    const box = el('div', undefined, 'approval');
    const { command, reason, grantRoot, permissions } = request.params;
    box.append(el('strong', 'Aprovar ação do Codex?'), el('p', command || grantRoot || reason || JSON.stringify(permissions || 'Alterar arquivos')));
    for (const [text, allow] of [['Permitir', true], ['Recusar', false]]) {
      box.append(button(text, async () => {
        await api('approval', { chat: request.chatId, id: request.id, allow });
        await refresh();
      }));
    }
    return box;
  }));
}
function render() {
  const chat = state.chat;
  $('title').textContent = chat?.title || 'Nova conversa';
  $('project').textContent = chat ? basename(chat.cwd) : 'Escolher pasta';
  $('folder').title = chat?.cwd || 'Pasta do projeto';
  const modelLabel = models?.find(model => model.model === chat?.model)?.displayName || chat?.model || 'Modelo';
  const effort = { minimal: 'Mínimo', low: 'Baixo', medium: 'Médio', high: 'Alto', xhigh: 'Muito alto', max: 'Máximo', ultra: 'Ultra', none: 'Nenhum' };
  $('model').textContent = modelLabel + ' ⌄';
  $('effort').textContent = (effort[chat?.effort] || chat?.effort || 'Esforço') + ' ⌄';
  const running = Boolean(chat?.running);
  $('send').textContent = running ? '■' : '↑';
  $('send').classList.toggle('stop', running);
  $('send').setAttribute('aria-label', running ? 'Interromper' : 'Enviar');
  $('send').disabled = busy || (!running && uploads > 0);
  $('folder').disabled = $('model').disabled = $('effort').disabled = running || busy;
  renderList();
  renderMessages(chat);
  renderApprovals();
}
async function refresh() {
  const request = ++serial;
  const value = await api('state?chat=' + encodeURIComponent(active || ''));
  if (request !== serial) return;
  state = value;
  document.querySelector('.dot').classList.add('online');
  render();
}
async function select(id) {
  if (active !== id) drafts.set(active, { text: $('prompt').value, attachments });
  const draft = drafts.get(id);
  active = id;
  localStorage.setItem('chat', id);
  state.chat = undefined;
  state.approvals = [];
  $('prompt').value = draft?.text || '';
  attachments = draft?.attachments || [];
  renderAttachments();
  feedback();
  menu(false);
  await refresh();
  $('scroll').scrollTop = $('scroll').scrollHeight;
}
async function create(cwd) {
  if (newChat) return newChat;
  newChat = (async () => {
    const previous = active;
    const chat = await api('new', { cwd });
    if (!previous) {
      drafts.set(chat.id, { text: $('prompt').value, attachments });
    }
    await select(chat.id);
    return chat.id;
  })();
  try { return await newChat; }
  finally { newChat = null; }
}
const ensureChat = () => active || create();

function renderAttachments() {
  $('attachments').replaceChildren(...attachments.map((attachment, index) =>
    button(attachment.name + ' ×', () => {
      attachments = attachments.filter((_, i) => index !== i);
      renderAttachments();
    })));
}
async function attach(files) {
  if (!files.length) return;
  const id = await ensureChat();
  for (const file of files) {
    try {
      feedback('Anexando ' + file.name + '…');
      const response = await fetch('api/attach?' + new URLSearchParams({ chat: id, name: file.name }), {
        method: 'POST', headers: { 'Content-Type': file.type || 'application/octet-stream' }, body: file
      });
      const data = await response.json();
      if (!response.ok) throw new Error(data.error);
      if (active === id) {
        attachments = [...attachments, data];
        renderAttachments();
      } else {
        const draft = drafts.get(id);
        draft.attachments = [...draft.attachments, data];
      }
      feedback();
    } catch (error) {
      feedback(error.message || 'Falha ao anexar ' + file.name);
      break;
    }
  }
}
async function folders(location) {
  const id = active;
  const request = ++folderRequest;
  dialog('Pasta do projeto', el('p', 'Abrindo pastas…'));
  try {
    const data = await api('folders?' + new URLSearchParams({ path: location || '/sdcard' }));
    if (request !== folderRequest || !$('dialog').open) return;
    const controls = el('div', undefined, 'folder-actions');
    if (data.parent !== data.location) controls.append(button('↑ Pasta acima', () => folders(data.parent)));
    controls.append(button('Usar esta pasta', async () => {
      if (id) await api('settings', { chat: id, cwd: data.location });
      else await create(data.location);
      closeDialog();
      await refresh();
    }, 'primary'));
    const list = el('div', undefined, 'choices');
    for (const name of data.folders) list.append(button('▱ ' + name, () => folders(data.location.replace(/\/$/, '') + '/' + name)));
    dialog('Pasta do projeto', el('p', data.location, 'path'), controls, list);
  } catch (error) {
    if (request === folderRequest && $('dialog').open) dialog('Pasta do projeto', el('p', error.message), button('Abrir armazenamento', () => folders('/sdcard')));
  }
}
async function choices(type) {
  const id = active;
  const title = type === 'model' ? 'Escolher modelo' : 'Esforço de raciocínio';
  dialog(title, el('p', 'Consultando o Codex…'));
  try {
    models ||= await api('models');
    if (!$('dialog').open || $('dialog-title').textContent !== title || active !== id) return;
    const model = models.find(model => model.model === state.chat?.model) || models.find(model => model.isDefault);
    const values = type === 'model'
      ? models.filter(model => !model.hidden).map(model => [model.model, model.displayName || model.model])
      : [['', 'Padrão do modelo'], ...(model?.supportedReasoningEfforts || []).map(option => [option.reasoningEffort, ({
          minimal: 'Mínimo', low: 'Baixo', medium: 'Médio', high: 'Alto', xhigh: 'Muito alto', max: 'Máximo', ultra: 'Ultra', none: 'Nenhum'
        })[option.reasoningEffort] || option.reasoningEffort])];
    const list = el('div', undefined, 'choices');
    for (const [value, label] of values) list.append(button(label, async () => {
      const chat = id || await ensureChat();
      await api('settings', { chat, [type]: value, ...(type === 'model' ? { effort: '' } : {}) });
      closeDialog();
      await refresh();
    }, (state.chat?.[type] || '') === value ? 'active' : ''));
    dialog(title, list);
  } catch (error) {
    if ($('dialog').open && $('dialog-title').textContent === title) dialog(title, el('p', error.message));
  }
}
async function status() {
  menu(false);
  const title = 'Status do ambiente';
  dialog(title, el('p', 'Consultando o Ubuntu…'));
  try {
    const data = await api('status');
    if (!$('dialog').open || $('dialog-title').textContent !== title) return;
    const quota = data.limits.rateLimits;
    const rows = [
      ['Ambiente', (data.info.platformOs || 'Linux') + ' · Ubuntu / PRoot'],
      ['Conta', data.account.account?.email || data.account.account?.type || 'Sem sessão'],
      ['Plano', data.account.account?.planType],
      ['Modelo do chat', state.chat?.model],
      ['Tokens do chat', state.chat?.usage?.total?.totalTokens],
      ['Limite primário usado', quota?.primary && quota.primary.usedPercent + '%'],
      ['Limite secundário usado', quota?.secondary && quota.secondary.usedPercent + '%'],
      ['Pasta atual', state.chat?.cwd || data.workspace]
    ];
    const list = el('dl');
    for (const [label, value] of rows) if (value != null) {
      list.append(el('dt', label), el('dd', String(value), 'path'));
    }
    dialog(title, list);
  } catch (error) {
    if ($('dialog').open && $('dialog-title').textContent === title) dialog(title, el('p', error.message));
  }
}

$('menu').onclick = () => menu(true);
$('close-menu').onclick = $('scrim').onclick = () => menu(false);
$('new').onclick = () => create().catch(error => feedback(error.message));
$('search').oninput = renderList;
$('folder').onclick = $('empty-folder').onclick = () => folders(state.chat?.cwd || '/sdcard');
$('model').onclick = () => choices('model');
$('effort').onclick = () => choices('effort');
$('status').onclick = status;
$('dialog-close').onclick = closeDialog;
$('attach').onclick = $('empty-files').onclick = () => $('files').click();
$('files').onchange = () => {
  const files = [...$('files').files];
  $('files').value = '';
  uploads++;
  render();
  attach(files).catch(error => feedback(error.message)).finally(() => {
    uploads--;
    render();
  });
};
$('composer').onsubmit = async event => {
  event.preventDefault();
  if (busy || (!state.chat?.running && uploads > 0)) return;
  const text = $('prompt').value.trim();
  const files = attachments;
  if (!state.chat?.running && !text && !files.length) return;
  busy = true;
  render();
  feedback();
  try {
    if (state.chat?.running) await api('stop', { chat: active });
    else {
      const chat = await ensureChat();
      await api('send', { chat, text, attachments: files });
      if (active === chat) {
        if ($('prompt').value.trim() === text) $('prompt').value = '';
        attachments = attachments.filter(file => !files.includes(file));
        renderAttachments();
      } else if (drafts.has(chat)) {
        const draft = drafts.get(chat);
        if (draft.text.trim() === text) draft.text = '';
        draft.attachments = draft.attachments.filter(file => !files.includes(file));
      }
    }
    await refresh();
  } catch (error) { feedback(error.message); }
  finally {
    busy = false;
    render();
  }
};
$('prompt').onkeydown = event => {
  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) $('composer').requestSubmit();
};
$('messages').onclick = async event => {
  const link = event.target.closest('a');
  if (link && !window.AndroidApp) {
    event.preventDefault();
    window.open(link.href, '_blank', 'noopener,noreferrer');
    return;
  }
  const copy = event.target.closest('.copy');
  if (!copy) return;
  const text = copy.closest('.codebox').querySelector('code').textContent;
  try {
    if (window.AndroidApp) window.AndroidApp.copy(text);
    else await navigator.clipboard.writeText(text);
    copy.textContent = 'Copiado';
    setTimeout(() => { copy.textContent = 'Copiar'; }, 1100);
  } catch (error) { feedback(error.message); }
};

$('reconnect').onclick = () => window.AndroidApp?.restart();
async function poll() {
  try {
    await refresh();
    if (!$('reconnect').hidden) feedback();
    $('reconnect').hidden = true;
  }
  catch {
    document.querySelector('.dot').classList.remove('online');
    feedback('O serviço no Ubuntu está desconectado.');
    $('reconnect').hidden = !window.AndroidApp;
  }
  setTimeout(poll, state.chat?.running ? 350 : 1100);
}
poll();
