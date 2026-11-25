package local.codex.mobile.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import local.codex.mobile.R
import local.codex.mobile.core.MobileViewModel
import org.json.JSONObject

@Composable internal fun ConversationActions(chat: JSONObject, vm: MobileViewModel, close: () -> Unit) {
    var step by remember { mutableStateOf("menu") }
    var title by remember { mutableStateOf(chat.optString("title")) }
    val id = chat.getString("id")
    val pinned = chat.optBoolean("pinned")
    val archived = chat.optBoolean("archived")
    fun change(action: String, value: Boolean? = null, newTitle: String? = null) {
        vm.conversationAction(id, action, value, newTitle)
        close()
    }
    when (step) {
        "rename" -> AlertDialog(onDismissRequest = close, title = { Text("Renomear conversa") },
            text = { OutlinedTextField(title, { title = it }, label = { Text("Título") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { change("rename", newTitle = title) }, enabled = title.trim().length in 1..120) {
                    Text("Salvar")
                }
            }, dismissButton = { TextButton(onClick = close) { Text("Cancelar") } })
        "delete" -> AlertDialog(onDismissRequest = close, title = { Text("Excluir conversa?") },
            text = { Text("O histórico da conversa será excluído. Os arquivos da pasta do projeto permanecem no aparelho.") },
            confirmButton = { TextButton(onClick = { change("delete") }) { Text("Excluir", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = close) { Text("Cancelar") } })
        else -> AlertDialog(onDismissRequest = close, title = { Text("Opções da conversa") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    DropdownMenuItem(text = { Text(if (pinned) "Desafixar" else "Fixar") }, onClick = { change("pin", !pinned) })
                    DropdownMenuItem(text = { Text("Renomear") }, onClick = { step = "rename" })
                    DropdownMenuItem(text = { Text(if (archived) "Desarquivar" else "Arquivar") },
                        onClick = { change("archive", !archived) })
                    DropdownMenuItem(text = { Text("Excluir", color = MaterialTheme.colorScheme.error) }, onClick = { step = "delete" })
                }
            }, confirmButton = { TextButton(onClick = close) { Text("Fechar") } })
    }
}

@Suppress("DEPRECATION")
@Composable internal fun MessageActions(text: String, outgoing: Boolean, edit: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    fun copy() {
        val copied = runCatching { clipboard.setText(AnnotatedString(text)) }.isSuccess
        Toast.makeText(context, if (copied) "Mensagem copiada" else "Texto grande demais. Selecione um trecho.",
            Toast.LENGTH_SHORT).show()
    }
    fun share() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(intent, "Compartilhar mensagem"))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!outgoing) IconButton(onClick = ::copy) {
            Symbol(R.drawable.symbol_content_copy, "Copiar resposta", color = Ink.Muted)
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Symbol(R.drawable.symbol_ellipsis, "Opções da mensagem", color = Ink.Muted)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Copiar") }, onClick = { copy(); menu = false })
                DropdownMenuItem(text = { Text("Selecionar texto") }, onClick = { selection = true; menu = false })
                if (edit != null) DropdownMenuItem(text = { Text("Editar para reenviar") }, onClick = { edit(); menu = false })
                if (text.length < 100000) {
                    DropdownMenuItem(text = { Text("Compartilhar") }, onClick = { share(); menu = false })
                }
            }
        }
    }
    if (selection) Dialog(onDismissRequest = { selection = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Ink.Background) {
            Column(Modifier.safeDrawingPadding()) {
                PageTitle("Selecionar texto") { selection = false }
                SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(18.dp)) {
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                }
                TextButton(onClick = ::copy, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Copiar tudo") }
            }
        }
    }
}
