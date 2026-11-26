package local.codex.mobile.core

import local.codex.mobile.BuildConfig

import android.content.Context
import android.net.ConnectivityManager
import android.os.Environment
import android.os.SystemClock
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.zip.GZIPInputStream

internal data class StartupProgress(
    val title: String,
    val line: String,
    val fraction: Float? = null,
    val entries: Int? = null
)

internal class UbuntuRuntime private constructor(private val context: Context) {
    private val root = File(context.filesDir, "ubuntu")
    private val mutex = Mutex()
    private var process: Process? = null
    private var client: CodexClient? = null
    private var hasStorage = false
    private val output = ArrayDeque<String>()

    suspend fun start(progress: (StartupProgress) -> Unit): CodexClient = mutex.withLock {
        withContext(Dispatchers.IO) {
            val shared = Environment.isExternalStorageManager()
            if (process?.isAlive == true && client?.ready() == true && hasStorage == shared) {
                return@withContext checkNotNull(client)
            }
            process?.let { previous ->
                previous.destroy()
                if (!previous.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    previous.destroyForcibly()
                    check(previous.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        "O serviço anterior do Ubuntu ainda está ativo."
                    }
                }
            }
            client = null
            hasStorage = shared
            progress(StartupProgress("Preparando Ubuntu…", "Verificando o ambiente local."))
            install(progress)
            progress(StartupProgress("Iniciando Codex…", "Abrindo o serviço local no Ubuntu."))
            val native = File(context.applicationInfo.nativeLibraryDir)
            val token = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") {
                "%02x".format(it.toInt() and 255)
            }
            val port = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val sharedDir = Environment.getExternalStorageDirectory()
            if (shared) runCatching {
                val appFolder = File(sharedDir, "codex-android")
                appFolder.mkdirs()
                File(appFolder, ".nomedia").createNewFile()
            }
            val workspace = if (shared) "/sdcard/codex-android/arquivos do chat" else "/root/Projetos"
            val dns = File(context.filesDir, "ubuntu-resolv.conf")
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val addresses = connectivity?.getLinkProperties(connectivity.activeNetwork)?.dnsServers.orEmpty()
            dns.writeText(addresses.mapNotNull { it.hostAddress?.substringBefore('%') }
                .ifEmpty { listOf("1.1.1.1", "8.8.8.8") }.joinToString("\n", postfix = "\n") { "nameserver $it" })
            val tmp = File(context.cacheDir, "proot").apply { mkdirs() }
            val launch = mutableListOf(
                File(native, "libproot.so").path, "--kill-on-exit", "-0", "-r", root.path,
                "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-b", "${dns.path}:/etc/resolv.conf", "-w", "/opt/codex-mobile"
            )
            if (shared) {
                launch += listOf("-b", "${sharedDir.path}:/sdcard")
                if (File("/storage").isDirectory) launch += listOf("-b", "/storage:/storage")
            }
            launch += listOf(
                "/usr/bin/env", "-i",
                "HOME=/root", "USER=root", "LOGNAME=root", "SHELL=/bin/bash", "TERM=xterm-256color",
                "LANG=C.UTF-8", "TMPDIR=/tmp", "PATH=/opt/node/bin:/opt/codex/bin:/opt/codex/codex-path:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "CODEX_MOBILE_PORT=$port", "CODEX_MOBILE_TOKEN=$token", "CODEX_MOBILE_WORKSPACE=$workspace",
                "CODEX_MOBILE_INTERNAL_WORKSPACE=/root/Projetos", "CODEX_MOBILE_EMBEDDED=1",
                "CODEX_MOBILE_DEVICE_CONTROL=${if (BuildConfig.DEVICE_CONTROL) 1 else 0}",
                "CODEX_MOBILE_CHATSTORE=/opt/codex-mobile/chatstore",
                "CODEX_MOBILE_RELAY=/opt/codex-mobile/chatstore",
                "/opt/node/bin/node", "/opt/codex-mobile/server.mjs"
            )
            synchronized(output) { output.clear() }
            val builder = ProcessBuilder(launch).redirectErrorStream(true)
            builder.environment().apply {
                remove("LD_PRELOAD")
                put("LD_LIBRARY_PATH", native.path)
                put("PROOT_LOADER", File(native, "libproot_loader.so").path)
                put("PROOT_TMP_DIR", tmp.path)
                put("PROOT_NO_SECCOMP", "1")
            }
            val child = builder.start()
            process = child
            Thread({
                drainRuntimeOutput(child.inputStream, { child.isAlive }) { line ->
                    synchronized(output) {
                        if (output.size >= 16) output.removeFirst()
                        output.addLast(line.replace(token, "[local]").take(450))
                    }
                }
            }, "codex-runtime-output").apply { isDaemon = true; start() }
            val connection = CodexClient("http://127.0.0.1:$port/$token/")
            repeat(45) {
                if (!child.isAlive) {
                    val detail = synchronized(output) { output.takeLast(3).joinToString("\n") }
                    error("O Ubuntu encerrou durante a inicialização.\n$detail")
                }
                if (connection.ready()) {
                    client = connection
                    return@withContext connection
                }
                delay(350)
            }
            child.destroy()
            error("O Ubuntu iniciou, mas o serviço Codex não respondeu.")
        }
    }

    private fun install(progress: (StartupProgress) -> Unit) {
        val marker = File(root, ".codex-mobile-runtime-v1")
        if (!marker.exists()) {
            val staging = File(context.filesDir, "ubuntu-instalando")
            if (staging.exists()) removeStaging(staging)
            check(staging.mkdir()) { "Falha ao reservar espaço para o Ubuntu." }
            listOf("ubuntu.tgz", "tools.tgz").forEachIndexed { index, asset ->
                val title = if (index == 0) "Extraindo Ubuntu · 1/2" else "Extraindo Codex e Node.js · 2/2"
                unpack(asset, staging, title, progress)
            }
            progress(StartupProgress("Finalizando instalação…", "Preparando as pastas e o serviço do Codex."))
            File(staging, "root/Projetos").mkdirs()
            listOf("sdcard", "storage", "opt/codex-mobile").forEach { File(staging, it).mkdirs() }
            File(staging, marker.name).createNewFile()
            check(staging.renameTo(root)) { "Falha ao concluir a instalação do Ubuntu." }
        }
        val certificates = File(root, "etc/ssl/certs/ca-certificates.crt")
        if (!certificates.exists()) {
            certificates.parentFile?.mkdirs()
            context.assets.open("ca-certificates.crt").use { source ->
                certificates.outputStream().use { source.copyTo(it) }
            }
        }
        val versionFile = File(root, "opt/codex-mobile/assets-version")
        val apk = File(context.applicationInfo.sourceDir)
        val version = "${apk.path}:${apk.lastModified()}:${apk.length()}"
        val targets = listOf(
            "codex-mobile.rules" to File(root, "root/.codex/rules/codex-mobile.rules"),
            "chatstore.bin" to File(root, "opt/codex-mobile/chatstore"),
            "server.mjs" to File(root, "opt/codex-mobile/server.mjs")
        )
        if (versionFile.isFile && versionFile.readText() == version &&
            targets.all { (asset, file) -> file.length() > 0 && (asset != "chatstore.bin" || file.canExecute()) }) return
        for ((asset, destination) in targets) {
            destination.parentFile?.mkdirs()
            val temp = File(destination.parentFile, destination.name + ".new")
            context.assets.open(asset).use { src -> temp.outputStream().use { src.copyTo(it) } }
            if (asset == "chatstore.bin") check(temp.setExecutable(true, true))
            check(temp.renameTo(destination)) { "Não foi possível atualizar $asset no Ubuntu." }
        }
        versionFile.writeText(version)
    }

    private fun removeStaging(dir: File) {
        if (!java.nio.file.Files.isSymbolicLink(dir.toPath()) && dir.isDirectory) {
            dir.listFiles()?.forEach(::removeStaging)
        }
        check(dir.delete()) { "Falha ao limpar uma extração incompleta." }
    }

    private fun unpack(asset: String, staging: File, title: String, progress: (StartupProgress) -> Unit) {
        val length = runCatching { context.assets.openFd(asset).use { it.length } }.getOrNull()
            ?.takeIf { it > 0 }
        var extracted = 0
        var lastUpdate = Long.MIN_VALUE
        var current = "Abrindo $asset…"
        fun report(bytes: Long, force: Boolean = false) {
            val time = SystemClock.elapsedRealtime()
            if (!force && lastUpdate != Long.MIN_VALUE && time - lastUpdate < 180) return
            lastUpdate = time
            progress(StartupProgress(title, current,
                length?.let { (bytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }, extracted))
        }
        report(0, force = true)
        val hardLinks = mutableListOf<Pair<File, File>>()
        val modes = mutableListOf<Pair<File, Int>>()
        val rootPath = staging.canonicalFile.toPath()
        fun entryFile(name: String): File {
            val target = rootPath.resolve(name.removePrefix("./")).normalize()
            require(target.startsWith(rootPath) && target.toFile().parentFile?.canonicalFile?.toPath()?.startsWith(rootPath) == true) {
                "Caminho inválido no pacote do Ubuntu."
            }
            return target.toFile()
        }
        context.assets.open(asset).buffered().use { source ->
            val compressed = ArchiveInput(source)
            TarArchiveInputStream(GZIPInputStream(compressed, 32 * 1024)).use { tar ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val entry = tar.nextEntry ?: break
                    if (entry.name == "." || entry.name == "./") continue
                    current = entry.name
                    report(compressed.bytes)
                    val target = entryFile(entry.name)
                    target.parentFile?.mkdirs()
                    when {
                        entry.isDirectory -> {
                            target.mkdirs()
                            modes += target to (entry.mode and 511)
                        }
                        entry.isSymbolicLink -> Os.symlink(entry.linkName, target.path)
                        entry.isLink -> hardLinks += target to entryFile(entry.linkName.removePrefix("/"))
                        entry.isFile -> {
                            target.outputStream().use { stream ->
                                while (true) {
                                    val count = tar.read(buffer)
                                    if (count <= 0) break
                                    stream.write(buffer, 0, count)
                                    report(compressed.bytes)
                                }
                            }
                            Os.chmod(target.path, entry.mode and 511 or 384)
                        }
                    }
                    extracted++
                }
            }
            current = "Conferindo arquivos e permissões…"
            report(compressed.bytes, force = true)
        }
        // Android can reject hard links in private app storage. Archive aliases
        // need the same bytes and permissions, so extract independent copies.
        for ((target, source) in hardLinks) {
            check(source.isFile && !java.nio.file.Files.isSymbolicLink(source.toPath())) {
                "Arquivo ausente no pacote do Ubuntu: ${source.name}."
            }
            source.copyTo(target)
            Os.chmod(target.path, Os.stat(source.path).st_mode and 511)
        }
        for ((target, mode) in modes.asReversed()) Os.chmod(target.path, mode or 448)
        current = "$asset extraído."
        report(length ?: 0L, force = true)
    }

    private class ArchiveInput(source: InputStream) : FilterInputStream(source) {
        var bytes = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) bytes++ }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) bytes += it }
    }

    companion object {
        @Volatile private var instance: UbuntuRuntime? = null
        fun get(context: Context): UbuntuRuntime = instance ?: synchronized(this) {
            instance ?: UbuntuRuntime(context.applicationContext).also { instance = it }
        }
    }
}
