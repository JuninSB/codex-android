package local.codex.mobile.core

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

internal data class QueuedMessage(
    val id: String,
    val chat: String,
    val text: String,
    val attachments: JSONArray,
    val createChat: Boolean,
    val yolo: Boolean,
    val error: String? = null
) {
    fun json() = obj("id" to id, "chat" to chat, "text" to text, "attachments" to attachments,
        "createChat" to createChat, "yolo" to yolo, "error" to error)
}

internal data class MobileState(
    val startup: StartupProgress? = StartupProgress("Preparando Ubuntu…", "Verificando o ambiente local."),
    val connected: Boolean = false,
    val connectionError: String? = null,
    val syncingChat: Boolean = false,
    val accountLoaded: Boolean = false,
    val account: JSONObject? = null,
    val login: JSONObject? = null,
    val loginError: String? = null,
    val active: String? = null,
    val chat: JSONObject? = null,
    val chats: List<JSONObject> = emptyList(),
    val approvals: List<JSONObject> = emptyList(),
    val catalog: List<JSONObject> = emptyList(),
    val catalogLoading: Boolean = false,
    val folders: JSONObject? = null,
    val folderError: String? = null,
    val usage: JSONObject? = null,
    val rateLimits: JSONObject? = null,
    val rateLimitsLoading: Boolean = false,
    val error: String? = null,
    val busy: Boolean = false,
    val prompt: String = "",
    val vaultExists: Boolean = false,
    val vaultUnlocked: Boolean = false,
    val vaultBusy: Boolean = false,
    val vaultError: String? = null,
    val credentials: List<CredentialLabel> = emptyList(),
    val credentialRequest: JSONObject? = null,
    val deviceControlChat: String? = null,
    val deviceControlRequest: JSONObject? = null,
    val attachments: List<Attachment> = emptyList(),
    val queue: List<QueuedMessage> = emptyList(),
    val sending: String? = null,
    val networkAvailable: Boolean = true,
    val yolo: Boolean = false
) {
    val running get() = connected && chat?.optBoolean("running") == true
}

internal class MobileViewModel(application: Application) : AndroidViewModel(application) {
    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
    private val runtime = UbuntuRuntime.get(application)
    private val prefs = application.getSharedPreferences("codex-mobile", 0)
    private val vault = CredentialVault(application)
    private var vaultSession: CredentialVault.Session? = null
    private val androidInFlight = mutableSetOf<String>()
    private val mutable = MutableStateFlow(MobileState(
        vaultExists = vault.exists(),
        yolo = prefs.getBoolean("yolo", false),
        queue = runCatching { JSONArray(prefs.getString("outbox", "[]")) }.getOrNull().objects().map {
            QueuedMessage(it.getString("id"), it.getString("chat"), it.getString("text"),
                it.getJSONArray("attachments"), it.optBoolean("createChat"), it.optBoolean("yolo"), it.string("error"))
        }
    ))
    val state = mutable.asStateFlow()
    private lateinit var client: CodexClient
    private var lifecycle: Job? = null
    private var folderRequest: Job? = null
    private var nextSnapshot = 0
    private var lastVersion = -1
    private var lastChat: String? = null
    private val chatCache = mutableMapOf<String, JSONObject>()
    private val drafts = mutableMapOf<String?, Pair<String, List<Attachment>>>()
    private val catalogPrefs = application.getSharedPreferences("codex-mobile-catalog", 0)
    private var catalogFingerprint: String? = null

    init {
        viewModelScope.launch {
            val titles = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { JSONArray(catalogPrefs.getString("titles", "[]")) }.getOrNull().objects()
            }
            mutable.update { if (!it.connected && it.chats.isEmpty()) it.copy(chats = titles) else it }
        }
    }


    suspend fun searchConversations(query: String): List<JSONObject> {
        if (!state.value.connected) return state.value.chats.filter {
            !it.optBoolean("archived") && it.optString("title").contains(query, ignoreCase = true)
        }
        return client.get("search", mapOf("q" to query)).optJSONArray("results").objects()
    }

    fun setYolo(enabled: Boolean) {
        prefs.edit().putBoolean("yolo", enabled).apply()
        mutable.update { it.copy(yolo = enabled) }
    }

    fun ensureStarted() {
        if (lifecycle == null) start()
    }

    fun start() {
        lifecycle?.cancel()
        lifecycle = viewModelScope.launch {
            var retryDelay = 750L
            while (true) {
                try {
                    mutable.update { it.copy(connected = false) }
                    client = runtime.start { message -> mutable.update { it.copy(startup = message) } }
                    lastVersion = -1
                    mutable.update { it.copy(startup = null, connected = true, connectionError = null) }
                    kotlinx.coroutines.coroutineScope {
                        launch { refreshAccount() }
                        refresh()
                    }
                    retryDelay = 750L
                    while (true) {
                        val online = connectivity?.let { manager ->
                            manager.getNetworkCapabilities(manager.activeNetwork)
                                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                        } ?: true
                        mutable.update { it.copy(networkAvailable = online) }
                        if (state.value.login != null) refreshAccount()
                        if (online) flushQueue()
                        delay(if (state.value.running) 350 else 1100)
                        refresh()
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    val status = if (error is CodexHttpException) " HTTP ${error.status}" else ""
                    android.util.Log.w("CodexRuntime", "Reconexão: ${error.javaClass.simpleName}$status")
                    mutable.update { it.copy(startup = null, connected = false, syncingChat = false,
                        connectionError = "Conexão com o Ubuntu interrompida. Reconectando…") }
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(12_000L)
                }
            }
        }
    }

    fun storageChanged() {
        if (state.value.busy || state.value.chats.any { it.optBoolean("running") }) return
        start()
    }

    private suspend fun refresh() {
        val current = state.value.active
        val sequence = ++nextSnapshot
        val query = buildMap {
            current?.let { put("chat", it) }
            if (current == lastChat && lastVersion >= 0 && (current == null || chatCache.containsKey(current))) {
                put("since", lastVersion.toString())
            }
        }
        val data = client.get("state", query)
        if (sequence != nextSnapshot || current != state.value.active) return
        if (state.value.connected) {
            for (request in data.optJSONArray("automatic").objects()) {
                val chat = request.getString("chatId")
                val id = request.getString("id")
                if (androidInFlight.add(id)) work {
                    try { executeAndroid(chat, id, automatic = true) }
                    finally { androidInFlight.remove(id) }
                }
            }
        }
        val deviceSession = DeviceControlSession.chat
        mutable.update { if (it.deviceControlChat != deviceSession) it.copy(deviceControlChat = deviceSession) else it }
        if (deviceSession != null && deviceSession == current && DeviceControlService.connected) {
            for (request in data.optJSONArray("approvals").objects()) {
                val id = request.optString("id")
                if (request.optJSONObject("params")?.optString("target") == "Controle do dispositivo" && androidInFlight.add(id)) work {
                    try { executeAndroid(deviceSession, id) } finally { androidInFlight.remove(id) }
                }
            }
        }
        if (data.optBoolean("unchanged")) return
        val version = data.optInt("version")
        if (version == lastVersion && current == lastChat) return
        val selectedChat = data.optJSONObject("chat")
        if (selectedChat != null && data.optBoolean("messagesDelta") && current != null) {
            val cached = chatCache[current]?.optJSONArray("messages")
            val changed = selectedChat.optJSONArray("messages").objects()
            if (changed.isEmpty()) selectedChat.put("messages", cached)
            else {
                val merged = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val entries = LinkedHashMap<String, JSONObject>()
                    for (message in cached.objects()) entries[message.getString("id")] = message
                    for (message in changed) entries[message.getString("id")] = message
                    JSONArray(entries.values)
                }
                if (sequence != nextSnapshot || current != state.value.active) return
                selectedChat.put("messages", merged)
            }
        }
        lastVersion = version
        lastChat = current
        if (current != null && selectedChat != null) {
            chatCache.remove(current)
            chatCache[current] = selectedChat
            if (chatCache.size > 6) chatCache.remove(chatCache.keys.first())
        }
        val titles = data.optJSONArray("chats").objects()
        val catalog = JSONArray(titles.map { row ->
            obj("id" to row.optString("id"), "title" to row.optString("title"),
                "pinned" to row.optBoolean("pinned"), "archived" to row.optBoolean("archived"))
        }).toString()
        if (catalog != catalogFingerprint) {
            catalogFingerprint = catalog
            catalogPrefs.edit().putString("titles", catalog).apply()
        }
        mutable.update {
            it.copy(chat = selectedChat, chats = titles, syncingChat = false,
                approvals = data.optJSONArray("approvals").objects())
        }
    }

    private suspend fun refreshAccount() {
        val data = client.get("account")
        mutable.update {
            it.copy(
                accountLoaded = true,
                account = data.optJSONObject("account"),
                login = data.optJSONObject("login"),
                loginError = data.optJSONObject("loginOutcome")?.takeUnless { status -> status.optBoolean("success") }?.string("error")
            )
        }
    }

    private fun work(lock: Boolean = false, operation: suspend () -> Unit) {
        if (!state.value.connected || (lock && state.value.busy)) return
        if (lock) mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                mutable.update { it.copy(error = null) }
                operation()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(error = if (error is IOException) "O Ubuntu interrompeu a conexão. Aguarde a reconexão."
                    else error.message ?: "A operação falhou.") }
            } finally {
                if (lock) mutable.update { it.copy(busy = false) }
            }
        }
    }

    fun clearError() = mutable.update { it.copy(error = null) }
    fun prompt(text: String) = mutable.update { it.copy(prompt = text) }
    fun remove(attachment: Attachment) = mutable.update { it.copy(attachments = it.attachments - attachment) }

    private fun switch(id: String?) {
        val current = state.value
        if (id == current.active) return
        drafts[current.active] = current.prompt to current.attachments
        val draft = drafts[id]
        mutable.update {
            it.copy(active = id, chat = chatCache[id], syncingChat = id != null && it.connected, approvals = emptyList(),
                prompt = draft?.first.orEmpty(), attachments = draft?.second.orEmpty())
        }
    }

    fun conversationAction(id: String, action: String, value: Boolean? = null, title: String? = null) = work(true) {
        if (action == "delete" && state.value.queue.any { it.chat == id }) {
            error("Remova as mensagens pendentes antes de excluir a conversa.")
        }
        client.post("conversation", obj("chat" to id, "action" to action, "value" to value, "title" to title))
        if (action == "delete" || (action == "archive" && value == true)) {
            chatCache.remove(id)
            if (state.value.active == id) switch(null)
        }
        refresh()
    }

    fun select(id: String) {
        switch(id)
        if (state.value.connected) work { refresh() }
    }
    fun newChat() = switch(null)

    private suspend fun ensureChat(cwd: String? = null): String {
        val before = state.value
        if (before.active != null) {
            if (before.queue.any { it.chat == before.active && it.createChat }) {
                client.post("new", obj("id" to before.active, "cwd" to cwd))
            }
            return before.active
        }
        val id = client.post("new", obj("cwd" to cwd)).getString("id")
        drafts[id] = before.prompt to before.attachments
        switch(id)
        refresh()
        drafts.remove(null)
        return id
    }

    fun login(device: Boolean, open: (String) -> Unit) = work(true) {
        val handoff = client.post("login", obj("device" to device))
        mutable.update { it.copy(login = handoff, loginError = null) }
        handoff.string("authUrl")?.let(open)
    }

    fun cancelLogin() = work(true) {
        client.post("login/cancel")
        refreshAccount()
    }

    fun logout() = work(true) {
        client.post("logout")
        lockVault()
        refreshAccount()
    }

    fun models() = work {
        mutable.update { it.copy(catalogLoading = true) }
        try {
            val data = client.models()
            mutable.update { it.copy(catalog = data) }
        } finally {
            mutable.update { it.copy(catalogLoading = false) }
        }
    }

    fun configure(model: String, effort: String, applied: () -> Unit) = work(true) {
        val id = ensureChat()
        client.post("settings", obj("chat" to id, "model" to model, "effort" to effort))
        refresh()
        applied()
    }

    fun rateLimits() = work {
        mutable.update { it.copy(rateLimitsLoading = true, rateLimits = null) }
        try {
            val limits = client.get("rate-limits")
            mutable.update { it.copy(rateLimits = limits) }
        } finally {
            mutable.update { it.copy(rateLimitsLoading = false) }
        }
    }

    fun usage() = work {
        val data = client.get("status")
        mutable.update { it.copy(usage = data) }
    }

    fun folders(location: String, hidden: Boolean, mode: String) {
        if (!state.value.connected) return
        folderRequest?.cancel()
        mutable.update { it.copy(folders = null, folderError = null) }
        folderRequest = viewModelScope.launch {
            try {
                val data = client.get("folders", mapOf("path" to location, "hidden" to hidden.toString(), "mode" to mode))
                mutable.update { it.copy(folders = data) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(folderError = error.message ?: "Não foi possível abrir a pasta.") }
            }
        }
    }

    fun mediaUrl(path: String): String? = if (state.value.connected) client.mediaUrl(path) else null

    fun stopTool(id: String, environment: String?) = work {
        val chat = state.value.active ?: return@work
        if (environment == "Ubuntu · Credencial" || environment == "ADB shell") {
            client.post("tool/stop", obj("chat" to chat, "id" to id))
            AndroidShell.stop(id)
        } else client.post("stop", obj("chat" to chat))
        refresh()
    }

    suspend fun readFile(path: String, export: Boolean, metadata: Boolean): LocalArtifact {
        check(state.value.connected) { "Aguardando conexão com o Ubuntu." }
        val data = client.get("file", mapOf("path" to path, "export" to export.toString(), "metadata" to metadata.toString()))
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            LocalArtifact(data.getString("name"), data.optBoolean("image"), data.optBoolean("truncated"),
                android.util.Base64.decode(data.optString("content"), android.util.Base64.DEFAULT),
                data.string("path").takeIf { data.optBoolean("directory") }, data.optLong("bytes", -1))
        }
    }

    fun createFolder(parent: String, name: String, mode: String, hidden: Boolean, created: () -> Unit,
                     failed: (String) -> Unit) = work(true) {
        try {
            client.post("mkdir", obj("path" to parent, "name" to name))
            folders(parent, hidden, mode)
            created()
        } catch (error: CodexHttpException) {
            failed(error.message ?: "Não foi possível criar a pasta.")
        } catch (error: IOException) {
            failed("Conexão interrompida. Confira a pasta ao reconectar.")
        }
    }

    fun chooseFolder(path: String, close: () -> Unit) = work(true) {
        val current = state.value
        if (current.active == null || current.chat == null) ensureChat(path)
        else client.post("settings", obj("chat" to current.active, "cwd" to path))
        refresh()
        close()
    }

    fun attach(paths: List<String>, close: () -> Unit) = work(true) {
        if (paths.isEmpty()) return@work
        val id = ensureChat()
        val response = client.post("attach-local", obj("chat" to id, "paths" to JSONArray(paths)))
        val files = response.optJSONArray("attachments").objects().map {
            Attachment(it.getString("id"), it.getString("name"), it.optBoolean("image"))
        }
        if (state.value.active == id) mutable.update { it.copy(attachments = it.attachments + files) }
        else drafts[id]?.let { drafts[id] = it.first to (it.second + files) }
        close()
    }

    fun send() {
        val snapshot = state.value
        if (snapshot.prompt.isBlank() && snapshot.attachments.isEmpty()) {
            if (snapshot.running) work(true) {
                client.post("stop", obj("chat" to snapshot.active))
            }
            return
        }
        if (snapshot.prompt.toByteArray(Charsets.UTF_8).size > 1024 * 1024) {
            report("A mensagem ultrapassa 1 MB. Envie o conteúdo maior como anexo.")
            return
        }
        if (snapshot.vaultExists && !snapshot.vaultUnlocked && mentionedAliases(snapshot.prompt).isNotEmpty()) {
            report("Desbloqueie o cofre em Credenciais para enviar os detalhes da menção.")
            return
        }
        val localChat = snapshot.active ?: UUID.randomUUID().toString()
        val entry = QueuedMessage(UUID.randomUUID().toString(), localChat, snapshot.prompt,
            JSONArray(snapshot.attachments.map(Attachment::data)),
            snapshot.active == null || snapshot.queue.any { it.chat == localChat && it.createChat },
            snapshot.yolo)
        val queue = snapshot.queue + entry
        if (!prefs.edit().putString("outbox", encodeQueue(queue)).commit()) {
            report("Não foi possível salvar a mensagem na fila.")
            return
        }
        mutable.update { it.copy(queue = queue, active = localChat, prompt = "", attachments = emptyList()) }
    }

    fun removeQueued(id: String) {
        if (state.value.sending == id) return
        val snapshot = state.value
        val queue = snapshot.queue.filterNot { it.id == id }
        updateQueue(queue)
        if (snapshot.queue.any { it.id == id && it.chat == snapshot.active && it.createChat } &&
            queue.none { it.chat == snapshot.active } && snapshot.chats.none { it.string("id") == snapshot.active }
        ) switch(null)
    }

    private fun mentionedAliases(text: String): Set<String> =
        Regex("""(?<![\w+])\+([a-zA-Z0-9][a-zA-Z0-9_.-]{0,39})(?![a-zA-Z0-9_.-])""")
            .findAll(text).map { it.groupValues[1].trimEnd('.').lowercase() }.toSet()

    private fun encodeQueue(queue: List<QueuedMessage>) = JSONArray(queue.map(QueuedMessage::json)).toString()

    private fun updateQueue(queue: List<QueuedMessage>) {
        prefs.edit().putString("outbox", encodeQueue(queue)).apply()
        mutable.update { it.copy(queue = queue) }
    }

    private suspend fun flushQueue() {
        val snapshot = state.value
        if (snapshot.account == null || snapshot.busy) return
        val entry = snapshot.queue.firstOrNull { queued ->
            (!snapshot.vaultExists || snapshot.vaultUnlocked || mentionedAliases(queued.text).isEmpty()) &&
                queued.error == null && snapshot.chats.none { it.string("id") == queued.chat && it.optBoolean("running") }
        } ?: return
        mutable.update { it.copy(sending = entry.id) }
        try {
            if (entry.createChat) client.post("new", obj("id" to entry.chat))
            val aliases = mentionedAliases(entry.text)
            val metadata = vaultSession?.entries.orEmpty().filter { it.label.name in aliases }.map(Credential::metadata)
            client.post("send", entry.json().put("requestId", entry.id).put("credentialMetadata", JSONArray(metadata)))
            updateQueue(state.value.queue.filterNot { it.id == entry.id })
        } catch (error: CodexHttpException) {
            if (error.status == 409) return
            if (error.status >= 500) throw IOException(error)
            updateQueue(state.value.queue.map {
                if (it.id == entry.id) it.copy(error = error.message) else it
            })
        } finally {
            mutable.update { it.copy(sending = null) }
        }
    }

    private fun vaultAction(block: suspend () -> Unit) {
        if (state.value.vaultBusy) return
        mutable.update { it.copy(vaultBusy = true, vaultError = null) }
        viewModelScope.launch {
            try { block() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(vaultError = error.message ?: "O cofre não respondeu.") }
            } finally { mutable.update { it.copy(vaultBusy = false) } }
        }
    }

    fun unlockVault(password: String, create: Boolean) = vaultAction {
        vaultSession = vault.open(password, create)
        mutable.update { it.copy(vaultExists = true, vaultUnlocked = true,
            credentials = vaultSession!!.entries.map(Credential::label)) }
    }

    fun lockVault() {
        vaultSession = null
        mutable.update { it.copy(vaultUnlocked = false, credentials = emptyList(),
            vaultError = null, credentialRequest = null) }
    }

    fun addCredential(name: String, description: String, instructions: String, type: CredentialType, secret: String,
                      username: String, host: String, port: String, saved: () -> Unit) = vaultAction {
        val session = checkNotNull(vaultSession) { "Desbloqueie o cofre." }
        val alias = name.trim().lowercase()
        require(alias.matches(Regex("[a-z0-9][a-z0-9_.-]{0,39}"))) {
            "No nome, use até 40 letras sem acento, números, pontos, hífens ou sublinhados."
        }
        require(session.entries.none { it.label.name == alias }) { "Esse nome já existe." }
        require(secret.isNotBlank() && secret.length <= 20000 && '\u0000' !in secret) { "Informe um segredo válido." }
        require(instructions.length <= 1000) { "Use até 1000 caracteres nas instruções." }
        require(!description.contains(secret) && !instructions.contains(secret)) {
            "Mantenha o valor secreto no campo protegido."
        }
        require(description.length <= 1000) { "Use até 1000 caracteres na descrição." }
        val user = if (type == CredentialType.TOKEN) "" else username.trim()
        if (type != CredentialType.TOKEN) require(user.isNotBlank() && user.length <= 256) { "Informe o usuário." }
        val address = if (type == CredentialType.SSH) host.trim() else ""
        if (type == CredentialType.SSH) require(address.isNotBlank() && address.length <= 256) { "Informe o IP ou host." }
        val sshPort = if (type == CredentialType.SSH) port.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: error("Informe uma porta entre 1 e 65535.") else 22
        val entry = Credential(CredentialLabel(alias, description.trim(), type, instructions.trim()), secret, user, address, sshPort)
        val updated = vault.save(session, session.entries + entry)
        vaultSession = updated
        mutable.update { it.copy(credentials = updated.entries.map(Credential::label)) }
        saved()
    }

    fun closeCredentialRequest() {
        mutable.update { it.copy(credentialRequest = null, vaultError = null) }
    }

    fun authorizeCredential(password: String) = vaultAction {
        val request = checkNotNull(state.value.credentialRequest)
        val chat = request.getString("chatId")
        val id = request.getString("id")
        val name = request.getJSONObject("params").getString("name")
        val session = vault.open(password)
        val entry = session.entries.firstOrNull { it.label.name == name }
            ?: error("Credencial +$name não encontrada no cofre.")
        sendCredential(chat, id, entry)
        vaultSession = session
        mutable.update { it.copy(credentialRequest = null, vaultUnlocked = true,
            credentials = session.entries.map(Credential::label)) }
        refresh()
    }

    private suspend fun sendCredential(chat: String, id: String, grant: Credential) {
        client.post("credential/run", obj("chat" to chat, "id" to id, "credential" to grant.requestJson()))
    }

    fun report(message: String) = mutable.update { it.copy(error = message) }

    fun requestDeviceControl(request: JSONObject) {
        mutable.update { it.copy(deviceControlRequest = request) }
    }

    fun cancelDeviceControlRequest() { mutable.update { it.copy(deviceControlRequest = null) } }

    fun enableDeviceControl() = work {
        val request = state.value.deviceControlRequest ?: return@work
        val chat = request.getString("chatId")
        check(chat == state.value.active && DeviceControlService.connected) { "Ative a acessibilidade e tente novamente." }
        val id = request.getString("id")
        check(androidInFlight.add(id)) { "Solicitação já em andamento." }
        try {
            DeviceControlSession.start(getApplication(), chat)
            mutable.update { it.copy(deviceControlChat = chat, deviceControlRequest = null) }
            executeAndroid(chat, id)
            refresh()
        } catch (error: Exception) {
            DeviceControlSession.stop(getApplication())
            mutable.update { it.copy(deviceControlChat = null) }
            throw error
        } finally { androidInFlight.remove(id) }
    }

    fun stopDeviceControl() {
        DeviceControlSession.stop(getApplication())
        mutable.update { it.copy(deviceControlChat = null, deviceControlRequest = null) }
    }

    fun approve(id: String, allow: Boolean) = work {
        val chat = state.value.active ?: return@work
        val pending = state.value.approvals.firstOrNull { it.optString("id") == id }
        if (allow && pending?.optBoolean("credential") == true) {
            mutable.update { it.copy(credentialRequest = pending, vaultError = null) }
            return@work
        }
        if (allow && pending?.optBoolean("android") == true) executeAndroid(chat, id)
        else client.post("approval", obj("chat" to chat, "id" to id, "allow" to allow))
        refresh()
    }

    private suspend fun executeAndroid(chat: String, id: String, automatic: Boolean = false) {
        val connection = client
        val request = connection.post("android/start", obj("chat" to chat, "id" to id, "automatic" to automatic))
        val target = request.getString("target")
        val result = try {
            if (target == "Controle do dispositivo") {
                check(DeviceControlSession.chat == chat) { "Sessão de controle encerrada." }
                DeviceControlService.execute(request.getString("command"))
            } else AndroidShell.run(getApplication<Application>(), request.getString("command"),
                request.string("cwd"), target, request.optBoolean("automatic"), id)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            obj("error" to (error.message ?: "O ambiente Android não respondeu."),
                "shizukuError" to (target == "ADB shell"))
        }
        if (result.optBoolean("shizukuError")) {
            report(result.string("error") ?: "Não foi possível conectar ao Shizuku.")
        }
        if (target == "Controle do dispositivo" && DeviceControlSession.chat != chat) {
            result.remove("image")
            result.put("error", "Sessão de controle encerrada.")
        }
        result.put("chat", chat).put("id", id)
        try {
            connection.post("android/result", result)
        } catch (error: CodexHttpException) {
            if (error.status != 409) throw error
        }
    }

}
