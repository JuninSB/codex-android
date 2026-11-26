package local.codex.mobile.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Path
import android.os.Build
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class DeviceControlService : AccessibilityService() {
    companion object {
        @Volatile private var instance: DeviceControlService? = null
        val connected get() = instance != null
        internal suspend fun execute(command: String): JSONObject {
            val service = instance ?: error("Ative o serviço de acessibilidade Controle do Codex Mobile.")
            return service.perform(JSONObject(command))
        }
    }
    override fun onServiceConnected() { instance = this }
    override fun onInterrupt() = Unit
    override fun onUnbind(intent: Intent?): Boolean { instance = null; DeviceControlSession.stop(this); return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; DeviceControlSession.stop(this); super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    private suspend fun perform(action: JSONObject): JSONObject = withContext(Dispatchers.Main) {
        when (action.getString("action")) {
            "screenshot" -> screenshot()
            "tap", "long_press", "swipe" -> {
                val metrics = resources.displayMetrics
                fun coordinate(name: String, max: Int): Float {
                    require(action.has(name)) { "Coordenada $name ausente." }
                    val v = action.getInt(name)
                    require(v in 0 until max) { "Coordenada $name fora da tela." }
                    return v.toFloat()
                }
                val x = coordinate("x", metrics.widthPixels); val y = coordinate("y", metrics.heightPixels)
                val kind = action.getString("action")
                val path = Path().apply {
                    moveTo(x, y)
                    if (kind == "swipe") lineTo(coordinate("x2", metrics.widthPixels), coordinate("y2", metrics.heightPixels))
                }
                val duration = when (kind) { "tap" -> 80L; "long_press" -> action.optLong("duration", 800L).coerceIn(500L, 3000L); else -> action.optLong("duration", 400L).coerceIn(100L, 3000L) }
                val done = CompletableDeferred<Boolean>()
                val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
                check(dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) { done.complete(true) }
                    override fun onCancelled(gestureDescription: GestureDescription?) { done.complete(false) }
                }, null)) { "O Android recusou o gesto." }
                check(withTimeout(5000) { done.await() }) { "O gesto foi cancelado." }
                obj("exit" to 0, "stdout" to "Gesto concluído.")
            }
            "back", "home" -> {
                val flag = if (action.getString("action") == "back") GLOBAL_ACTION_BACK else GLOBAL_ACTION_HOME
                check(performGlobalAction(flag)) { "O Android recusou a ação." }
                obj("exit" to 0, "stdout" to "Ação concluída.")
            }
            "open_app" -> {
                val packageName = action.getString("package")
                require(packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) { "Nome de pacote inválido." }
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                obj("exit" to 0, "stdout" to "Solicitada abertura de $packageName.")
            }
            else -> error("Ação desconhecida.")
        }
    }

    private suspend fun screenshot(): JSONObject {
        check(Build.VERSION.SDK_INT >= 30) { "Captura pela acessibilidade requer Android 11 ou superior." }
        val done = CompletableDeferred<JSONObject>()
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                try {
                    val buffer = result.hardwareBuffer
                    val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB))
                        ?: error("O Android não retornou uma imagem.")
                    val bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false)
                    buffer.close()
                    val out = ByteArrayOutputStream()
                    for (quality in listOf(65, 50, 35)) {
                        out.reset()
                        check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out))
                        if (out.size() <= 1_350_000) break
                    }
                    bitmap.recycle()
                    val bytes = out.toByteArray()
                    require(bytes.size <= 1_350_000) { "A captura excedeu o tamanho permitido." }
                    done.complete(obj("exit" to 0, "stdout" to "Tela atual: ${hardware.width} x ${hardware.height} pixels.",
                        "image" to ("data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))))
                    hardware.recycle()
                } catch (e: Exception) { done.complete(obj("error" to (e.message ?: "Falha na captura."))) }
            }
            override fun onFailure(errorCode: Int) { done.complete(obj("error" to "Falha ao capturar a tela (código $errorCode).")) }
        })
        return withTimeout(10000) { done.await() }
    }
}
