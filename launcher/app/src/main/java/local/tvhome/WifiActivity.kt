package local.tvhome

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.json.JSONObject
import java.io.File

/**
 * A QR code that joins the Wi-Fi, opened with a code typed on the homescreen. The details come
 * from /sdcard/TVHome/wifi.json, copied from a private file on the Mac; the password is only
 * ever in the code, never shown as text.
 */
class WifiActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val config = Config.load()
        val tiles = Tiles(this, config.typeface(config.fontWeight))
        val details = load()
        val code = details?.let { QrCode.encode(joinText(it)) }

        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
        if (code != null) {
            column.addView(tiles.text("Join the Wi-Fi", 26f, Color.WHITE))
            column.addView(QrView(this, code), LinearLayout.LayoutParams(tiles.dp(QR_DP), tiles.dp(QR_DP)).apply { setMargins(0, tiles.dp(20), 0, tiles.dp(20)) })
            column.addView(tiles.text(details.ssid, 18f, Color.WHITE))
            column.addView(tiles.text("Point your phone's camera at the code", 12f, DIM_TEXT).apply { setPadding(0, tiles.dp(6), 0, 0) })
        } else {
            column.addView(tiles.text("Wi-Fi details aren't set up yet", 20f, Color.WHITE))
            column.addView(tiles.text("Add them to home/wifi.json on the Mac, then run wifi-apply.sh", 12f, DIM_TEXT).apply { setPadding(0, tiles.dp(10), 0, 0) })
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(12, 12, 14)) }
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)
    }

    private class Details(
        val ssid: String,
        val password: String,
        val security: String,
    )

    private fun load(): Details? {
        val file = File(PATH)
        if (!file.canRead()) return null
        return try {
            val json = JSONObject(file.readText())
            Details(json.getString("ssid"), json.getString("password"), json.optString("security", "WPA")).takeIf { it.ssid.isNotEmpty() && it.password.isNotEmpty() }
        } catch (ex: org.json.JSONException) {
            null
        }
    }

    // The standard Wi-Fi QR format, which phone cameras understand
    private fun joinText(details: Details) = "WIFI:T:${details.security};S:${escape(details.ssid)};P:${escape(details.password)};;"

    private fun escape(value: String) =
        buildString {
            value.forEach { character ->
                if (character == BACKSLASH || character in ";,:\"") append(BACKSLASH)
                append(character)
            }
        }

    // White border included: scanners need the quiet zone around a code
    private class QrView(
        context: Context,
        private val code: QrCode,
    ) : View(context) {
        private val paint = Paint().apply { color = Color.BLACK }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.WHITE)
            val cells = code.size + QUIET_ZONE * 2
            val cell = width / cells
            val offset = (width - cell * code.size) / 2f
            for (y in 0 until code.size) {
                for (x in 0 until code.size) {
                    if (code.isDark(x, y)) {
                        canvas.drawRect(offset + x * cell, offset + y * cell, offset + (x + 1) * cell, offset + (y + 1) * cell, paint)
                    }
                }
            }
        }
    }

    companion object {
        private const val PATH = "/sdcard/TVHome/wifi.json"
        private const val QR_DP = 300
        private const val QUIET_ZONE = 4
        private const val BACKSLASH = 92.toChar()
        private val DIM_TEXT = Color.argb(170, 255, 255, 255)
    }
}
