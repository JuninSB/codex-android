package local.codex.mobile.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import local.codex.mobile.R
import local.codex.mobile.core.MobileState
import local.codex.mobile.core.StartupProgress
import local.codex.mobile.core.string

@Composable internal fun SetupLayout(step: Int, title: String, icon: Int, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 440.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val progress = remember { Animatable((step - 1) / 3f) }
            LaunchedEffect(step) { progress.animateTo(step / 3f, tween(420)) }
            LinearProgressIndicator(
                progress = { progress.value }, modifier = Modifier.fillMaxWidth().height(4.dp), color = Ink.Accent,
                trackColor = Ink.Divider, gapSize = 0.dp, drawStopIndicator = {}
            )
            Text("Etapa $step de 3", color = Ink.Muted, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) { Symbol(icon, null, color = Ink.Link) }
            }
            Text(title, style = MaterialTheme.typography.headlineMedium)
            content()
        }
    }
}

@Composable internal fun Welcome(next: () -> Unit) {
    SetupLayout(1, "Codex Mobile", R.drawable.symbol_terminal) {
        Text("Ambiente de desenvolvimento para Android", style = MaterialTheme.typography.titleMedium)
        Text("Trabalhe com o Codex em projetos locais e acompanhe a execução de comandos no Ubuntu.",
            color = Ink.Muted)
        Button(onClick = next, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Configurar ambiente") }
    }
}

@Composable internal fun Permissions(granted: Boolean, ask: () -> Unit, next: () -> Unit) {
    SetupLayout(2, "Arquivos e projetos", R.drawable.symbol_folder_open) {
        Text("O acesso ao armazenamento permite que o Bash trabalhe nas pastas compartilhadas do Android. Sem ele, você pode usar o espaço privado do app e anexar arquivos desse espaço pelo explorador integrado.",
            color = Ink.Muted)
        if (granted) Text("Acesso aos arquivos compartilhados concedido.", color = Ink.Link)
        Button(
            onClick = if (granted) next else ask,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) { Text(if (granted) "Continuar" else "Permitir acesso às pastas") }
        if (!granted) TextButton(onClick = next, modifier = Modifier.fillMaxWidth()) {
            Text("Usar espaço privado", color = Ink.Link)
        }
    }
}

@Suppress("DEPRECATION")
@Composable internal fun LoginScreen(
    state: MobileState,
    begin: (Boolean) -> Unit,
    launch: (String) -> Unit,
    cancel: () -> Unit,
    retry: () -> Unit,
    complete: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    SetupLayout(3, "Entre no Codex", R.drawable.symbol_account_circle) {
        val login = state.login
        when {
            state.startup != null -> {
                StartupShell(state.startup)
            }
            !state.connected -> {
                Text(state.connectionError ?: "Preparando o ambiente…", color = Ink.Muted)
                Button(onClick = retry) { Text("Tentar novamente") }
            }
            !state.accountLoaded -> {
                Text("Verificando sessão…", color = Ink.Muted)
                CircularProgressIndicator(Modifier.size(26.dp), color = Ink.Link)
            }
            state.account != null -> {
                Text(state.account.string("email") ?: "Conta conectada.", color = Ink.Link)
                Button(onClick = complete, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Usar Codex")
                }
            }
            login != null -> {
                val code = login.string("userCode")
                val url = login.string("authUrl") ?: login.string("verificationUrl")
                if (code == null) Text("Conclua o login no navegador. O app detecta a sessão ao voltar.", color = Ink.Muted)
                else {
                    Text("Abra a página da OpenAI e informe este código.", color = Ink.Muted)
                    TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                        Text("$code · Copiar", color = Ink.Link, style = mono)
                    }
                }
                if (url != null) Button(onClick = { launch(url) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Abrir no navegador")
                }
                TextButton(onClick = cancel, enabled = !state.busy) { Text("Cancelar", color = Ink.Link) }
            }
            else -> {
                Text("Entre com sua conta do ChatGPT para conversar com o Codex.", color = Ink.Muted)
                (state.loginError ?: state.error)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Button(onClick = { begin(false) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Entrar com ChatGPT")
                }
                TextButton(onClick = { begin(true) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Entrar com código", color = Ink.Link)
                }
            }
        }
    }
}


@Composable internal fun StartupShell(progress: StartupProgress) {
    Surface(shape = RoundedCornerShape(16.dp), color = Ink.Surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Symbol(R.drawable.symbol_terminal, null, color = Ink.Link)
                Text(progress.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                progress.fraction?.let { Text("${(it * 100).toInt()}%", color = Ink.Link, style = mono) }
            }
            Text(progress.line, style = mono, color = Ink.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            progress.entries?.let {
                Text("$it itens extraídos", color = Ink.Muted, style = MaterialTheme.typography.bodyMedium)
            }
            val fraction = progress.fraction
            if (fraction == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ink.Accent, trackColor = Ink.Divider)
            } else {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(),
                    color = Ink.Accent, trackColor = Ink.Divider)
            }
        }
    }
}
