package local.codex.mobile.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import local.codex.mobile.R
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser

private val parser = Parser.builder().extensions(listOf(TablesExtension.create()))
    .customDelimiterProcessor(Strikethrough.Processor()).build()
private val taskPattern = Regex("""^\[([ xX])]\s+""")
private val urlPattern = Regex(
    """https?://[^\s<>]+|\bmessage:\d+\b|(?<![\w+])\+[a-z][a-z0-9_.-]{0,39}\b|(?:file:|sandbox:)?/(?:sdcard|storage|root/Projetos|mnt/data|root/\.codex/generated_images)(?:/[^\s<>]*)?|\.\.?/[\w./-]+/?|(?<![\w@.])[\w/~.-]+\.(?:png|jpe?g|webp|gif|md|txt|json|ya?ml|csv|log|kt|java|js|ts|html|css|py|sh|pdf|zip|apk|mp4|m4v|webm|mkv|mov|3gp)\b""",
    RegexOption.IGNORE_CASE
)
private fun Node.children(): Sequence<Node> = generateSequence(firstChild) { it.next }
private fun plausibleFile(value: String) = value.startsWith("/") ||
    value.startsWith("file:") || value.startsWith("sandbox:") ||
    (value.none(Char::isWhitespace) && urlPattern.matches(value) && !safeUrl(value))
private fun navigationLink(value: String): String? {
    val id = value.substringAfter(':', "").removePrefix("+")
    return when (value.substringBefore(':')) {
        "message" -> id.takeIf { it.toIntOrNull()?.let { index -> index > 0 } == true }
            ?.let { "codex-message:$it" }
        "credential" -> id.lowercase().takeIf { it.matches(Regex("[a-z0-9][a-z0-9_.-]{0,39}")) }
            ?.let { "codex-credential:$it" }
        else -> null
    }
}

private fun safeUrl(uri: String) = uri.startsWith("https://", true) || uri.startsWith("http://", true)

private fun AnnotatedString.Builder.plain(text: String, links: Boolean, artifacts: ArtifactAccess?) {
    if (!links) {
        append(text)
        return
    }
    var start = 0
    for (match in urlPattern.findAll(text)) {
        append(text.substring(start, match.range.first))
        val value = match.value.trimEnd('.', ',', ';', ')', ']', '!', '?')
        val target = when {
            safeUrl(value) -> value
            value.startsWith("+") -> navigationLink("credential:" + value.drop(1))
            value.startsWith("message:") -> navigationLink(value)
            else -> artifacts?.resolve(value)?.let(::artifactLink)
        }
        if (target == null) append(value) else linked(value, target)
        append(match.value.substring(value.length))
        start = match.range.last + 1
    }
    append(text.substring(start))
}
private fun AnnotatedString.Builder.linked(label: String, target: String) =
    withLink(LinkAnnotation.Url(target, TextLinkStyles(SpanStyle(color = Ink.Link, textDecoration = TextDecoration.Underline)))) {
        append(label)
    }

private fun AnnotatedString.Builder.inline(node: Node, artifacts: ArtifactAccess?, links: Boolean = true, skip: Int = 0) {
    node.children().forEach { child ->
        when (child) {
            is Text -> plain(if (child === node.firstChild) child.literal.drop(skip) else child.literal, links, artifacts)
            is Code -> withStyle(SpanStyle(background = Ink.Elevated, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)) {
                val target = if (!links) null else if (child.literal.startsWith("+")) {
                    navigationLink("credential:" + child.literal.drop(1))
                } else if (plausibleFile(child.literal)) artifacts?.resolve(child.literal)?.let(::artifactLink)
                else null
                if (target == null) append(child.literal) else linked(child.literal, target)
            }
            is SoftLineBreak, is HardLineBreak -> append('\n')
            is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { inline(child, artifacts, links) }
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(child, artifacts, links) }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { inline(child, artifacts, links) }
            is Link -> {
                val target = if (safeUrl(child.destination)) child.destination
                    else navigationLink(child.destination) ?: artifacts?.resolve(child.destination, allowBare = true)?.let(::artifactLink)
                if (links && target != null) {
                    withLink(LinkAnnotation.Url(target, TextLinkStyles(SpanStyle(color = Ink.Link,
                        textDecoration = TextDecoration.Underline)))) { inline(child, artifacts, false) }
                } else inline(child, artifacts, false)
            }
            is HtmlInline -> append(child.literal)
            else -> inline(child, artifacts, links)
        }
    }
}

@Composable internal fun MarkdownBody(source: String) {
    val root by produceState<Node?>(null, source) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { parser.parse(source) }
    }
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val parsed = root
            if (parsed == null) Text(source.take(600), style = MaterialTheme.typography.bodyLarge)
            else parsed.children().forEach { Block(it) }
        }
    }
}

@Composable private fun Block(node: Node) {
    val artifacts = LocalArtifactAccess.current
    when (node) {
        is org.commonmark.node.Paragraph -> {
            val only = node.firstChild?.takeIf { it.next == null }
            val filename = when (only) {
                is Text -> only.literal.trim()
                is Code -> only.literal.trim()
                else -> null
            }?.takeIf(::plausibleFile)
            val imageFiles = node.children().mapNotNull { child ->
                val candidate = when {
                    child is Image -> child.destination to child.children().filterIsInstance<Text>().joinToString("") { it.literal }
                    child === only && child is Link -> child.destination to child.children().filterIsInstance<Text>().joinToString("") { it.literal }
                    filename != null && child === only -> filename to filename
                    else -> null
                } ?: return@mapNotNull null
                val path = artifacts?.resolve(candidate.first, allowBare = true)?.takeIf(::isArtifactImage) ?: return@mapNotNull null
                path to candidate.second.ifBlank { path.substringAfterLast('/') }
            }.take(4).toList()
            if (imageFiles.isEmpty() || (only == null && node.children().any { it !is Image })) {
                val standalone = if (only is Text && filename != null) artifacts?.resolve(filename) else null
                Text(buildAnnotatedString {
                    if (standalone != null && filename != null) linked(filename, artifactLink(standalone))
                    else inline(node, artifacts)
                }, style = MaterialTheme.typography.bodyLarge)
            }
            imageFiles.forEach { (path, label) -> InlineArtifactImage(path, label) }
        }
        is FencedCodeBlock -> CodePanel(node.info.orEmpty().substringBefore(' ').ifBlank { "Código" }, node.literal.trimEnd('\n'))
        is IndentedCodeBlock -> CodePanel("Código", node.literal.trimEnd('\n'))
        is HtmlBlock -> Text(node.literal)
        is ThematicBreak -> HorizontalDivider(color = Ink.Divider)
        is Heading -> Text(
            buildAnnotatedString { inline(node, artifacts) },
            style = if (node.level <= 2) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium
        )
        is BlockQuote -> Surface(color = Ink.Surface, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                node.children().forEach { Block(it) }
            }
        }
        is ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val initial = (node as? OrderedList)?.markerStartNumber ?: 1
            node.children().forEachIndexed { index, entry ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val first = entry.firstChild as? org.commonmark.node.Paragraph
                    val task = (first?.firstChild as? Text)?.literal?.let(taskPattern::find)
                    if (task != null) {
                        val checked = task.groupValues[1] != " "
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                            modifier = Modifier.semantics { stateDescription = if (checked) "Concluído" else "Pendente" },
                            colors = CheckboxDefaults.colors(checkedColor = Ink.Accent, uncheckedColor = Ink.Muted)
                        )
                    } else {
                        Text(if (node is OrderedList) "${initial + index}." else "•", color = Ink.Muted)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        entry.children().forEach { child ->
                            if (child === first && task != null) Text(
                                buildAnnotatedString { inline(child, artifacts, skip = task.value.length) },
                                style = MaterialTheme.typography.bodyLarge
                            ) else Block(child)
                        }
                    }
                }
            }
        }
        is TableBlock -> Surface(color = Ink.Surface, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) {
                node.children().forEach { section ->
                    val header = section is org.commonmark.ext.gfm.tables.TableHead
                    section.children().forEach { row ->
                        Row {
                            row.children().forEach { cell ->
                                Text(buildAnnotatedString { inline(cell, artifacts) }, Modifier.width(136.dp).padding(8.dp),
                                    fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
        }
        else -> Text(buildAnnotatedString { inline(node, artifacts) }, style = MaterialTheme.typography.bodyLarge)
    }
}

private val codeTokens = Regex(
    """//[^\n]*|/\*[\s\S]*?\*/|#[^\n]*|"(?:\\.|[^"\\])*+"|'(?:\\.|[^'\\])*+'|`[^`]*`|\b(?:0x[\da-fA-F]+|\d+(?:\.\d+)?)\b|\b(?:as|async|await|break|case|catch|class|const|continue|data|def|else|enum|export|false|finally|fn|for|from|fun|function|if|import|in|interface|let|new|null|object|package|pass|private|public|raise|return|select|then|throw|true|try|type|val|var|void|when|while|with|yield|None|True|False)\b"""
)

private fun highlightedCode(value: String, language: String): AnnotatedString {
    if (language.equals("diff", true)) return buildAnnotatedString {
        append(value)
        var offset = 0
        value.lineSequence().forEach { line ->
            val color = when {
                line.startsWith("+") -> Color(0xFFABD6A3)
                line.startsWith("-") -> Color(0xFFFFACAC)
                line.startsWith("@@") -> Ink.Link
                else -> null
            }
            if (color != null) addStyle(SpanStyle(color = color), offset, offset + line.length)
            offset += line.length + 1
        }
    }
    if (language.lowercase() in setOf("código", "saída", "text", "plaintext", "txt")) return AnnotatedString(value)
    return buildAnnotatedString {
        append(value)
        codeTokens.findAll(value).forEach { match ->
            val color = when {
                match.value.startsWith("/") || match.value.startsWith("#") -> Ink.Muted
                match.value.first() in listOf('"', '\'', '`') -> Color(0xFFABD6A3)
                match.value.first().isDigit() -> Color(0xFFFFC392)
                else -> Color(0xFFC5A8FF)
            }
            addStyle(SpanStyle(color = color), match.range.first, match.range.last + 1)
        }
    }
}

@Suppress("DEPRECATION")
@Composable internal fun CodePanel(label: String, value: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    var expanded by remember { mutableStateOf(false) }
    var wrap by remember { mutableStateOf(true) }
    var copied by remember(value) { mutableStateOf(false) }
    val preview = remember(value) { value.lineSequence().take(16).joinToString("\n").take(3500) }
    val snippet = remember(preview, label) { highlightedCode(preview, label) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1600)
            copied = false
        }
    }
    val copy = {
        clipboard.setText(AnnotatedString(value))
        copied = true
    }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Ink.Surface) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), color = Ink.Muted, style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = copy) {
                    Symbol(if (copied) R.drawable.symbol_check else R.drawable.symbol_content_copy,
                        if (copied) "Copiado" else "Copiar $label", color = Ink.Muted)
                }
                IconButton(onClick = { expanded = true }) {
                    Symbol(R.drawable.symbol_expand_more, "Abrir $label em tela cheia", color = Ink.Muted)
                }
            }
            HorizontalDivider(color = Ink.Divider)
            Text(snippet, Modifier.horizontalScroll(rememberScrollState()).padding(14.dp), style = mono)
            if (preview.length < value.length) {
                TextButton(onClick = { expanded = true }, modifier = Modifier.align(Alignment.Start)) {
                    Text("Ver conteúdo completo")
                }
            }
        }
    }
    if (expanded) Dialog(onDismissRequest = { expanded = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val full = remember(value, label) { highlightedCode(value, label) }
        Surface(Modifier.fillMaxSize(), color = Ink.Surface) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { expanded = false }) { Symbol(R.drawable.symbol_arrow_back, "Voltar") }
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    TextButton(onClick = { wrap = !wrap }) { Text(if (wrap) "Sem quebra" else "Quebrar linhas") }
                    IconButton(onClick = copy) {
                        Symbol(if (copied) R.drawable.symbol_check else R.drawable.symbol_content_copy,
                            if (copied) "Copiado" else "Copiar $label")
                    }
                }
                HorizontalDivider(color = Ink.Divider)
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text(full, (if (wrap) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState()))
                            .padding(14.dp), style = mono)
                    }
                }
            }
        }
    }
}
