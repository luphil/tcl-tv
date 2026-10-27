package local.tvhome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View

/** A dark glow fading out from one corner, so text there stays readable over any photo. */
class CornerGlow(
    context: Context,
    private val right: Boolean,
    private val bottom: Boolean,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        // Fully transparent before the view's nearest edge, so no seam can show
        paint.shader =
            RadialGradient(
                if (right) w.toFloat() else 0f,
                if (bottom) h.toFloat() else 0f,
                minOf(w, h) * 0.95f,
                intArrayOf(Color.argb(150, 0, 0, 0), Color.argb(70, 0, 0, 0), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}
