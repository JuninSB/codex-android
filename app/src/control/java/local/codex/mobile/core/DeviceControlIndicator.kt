package local.codex.mobile.core

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import local.codex.mobile.R

/** Non-interactive overlay; it never blocks touches on the underlying application. */
internal class DeviceControlIndicator(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = BitmapFactory.decodeResource(resources, R.drawable.ic_codex_official)
    private val density = resources.displayMetrics.density
    private var message = "Codex está controlando o dispositivo…"
    private fun dp(value: Float) = value * density

    fun updateMessage(value: String) { message = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = dp(5f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(0xff7ab7ff.toInt(), 0xff3466e8.toInt(), 0xff172c76.toInt(), 0xff8cc7ff.toInt()),
            null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2, dp(22f), dp(22f), paint)
        paint.shader = null
        paint.style = Paint.Style.FILL
        val top = dp(40f)
        val iconSize = dp(34f)
        val left = dp(14f)
        paint.color = 0xe61b2440.toInt()
        canvas.drawCircle(left + iconSize / 2, top + iconSize / 2, iconSize / 2 + dp(3f), paint)
        icon?.let { canvas.drawBitmap(it, null, RectF(left, top, left + iconSize, top + iconSize), paint) }
        paint.color = 0xee18243e.toInt()
        val bubbleLeft = left + iconSize + dp(9f)
        val available = (width - bubbleLeft - dp(14f)).coerceAtLeast(0f)
        paint.textSize = dp(13f)
        val shown = android.text.TextUtils.ellipsize(message, android.text.TextPaint(paint), available - dp(20f), android.text.TextUtils.TruncateAt.END).toString()
        val bubbleWidth = (paint.measureText(shown) + dp(20f)).coerceAtMost(available)
        canvas.drawRoundRect(bubbleLeft, top, bubbleLeft + bubbleWidth, top + iconSize, dp(14f), dp(14f), paint)
        paint.color = Color.WHITE
        canvas.drawText(shown, bubbleLeft + dp(10f), top + iconSize / 2 - (paint.ascent() + paint.descent()) / 2, paint)
    }
}
