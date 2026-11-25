package local.codex.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import local.codex.mobile.R
import local.codex.mobile.core.MobileState
import local.codex.mobile.core.MobileViewModel
import local.codex.mobile.core.objects
import local.codex.mobile.core.string
import org.json.JSONObject

internal fun projectName(path: String?): String {
    if (path == null) return "Projetos"
    val name = path.trimEnd('/').substringAfterLast('/')
    return if (name.matches(Regex("[a-f0-9-]{36}"))) "Arquivos do chat" else name.ifBlank { "/" }
}
internal fun effortName(name: String?) = when (name) {
    "none" -> "Nenhum"
    "minimal" -> "Mínimo"
    "low" -> "Baixo"
    "medium" -> "Médio"
    "high" -> "Alto"
    "xhigh" -> "Muito alto"
    "max" -> "Máximo"
    "ultra" -> "Ultra"
    else -> name ?: "Esforço"
}

@Composable internal fun ChatScreen(
    state: MobileState,
    vm: MobileViewModel,
    compact: Boolean,
    openDrawer: () -> Unit,
    openFolders: () -> Unit,
    chooseProject: () -> Unit,
    openUsage: () -> Unit,
    openCredentials: () -> Unit,
    configure: () -> Unit,
    reconnect: () -> Unit,
    openAttachments: () -> Unit,
    messageTarget: Int?,
    onMessageOpened: () -> Unit
) {
    val chat = state.chat
    val title = chat?.string("title") ?: "Nova conversa"
    val model = chat?.string("model")
    val displayModel = state.catalog.firstOrNull { it.string("model") == model }?.string("displayName") ?: model
    val messages = remember(chat) { chat?.optJSONArray("messages").objects() }
    val pending = state.queue.filter { entry ->
        entry.chat == state.active && messages.none { it.optString("id") == entry.id && entry.error == null }
    }
    val list = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    fun dismissInput() {
        focus.clearFocus(force = true)
        keyboard?.hide()
    }
    val ready = state.connected && state.accountLoaded && state.account != null
    val loading = state.running || state.busy || state.syncingChat || state.startup != null ||
        (state.connected && !state.accountLoaded)
    val lastMessage = messages.lastOrNull()
    val activeCommand = if (state.running) messages.lastOrNull {
        it.string("type") == "command" && it.string("status") in setOf("inProgress", "in_progress", null)
    } else null
    var pinned by remember(state.active) { mutableStateOf(true) }
    var highlighted by remember(state.active) { mutableStateOf<String?>(null) }
    LaunchedEffect(messageTarget) {
        val target = messageTarget ?: return@LaunchedEffect
        val result = messages.withIndex().filter { it.value.string("type") == "user" }.getOrNull(target - 1)
        if (result == null) {
            vm.report("Mensagem $target não encontrada neste chat.")
        } else {
            pinned = false
            list.scrollToItem(result.index)
            highlighted = result.value.string("id")
        }
        onMessageOpened()
    }
    LaunchedEffect(highlighted) {
        if (highlighted != null) {
            kotlinx.coroutines.delay(1800)
            highlighted = null
        }
    }
    LaunchedEffect(state.active) { dismissInput() }
    LaunchedEffect(list, state.active) {
        snapshotFlow { list.isScrollInProgress to list.canScrollForward }.collect { (scrolling, canScroll) ->
            if (scrolling) pinned = !canScroll
        }
    }
    LaunchedEffect(state.active, messages.size, pending.size, lastMessage?.toString()) {
        if (pinned && messages.size + pending.size > 0) list.scrollToItem(messages.size + pending.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (compact) IconButton(onClick = { dismissInput(); openDrawer() }) { Symbol(R.drawable.symbol_menu, "Conversas") }
                Column(Modifier.weight(1f).padding(start = if (compact) 0.dp else 12.dp)) {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Row(
                        Modifier.heightIn(min = 32.dp).clickable { dismissInput(); openFolders() },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Symbol(R.drawable.symbol_folder, null, color = Ink.Muted)
                        Text(projectName(chat?.string("cwd")), color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { dismissInput(); menuOpen = true }) {
                        Text("Menu", color = Ink.Link)
                        Symbol(R.drawable.symbol_expand_more, null, color = Ink.Link)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Abrir pasta atual") },
                            leadingIcon = { Symbol(R.drawable.symbol_folder_open, null) },
                            onClick = { menuOpen = false; openFolders() })
                        DropdownMenuItem(text = { Text("Trocar pasta do projeto") }, enabled = ready && !state.running,
                            leadingIcon = { Symbol(R.drawable.symbol_folder, null) },
                            onClick = { menuOpen = false; chooseProject() })
                        DropdownMenuItem(text = { Text("Uso") }, leadingIcon = { Symbol(R.drawable.symbol_bar_chart, null) },
                            onClick = { menuOpen = false; openUsage() })
                        DropdownMenuItem(text = { Text("Credenciais") }, leadingIcon = { Symbol(R.drawable.symbol_account_circle, null) },
                            onClick = { menuOpen = false; openCredentials() })
                        DropdownMenuItem(
                            text = { Column {
                                Text("Modo YOLO")
                                Text("Instala no Ubuntu e agiliza consultas no Android", color = Ink.Muted,
                                    style = MaterialTheme.typography.bodySmall)
                            } },
                            trailingIcon = { Switch(checked = state.yolo, onCheckedChange = null) },
                            onClick = { vm.setYolo(!state.yolo); menuOpen = false }
                        )
                    }
                }
            }
            val progressAlpha by animateFloatAsState(if (loading) 1f else 0f, tween(180), label = "chat progress")
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (loading || progressAlpha > 0f) {
                    LinearProgressIndicator(Modifier.fillMaxSize().alpha(progressAlpha), color = Ink.Accent,
                        trackColor = Ink.Surface)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (messages.isNotEmpty() || pending.isNotEmpty()) LazyColumn(
                Modifier.widthIn(max = 800.dp).fillMaxWidth().fillMaxHeight(),
                state = list,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(messages, key = { it.getString("id") }) { message ->
                    val highlight by animateColorAsState(
                        if (highlighted == message.string("id")) Ink.Accent.copy(alpha = .16f)
                        else androidx.compose.ui.graphics.Color.Transparent, label = "Mensagem referenciada")
                    Box(Modifier.fillMaxWidth().background(highlight, RoundedCornerShape(18.dp))) { Message(message, vm::stopTool, vm::prompt) }
                }
                items(pending, key = { "queued-" + it.id }) { entry ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f, fill = false)) {
                            val message = JSONObject().put("id", entry.id).put("type", "user").put("text", entry.text)
                                .put("files", org.json.JSONArray(entry.attachments.objects().map { it.optString("name") }))
                            Message(message)
                            Text(entry.error ?: if (state.sending == entry.id) "Enviando…" else "Na fila",
                                color = if (entry.error != null) MaterialTheme.colorScheme.error else Ink.Muted,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { vm.removeQueued(entry.id) }, enabled = state.sending != entry.id) {
                            Symbol(R.drawable.symbol_close, "Remover mensagem da fila")
                        }
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 800.dp).fillMaxWidth().padding(horizontal = 12.dp)) {
            state.connectionError?.let { error ->
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { dismissInput(); reconnect() }) { Text("Reconectar") }
                }
            }
            if (state.connected && !state.networkAvailable) {
                Text("Sem internet. As mensagens aguardam na fila.",
                    Modifier.padding(start = 10.dp, bottom = 6.dp), color = Ink.Muted,
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (ready) for (request in state.approvals) Approval(request, vm)
            AnimatedVisibility(state.running && activeCommand == null) {
                Text("Codex trabalhando…", Modifier.padding(start = 10.dp, bottom = 6.dp),
                    color = Ink.Muted, style = MaterialTheme.typography.bodyMedium)
            }
            if (activeCommand != null) {
                MiniShell(activeCommand, vm::stopTool)
            }
            CredentialSuggestions(state, vm::prompt)
            Composer(state, vm, ready, { dismissInput(); openAttachments() }, { dismissInput(); configure() }, displayModel) {
                dismissInput()
                pinned = true
                vm.send()
            }
        }
    }
}

@Composable private fun Composer(
    state: MobileState,
    vm: MobileViewModel,
    ready: Boolean,
    chooseFiles: () -> Unit,
    configure: () -> Unit,
    model: String?,
    submit: () -> Unit
) {
    Surface(
        Modifier.fillMaxWidth().padding(bottom = 10.dp),
        color = Ink.Surface, shape = RoundedCornerShape(26.dp)
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            for (attachment in state.attachments) {
                Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Symbol(R.drawable.symbol_attach_file, null, color = Ink.Muted)
                    Text(attachment.name, Modifier.weight(1f).padding(start = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { vm.remove(attachment) }) { Symbol(R.drawable.symbol_close, "Remover anexo") }
                }
            }
            ComposerInput(state.prompt, vm::prompt)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = chooseFiles, enabled = ready && !state.busy) {
                    Symbol(R.drawable.symbol_add, "Anexar arquivos")
                }
                Spacer(Modifier.weight(1f))
                val effort = state.chat?.string("effort")?.let(::effortName)
                TextButton(onClick = configure, enabled = !state.running && !state.busy) {
                    Symbol(R.drawable.symbol_tune, "Configurar modelo e esforço", color = Ink.Muted)
                    Spacer(Modifier.width(7.dp))
                    Text(listOfNotNull(model, effort).joinToString(" · ").ifBlank { "Configurar" },
                        modifier = Modifier.widthIn(max = 178.dp), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = Ink.Link)
                }
                val hasDraft = state.prompt.isNotBlank() || state.attachments.isNotEmpty()
                val stop = state.running && !hasDraft
                val enabled = hasDraft || (ready && stop && !state.busy)
                val fill by animateColorAsState(if (enabled) Ink.Accent else Ink.Elevated, label = "send")
                FilledIconButton(
                    onClick = submit,
                    enabled = enabled,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = fill, disabledContainerColor = fill)
                ) {
                    Symbol(if (stop) R.drawable.symbol_stop else R.drawable.symbol_arrow_upward,
                        if (stop) "Interromper resposta" else "Enviar mensagem",
                        color = if (enabled) Ink.Text else Ink.Muted)
                }
            }
        }
    }
}

@Composable private fun ComposerInput(value: String, onChange: (String) -> Unit) {
    val requester = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    var acquired by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val field = Modifier.fillMaxWidth().heightIn(min = 44.dp, max = 132.dp)
    val placeholder = "Escreva para o Codex…"
    if (editing) {
        BasicTextField(
            value, onChange,
            field.focusRequester(requester).onFocusChanged { focus ->
                if (focus.isFocused) acquired = true
                else if (acquired) {
                    acquired = false
                    editing = false
                }
            }.padding(horizontal = 10.dp, vertical = 7.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.Text),
            cursorBrush = SolidColor(Ink.Link),
            maxLines = 5,
            decorationBox = { input -> Box {
                if (value.isEmpty()) Text(placeholder, color = Ink.Muted)
                input()
            } }
        )
        LaunchedEffect(Unit) {
            requester.requestFocus()
            keyboard?.show()
        }
    } else {
        Text(value.ifEmpty { placeholder },
            field.clickable(onClickLabel = "Editar mensagem") { editing = true }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            color = if (value.isEmpty()) Ink.Muted else Ink.Text, style = MaterialTheme.typography.bodyLarge,
            maxLines = 5, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun Message(message: JSONObject, stop: ((String, String?) -> Unit)? = null, edit: ((String) -> Unit)? = null) {
    val text = message.string("text").orEmpty()
    when (message.string("type")) {
        "user" -> UserMessage(message, text, edit)
        "agent" -> if (text.isNotEmpty()) MessageActions(text, outgoing = false) {
            MarkdownBody(text, selectable = false)
        }
        "command" -> CommandExecution(message, stop = stop)
        "diff" -> Column {
            CodePanel("diff", text)
            if (message.optBoolean("truncated")) Text("Diff parcial", color = Ink.Muted)
        }
        "worked" -> WorkedFooter(message)
        "change" -> {
            val artifacts = LocalArtifactAccess.current
            val uri = LocalUriHandler.current
            Column {
                Text("Arquivos alterados", color = Ink.Muted, style = MaterialTheme.typography.bodyMedium)
                text.lineSequence().filter(String::isNotBlank).forEach { file ->
                    val target = artifacts?.resolve(file)
                    if (target == null) Text(file, color = Ink.Muted, style = MaterialTheme.typography.bodyMedium)
                    else Text(file, Modifier.clickable { uri.openUri(artifactLink(target)) }.padding(vertical = 5.dp),
                        color = Ink.Link, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        "notice" -> Text(text, color = MaterialTheme.colorScheme.error)
    }
}

@Composable private fun WorkedFooter(message: JSONObject) {
    val context = LocalContext.current
    val millis = message.optLong("durationMs", -1)
    val end = message.optLong("finishedAt", 0)
    if (millis < 0 || end <= 0) return
    val label = remember(millis, end, context) {
        val duration = durationText(millis)
        val time = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(end))
        "Trabalhou por $duration • $time"
    }
    Text(label, Modifier.padding(vertical = 2.dp), color = Ink.Muted, style = MaterialTheme.typography.labelMedium)
}

@Composable private fun UserMessage(message: JSONObject, text: String, edit: ((String) -> Unit)?) {
    var expanded by rememberSaveable(message.optString("id")) { mutableStateOf(false) }
    var overflow by remember(text) { mutableStateOf(false) }
    val preview = remember(text) { text.take(2500) }
    val hasMore = preview.length < text.length || overflow
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        val bubbleWidth = minOf(520.dp, maxWidth * .87f)
        val bubble: @Composable () -> Unit = {
            Surface(
                modifier = Modifier.widthIn(max = bubbleWidth),
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (text.isNotEmpty()) {
                        if (expanded) MarkdownBody(text, selectable = false)
                        else Text(preview, style = MaterialTheme.typography.bodyLarge, maxLines = 8,
                            overflow = TextOverflow.Ellipsis, onTextLayout = { overflow = it.hasVisualOverflow })
                    }
                    message.optJSONArray("files")?.let { files ->
                        for (i in 0 until files.length()) {
                            Text(files.optString(i), color = Ink.Link, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (expanded || hasMore) {
                        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                            Text(if (expanded) "Mostrar menos" else "Mostrar mais", color = Ink.Link)
                            Symbol(R.drawable.symbol_expand_more, null,
                                Modifier.padding(start = 4.dp).size(18.dp).rotate(if (expanded) 180f else 0f), Ink.Link)
                        }
                    }
                }
            }
        }
        if (edit == null && text.isEmpty()) bubble()
        else MessageActions(text, outgoing = true, edit = edit?.let { action -> { action(text) } }, content = bubble)
    }
}

private fun durationText(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return buildList {
        if (seconds >= 3600) add("${seconds / 3600}h")
        if (seconds >= 60) add("${seconds / 60 % 60}m")
        add("${seconds % 60}s")
    }.joinToString(" ")
}

@Composable private fun MiniShell(command: JSONObject, stop: (String, String?) -> Unit) {
    val latestLines = remember(command) {
        command.string("output")?.trimEnd()?.lineSequence()?.filter { it.isNotBlank() }
            ?.toList()?.takeLast(2)?.joinToString("\n").orEmpty()
    }
    CommandExecution(command, livePreview = latestLines.ifBlank { "Aguardando saída…" }, stop = stop)
    Spacer(Modifier.height(6.dp))
}

@Composable internal fun CommandExecution(command: JSONObject, livePreview: String? = null,
    stop: ((String, String?) -> Unit)? = null) {
    var expanded by rememberSaveable(command.string("id")) { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "output")
    val body = command.string("text").orEmpty()
    val output = command.string("output")
    val code = command.string("exit")
    val running = command.string("status") in setOf("inProgress", "in_progress")
    val start = command.optLong("startedAt", -1)
    val elapsed by produceState(-1L, start, running, command.optLong("durationMs", -1)) {
        value = command.optLong("durationMs", -1)
        while (running && start > 0) {
            value = System.currentTimeMillis() - start
            kotlinx.coroutines.delay(1000)
        }
    }
    val status = when (command.string("status")) {
        "completed" -> if (code != null && code != "0") "Saiu com código $code" else "Concluído"
        "failed" -> if (code == null) "Falhou" else "Falhou · código $code"
        "declined" -> "Recusado"
        "interrupted" -> "Interrompido"
        "inProgress", "in_progress" -> "Executando"
        else -> "Bash"
    }
    Surface(
        color = Ink.Surface, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClickLabel = if (expanded) "Recolher saída" else "Mostrar saída") { expanded = !expanded }
                    .semantics { stateDescription = if (expanded) "Saída expandida" else "Saída recolhida" }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Symbol(R.drawable.symbol_terminal, null, color = Ink.Link)
                Column(Modifier.weight(1f)) {
                    Text(body.ifBlank { "Executando comando" }, style = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val environment = command.string("environment") ?: "Ubuntu"
                    val details = listOfNotNull(environment, status, elapsed.takeIf { it >= 0 }?.let(::durationText),
                        code?.takeIf { it.toIntOrNull() != null }?.let { "Saída $it" })
                    Text(details.joinToString(" · "), color = Ink.Muted, style = MaterialTheme.typography.bodyMedium)
                }
                Symbol(R.drawable.symbol_expand_more, null, Modifier.rotate(rotation), Ink.Muted)
            }
            if (running && stop != null && (expanded || livePreview != null)) {
                TextButton(onClick = { stop(command.optString("id"), command.string("environment")) },
                    modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(if (command.string("environment") in setOf("ADB shell", "Ubuntu · Credencial"))
                        "Interromper execução" else "Interromper turno")
                }
            }
            if (!expanded && livePreview != null) {
                Text(livePreview, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis, style = mono, color = Ink.Muted)
            }
            AnimatedVisibility(expanded) {
                val content = if (livePreview == null) Modifier else Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())
                Column(content.padding(horizontal = 7.dp).padding(bottom = 7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HorizontalDivider(color = Ink.Divider)
                    if (body.isNotBlank()) CodePanel("Comando", body)
                    if (command.optBoolean("truncated")) Text("Mostrando o fim da saída.", Modifier.padding(10.dp), color = Ink.Muted)
                    if (output != null) CodePanel("Saída", output)
                    else Text(if (status == "Executando") "Aguardando saída…" else "Sem saída.",
                        Modifier.padding(10.dp), color = Ink.Muted)
                }
            }
        }
    }
}

@Composable private fun Approval(request: JSONObject, vm: MobileViewModel) {
    val params = request.optJSONObject("params")
    Surface(Modifier.fillMaxWidth().padding(bottom = 7.dp), color = Ink.Surface, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp)) {
            val target = params?.string("target")
            val credential = params?.string("name")
            Text(when {
                credential != null -> "Usar a credencial +$credential?"
                target != null -> "Permitir execução em $target?"
                else -> "Aprovar ação do Codex?"
            }, style = MaterialTheme.typography.titleMedium)
            params?.string("cwd")?.let { Text(it, style = mono, color = Ink.Muted) }
            val command = params?.string("command") ?: params?.string("reason") ?: params?.string("grantRoot") ?: "Permissão para alterar arquivos"
            if (target == null) Text(command, style = mono, maxLines = 4, overflow = TextOverflow.Ellipsis)
            else Text(command, Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState()), style = mono)
            Row {
                TextButton(onClick = { vm.approve(request.optString("id"), false) }) { Text("Recusar") }
                TextButton(onClick = { vm.approve(request.optString("id"), true) }) { Text("Permitir") }
            }
        }
    }
}
