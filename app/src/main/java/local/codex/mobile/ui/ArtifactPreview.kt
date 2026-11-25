package local.codex.mobile.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.codex.mobile.R
import local.codex.mobile.core.LocalArtifact
import java.io.File

internal class ArtifactAccess(
    val project: String?,
    val connected: Boolean,
    val read: suspend (String, Boolean, Boolean) -> LocalArtifact,
    val media: (String) -> String?
)

internal val LocalArtifactAccess = staticCompositionLocalOf<ArtifactAccess?> { null }

internal fun ArtifactAccess.resolve(target: String, allowBare: Boolean = false): String? {
    if (target.startsWith("file:") || target.startsWith("sandbox:")) {
        val uri = Uri.parse(target)
        if (!uri.authority.isNullOrEmpty() && uri.authority != "localhost") return null
        return uri.path?.takeIf { it.startsWith("/") }
    }
    if (target.startsWith("#") || target.startsWith("?")) return null
    val raw = Uri.decode(target.replace(Regex("""#L\d+(?:-L\d+)?$"""), "")
        .replace(Regex(""":\d+(?::\d+)?$"""), ""))
    if (raw.isBlank() || raw.startsWith("//") || ':' in raw || '\u0000' in raw) return null
    if (raw.startsWith("/")) return raw
    if (!allowBare && !raw.contains('/') && !raw.contains('.')) return null
    return project?.trimEnd('/')?.plus("/" + raw)
}

internal fun artifactLink(path: String) = "codex-file:" + Uri.encode(path)
internal fun isArtifactImage(path: String) = path.substringAfterLast('.').lowercase() in
    setOf("png", "jpg", "jpeg", "webp", "gif")

private fun isVideo(path: String) = path.substringAfterLast('.').lowercase() in
    setOf("mp4", "m4v", "webm", "mkv", "mov", "3gp")

@Composable private fun VideoPlayer(address: String) {
    var error by remember(address) { mutableStateOf(false) }
    if (error) {
        Text("Este formato de vídeo não pôde ser reproduzido pelo Android.",
            color = MaterialTheme.colorScheme.error)
        return
    }
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(220.dp),
        factory = { context ->
            VideoView(context).apply {
                setMediaController(MediaController(context).also { it.setAnchorView(this) })
                setOnErrorListener { _, _, _ -> error = true; true }
                setVideoURI(Uri.parse(address))
                setOnPreparedListener { start() }
            }
        },
        onRelease = { it.stopPlayback() }
    )
}

private data class Preview(val name: String, val truncated: Boolean, val image: ImageBitmap?, val text: String?, val directory: String?, val size: Long)

@Composable private fun rememberPreview(path: String, metadata: Boolean = false): State<Result<Preview>?> {
    val access = LocalArtifactAccess.current
    return produceState<Result<Preview>?>(null, path, access?.connected, metadata) {
        value = null
        if (access?.connected != true) return@produceState
        value = try {
            val file = access.read(path, false, metadata)
            Result.success(withContext(Dispatchers.Default) {
                val bitmap = if (file.image) {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(file.bytes, 0, file.bytes.size, options)
                    options.inJustDecodeBounds = false
                    options.inSampleSize = 1
                    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1600) options.inSampleSize *= 2
                    BitmapFactory.decodeByteArray(file.bytes, 0, file.bytes.size, options)?.asImageBitmap()
                } else null
                val text = if (file.image || file.bytes.any { it == 0.toByte() }) null else file.bytes.decodeToString()
                Preview(file.name, file.truncated, bitmap, text, file.directory, file.size)
            })
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Result.failure(error)
        }
    }
}

@Composable internal fun InlineArtifactImage(path: String, label: String) {
    val uri = LocalUriHandler.current
    val preview by rememberPreview(path)
    val bitmap = preview?.getOrNull()?.image
    if (bitmap != null) {
        Image(bitmap, label, Modifier.fillMaxWidth().heightIn(max = 290.dp)
            .clickable(onClickLabel = "Abrir imagem no explorador") { uri.openUri(artifactLink(path)) })
    } else {
        TextButton(onClick = { uri.openUri(artifactLink(path)) }) {
            Symbol(R.drawable.symbol_image, null)
            Spacer(Modifier.width(8.dp))
            Text(label)
        }
    }
}

@Composable internal fun ArtifactDialog(path: String, openDirectory: (String) -> Unit, dismiss: () -> Unit) {
    val access = LocalArtifactAccess.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val video = isVideo(path)
    val preview by rememberPreview(path, metadata = video)
    val media = remember(path, video, access?.connected) {
        if (video && access?.connected == true) access.media(path) else null
    }
    var opening by remember(path) { mutableStateOf(false) }
    var failure by remember(path) { mutableStateOf<String?>(null) }
    val file = preview?.getOrNull()
    file?.directory?.let { directory ->
        LaunchedEffect(directory) { openDirectory(directory) }
        return
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(file?.name ?: path.substringAfterLast('/')) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    access?.connected != true -> Text("Aguardando conexão com o Ubuntu.")
                    preview == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    video && file?.size == 0L -> Text("Este arquivo de vídeo está vazio (0 bytes).")
                    video && file != null && media != null -> key(media) { VideoPlayer(media) }
                    file?.image != null -> Image(file.image, file.name, Modifier.fillMaxWidth().heightIn(max = 400.dp))
                    file?.text != null -> {
                        Text(file.text, style = mono)
                        if (file.truncated) Text("Prévia parcial", color = Ink.Muted)
                    }
                    file != null -> Text("Prévia indisponível para este formato.")
                    else -> Text(preview?.exceptionOrNull()?.message ?: "Arquivo indisponível.")
                }
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = access?.connected == true && !opening, onClick = {
                val reader = access ?: return@TextButton
                opening = true
                scope.launch {
                    try {
                        val artifact = reader.read(path, true, false)
                        if (artifact.directory != null) {
                            openDirectory(artifact.directory)
                            return@launch
                        }
                        val copy = withContext(Dispatchers.IO) {
                            val folder = File(context.cacheDir, "exportacoes").apply { mkdirs() }
                            folder.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }
                                ?.forEach(File::deleteRecursively)
                            val target = File(java.nio.file.Files.createTempDirectory(folder.toPath(), "arquivo-").toFile(), artifact.name)
                            target.writeBytes(artifact.bytes)
                            target
                        }
                        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(copy.extension.lowercase())
                            ?: if (copy.extension == "md") "text/markdown" else "application/octet-stream"
                        val address = FileProvider.getUriForFile(context, "${context.packageName}.exports", copy)
                        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(address, mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(intent, "Abrir com"))
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        failure = if (error is ActivityNotFoundException) "Nenhum app externo pode abrir este arquivo."
                            else error.message ?: "Não foi possível abrir o arquivo."
                    } finally { opening = false }
                }
            }) { Text(if (opening) "Preparando…" else "Abrir com") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Fechar") } }
    )
}

internal fun externalFolderUri(path: String): Uri? {
    val roots = listOf("/sdcard", "/storage/emulated/0")
    val root = roots.firstOrNull { path == it || path.startsWith("$it/") }
    val document = if (root != null) "primary:" + path.removePrefix(root).trimStart('/')
        else Regex("""^/storage/([A-Fa-f0-9]{4}-[A-Fa-f0-9]{4})(?:/(.*))?$""").matchEntire(path)
            ?.let { it.groupValues[1] + ":" + it.groupValues[2] } ?: return null
    return android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", document)
}

internal fun openExternalFolder(context: android.content.Context, path: String) {
    val uri = externalFolderUri(path) ?: return
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (error: RuntimeException) {
        if (error !is ActivityNotFoundException && error !is SecurityException) throw error
        context.startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
            .addCategory(Intent.CATEGORY_OPENABLE).putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, uri))
    }
}
