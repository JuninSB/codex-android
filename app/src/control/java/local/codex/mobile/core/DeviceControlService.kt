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
        internal fun setOverlay(active: Boolean, message: String? = null) {
            instance?.let { service -> android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (active) service.showIndicator(message) else service.hideIndicator()
            } }
        }
        internal suspend fun execute(command: String): JSONObject {
            val service = instance ?: error("Ative o serviço de acessibilidade Controle do Codex Mobile.")
            return service.perform(JSONObject(command))
        }
    }
    private var indicator: DeviceControlIndicator? = null
    private var indicatorManager: android.view.WindowManager? = null

    override fun onServiceConnected() { instance = this }
    override fun onInterrupt() = Unit
    override fun onUnbind(intent: Intent?): Boolean { hideIndicator(); instance = null; DeviceControlSession.stop(this); return super.onUnbind(intent) }
    override fun onDestroy() { hideIndicator(); instance = null; DeviceControlSession.stop(this); super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    private fun showIndicator(message: String?) {
        var view = indicator
        if (view == null) {
            view = DeviceControlIndicator(this)
            val manager = getSystemService(android.view.WindowManager::class.java)
            val params = android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            try { manager.addView(view, params) } catch (_: Exception) { return }
            indicator = view
            indicatorManager = manager
        }
        if (message != null) view.updateMessage(message)
    }

    private fun hideIndicator() {
        indicator?.let { view -> runCatching { indicatorManager?.removeViewImmediate(view) } }
        indicator = null
        indicatorManager = null
    }

    private suspend fun perform(action: JSONObject): JSONObject = withContext(Dispatchers.Main) {
        val actionName = action.getString("action")
        showIndicator(when (actionName) {
            "screenshot" -> "Verificando a tela…"
            "tap" -> "Tocando na tela…"
            "long_press" -> "Segurando na tela…"
            "swipe" -> "Deslizando na tela…"
            "back" -> "Voltando…"
            "home" -> "Abrindo a tela inicial…"
            "open_app" -> "Abrindo aplicativo…"
            else -> null
        })
        when (actionName) {
            "screenshot", "watch_region" -> {
                val session = checkNotNull(DeviceControlSession.chat)
                fun authorized() = check(DeviceControlSession.chat == session && instance === this@DeviceControlService)
                val threshold = action.optInt("changedPixels", 1)
                val watching = actionName == "watch_region"
                var baseline: IntArray? = null
                val deadline = android.os.SystemClock.elapsedRealtime() + action.optLong("timeoutMs", 30000).coerceIn(1000, 90000)
                if (watching) {
                    require(action.has("width") && action.has("height"))
                    do {
                        authorized()
                        val pixels = mutableListOf<IntArray>()
                        screenshot(action, pixels)
                        val current = pixels.single()
                        val first = baseline
                        require(threshold in 1..current.size)
                        if (first == null) baseline = current
                        else if (current.indices.count { current[it] != first[it] } >= threshold) break
                        kotlinx.coroutines.delay(750)
                        check(android.os.SystemClock.elapsedRealtime() < deadline) { "Tempo de observação esgotado." }
                    } while (true)
                    if (!action.optBoolean("capture")) return@withContext obj("exit" to 0, "stdout" to "A região mudou. Continue com a ação solicitada.")
                    kotlinx.coroutines.delay(750)
                }
                kotlinx.coroutines.delay(action.optLong("delayMs").coerceIn(0, 20000))
                authorized()
                screenshot(action)
            }
            "insert_text" -> {
                val node = findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: error("Toque primeiro no campo de texto.")
                check(node.isEditable && !node.isPassword) { "Campo incompatível com inserção de texto." }
                val previous = node.text?.toString().orEmpty()
                val start = node.textSelectionStart.takeIf { it in 0..previous.length } ?: previous.length
                val end = node.textSelectionEnd.takeIf { it in 0..previous.length } ?: start
                val value = previous.replaceRange(minOf(start,end), maxOf(start,end), action.getString("text"))
                val args = android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
                check(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)) { "O campo recusou o texto." }
                obj("exit" to 0, "stdout" to "Texto inserido.")
            }
            "speak" -> {
                val message = action.optString("message").trim()
                require(message.isNotEmpty() && message.length <= 120 && message.none { it.isISOControl() }) { "Informe uma mensagem curta para o indicador." }
                showIndicator(message)
                obj("exit" to 0, "stdout" to "Mensagem exibida no indicador.")
            }
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
                try {
                    startActivity(intent)
                } catch (_: android.content.ActivityNotFoundException) {
                    error("A abertura nativa de $packageName não encontrou uma atividade. " +
                        "Se o Shizuku estiver conectado, use adb_shell para resolver a atividade de launcher desse pacote " +
                        "e abra o componente encontrado com am start -n.")
                } catch (_: SecurityException) {
                    error("O Android recusou a abertura nativa de $packageName. " +
                        "Se o Shizuku estiver conectado, use adb_shell para verificar a atividade de launcher exportada " +
                        "e tente abrir o componente encontrado com am start -n.")
                }
                obj("exit" to 0, "stdout" to "Solicitada abertura de $packageName.")
            }
            else -> error("Ação desconhecida.")
        }
    }

    private suspend fun screenshot(action: JSONObject, samples: MutableList<IntArray>? = null): JSONObject {
        check(Build.VERSION.SDK_INT >= 30) { "Captura pela acessibilidade requer Android 11 ou superior." }
        val done = CompletableDeferred<JSONObject>()
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                try {
                    val buffer = result.hardwareBuffer
                    val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB))
                        ?: error("O Android não retornou uma imagem.")
                    val full = hardware.copy(Bitmap.Config.ARGB_8888, false)
                    buffer.close()
                    hardware.recycle()
                    val x = action.optInt("x")
                    val y = action.optInt("y")
                    val w = action.optInt("width", full.width - x)
                    val h = action.optInt("height", full.height - y)
                    require(x >= 0 && y >= 0 && w in 1..(full.width-x) && h in 1..(full.height-y)) { "Região fora da tela." }
                    if (samples != null) {
                        val pixels = IntArray(w*h)
                        full.getPixels(pixels,0,w,x,y,w,h)
                        full.recycle()
                        samples.add(pixels)
                        done.complete(obj("exit" to 0))
                        return
                    }
                    val crop = Bitmap.createBitmap(full,x,y,w,h)
                    if (crop !== full) full.recycle()
                    val ow = action.optInt("outputWidth", w)
                    val oh = action.optInt("outputHeight", h)
                    require(ow in 1..w && oh in 1..h)
                    val bitmap = Bitmap.createScaledBitmap(crop,ow,oh,true)
                    if (bitmap !== crop) crop.recycle()
                    val out = ByteArrayOutputStream()
                    for (quality in listOf(65, 50, 35)) {
                        out.reset()
                        check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out))
                        if (out.size() <= 1_350_000) break
                    }
                    bitmap.recycle()
                    val bytes = out.toByteArray()
                    require(bytes.size <= 1_350_000) { "A captura excedeu o tamanho permitido." }
                    done.complete(obj("exit" to 0, "stdout" to "Origem ($x,$y), região ${w}x$h, imagem ${ow}x$oh.",
                        "image" to ("data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))))
                } catch (e: Exception) { done.complete(obj("error" to (e.message ?: "Falha na captura."))) }
            }
            override fun onFailure(errorCode: Int) { done.complete(obj("error" to "Falha ao capturar a tela (código $errorCode).")) }
        })
        return withTimeout(10000) { done.await() }
    }
}
