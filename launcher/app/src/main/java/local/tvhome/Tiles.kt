package local.tvhome

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Builds the focusable 16:9 tiles used on the home row and the all-apps grid. */
class Tiles(
    private val context: Context,
    private val typeface: Typeface,
) {
    private val pm = context.packageManager
    private val density = context.resources.displayMetrics.density

    fun dp(value: Int) = (value * density).toInt()

    /** Launch intent for an installed app (TV entry point first), or null if it's missing. */
    fun launchIntent(packageName: String): Intent? =
        (pm.getLeanbackLaunchIntentForPackage(packageName) ?: pm.getLaunchIntentForPackage(packageName))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun appLabel(packageName: String): String =
        try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (ex: PackageManager.NameNotFoundException) {
            packageName
        }

    fun appTile(
        packageName: String,
        launch: Intent,
    ): FrameLayout {
        val tile = baseTile()
        val banner = banner(packageName, launch)
        if (banner != null) {
            tile.addView(
                ImageView(context).apply {
                    setImageDrawable(banner)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
        } else {
            // No TV banner: icon over a dark card, with the name underneath
            tile.background = cardBackground()
            val column =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                }
            column.addView(
                ImageView(context).apply { setImageDrawable(pm.getApplicationIcon(packageName)) },
                LinearLayout.LayoutParams(dp(40), dp(40)),
            )
            column.addView(text(appLabel(packageName), 11f, Color.WHITE).apply { setPadding(0, dp(6), 0, 0) })
            tile.addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        return tile
    }

    /** An HDMI input: the device's logo beside its name, on its brand colours. */
    fun inputTile(
        title: String,
        subtitle: String?,
        logo: String?,
        colors: List<Int>,
    ): FrameLayout {
        val logoRes =
            when (logo) {
                "apple" -> R.drawable.logo_apple
                "playstation" -> R.drawable.logo_playstation
                else -> null
            }
        if (logoRes == null && colors.isEmpty()) return textTile(title, subtitle)
        val tile = baseTile()
        tile.background =
            if (colors.isEmpty()) {
                cardBackground()
            } else {
                GradientDrawable(GradientDrawable.Orientation.TL_BR, (if (colors.size == 1) colors + colors else colors).toIntArray())
                    .apply { cornerRadius = dp(10).toFloat() }
            }
        val content =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
        if (logoRes != null) {
            content.addView(
                ImageView(context).apply { setImageResource(logoRes) },
                LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) },
            )
        }
        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.START
            }
        column.addView(text(title, 18f, Color.WHITE).apply { gravity = Gravity.START })
        if (subtitle != null) {
            column.addView(text(subtitle, 10f, Color.argb(190, 255, 255, 255)).apply { gravity = Gravity.START })
        }
        content.addView(column)
        tile.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return tile
    }

    /** A text-only tile, used for settings, all apps and inputs without a logo or colours. */
    fun textTile(
        title: String,
        subtitle: String?,
    ): FrameLayout {
        val tile = baseTile()
        tile.background = cardBackground()
        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
        column.addView(text(title, 20f, Color.WHITE))
        if (subtitle != null) column.addView(text(subtitle, 10f, Color.argb(170, 255, 255, 255)))
        tile.addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return tile
    }

    fun text(
        value: String,
        sizeSp: Float,
        color: Int,
    ) = TextView(context).apply {
        text = value
        typeface = this@Tiles.typeface
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        gravity = Gravity.CENTER
        setShadowLayer(8f, 0f, 2f, Color.argb(160, 0, 0, 0))
    }

    private fun banner(
        packageName: String,
        launch: Intent,
    ): Drawable? {
        val activity = launch.component?.let { pm.getActivityInfo(it, 0) }
        return activity?.loadBanner(pm) ?: pm.getApplicationInfo(packageName, 0).loadBanner(pm)
    }

    private fun baseTile(): FrameLayout =
        FrameLayout(context).apply {
            isFocusable = true
            isClickable = true
            clipToOutline = true
            outlineProvider =
                object : ViewOutlineProvider() {
                    override fun getOutline(
                        view: View,
                        outline: Outline,
                    ) = outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
                }
            // White ring when focused, nothing otherwise
            foreground =
                GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(3), Color.TRANSPARENT)
                }
            setOnFocusChangeListener { view, focused ->
                ((view as FrameLayout).foreground as GradientDrawable).setStroke(dp(3), if (focused) Color.WHITE else Color.TRANSPARENT)
                val scale = if (focused) 1.12f else 1f
                view.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
            }
        }

    private fun cardBackground() =
        GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.argb(150, 20, 20, 24))
        }

    companion object {
        const val WIDTH_DP = 132
        const val HEIGHT_DP = 74
    }
}
