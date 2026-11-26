package local.codex.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import local.codex.mobile.R
import local.codex.mobile.core.MobileState
import local.codex.mobile.core.MobileViewModel
import org.json.JSONObject

@Composable internal fun SearchScreen(state: MobileState, vm: MobileViewModel, back: () -> Unit, select: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query, state.connected) {
        val term = query.trim()
        if (term.isEmpty()) { results = emptyList(); loading = false; error = false; return@LaunchedEffect }
        loading = true
        error = false
        delay(300)
        try { results = vm.searchConversations(term) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { results = emptyList(); error = true }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize()) {
        PageTitle("Pesquisar", back)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            if (query.isBlank()) {
                item { Text("Conversas recentes", Modifier.padding(horizontal = 8.dp, vertical = 12.dp), color = Ink.Muted, style = MaterialTheme.typography.labelLarge) }
                items(state.chats.filterNot { it.optBoolean("archived") }.take(20), key = { it.optString("id") }) { chat ->
                    SearchRow(chat, select)
                }
            } else {
                if (!state.connected) item { Text("Sem conexão: buscando apenas nos títulos salvos.", Modifier.padding(8.dp), color = Ink.Muted, style = MaterialTheme.typography.bodySmall) }
                if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ink.Accent) }
                else if (error) item { Text("Não foi possível pesquisar agora.", Modifier.padding(8.dp), color = Ink.Muted) }
                else if (results.isEmpty()) item { Text("Nenhuma conversa encontrada.", Modifier.padding(8.dp), color = Ink.Muted) }
                else items(results, key = { it.optString("id") }) { chat -> SearchRow(chat, select) }
            }
        }
        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Pesquisar conversas") },
            leadingIcon = { Symbol(R.drawable.symbol_search, null, color = Ink.Muted) },
            shape = RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Ink.Control, unfocusedContainerColor = Ink.Control,
                focusedBorderColor = Ink.Contour, unfocusedBorderColor = Ink.Contour),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).focusRequester(focus))
    }
}

@Composable private fun SearchRow(chat: JSONObject, select: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { select(chat.optString("id")) }.padding(horizontal = 8.dp, vertical = 12.dp)) {
        Text(chat.optString("title").ifBlank { "Nova conversa" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
        chat.optString("snippet").takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = Ink.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
