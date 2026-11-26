package local.codex.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import local.codex.mobile.R
import local.codex.mobile.core.MobileState
import local.codex.mobile.core.MobileViewModel
import local.codex.mobile.core.objects
import local.codex.mobile.core.string
import org.json.JSONObject

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun ConversationList(
    state: MobileState,
    create: () -> Unit,
    select: (String) -> Unit,
    settings: () -> Unit,
    search: () -> Unit,
    vm: MobileViewModel,
    modifier: Modifier = Modifier
) {
    var query by rememberSaveable { mutableStateOf("") }
    var archived by rememberSaveable { mutableStateOf(false) }
    var chatOptions by remember { mutableStateOf<JSONObject?>(null) }
    chatOptions?.let { selected -> ConversationActions(selected, vm) { chatOptions = null } }
    Column(modifier.fillMaxHeight().padding(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, top = 8.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Codex Mobile", style = MaterialTheme.typography.titleLarge)
                Text("Conversas", color = Ink.Muted, style = MaterialTheme.typography.bodyMedium)
            }
            ControlButton(R.drawable.symbol_search, "Buscar conversas", search)
        }
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = Ink.Divider)
        Spacer(Modifier.height(6.dp))
        val local = state.queue.distinctBy { it.chat }.filter { entry ->
            state.chats.none { it.optString("id") == entry.chat }
        }.map { entry ->
            JSONObject().put("id", entry.chat).put("title", entry.text.take(64).ifBlank { "Nova conversa" })
        }
        if (state.chats.any { it.optBoolean("archived") } || archived) {
            TextButton(onClick = { archived = !archived }) { Text(if (archived) "Ver ativas" else "Ver arquivadas") }
        }
        val list = (local + state.chats).filter {
            it.optBoolean("archived") == archived && it.optString("title").contains(query, ignoreCase = true)
        }.sortedByDescending { it.optBoolean("pinned") }
        LazyColumn(Modifier.weight(1f)) {
            items(list, key = { it.getString("id") }) { chat ->
                val id = chat.getString("id")
                val active = id == state.active
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (active) Ink.Control else Ink.Surface,
                    border = if (active) BorderStroke(1.dp, Ink.Contour) else null
                ) {
                    val canManage = state.connected && !state.busy && state.chats.any { it.optString("id") == id }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).combinedClickable(
                        onClick = { select(id) },
                        onLongClickLabel = "Opções da conversa",
                        onLongClick = if (canManage) ({ chatOptions = chat }) else null
                    )
                        .semantics { selected = active }.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(chat.optString("title"), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (chat.optBoolean("running")) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)

                    }
                }
            }
            if (list.isEmpty()) item {
                Text(if (query.isBlank()) "Suas conversas aparecem aqui." else "Nenhuma conversa encontrada.",
                    Modifier.padding(12.dp), color = Ink.Muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        HorizontalDivider(color = Ink.Divider)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { query = ""; create() },
                shape = RoundedCornerShape(22.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.heightIn(min = 46.dp)
            ) {
                Symbol(R.drawable.symbol_add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Nova conversa")
            }
            Spacer(Modifier.weight(1f))
            ControlButton(R.drawable.symbol_settings, "Configurações", settings)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ConfigurationSheet(state: MobileState, dismiss: () -> Unit, apply: (String, String) -> Unit) {
    val originalModel = state.chat?.string("model").orEmpty()
    val originalEffort = state.chat?.string("effort").orEmpty()
    var model by rememberSaveable { mutableStateOf(originalModel) }
    var effort by rememberSaveable { mutableStateOf(originalEffort) }
    val catalog = state.catalog.mapNotNull { entry -> entry.string("model")?.let { it to (entry.string("displayName") ?: it) } }
    val modelEntry = state.catalog.firstOrNull { it.string("model") == model }
        ?: state.catalog.firstOrNull { it.optBoolean("isDefault") }
    val efforts = modelEntry?.optJSONArray("supportedReasoningEfforts").objects().mapNotNull { entry ->
        entry.string("reasoningEffort")?.let { it to effortName(it) }
    }

    ModalBottomSheet(onDismissRequest = dismiss, containerColor = Ink.Surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 16.dp)) {
            Text("Configurar conversa", Modifier.padding(start = 4.dp, bottom = 14.dp), style = MaterialTheme.typography.titleLarge)
            Surface(color = Ink.Elevated, shape = RoundedCornerShape(16.dp)) {
                Column {
                    ConfigurationRow("Modelo", modelEntry?.string("displayName") ?: model.ifBlank { "Padrão" },
                        catalog, modelEntry?.string("model") ?: model) {
                        if (it != (modelEntry?.string("model") ?: model)) {
                            model = it
                            effort = ""
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 15.dp), color = Ink.Divider)
                    ConfigurationRow("Esforço", if (effort.isEmpty()) "Padrão" else effortName(effort),
                        if (efforts.isEmpty()) emptyList() else listOf("" to "Padrão do modelo") + efforts, effort) {
                        effort = it
                    }
                }
            }
            if (state.catalogLoading && catalog.isEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp), color = Ink.Accent)
            } else if (catalog.isEmpty()) {
                Text("Catálogo indisponível.", Modifier.padding(start = 4.dp, top = 10.dp), color = Ink.Muted)
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    if (model == originalModel && effort == originalEffort) dismiss()
                    else apply(model.ifBlank { modelEntry?.string("model").orEmpty() }, effort)
                },
                enabled = state.connected && !state.busy && !state.running,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) { Text("Concluir") }
        }
    }
}

@Composable private fun ConfigurationRow(
    label: String, value: String, options: List<Pair<String, String>>,
    selected: String, choose: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(label) },
            trailingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(value, Modifier.widthIn(max = 170.dp), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = Ink.Muted)
                    Symbol(R.drawable.symbol_expand_more, null, color = Ink.Muted)
                }
            },
            modifier = Modifier.clickable(enabled = options.isNotEmpty()) { expanded = true },
            colors = ListItemDefaults.colors(containerColor = Ink.Elevated)
        )
        Box(Modifier.align(Alignment.BottomEnd)) {
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(max = 265.dp).heightIn(max = 360.dp)) {
                for ((key, name) in options) {
                    DropdownMenuItem(
                        text = { Text(name) },
                        trailingIcon = if (key == selected) {
                            { Symbol(R.drawable.symbol_check, "Selecionado", color = Ink.Link) }
                        } else null,
                        onClick = { expanded = false; choose(key) },
                        modifier = Modifier.semantics { this.selected = key == selected }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AttachmentSheet(dismiss: () -> Unit, open: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = Ink.Surface) {
        Text("Adicionar ao chat", Modifier.padding(start = 22.dp, bottom = 8.dp), style = MaterialTheme.typography.titleLarge)
        listOf(
            Triple("files", "Arquivos", R.drawable.symbol_attach_file),
            Triple("images", "Imagens", R.drawable.symbol_image),
            Triple("projects", "Pasta do projeto", R.drawable.symbol_folder_open)
        ).forEach { (mode, label, icon) ->
            ListItem(
                modifier = Modifier.clickable { open(mode) },
                headlineContent = { Text(label) },
                leadingContent = { Symbol(icon, null, color = Ink.Link) },
                colors = ListItemDefaults.colors(containerColor = Ink.Surface)
            )
        }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable internal fun Explorer(
    state: MobileState,
    vm: MobileViewModel,
    mode: String,
    shared: Boolean,
    back: () -> Unit,
    storage: () -> Unit,
    initialFile: String? = null
) {
    val project = mode == "projects"
    val browse = mode == "browse"
    val root = if (shared) "/sdcard" else "/root/Projetos"
    var location by rememberSaveable { mutableStateOf(initialFile?.substringBeforeLast('/')?.ifBlank { "/" }
        ?: state.chat?.string("cwd") ?: root) }
    var filePreview by rememberSaveable { mutableStateOf(initialFile) }
    var folderDialog by rememberSaveable { mutableStateOf(false) }
    var folderName by rememberSaveable { mutableStateOf("") }
    var creationError by rememberSaveable { mutableStateOf<String?>(null) }
    var externalError by remember { mutableStateOf<String?>(null) }
    var showVolumes by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    var hidden by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val folder = state.folders
    val resolved = folder?.string("location")
    val sectionTitle = when (mode) {
        "images" -> "Imagens"
        "files", "browse" -> "Arquivos"
        else -> "Projetos"
    }
    LaunchedEffect(location, hidden, shared, state.connected) { vm.folders(location, hidden, mode) }

    Column(Modifier.fillMaxSize()) {
        PageTitle(sectionTitle, back)
        Text(resolved ?: location, Modifier.padding(start = 18.dp, end = 18.dp, bottom = 8.dp), color = Ink.Muted, style = mono)
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { location = root }) {
                Text(if (shared) "Armazenamento" else "Espaço privado", color = Ink.Link)
            }
            if (shared) TextButton(onClick = { showVolumes = true }) { Text("Volumes", color = Ink.Link) }
            Spacer(Modifier.weight(1f))
            FilterChip(selected = hidden, onClick = { hidden = !hidden }, label = { Text("Ocultas") })
        }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = state.connected && resolved != null && !state.busy, onClick = {
                folderName = ""
                creationError = null
                folderDialog = true
            }) {
                Symbol(R.drawable.symbol_add, null)
                Spacer(Modifier.width(4.dp))
                Text("Nova pasta")
            }
            if (externalFolderUri(resolved ?: location) != null) {
                TextButton(onClick = {
                    externalError = try {
                        openExternalFolder(context, resolved ?: location)
                        null
                    } catch (_: android.content.ActivityNotFoundException) { "Explorador externo indisponível." }
                }) { Text("Explorador externo") }
            }
        }
        externalError?.let { Text(it, Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.error) }
        HorizontalDivider(color = Ink.Divider)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            state.folderError?.let { error ->
                item { Text(error, Modifier.padding(18.dp), color = MaterialTheme.colorScheme.error) }
            }
            if (folder != null && resolved != null) {
                val parent = folder.string("parent")
                if (parent != null && parent != resolved) item {
                    FolderRow("Pasta acima", R.drawable.symbol_arrow_back) { location = parent }
                }
                val contents = folder.optJSONArray("folders")
                val folders = if (contents == null) emptyList() else (0 until contents.length()).map(contents::getString)
                items(folders, key = { "dir:$it" }) { dir ->
                    FolderRow(dir, R.drawable.symbol_folder) { location = resolved.trimEnd('/') + "/" + dir }
                }
                val candidates = folder.optJSONArray("files")
                val files = if (candidates == null) emptyList() else (0 until candidates.length()).map(candidates::getString)
                items(files, key = { "file:$it" }) { file ->
                    val filePath = resolved.trimEnd('/') + "/" + file
                    val checked = filePath in selected
                    val toggle = {
                        if (browse) filePreview = filePath
                        else if (checked || selected.size < 20) selected = ArrayList(
                            if (checked) selected - filePath else selected + filePath
                        )
                    }
                    ListItem(
                        modifier = Modifier.clickable(onClick = toggle).semantics { this.selected = checked },
                        headlineContent = { Text(file) },
                        leadingContent = { Symbol(if (mode == "images") R.drawable.symbol_image else R.drawable.symbol_description, null, color = Ink.Link) },
                        trailingContent = {
                            if (browse) Symbol(R.drawable.symbol_chevron_right, null, color = Ink.Muted)
                            else Checkbox(checked, onCheckedChange = null)
                        },
                        colors = ListItemDefaults.colors(containerColor = Ink.Background)
                    )
                }
                if (files.isEmpty() && folders.isEmpty()) item {
                    Text("Pasta vazia para esta seleção.", Modifier.padding(18.dp), color = Ink.Muted)
                }
            } else if (state.folderError == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
        if (!shared) TextButton(onClick = storage, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Habilitar pastas do Android", color = Ink.Link)
        }
        if (!browse) Button(onClick = {
            if (project) resolved?.let { vm.chooseFolder(it, back) }
            else vm.attach(selected, back)
        }, enabled = (if (project) resolved != null else selected.isNotEmpty()) && !state.busy && !state.running,
            modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp)) {
            Text(if (project) "Usar esta pasta" else "Anexar (${selected.size})")
        }
    }
    if (showVolumes) {
        val manager = context.getSystemService(android.os.storage.StorageManager::class.java)
        val volumes = manager?.storageVolumes.orEmpty().filter {
            it.state == android.os.Environment.MEDIA_MOUNTED || it.state == android.os.Environment.MEDIA_MOUNTED_READ_ONLY
        }
        AlertDialog(onDismissRequest = { showVolumes = false }, title = { Text("Volumes do Android") },
            text = {
                Column {
                    if (volumes.isEmpty()) Text("Nenhum volume disponível.")
                    volumes.forEach { volume ->
                        val address = if (volume.isPrimary) "/sdcard" else volume.directory?.absolutePath
                        TextButton(enabled = address != null, onClick = {
                            if (address != null) location = address
                            showVolumes = false
                        }) { Text(volume.getDescription(context)) }
                    }
                }
            }, confirmButton = { TextButton(onClick = { showVolumes = false }) { Text("Fechar") } })
    }
    filePreview?.let { path ->
        ArtifactDialog(path, openDirectory = { directory ->
            location = directory
            filePreview = null
        }) { filePreview = null }
    }
    if (folderDialog) AlertDialog(
        onDismissRequest = { if (!state.busy) folderDialog = false },
        title = { Text("Nova pasta") },
        text = {
            OutlinedTextField(folderName, { folderName = it; creationError = null },
                label = { Text("Nome da pasta") }, singleLine = true, isError = creationError != null,
                supportingText = { creationError?.let { Text(it) } })
        },
        confirmButton = {
            TextButton(enabled = state.connected && !state.busy && folderName.isNotBlank() && resolved != null,
                onClick = {
                    resolved?.let { parent ->
                        vm.createFolder(parent, folderName, mode, hidden, { folderDialog = false }, { creationError = it })
                    }
                }) { Text("Criar") }
        },
        dismissButton = { TextButton(enabled = !state.busy, onClick = { folderDialog = false }) { Text("Cancelar") } }
    )
}

@Composable private fun FolderRow(label: String, icon: Int, open: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = open),
        headlineContent = { Text(label) },
        leadingContent = { Symbol(icon, null, color = Ink.Link) },
        trailingContent = { Symbol(R.drawable.symbol_chevron_right, null, color = Ink.Muted) },
        colors = ListItemDefaults.colors(containerColor = Ink.Background)
    )
}

@Composable internal fun PageTitle(label: String, back: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = back) { Symbol(R.drawable.symbol_arrow_back, "Voltar") }
        Text(label, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable internal fun Settings(state: MobileState, back: () -> Unit, usage: () -> Unit, storage: () -> Unit, logout: () -> Unit, connectTermux: () -> Unit, connectShizuku: () -> Unit, background: Boolean, setBackground: (Boolean) -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PageTitle("Configurações", back)
        val email = state.account?.string("email")
        if (email != null) SettingsRow(email, state.account.string("planType"), R.drawable.symbol_account_circle)
        ListItem(
            headlineContent = { Text("Executar em segundo plano") },
            supportingContent = { Text("Mantém o ambiente ativo com uma notificação.") },
            trailingContent = { Switch(checked = background, onCheckedChange = setBackground) },
            colors = ListItemDefaults.colors(containerColor = Ink.Background)
        )
        SettingsRow("Uso e ambiente", "Limites, modelo e Ubuntu", R.drawable.symbol_terminal, usage)
        SettingsRow("Pastas do Android", "Acesso ao armazenamento compartilhado", R.drawable.symbol_folder_open, storage)
        SettingsRow("Conectar Termux", "Ferramenta opcional termux_shell", R.drawable.symbol_terminal, connectTermux)
        SettingsRow("Conectar Shizuku", "Ferramenta opcional adb_shell", R.drawable.symbol_terminal, connectShizuku)
        if (state.account != null) SettingsRow("Sair da conta", null, R.drawable.symbol_logout) { confirm = true }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Sair da conta?") },
        text = { Text("Você pode entrar de novo para continuar usando suas conversas.") },
        confirmButton = { TextButton(onClick = { confirm = false; logout() }) { Text("Sair") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } }
    )
}

@Composable private fun SettingsRow(title: String, detail: String?, icon: Int, action: (() -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = detail?.let { { Text(it) } },
        leadingContent = { Symbol(icon, null, color = Ink.Link) },
        trailingContent = if (action != null) { { Symbol(R.drawable.symbol_chevron_right, null, color = Ink.Muted) } } else null,
        modifier = if (action != null) Modifier.clickable(onClick = action) else Modifier,
        colors = ListItemDefaults.colors(containerColor = Ink.Background)
    )
}

@Composable internal fun Usage(state: MobileState, back: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PageTitle("Uso e ambiente", back)
        val data = state.usage
        if (data == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else {
            val quota = data.optJSONObject("limits")?.optJSONObject("rateLimits")
            val tokens = state.chat?.optJSONObject("usage")?.optJSONObject("total")?.string("totalTokens")
            val rows = listOf(
                "Ambiente" to "Ubuntu / PRoot",
                "Pasta do projeto" to (state.chat?.string("cwd") ?: data.string("workspace")),
                "Modelo da conversa" to state.chat?.string("model"),
                "Tokens da conversa" to tokens,
                "Limite primário usado" to quota?.optJSONObject("primary")?.string("usedPercent")?.plus("%"),
                "Limite secundário usado" to quota?.optJSONObject("secondary")?.string("usedPercent")?.plus("%")
            )
            LazyColumn {
                for ((title, value) in rows) if (value != null) item {
                    SettingsRow(title, value, R.drawable.symbol_terminal)
                }
            }
        }
    }
}
