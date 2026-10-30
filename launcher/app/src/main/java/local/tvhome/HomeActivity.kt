package local.tvhome

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.tv.TvContract
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class HomeActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var wallpaper: WallpaperView
    private lateinit var row: LinearLayout
    private lateinit var clock: TextClock
    private lateinit var weatherText: TextView
    private lateinit var weatherDetail: TextView
    private lateinit var weatherIcon: ImageView

    // Everything that fades away in ambient mode
    private val chrome = mutableListOf<View>()
    private var ambient = false
    private var swallowKeyUp = -1
    private var settingsButton: View? = null

    private var config = Config()
    private var configStamp = -1L
    private var appsChanged = true
    private var lastWeather = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor()
    private val weatherTick = Runnable { refreshWeather(force = true) }

    // Straight after boot /sdcard can be unreadable for a while; until the real config is
    // readable the screen shows defaults, so keep checking and rebuild once it appears
    private val configRetry = Runnable { reloadConfigIfChanged() }
    private val idle = Runnable { setAmbient(true) }

    private val packageReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                appsChanged = true
            }
        }

    // The TV turning off is when to keep the photo on screen for the next start-up
    private val screenOffReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (::wallpaper.isInitialized) wallpaper.saveBootPhoto()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        setContentView(root)
        registerReceiver(
            packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            },
        )
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onResume() {
        super.onResume()
        reloadConfigIfChanged()
        wallpaper.start()
        refreshWeather(force = false)
        // Coming back home always shows the row
        setAmbient(false, animate = false)
    }

    // Re-read the config whenever it has been pushed again from the Mac
    private fun reloadConfigIfChanged() {
        handler.removeCallbacks(configRetry)
        val stamp = Config.lastModified()
        if (stamp != configStamp || appsChanged) {
            configStamp = stamp
            appsChanged = false
            config = Config.load()
            val wasRunning = ::wallpaper.isInitialized
            buildScreen()
            if (wasRunning) {
                wallpaper.start()
                refreshWeather(force = true)
            }
        }
        if (!Config.readable()) handler.postDelayed(configRetry, CONFIG_RETRY_MS)
    }

    override fun onPause() {
        super.onPause()
        wallpaper.stop()
        handler.removeCallbacks(weatherTick)
        handler.removeCallbacks(configRetry)
        handler.removeCallbacks(idle)
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        unregisterReceiver(screenOffReceiver)
        super.onDestroy()
    }

    // Pressing Home while already home jumps back to the start of the row
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (::row.isInitialized && row.childCount > 0) {
            (row.parent as HorizontalScrollView).smoothScrollTo(0, 0)
            firstTile()?.requestFocus()
        }
    }

    // Home has nowhere to go back to
    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean = keyCode == KeyEvent.KEYCODE_BACK || super.onKeyDown(keyCode, event)

    private fun buildScreen() {
        val previous = if (::wallpaper.isInitialized) wallpaper.also { it.stop() } else null
        root.removeAllViews()
        chrome.clear()
        settingsButton = null
        val tiles = Tiles(this, config.typeface(config.fontWeight))

        wallpaper = previous ?: WallpaperView(this)
        wallpaper.configure(config.wallpaperFolder, config.wallpaperSeconds, config.wallpaperShuffle, config.wallpaperZoom)
        root.addView(wallpaper, matchParent())

        val rowOnTop = config.rowPosition == "top"
        val clockGravity = cornerGravity(config.clockPosition)
        val clockOnTop = config.clockPosition.startsWith("top")
        val clockOnRight = config.clockPosition.endsWith("right")

        // A soft shade behind the row, for legibility
        root.addView(
            View(this).apply {
                val fade = if (rowOnTop) GradientDrawable.Orientation.TOP_BOTTOM else GradientDrawable.Orientation.BOTTOM_TOP
                background = GradientDrawable(fade, intArrayOf(Color.argb(160, 0, 0, 0), Color.TRANSPARENT))
                chrome += this
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tiles.dp(210), if (rowOnTop) Gravity.TOP else Gravity.BOTTOM),
        )
        // A round glow from the clock's corner, as dark as each photo needs there
        val glow = CornerGlow(this, right = clockOnRight, bottom = !clockOnTop)
        root.addView(glow, FrameLayout.LayoutParams(tiles.dp(480), tiles.dp(480), clockGravity))
        wallpaper.onPhoto = { photo ->
            val brightness = cornerBrightness(photo, right = clockOnRight, bottom = !clockOnTop)
            glow.animate().alpha(0.45f + 0.55f * brightness).setDuration(1500).start()
        }

        root.addView(
            statusBlock(tiles, clockOnRight),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, clockGravity).apply {
                // The same visible gap to the side and bottom edges (the date line's descent adds ~2dp below)
                setMargins(tiles.dp(CORNER_X_DP), tiles.dp(CORNER_Y_DP), tiles.dp(CORNER_X_DP), tiles.dp(CORNER_Y_DP))
            },
        )
        if (config.settingsPosition != "none") addSettingsButton(tiles)

        // The single row of tiles. It scrolls inside a frame inset from the screen edges:
        // tiles passing the frame's sides fade out instead of being cut by the bezel, and
        // the scroller keeps the focused tile at least one fade-width inside the frame.
        row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
                // Room for the focused tile to grow (and cast its shadow) without being clipped
                setPadding(tiles.dp(ROW_INSET_DP), tiles.dp(14), tiles.dp(ROW_INSET_DP), tiles.dp(14))
            }
        val scroller =
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                isHorizontalFadingEdgeEnabled = true
                setFadingEdgeLength(tiles.dp(ROW_INSET_DP))
                isFillViewport = false
                clipChildren = false
                addView(row)
                chrome += this
            }
        root.addView(
            scroller,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, if (rowOnTop) Gravity.TOP else Gravity.BOTTOM).apply {
                setMargins(tiles.dp(ROW_MARGIN_DP), if (rowOnTop) tiles.dp(32) else 0, tiles.dp(ROW_MARGIN_DP), if (rowOnTop) 0 else tiles.dp(32))
            },
        )

        // Size the tiles so the whole row fits on screen (never larger than the standard size)
        val shown = config.items.count { it !is Config.Item.App || tiles.launchIntent(it.packageName) != null }
        val roomDp = resources.configuration.screenWidthDp - 2 * (ROW_MARGIN_DP + ROW_INSET_DP) - (shown - 1) * TILE_GAP_DP
        val tileWidthDp = if (shown > 0) minOf(Tiles.WIDTH_DP, roomDp / shown) else Tiles.WIDTH_DP
        config.items.forEach { item -> addTile(item, tiles, tileWidthDp) }
        firstTile()?.requestFocus()
    }

    /** Weather (icon, temperature; conditions and today's high/low) beside the clock (time; date). */
    private fun statusBlock(
        tiles: Tiles,
        clockOnRight: Boolean,
    ): View {
        // Bottom-aligned, so the two small lines share a baseline under the two big ones
        val status =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
            }
        val align = if (clockOnRight) Gravity.END else Gravity.START
        if (config.weather != null) {
            val weather =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = align
                }
            val now =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
            weatherIcon = ImageView(this).apply { visibility = View.GONE }
            now.addView(weatherIcon, LinearLayout.LayoutParams(tiles.dp(26), tiles.dp(26)).apply { marginEnd = tiles.dp(8) })
            weatherText = tiles.text("", 30f, Color.WHITE)
            weatherText.typeface = config.typeface(config.clockWeight)
            now.addView(weatherText)
            weather.addView(now)
            weatherDetail = tiles.text("", 12f, Color.WHITE)
            weather.addView(weatherDetail)
            status.addView(weather)
            // A hairline between weather and clock
            status.addView(
                View(this).apply { setBackgroundColor(Color.argb(90, 255, 255, 255)) },
                LinearLayout.LayoutParams(tiles.dp(1), tiles.dp(44)).apply { setMargins(tiles.dp(20), 0, tiles.dp(20), tiles.dp(6)) },
            )
        }
        if (config.clock) {
            val clockColumn =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = align
                }
            clock =
                TextClock(this).apply {
                    format24Hour = config.clockFormat
                    format12Hour = config.clockFormat
                    this.typeface = config.typeface(config.clockWeight)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
                    setTextColor(Color.WHITE)
                    setShadowLayer(10f, 0f, 2f, Color.argb(160, 0, 0, 0))
                    includeFontPadding = false
                }
            clockColumn.addView(clock)
            if (config.dateFormat.isNotBlank()) {
                clockColumn.addView(
                    TextClock(this).apply {
                        format24Hour = config.dateFormat
                        format12Hour = config.dateFormat
                        this.typeface = config.typeface(config.fontWeight)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTextColor(Color.WHITE)
                        setShadowLayer(8f, 0f, 2f, Color.argb(160, 0, 0, 0))
                    },
                )
            }
            status.addView(clockColumn)
        }
        return status
    }

    /** A small, quiet cog in a corner; fully visible only when focused. */
    private fun addSettingsButton(tiles: Tiles) {
        val button =
            FrameLayout(this).apply {
                isFocusable = true
                isClickable = true
                alpha = SETTINGS_IDLE_ALPHA
                background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.TRANSPARENT)
                    }
                setOnFocusChangeListener { view, focused ->
                    (view.background as GradientDrawable).setColor(if (focused) Color.argb(70, 255, 255, 255) else Color.TRANSPARENT)
                    view.animate().alpha(if (focused) 1f else SETTINGS_IDLE_ALPHA).scaleX(if (focused) 1.1f else 1f).scaleY(if (focused) 1.1f else 1f).setDuration(150).start()
                }
                setOnClickListener { launch(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                contentDescription = "Settings"
                addView(ImageView(this@HomeActivity).apply { setImageResource(R.drawable.ic_settings) }, FrameLayout.LayoutParams(tiles.dp(24), tiles.dp(24), Gravity.CENTER))
                chrome += this
            }
        settingsButton = button
        root.addView(
            button,
            FrameLayout.LayoutParams(tiles.dp(44), tiles.dp(44), cornerGravity(config.settingsPosition)).apply {
                // Its icon sits the same distance from the edges as the clock and weather, level with the
                // date line; the round focus backdrop (10dp around the icon) reaches just past that.
                // The icon's lines are drawn 2dp inside its box.
                setMargins(tiles.dp(CORNER_X_DP - 12), tiles.dp(CORNER_Y_DP - 10), tiles.dp(CORNER_X_DP - 12), tiles.dp(CORNER_Y_DP - 10))
            },
        )
    }

    private fun addTile(
        item: Config.Item,
        tiles: Tiles,
        widthDp: Int,
    ) {
        val heightDp = widthDp * Tiles.HEIGHT_DP / Tiles.WIDTH_DP
        var name: String? = null
        val (tile, action) =
            when (item) {
                is Config.Item.App -> {
                    val launch = tiles.launchIntent(item.packageName) ?: return // not installed
                    name = item.label ?: tiles.appLabel(item.packageName)
                    tiles.appTile(item.packageName, launch) to { launch(launch) }
                }

                is Config.Item.Input -> {
                    tiles.inputTile(item.label ?: "Input", item.subtitle, item.logo, item.colors) to {
                        launch(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(item.inputId)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }

                is Config.Item.AllApps -> {
                    tiles.textTile("⋯", item.label ?: "All apps") to { launch(Intent(this, AllAppsActivity::class.java)) }
                }

                is Config.Item.Settings -> {
                    tiles.textTile("⚙", item.label ?: "Settings") to { launch(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
        tile.setOnClickListener { action() }

        // Each tile sits over its name, which appears only while it's focused (apps only:
        // input and text tiles already say what they are)
        val cell =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
            }
        cell.addView(tile, LinearLayout.LayoutParams(tiles.dp(widthDp), tiles.dp(heightDp)))
        val label =
            tiles.text(name ?: "", 11f, Color.WHITE).apply {
                alpha = 0f
                maxLines = 1
            }
        cell.addView(label, LinearLayout.LayoutParams(tiles.dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = tiles.dp(12) })
        val focus = tile.onFocusChangeListener
        tile.setOnFocusChangeListener { view, focused ->
            focus.onFocusChange(view, focused)
            if (name != null) label.animate().alpha(if (focused) 1f else 0f).setDuration(150).start()
        }
        row.addView(cell, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = tiles.dp(TILE_GAP_DP) })
    }

    private fun firstTile(): View? = (row.getChildAt(0) as? ViewGroup)?.getChildAt(0)

    // Ambient mode: with no button pressed for a while, the row and cog fade away
    // and only the photo, clock and weather stay. Any button brings them back
    // (that press only wakes the screen; it doesn't also act on the focused tile).
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (ambient) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                setAmbient(false)
                swallowKeyUp = event.keyCode
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == swallowKeyUp) {
            swallowKeyUp = -1
            return true
        }
        restartIdleTimer()
        return super.dispatchKeyEvent(event)
    }

    private fun restartIdleTimer() {
        handler.removeCallbacks(idle)
        if (config.ambientSeconds > 0) handler.postDelayed(idle, config.ambientSeconds * 1000L)
    }

    private fun setAmbient(
        on: Boolean,
        animate: Boolean = true,
    ) {
        ambient = on
        chrome.forEach { view ->
            view.animate().cancel()
            // The cog rests at its own, lower opacity
            val target = if (on) 0f else if (view == settingsButton && !view.isFocused) SETTINGS_IDLE_ALPHA else 1f
            if (animate) view.animate().alpha(target).setDuration(if (on) AMBIENT_FADE_OUT_MS else AMBIENT_FADE_IN_MS).start() else view.alpha = target
        }
        if (!on) restartIdleTimer()
    }

    private fun launch(intent: Intent) {
        try {
            startActivity(intent)
        } catch (ex: ActivityNotFoundException) {
            Log.e(TAG, "Can't open $intent", ex)
            Toast.makeText(this, "Can't open that", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshWeather(force: Boolean) {
        val weather = config.weather ?: return
        handler.removeCallbacks(weatherTick)
        handler.postDelayed(weatherTick, WEATHER_INTERVAL_MS)
        if (!force && System.currentTimeMillis() - lastWeather < WEATHER_INTERVAL_MS) return
        background.execute {
            val reading = WeatherFetcher.fetch(weather) ?: return@execute
            handler.post {
                lastWeather = System.currentTimeMillis()
                weatherText.text = "${reading.temperature}°"
                weatherIcon.visibility = if (reading.icon != null) View.VISIBLE else View.GONE
                reading.icon?.let { weatherIcon.setImageResource(it) }
                weatherDetail.text = listOf(reading.description, "H ${reading.high}°  L ${reading.low}°").filter { it.isNotEmpty() }.joinToString("  ·  ")
            }
        }
    }

    private fun cornerGravity(position: String): Int =
        when (position) {
            "top-left" -> Gravity.TOP or Gravity.START
            "bottom-left" -> Gravity.BOTTOM or Gravity.START
            "bottom-right" -> Gravity.BOTTOM or Gravity.END
            else -> Gravity.TOP or Gravity.END
        }

    private fun matchParent() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    /** Average brightness (0-1) of the screen corner the photo will cover, as shown centre-cropped. */
    private fun cornerBrightness(
        photo: Bitmap,
        right: Boolean,
        bottom: Boolean,
    ): Float {
        val screenW = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val screenH = root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val scale = maxOf(screenW.toFloat() / photo.width, screenH.toFloat() / photo.height)
        val offsetX = (photo.width - screenW / scale) / 2f
        val offsetY = (photo.height - screenH / scale) / 2f
        val steps = 12
        var total = 0f
        for (i in 0 until steps) {
            for (j in 0 until steps) {
                // Sample the corner's outer 35% of the screen in each direction
                val fx = (i + 0.5f) / steps * 0.35f
                val fy = (j + 0.5f) / steps * 0.35f
                val sx = (if (right) 1f - fx else fx) * screenW
                val sy = (if (bottom) 1f - fy else fy) * screenH
                val x = (offsetX + sx / scale).toInt().coerceIn(0, photo.width - 1)
                val y = (offsetY + sy / scale).toInt().coerceIn(0, photo.height - 1)
                val c = photo.getPixel(x, y)
                total += (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f
            }
        }
        return total / (steps * steps)
    }

    companion object {
        private const val TAG = "TVHome"
        private const val WEATHER_INTERVAL_MS = 30 * 60 * 1000L
        private const val CONFIG_RETRY_MS = 3000L

        // Gap between the screen edge and the row's frame, and the fade inside it
        private const val ROW_MARGIN_DP = 16
        private const val ROW_INSET_DP = 32
        private const val TILE_GAP_DP = 14

        // Distance from the screen's side and bottom edges to the clock/weather block and the cog
        private const val CORNER_X_DP = 20
        private const val CORNER_Y_DP = 18

        private const val SETTINGS_IDLE_ALPHA = 0.55f
        private const val AMBIENT_FADE_OUT_MS = 1200L
        private const val AMBIENT_FADE_IN_MS = 250L
    }
}
