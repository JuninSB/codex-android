package local.codex.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import local.codex.mobile.core.CredentialType
import local.codex.mobile.core.MobileState
import local.codex.mobile.core.MobileViewModel
import local.codex.mobile.core.string

@Composable internal fun CredentialsScreen(state: MobileState, vm: MobileViewModel, back: () -> Unit, alias: String? = null) {
    var editor by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PageTitle(alias?.let { "+$it" } ?: "Credenciais", back)
        if (!state.vaultUnlocked) {
            VaultLogin(state, vm)
        } else if (alias != null) {
            val label = state.credentials.firstOrNull { it.name == alias }
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (label == null) Text("Esta credencial não está no cofre.", color = Ink.Muted)
                else {
                    Text(label.type.title, style = MaterialTheme.typography.titleMedium)
                    if (label.description.isNotBlank()) Text(label.description)
                    if (label.instructions.isNotBlank()) Text(label.instructions)
                }
                TextButton(onClick = vm::lockVault, enabled = !state.vaultBusy) { Text("Bloquear cofre") }
            }
        } else if (editor) {
            NewCredential(state, vm) { editor = false }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { editor = true }) { Text("Nova credencial") }
                TextButton(onClick = vm::lockVault, enabled = !state.vaultBusy) { Text("Bloquear cofre") }
            }
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 18.dp)) {
                items(state.credentials, key = { it.name }) { label ->
                    ListItem(
                        headlineContent = { Text("+${label.name}") },
                        supportingContent = { Text(listOf(label.type.title, label.description).filter(String::isNotBlank).joinToString(" · ")) },
                        colors = ListItemDefaults.colors(containerColor = Ink.Background)
                    )
                }
                if (state.credentials.isEmpty()) item {
                    Text("Adicione uma credencial para mencionar pelo nome no chat.", Modifier.padding(18.dp), color = Ink.Muted)
                }
            }
        }
    }
}

@Composable private fun VaultLogin(state: MobileState, vm: MobileViewModel) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val creating = !state.vaultExists
    Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (creating) "Proteja o cofre com uma senha de pelo menos 8 caracteres."
            else "Entre com sua senha para abrir o cofre.", color = Ink.Muted)
        PasswordField(password, { password = it }, "Senha do cofre")
        if (creating) PasswordField(confirmation, { confirmation = it }, "Confirmar senha")
        state.vaultError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { vm.unlockVault(password, creating) },
            enabled = !state.vaultBusy && password.isNotEmpty() && (!creating || password == confirmation)) {
            Text(if (state.vaultBusy) "Aguarde…" else if (creating) "Criar cofre" else "Desbloquear")
        }
    }
}

@Composable private fun PasswordField(value: String, change: (String) -> Unit, title: String) =
    OutlinedTextField(value, change, modifier = Modifier.fillMaxWidth(), label = { Text(title) },
        singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))

@Composable private fun NewCredential(state: MobileState, vm: MobileViewModel, close: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var type by remember { mutableStateOf(CredentialType.TOKEN) }
    Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Nova credencial", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (option in CredentialType.entries) {
                FilterChip(selected = type == option, onClick = { type = option }, label = { Text(option.title) })
            }
        }
        OutlinedTextField(name, { name = it }, label = { Text("Nome") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(description, { description = it }, label = { Text("Descrição") }, modifier = Modifier.fillMaxWidth())
        if (type != CredentialType.TOKEN) {
            OutlinedTextField(username, { username = it }, label = { Text("Usuário") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        PasswordField(secret, { secret = it }, if (type == CredentialType.TOKEN) "Token" else "Senha")
        if (type == CredentialType.SSH) {
            OutlinedTextField(host, { host = it }, label = { Text("IP ou host") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(port, { port = it }, label = { Text("Porta") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        state.vaultError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.addCredential(name, description, instructions, type, secret, username, host, port, close) },
                enabled = !state.vaultBusy && name.isNotBlank() && secret.isNotBlank()) { Text("Salvar") }
            TextButton(onClick = close, enabled = !state.vaultBusy) { Text("Cancelar") }
        }
    }
}

@Composable internal fun CredentialAuthorization(state: MobileState, vm: MobileViewModel) {
    val request = state.credentialRequest ?: return
    val alias = request.optJSONObject("params")?.string("name").orEmpty()
    var password by remember(request.optString("id")) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!state.vaultBusy) vm.closeCredentialRequest() },
        title = { Text("Autorizar +$alias") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val target = request.optJSONObject("params")?.string("command").orEmpty()
                Text(if (target.startsWith("file:")) "Criar um arquivo com o token dentro da pasta do projeto?"
                    else "Aprovar uma solicitação HTTPS com esta credencial?")
                request.optJSONObject("params")?.string("command")?.let { Text(it, style = mono) }
                PasswordField(password, { password = it }, "Senha do cofre")
                state.vaultError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { vm.authorizeCredential(password) },
            enabled = !state.vaultBusy && password.isNotEmpty()) { Text("Autorizar solicitação") } },
        dismissButton = { TextButton(onClick = vm::closeCredentialRequest, enabled = !state.vaultBusy) { Text("Cancelar") } },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)
    )
}

@Composable internal fun CredentialSuggestions(state: MobileState, change: (String) -> Unit) {
    val mention = Regex("(^|\\s)\\+([a-zA-Z0-9_.-]*)$").find(state.prompt) ?: return
    val prefix = mention.groupValues[2].lowercase()
    val candidates = state.credentials.filter { it.name.startsWith(prefix) }.take(4)
    if (candidates.isEmpty()) return
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), color = Ink.Surface,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Column {
            for (label in candidates) {
                ListItem(
                    modifier = Modifier.clickable {
                        change(state.prompt.substring(0, mention.range.first) + mention.groupValues[1] + "+${label.name} ")
                    },
                    headlineContent = { Text("+${label.name}") },
                    supportingContent = { Text(label.type.title) },
                    colors = ListItemDefaults.colors(containerColor = Ink.Surface)
                )
            }
        }
    }
}
