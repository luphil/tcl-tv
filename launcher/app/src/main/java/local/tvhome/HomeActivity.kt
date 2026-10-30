package local.tvhome

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
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
    private val cornerButtons = mutableListOf<View>()
    private val cells = mutableListOf<FrameLayout>()
    private var statusView: ViewGroup? = null
    private var scrollerView: View? = null

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
        cornerButtons.clear()
        cells.clear()
        val tiles = Tiles(this, config.typeface(config.fontWeight))

        wallpaper = previous ?: WallpaperView(this)
        wallpaper.configure(config.wallpaperFolder, config.wallpaperSeconds, config.wallpaperShuffle, config.wallpaperZoom)
        root.addView(wallpaper, matchParent())

        val rowOnTop = config.rowPosition == "top"
        val clockGravity = cornerGravity(config.clockPosition)
        val clockOnTop = config.clockPosition.startsWith("top")
        val clockOnRight = config.clockPosition.endsWith("right")
        // The clock shares the row's strip when they're on the same edge of the screen
        val clockInBar = clockOnTop == rowOnTop

        // A soft shade along the row's edge, for legibility
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
        if (config.buttonsPosition != "none") addCornerButtons(tiles)

        // The row of tiles, scrolling if they don't all fit (see fitTiles for their size).
        // Its padding is room for the focused tile to grow and cast its shadow.
        row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = if (rowOnTop) Gravity.TOP else Gravity.BOTTOM
                clipChildren = false
                clipToPadding = false
                setPadding(tiles.dp(ROW_PAD_DP), tiles.dp(ROW_PAD_DP), tiles.dp(ROW_PAD_DP), tiles.dp(ROW_PAD_DP))
            }
        val scroller =
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                isHorizontalFadingEdgeEnabled = true
                // Shown only while there's more row to scroll to on that side
                setFadingEdgeLength(tiles.dp(ROW_FADE_DP))
                isFillViewport = false
                // (Clips its row: tiles scrolled out of view must not draw over the clock)
                addView(row)
                chrome += this
                addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> post { fitTiles(tiles) } }
            }
        scrollerView = scroller

        // One strip along the row's edge: the tiles, then (if it's on that edge too) the clock
        // block, each bottom-aligned (top-aligned along the top) so their edges line up. Every
        // outer edge sits EDGE_DP from the screen edges; the paddings and font spacing are
        // taken off the margins so the visible gaps come out equal.
        val bar =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = if (rowOnTop) Gravity.TOP else Gravity.BOTTOM
                clipChildren = false
            }
        val status = statusBlock(tiles, clockOnRight)
        statusView = status
        status.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> view.post { fitTiles(tiles) } }
        // The block ends with the small lines (no font padding), so its box ends at their descent:
        // place it so their baseline sits EDGE_DP from the screen edge, level with the tiles' bottoms
        val descent = smallLineDescent()
        val statusParams = { inBar: Boolean ->
            val x = tiles.dp(EDGE_DP - STATUS_SIDE_BEARING_DP)
            val y = if (inBar) tiles.dp(ROW_PAD_DP) - descent else tiles.dp(EDGE_DP) - descent
            if (inBar) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (clockOnRight) marginStart = tiles.dp(BAR_GAP_DP) else marginEnd = tiles.dp(BAR_GAP_DP)
                    if (clockOnRight) marginEnd = x - tiles.dp(EDGE_DP - ROW_PAD_DP) else marginStart = x - tiles.dp(EDGE_DP - ROW_PAD_DP)
                    if (rowOnTop) topMargin = y else bottomMargin = y
                }
            } else {
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, clockGravity).apply { setMargins(x, y, x, y) }
            }
        }
        val scrollerParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        if (clockInBar && !clockOnRight) bar.addView(status, statusParams(true))
        bar.addView(scroller, scrollerParams)
        if (clockInBar && clockOnRight) bar.addView(status, statusParams(true))
        if (!clockInBar) root.addView(status, statusParams(false))
        val barInset = tiles.dp(EDGE_DP - ROW_PAD_DP)
        root.addView(
            bar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, if (rowOnTop) Gravity.TOP else Gravity.BOTTOM).apply {
                setMargins(barInset, barInset, barInset, barInset)
            },
        )

        config.items.forEach { item -> addTile(item, tiles) }
        firstTile()?.requestFocus()
    }

    /**
     * Make the tiles exactly as tall as the clock block beside them, from the top of the time's
     * digits to the date's baseline, so the strip reads as one band. (Standard size if there's
     * no clock in the strip.) If they're then too wide to all fit, the row scrolls.
     */
    private fun fitTiles(tiles: Tiles) {
        if (cells.isEmpty()) return
        val status = statusView
        val clockView = if (::clock.isInitialized) clock else null
        val height =
            if (status != null && clockView != null && status.parent == scrollerView?.parent && status.height > 0) {
                val digits = Rect()
                clockView.paint.getTextBounds("0123456789", 0, 10, digits)
                val box = Rect(0, 0, clockView.width, clockView.height)
                status.offsetDescendantRectToMyCoords(clockView, box)
                val digitTop = box.top + clockView.baseline + digits.top
                status.height - smallLineDescent() - digitTop
            } else {
                tiles.dp(Tiles.HEIGHT_DP)
            }
        // The tiles fill the row exactly, so the gap from the last one to the clock block matches
        // the screen margins, as long as that only means changing their natural width a little
        // (banners crop from the centre); otherwise they keep it and the row scrolls
        val natural = height * Tiles.WIDTH_DP / Tiles.HEIGHT_DP
        val rowWidth = scrollerView?.width ?: 0
        val fit = (rowWidth - 2 * tiles.dp(ROW_PAD_DP) - (cells.size - 1) * tiles.dp(TILE_GAP_DP)) / cells.size
        val width = if (rowWidth > 0 && fit >= natural * MIN_SQUEEZE && fit <= natural * MAX_STRETCH) fit else natural
        cells.forEach { tile ->
            resize(tile, width, height)
            tiles.logoIn(tile)?.let { (logo, scale) -> (height * Tiles.LOGO_SHARE * scale).toInt().let { resize(logo, it, it) } }
        }
    }

    private fun resize(
        view: View,
        width: Int,
        height: Int,
    ) {
        val params = view.layoutParams
        if (params.width != width || params.height != height) {
            params.width = width
            params.height = height
            view.layoutParams = params
        }
    }

    /** How far the small lines (weather conditions, date) reach below their baseline. */
    private fun smallLineDescent(): Int =
        TextView(this)
            .apply {
                typeface = config.typeface(config.fontWeight)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, SMALL_LINE_SP)
            }.paint.fontMetricsInt.descent

    /** Weather (icon, temperature; conditions and today's high/low) beside the clock (time; date). */
    private fun statusBlock(
        tiles: Tiles,
        clockOnRight: Boolean,
    ): ViewGroup {
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
            weatherText.includeFontPadding = false
            now.addView(weatherText)
            // Lines take their natural width (a vertical layout would stretch them), so the
            // column's alignment applies: right-aligned in a right-hand corner
            weather.addView(now, wrapContent())
            weatherDetail = tiles.text("", SMALL_LINE_SP, Color.WHITE).apply { includeFontPadding = false }
            weather.addView(weatherDetail, wrapContent().apply { topMargin = gapUnder(weatherText, tiles) })
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
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
                    setTextColor(Color.WHITE)
                    setShadowLayer(10f, 0f, 2f, Color.argb(160, 0, 0, 0))
                    includeFontPadding = false
                }
            clockColumn.addView(clock, wrapContent())
            val clockGap = gapUnder(clock, tiles)
            if (config.dateFormat.isNotBlank()) {
                clockColumn.addView(
                    TextClock(this).apply {
                        format24Hour = config.dateFormat
                        format12Hour = config.dateFormat
                        this.typeface = config.typeface(config.fontWeight)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, SMALL_LINE_SP)
                        setTextColor(Color.WHITE)
                        setShadowLayer(8f, 0f, 2f, Color.argb(160, 0, 0, 0))
                        includeFontPadding = false
                    },
                    wrapContent().apply { topMargin = clockGap },
                )
            }
            status.addView(clockColumn)
        }
        return status
    }

    /** Small, quiet icon buttons in a corner (all apps, settings); fully visible only when focused. */
    private fun addCornerButtons(tiles: Tiles) {
        val position = config.buttonsPosition
        val onRight = position.endsWith("right")
        // A little shade so the icons read on bright photos
        root.addView(
            CornerGlow(this, right = onRight, bottom = position.startsWith("bottom")).apply {
                alpha = 0.6f
                chrome += this
            },
            FrameLayout.LayoutParams(tiles.dp(220), tiles.dp(220), cornerGravity(position)),
        )
        val group =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                // The focused button grows past its box: leave room for its round backdrop
                clipChildren = false
                clipToPadding = false
                val room = tiles.dp(BUTTON_GROW_ROOM_DP)
                setPadding(room, room, room, room)
            }
        // Settings, then all apps, left to right
        val buttons =
            listOf(
                Triple(R.drawable.ic_settings, "Settings") { launch(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                Triple(R.drawable.ic_all_apps, "All apps") { launch(Intent(this, AllAppsActivity::class.java)) },
            )
        buttons.forEach { (icon, name, action) ->
            val button =
                FrameLayout(this).apply {
                    isFocusable = true
                    isClickable = true
                    alpha = BUTTON_IDLE_ALPHA
                    contentDescription = name
                    background =
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.TRANSPARENT)
                        }
                    setOnFocusChangeListener { view, focused ->
                        (view.background as GradientDrawable).setColor(if (focused) Color.argb(85, 24, 24, 26) else Color.TRANSPARENT)
                        val scale = if (focused) 1.1f else 1f
                        view.animate().alpha(if (focused) 1f else BUTTON_IDLE_ALPHA).scaleX(scale).scaleY(scale).setDuration(150).start()
                    }
                    setOnClickListener { action() }
                    addView(ImageView(this@HomeActivity).apply { setImageResource(icon) }, FrameLayout.LayoutParams(tiles.dp(BUTTON_ICON_DP), tiles.dp(BUTTON_ICON_DP), Gravity.CENTER))
                    chrome += this
                }
            cornerButtons += button
            group.addView(button, LinearLayout.LayoutParams(tiles.dp(BUTTON_DP), tiles.dp(BUTTON_DP)))
        }
        // The icons' outer edges sit EDGE_DP from the screen edges: take off the backdrop around
        // each icon and the ~2dp its lines are drawn inside their box
        val inset = tiles.dp(EDGE_DP - (BUTTON_DP - BUTTON_ICON_DP) / 2 - 2 - BUTTON_GROW_ROOM_DP)
        root.addView(
            group,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, cornerGravity(position)).apply {
                setMargins(inset, inset, inset, inset)
            },
        )
    }

    private fun addTile(
        item: Config.Item,
        tiles: Tiles,
    ) {
        val (tile, action) =
            when (item) {
                is Config.Item.App -> {
                    val launch = tiles.launchIntent(item.packageName) ?: return // not installed
                    tiles.appTile(item.packageName, launch, item.inset) to { launch(launch) }
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
        // fitTiles sets the real size once the clock block is laid out
        cells += tile
        row.addView(
            tile,
            LinearLayout.LayoutParams(tiles.dp(Tiles.WIDTH_DP), tiles.dp(Tiles.HEIGHT_DP)).apply { if (cells.size > 1) marginStart = tiles.dp(TILE_GAP_DP) },
        )
    }

    private fun firstTile(): View? = cells.firstOrNull()

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
            // The corner buttons rest at their own, lower opacity; the glow behind them too
            val target =
                when {
                    on -> 0f
                    view in cornerButtons && !view.isFocused -> BUTTON_IDLE_ALPHA
                    view is CornerGlow -> 0.6f
                    else -> 1f
                }
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

    /**
     * The gap to leave under a big line (temperature, time) before its small line, so that both
     * big lines' baselines sit at the same height whatever their sizes: the gap plus the font's
     * descent below the baseline always comes to the same total.
     */
    private fun gapUnder(
        big: TextView,
        tiles: Tiles,
    ): Int = maxOf(0, tiles.dp(BASELINE_TO_SMALL_LINE_DP) - big.paint.fontMetricsInt.descent)

    private fun wrapContent() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

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

        // Visible gap between the screen edges and everything at them: tiles, clock block, icons
        private const val EDGE_DP = 21

        // The clock's last digit sits ~1dp inside its box; taken off its side margin
        private const val STATUS_SIDE_BEARING_DP = 1

        // Weather conditions and date
        private const val SMALL_LINE_SP = 12f

        // Room around the tiles for the focused one to grow; gaps between tiles and before the clock
        private const val ROW_PAD_DP = 14
        private const val TILE_GAP_DP = 14
        // Between the row and the clock block: with the row's end padding, the visible gap is EDGE_DP
        private const val BAR_GAP_DP = EDGE_DP - ROW_PAD_DP
        private const val ROW_FADE_DP = 32

        // How much narrower or wider than natural the tiles may get to fill the row exactly
        private const val MIN_SQUEEZE = 0.85f
        private const val MAX_STRETCH = 1.2f

        // From the temperature's and time's baselines down to the top of the small lines below
        private const val BASELINE_TO_SMALL_LINE_DP = 14

        private const val BUTTON_IDLE_ALPHA = 0.55f

        // Corner buttons: the icon, and the round focus backdrop around it
        private const val BUTTON_ICON_DP = 20
        private const val BUTTON_DP = 36
        private const val BUTTON_GROW_ROOM_DP = 4
        private const val AMBIENT_FADE_OUT_MS = 1200L
        private const val AMBIENT_FADE_IN_MS = 250L
    }
}
