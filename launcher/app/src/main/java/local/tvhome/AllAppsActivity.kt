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

    companion object {
        private const val COLUMNS = 5
    }
}
