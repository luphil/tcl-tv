package local.tvhome

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

    private var config = Config()
    private var configStamp = -1L
    private var appsChanged = true
    private var lastWeather = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor()
    private val weatherTick = Runnable { refreshWeather(force = true) }

    private val packageReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                appsChanged = true
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
    }

    override fun onResume() {
        super.onResume()
        // Re-read the config whenever it has been pushed again from the Mac
        val stamp = Config.lastModified()
        if (stamp != configStamp || appsChanged) {
            configStamp = stamp
            appsChanged = false
            config = Config.load()
            buildScreen()
        }
        wallpaper.start()
        refreshWeather(force = false)
    }

    override fun onPause() {
        super.onPause()
        wallpaper.stop()
        handler.removeCallbacks(weatherTick)
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }

    // Pressing Home while already home jumps back to the start of the row
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (::row.isInitialized && row.childCount > 0) {
            (row.parent as HorizontalScrollView).smoothScrollTo(0, 0)
            row.getChildAt(0).requestFocus()
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
        val tiles = Tiles(this, config.typeface(config.fontWeight))

        wallpaper = previous ?: WallpaperView(this)
        wallpaper.configure(config.wallpaperFolder, config.wallpaperSeconds, config.wallpaperShuffle, config.wallpaperZoom)
        root.addView(wallpaper, matchParent())

        val rowOnTop = config.rowPosition == "top"
        val clockGravity = cornerGravity(config.clockPosition)
        val clockOnTop = config.clockPosition.startsWith("top")
        val clockOnRight = config.clockPosition.endsWith("right")

        // Darken behind the row and the clock for legibility
        root.addView(
            View(this).apply {
                val fade = if (rowOnTop) GradientDrawable.Orientation.TOP_BOTTOM else GradientDrawable.Orientation.BOTTOM_TOP
                background = GradientDrawable(fade, intArrayOf(Color.argb(200, 0, 0, 0), Color.TRANSPARENT))
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tiles.dp(300), if (rowOnTop) Gravity.TOP else Gravity.BOTTOM),
        )
        // A soft round glow from the clock's corner
        root.addView(
            CornerGlow(this, right = clockOnRight, bottom = !clockOnTop),
            FrameLayout.LayoutParams(tiles.dp(400), tiles.dp(400), clockGravity),
        )

        // Weather then clock, in the configured corner
        val status =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        weatherText = tiles.text("", 22f, Color.WHITE).apply { setPadding(0, 0, tiles.dp(18), 0) }
        clock =
            TextClock(this).apply {
                format24Hour = config.clockFormat
                format12Hour = config.clockFormat
                this.typeface = config.typeface(config.clockWeight)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
                setTextColor(Color.WHITE)
                setShadowLayer(10f, 0f, 2f, Color.argb(160, 0, 0, 0))
            }
        if (config.weather != null) status.addView(weatherText)
        if (config.clock) status.addView(clock)
        root.addView(
            status,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, clockGravity).apply {
                setMargins(tiles.dp(40), tiles.dp(24), tiles.dp(40), tiles.dp(24))
            },
        )

        // The single row of tiles. It scrolls inside a frame inset from the screen edges:
        // tiles passing the frame's sides fade out instead of being cut by the bezel, and
        // the scroller keeps the focused tile at least one fade-width inside the frame.
        row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
                // Room for the focused tile to grow without being clipped
                setPadding(tiles.dp(ROW_INSET_DP), tiles.dp(14), tiles.dp(ROW_INSET_DP), tiles.dp(14))
            }
        val scroller =
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                isHorizontalFadingEdgeEnabled = true
                setFadingEdgeLength(tiles.dp(ROW_INSET_DP))
                isFillViewport = false
                addView(row)
            }
        root.addView(
            scroller,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, if (rowOnTop) Gravity.TOP else Gravity.BOTTOM).apply {
                setMargins(tiles.dp(ROW_MARGIN_DP), if (rowOnTop) tiles.dp(28) else 0, tiles.dp(ROW_MARGIN_DP), if (rowOnTop) 0 else tiles.dp(28))
            },
        )

        config.items.forEach { item -> addTile(item, tiles) }
        row.getChildAt(0)?.requestFocus()
    }

    private fun addTile(
        item: Config.Item,
        tiles: Tiles,
    ) {
        val (tile, action) =
            when (item) {
                is Config.Item.App -> {
                    val launch = tiles.launchIntent(item.packageName) ?: return // not installed
                    tiles.appTile(item.packageName, launch) to { launch(launch) }
                }

                is Config.Item.Input -> {
                    tiles.textTile(item.label ?: "Input", item.subtitle) to {
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
        row.addView(
            tile,
            LinearLayout.LayoutParams(tiles.dp(Tiles.WIDTH_DP), tiles.dp(Tiles.HEIGHT_DP)).apply { marginEnd = tiles.dp(18) },
        )
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
                weatherText.text = "${reading.temperature}° ${reading.symbol}".trim()
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

    private fun matchParent() =FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    companion object {
        private const val TAG = "TVHome"
        private const val WEATHER_INTERVAL_MS = 30 * 60 * 1000L

        // Gap between the screen edge and the row's frame, and the fade inside it
        private const val ROW_MARGIN_DP = 24
        private const val ROW_INSET_DP = 32
    }
}
