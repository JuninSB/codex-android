package local.codex.mobile.core

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

internal const val TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND"
internal object AndroidShell {
    private val adbLock = Mutex()
    private val rishProcesses = ConcurrentHashMap<String, Process>()
    private val rishTimeout = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "rish-timeout").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    fun stop(id: String) { rishProcesses.remove(id)?.destroyForcibly() }

    val termuxResults = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    suspend fun run(context: Context, command: String, cwd: String?, target: String, automatic: Boolean, id: String): JSONObject =
        when (target) {
            "Termux" -> termux(context, command, cwd, automatic)
            "ADB shell" -> adbLock.withLock { adb(context, command, id) }
            else -> error("Ambiente Android desconhecido.")
        }

    private suspend fun termux(context: Context, command: String, cwd: String?, automatic: Boolean): JSONObject {
        check(context.checkSelfPermission(TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            "Autorize o Termux nas configurações do Codex Mobile."
        }
        val id = UUID.randomUUID().toString()
        val result = CompletableDeferred<JSONObject>()
        termuxResults[id] = result
        val callback = Intent(context, TermuxResultReceiver::class.java)
            .setData(Uri.parse("codex-mobile-result://termux/$id"))
        val flags = PendingIntent.FLAG_ONE_SHOT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pending = PendingIntent.getBroadcast(context, 0, callback, flags)
        val args = if (automatic) arrayOf("-k", "3", "90", "env", "-u", "BASH_ENV", "bash", "--noprofile", "--norc", "-c", command)
            else arrayOf("-k", "3", "90", "bash", "-lc", command)
        val request = Intent("com.termux.RUN_COMMAND")
            .setClassName("com.termux", "com.termux.app.RunCommandService")
            .putExtra("com.termux.RUN_COMMAND_PATH", "\$PREFIX/bin/timeout")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args)
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", cwd ?: "~/")
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending)
        try {
            check(context.startService(request) != null) { "Termux não encontrado." }
            return withTimeoutOrNull(100000) { result.await() } ?: error("O Termux não retornou a saída.")
        } finally {
            termuxResults.remove(id)
            pending.cancel()
        }
    }

    private suspend fun adb(context: Context, command: String, id: String): JSONObject = withContext(Dispatchers.IO) {
        require(command.isNotBlank() && command.length <= 12000 && '\u0000' !in command)
        val dex = File(context.codeCacheDir, "rish-1953c1fd.dex")
        if (!dex.isFile || dex.length() != 59672L) {
            val staging = File.createTempFile("rish-", ".dex", context.codeCacheDir)
            try {
                FileOutputStream(staging).use { output ->
                    check(staging.setReadOnly()) { "Não foi possível proteger o DEX do Shizuku." }
                    context.assets.open("rish_shizuku.dex").use { it.copyTo(output) }
                }
                check(staging.length() == 59672L && staging.renameTo(dex)) { "Não foi possível preparar o rish." }
            } finally { staging.delete() }
        }
        val restrictedCommand = """if [ "$( /system/bin/id -u )" != 2000 ]; then
            echo 'O rish requer o Shizuku em modo ADB (UID shell).' >&2
            exit 126
        fi
        """.trimIndent() + "\n" + command
        val process = ProcessBuilder("/system/bin/app_process",
            "-Djava.class.path=${dex.absolutePath}", "/system/bin", "--nice-name=rish",
            "rikka.shizuku.shell.ShizukuShellLoader", "-c", restrictedCommand)
            .redirectErrorStream(true).apply { environment()["RISH_APPLICATION_ID"] = context.packageName }.start()
        rishProcesses[id] = process
        val timedOut = AtomicBoolean()
        val timeout = rishTimeout.schedule({
            timedOut.set(true)
            process.destroyForcibly()
        }, 90, TimeUnit.SECONDS)
        try {
            process.outputStream.close()
            val output = ByteArrayOutputStream()
            var truncated = false
            process.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    val available = (65536 - output.size()).coerceAtLeast(0)
                    output.write(buffer, 0, minOf(available, read))
                    if (read > available) truncated = true
                }
            }
            val exit = process.waitFor()
            val text = output.toString(Charsets.UTF_8.name())
            val failure = when {
                timedOut.get() -> "O tempo limite do rish foi atingido."
                "Server is not running" in text -> "Inicie o Shizuku e volte a conectar nas configurações."
                "Request timeout" in text -> "O Shizuku não respondeu. Confira se ele está ativo e autorizado."
                "permission denied" in text.lowercase() && "shizuku" in text.lowercase() ->
                    "Autorize o Codex Mobile no Shizuku e tente novamente."
                else -> null
            }
            obj("exit" to exit, "stdout" to text, "truncated" to truncated, "error" to failure,
                "shizukuError" to (failure != null))
        } finally {
            timeout.cancel(false)
            rishProcesses.remove(id, process)
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.data?.lastPathSegment ?: return
        val result = AndroidShell.termuxResults[id] ?: return
        val data = intent.getBundleExtra("result")
        if (data == null) {
            result.complete(obj("error" to "O Termux retornou um resultado vazio."))
            return
        }
        val stdout = data.getString("stdout").orEmpty()
        val stderr = data.getString("stderr").orEmpty()
        val internalError = data.getInt("err", Activity.RESULT_OK) != Activity.RESULT_OK
        result.complete(obj(
            "exit" to if (data.containsKey("exitCode")) data.getInt("exitCode") else null,
            "stdout" to stdout, "stderr" to stderr,
            "truncated" to (data.getInt("stdout_original_length") > stdout.length ||
                data.getInt("stderr_original_length") > stderr.length),
            "error" to if (internalError) data.getString("errmsg", "Falha no Termux.") else null
        ))
    }
}
