package local.tvhome

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView

/** Grid of every launchable app, for anything not pinned to the home row. */
class AllAppsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = Config.load()
        val tiles = Tiles(this, config.typeface(config.fontWeight))

        val apps =
            (queryLaunchable(Intent.CATEGORY_LEANBACK_LAUNCHER) + queryLaunchable(Intent.CATEGORY_LAUNCHER))
                .distinct()
                .filter { it != packageName }
                .mapNotNull { pkg -> tiles.launchIntent(pkg)?.let { Triple(pkg, tiles.appLabel(pkg), it) } }
                .sortedBy { it.second.lowercase() }

        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                // Let the focused (enlarged) tile draw into the padding
                clipToPadding = false
                setPadding(tiles.dp(48), tiles.dp(32), tiles.dp(48), tiles.dp(32))
            }
        val label = tiles.text("All apps", 18f, Color.WHITE).apply { gravity = Gravity.START }
        column.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // TV Home's own apps, first
        val own =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
            }
        OWN_APPS.forEach { app ->
            val tile = tiles.symbolTile(app.symbol, listOf(Color.parseColor(app.from), Color.parseColor(app.to)))
            val focus = tile.onFocusChangeListener
            tile.setOnFocusChangeListener { view, focused ->
                focus.onFocusChange(view, focused)
                if (focused) label.text = app.name
            }
            tile.setOnClickListener { startActivity(Intent(this, app.activity)) }
            own.addView(
                tile,
                LinearLayout.LayoutParams(tiles.dp(Tiles.WIDTH_DP), tiles.dp(Tiles.HEIGHT_DP)).apply {
                    setMargins(0, tiles.dp(14), tiles.dp(18), tiles.dp(4))
                },
            )
        }
        column.addView(own)

        apps.chunked(COLUMNS).forEach { chunk ->
            val row =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    clipChildren = false
                }
            chunk.forEach { (pkg, name, launch) ->
                val tile = tiles.appTile(pkg, launch)
                val focus = tile.onFocusChangeListener
                tile.setOnFocusChangeListener { view, focused ->
                    focus.onFocusChange(view, focused)
                    if (focused) label.text = name
                }
                tile.setOnClickListener { startActivity(launch) }
                row.addView(
                    tile,
                    LinearLayout.LayoutParams(tiles.dp(Tiles.WIDTH_DP), tiles.dp(Tiles.HEIGHT_DP)).apply {
                        setMargins(0, tiles.dp(14), tiles.dp(18), tiles.dp(4))
                    },
                )
            }
            column.addView(row)
        }

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(Color.argb(245, 12, 12, 14))
                clipChildren = false
                addView(column)
            },
        )
        (column.getChildAt(1) as? LinearLayout)?.getChildAt(0)?.requestFocus()
    }

    private fun queryLaunchable(category: String): List<String> =
        packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
            .map { it.activityInfo.packageName }

    private class OwnApp(
        val name: String,
        val symbol: Int,
        val from: String,
        val to: String,
        val activity: Class<out Activity>,
    )

    companion object {
        private const val COLUMNS = 5
        private val OWN_APPS =
            listOf(
                OwnApp("Live Hub", R.drawable.ic_app_live, "#0b3d4f", "#1aa3a3", LiveHubActivity::class.java),
                OwnApp("Metronome", R.drawable.ic_app_metronome, "#6b3a00", "#d98a1f", MetronomeActivity::class.java),
                OwnApp("Drums", R.drawable.ic_app_drums, "#5c0f1f", "#c8324b", DrumsActivity::class.java),
                OwnApp("Chord Book", R.drawable.ic_app_chords, "#1f4a24", "#4c9a4f", ChordBookActivity::class.java),
                OwnApp("Wi-Fi", R.drawable.ic_app_wifi, "#1a2f6b", "#3f6fd8", WifiActivity::class.java),
            )
    }
}
