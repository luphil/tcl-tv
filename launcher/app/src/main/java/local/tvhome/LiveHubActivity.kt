package local.tvhome

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Live feeds from around the world, opened with a code typed on the homescreen. Left/right step
 * through them, OK opens a menu of them all, Back leaves. YouTube feeds play in a web player
 * with ad and tracking requests blocked; a feed that has moved to a new video is found again.
 */
class LiveHubActivity : Activity() {
    private lateinit var feeds: List<LiveFeed>
    private lateinit var web: WebView
    private lateinit var video: VideoView
    private lateinit var card: LinearLayout
    private lateinit var nameText: TextView
    private lateinit var placeText: TextView
    private lateinit var liveText: TextView
    private lateinit var liveDot: View
    private lateinit var curtain: LinearLayout
    private lateinit var curtainName: TextView
    private lateinit var curtainNote: TextView
    private lateinit var menu: FeedMenu

    private val handler = Handler(Looper.getMainLooper())
    private val lookups = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("live", MODE_PRIVATE) }

    private var current = 0
    private var playerReady = false
    private var playing = false
    private var triedLookup = false

    // The live dot, breathing
    private val pulse by lazy {
        ObjectAnimator.ofPropertyValuesHolder(
            liveDot,
            PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.25f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.72f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.72f),
        ).apply {
            duration = PULSE_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
    }

    private val hideCard = Runnable { card.animate().alpha(0f).setDuration(600).start() }
    private val tick =
        object : Runnable {
            override fun run() {
                updateClock()
                updateSchedule()
                handler.postDelayed(this, TICK_MS)
            }
        }

    // Called from the web player
    private inner class Bridge {
        @JavascriptInterface
        fun ready() = handler.post { playerReady = true; play() }.let { }

        @JavascriptInterface
        fun state(value: Int) = handler.post { onPlayerState(value) }.let { }

        @JavascriptInterface
        fun error(code: Int) = handler.post { onPlayerError(code) }.let { }

        @JavascriptInterface
        fun quality(value: String) = Log.i(TAG, "Live quality ${feeds[current].name}: $value").let { }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        feeds = LiveFeeds.load(this)
        current = prefs.getInt(KEY_FEED, 0).coerceIn(feeds.indices)
        val config = Config.load()
        val tiles = Tiles(this, config.typeface(config.fontWeight))
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        video = VideoView(this).apply { visibility = View.GONE }
        root.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        web = newPlayer()
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Shown while a feed tunes in, or between a scheduled camera's dives
        curtain =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.BLACK)
            }
        curtainName = tiles.text("", 30f, Color.WHITE).apply { typeface = config.typeface(config.clockWeight) }
        curtainNote = tiles.text("", 13f, DIM).apply { setPadding(0, tiles.dp(10), 0, 0) }
        curtain.addView(curtainName)
        curtain.addView(curtainNote)
        root.addView(curtain, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // The name card, bottom left, on a soft dark fade
        card =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val edge = tiles.dp(21)
                setPadding(edge, tiles.dp(70), edge, edge)
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.argb(190, 0, 0, 0), Color.TRANSPARENT))
            }
        liveText = tiles.text("", 11f, Color.WHITE).apply { gravity = Gravity.START }
        liveDot =
            View(this).apply {
                background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(LIVE_RED)
                    }
            }
        val status =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(liveDot, LinearLayout.LayoutParams(tiles.dp(8), tiles.dp(8)).apply { marginEnd = tiles.dp(7) })
                addView(liveText)
            }
        nameText = tiles.text("", 24f, Color.WHITE).apply { gravity = Gravity.START; typeface = config.typeface(config.clockWeight) }
        placeText = tiles.text("", 12f, Color.WHITE).apply { gravity = Gravity.START }
        val hint = tiles.text("Left / Right  next feed     OK  all feeds     Back  exit", 10f, DIM).apply { gravity = Gravity.START; setPadding(0, tiles.dp(8), 0, 0) }
        listOf(status, nameText, placeText, hint).forEach { card.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        root.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        menu = FeedMenu(this, config.typeface(config.fontWeight), feeds).apply { visibility = View.GONE }
        root.addView(menu, FrameLayout.LayoutParams(tiles.dp(MENU_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))

        setContentView(root)
        show()
        refreshFeeds()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        handler.post(tick)
        pulse.start()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        pulse.cancel()
        prefs.edit().putInt(KEY_FEED, current).apply()
        web.evaluateJavascript("if (window.player && player.pauseVideo) player.pauseVideo()", null)
        web.onPause()
        video.stopPlayback()
    }

    override fun onDestroy() {
        lookups.shutdownNow()
        web.destroy()
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newPlayer() =
        WebView(this).apply {
            isFocusable = false
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.userAgentString = DESKTOP_AGENT
            addJavascriptInterface(Bridge(), "Hub")
            webViewClient =
                object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val url = request.url.toString()
                        if ("/embed/" in url && !request.isForMainFrame) return cleanPlayer(url)
                        if (BLOCKED.none { it in url }) return null
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                }
            loadDataWithBaseURL("https://local.tvhome/", PLAYER_PAGE, "text/html", "utf-8", null)
        }

    // Fetches the player page itself, adding a rule that hides YouTube's own overlays
    private fun cleanPlayer(url: String): WebResourceResponse? =
        try {
            val connection =
                (URL(url).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", DESKTOP_AGENT)
                    setRequestProperty("Referer", "https://local.tvhome/")
                    setRequestProperty("Accept-Language", "en")
                }
            val page = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val cleaned = page.replaceFirst("</head>", PLAYER_EXTRAS + "</head>")
            WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(cleaned.toByteArray()))
        } catch (ex: IOException) {
            Log.w(TAG, "Couldn't fetch the player", ex)
            null
        }

    private fun feed() = feeds[current]

    private fun liveId(feed: LiveFeed) = prefs.getString(idKey(feed), null) ?: feed.id

    private fun idKey(feed: LiveFeed) = "id:${feed.name}"

    // Tune in to the current feed
    private fun show() {
        val feed = feed()
        playing = false
        triedLookup = false
        nameText.text = feed.name
        placeText.text = feed.place
        curtainName.text = feed.name
        curtainNote.text = feed.place
        curtain.animate().cancel()
        curtain.alpha = 1f
        curtain.visibility = View.VISIBLE
        updateClock()
        showCard()
        play()
    }

    private fun play() {
        val feed = feed()
        if (feed.scheduled) {
            stopWeb()
            web.visibility = View.INVISIBLE
            updateSchedule()
            return
        }
        stopVideo()
        web.visibility = View.VISIBLE
        if (playerReady) web.evaluateJavascript("player.loadVideoById('${liveId(feed)}')", null)
    }

    private fun stopWeb() {
        if (playerReady) web.evaluateJavascript("player.stopVideo()", null)
    }

    private fun stopVideo() {
        if (video.visibility == View.VISIBLE) video.stopPlayback()
        video.visibility = View.GONE
    }

    // Scheduled cameras: a countdown until they're on, then the stream until they stop
    private fun updateSchedule() {
        val feed = feed()
        if (!feed.scheduled) return
        val wait = feed.untilOn()
        if (wait > 0) {
            if (video.visibility == View.VISIBLE) stopVideo()
            playing = false
            curtain.alpha = 1f
            curtain.visibility = View.VISIBLE
            curtainNote.text = "${feed.place}\n\nThis camera comes on for ${feed.minutes} minutes every ${if (feed.everyHours == 1) "hour" else "${feed.everyHours} hours"}.\nNext dive in ${countdown(wait)}"
            return
        }
        if (video.visibility != View.VISIBLE) {
            curtainNote.text = "${feed.place}\n\nDiving now"
            video.visibility = View.VISIBLE
            video.setOnPreparedListener { player ->
                Log.i(TAG, "Live playing ${feed.name}: ${player.videoWidth}x${player.videoHeight}")
                player.start()
                revealFeed()
            }
            video.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Live stream error ${feed.name}: $what $extra")
                curtainNote.text = "${feed.place}\n\nThis camera isn't streaming right now"
                true
            }
            video.setVideoURI(Uri.parse(feed.stream))
        }
    }

    private fun countdown(milliseconds: Long): String {
        val seconds = milliseconds / 1000
        val hours = seconds / 3600
        val minutes = seconds % 3600 / 60
        return if (hours > 0) "${hours}h ${minutes}m" else if (minutes > 0) "${minutes}m ${seconds % 60}s" else "${seconds}s"
    }

    private fun onPlayerState(state: Int) {
        Log.i(TAG, "Live state ${feed().name}: $state")
        if (state == STATE_PLAYING && !playing) {
            playing = true
            revealFeed()
        }
        if (state == STATE_ENDED) relocate()
    }

    private fun onPlayerError(code: Int) {
        Log.w(TAG, "Live error ${feed().name}: $code")
        relocate()
    }

    private fun revealFeed() {
        curtain.animate().alpha(0f).setDuration(CURTAIN_MS).withEndAction { curtain.visibility = View.GONE }.start()
    }

    // The stream has probably restarted under a new video: go and find it
    private fun relocate() {
        val feed = feed()
        if (feed.scheduled) return
        if (triedLookup) {
            curtain.alpha = 1f
            curtain.visibility = View.VISIBLE
            curtainNote.text = "${feed.place}\n\nThis feed is off the air right now"
            return
        }
        triedLookup = true
        curtainNote.text = "${feed.place}\n\nFinding this feed"
        lookups.execute {
            val found = LiveFeeds.findLiveId(feed)
            handler.post {
                if (found != null) prefs.edit().putString(idKey(feed), found).apply()
                if (feed === feed()) {
                    if (found != null) web.evaluateJavascript("player.loadVideoById('$found')", null) else relocate()
                }
            }
        }
    }

    // Refresh every feed's video now and then, so most never have to fail first
    private fun refreshFeeds() {
        if (System.currentTimeMillis() - prefs.getLong(KEY_REFRESHED, 0) < REFRESH_MS) return
        lookups.execute {
            feeds.filter { !it.scheduled }.forEach { feed ->
                LiveFeeds.findLiveId(feed)?.let { prefs.edit().putString(idKey(feed), it).apply() }
            }
            prefs.edit().putLong(KEY_REFRESHED, System.currentTimeMillis()).apply()
        }
    }

    private fun updateClock() {
        val feed = feed()
        val format = SimpleDateFormat("HH:mm", Locale.UK).apply { timeZone = feed.zone }
        // Only a camera that's actually on gets to say it's live
        val waiting = if (feed.scheduled) feed.untilOn() else 0
        val status = if (waiting > 0) "NEXT DIVE IN ${countdown(waiting).uppercase()}" else "LIVE"
        liveText.text = "$status     ${feed.group}     ${format.format(Date())} local time"
        liveDot.visibility = if (waiting > 0) View.GONE else View.VISIBLE
    }

    private fun showCard() {
        handler.removeCallbacks(hideCard)
        card.animate().cancel()
        card.animate().alpha(1f).setDuration(200).start()
        handler.postDelayed(hideCard, CARD_MS)
    }

    private fun step(by: Int) {
        current = (current + by).mod(feeds.size)
        show()
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        val menuOpen = menu.visibility == View.VISIBLE
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (!menuOpen) return super.onKeyDown(keyCode, event)
                menu.visibility = View.GONE
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount > 0) return true
                if (menuOpen) {
                    menu.visibility = View.GONE
                    current = menu.selected
                    show()
                } else {
                    menu.open(current)
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> if (menuOpen) menu.move(-1) else showCard()
            KeyEvent.KEYCODE_DPAD_DOWN -> if (menuOpen) menu.move(1) else showCard()
            KeyEvent.KEYCODE_DPAD_LEFT -> if (!menuOpen && event.repeatCount == 0) step(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (!menuOpen && event.repeatCount == 0) step(1)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    /** The list of feeds down the left, grouped, around the one selected. */
    private class FeedMenu(
        context: Context,
        typeface: Typeface,
        private val feeds: List<LiveFeed>,
    ) : View(context) {
        var selected = 0
            private set
        private var playing = 0
        private val density = resources.displayMetrics.density
        private val scaled = density * resources.configuration.fontScale
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        // Group headings and feeds, in display order
        private val lines =
            buildList {
                var group = ""
                feeds.forEachIndexed { i, feed ->
                    if (feed.group != group) {
                        group = feed.group
                        add(group to -1)
                    }
                    add(feed.name to i)
                }
            }

        fun open(current: Int) {
            selected = current
            playing = current
            visibility = VISIBLE
            invalidate()
        }

        fun move(by: Int) {
            selected = (selected + by).mod(feeds.size)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.argb(225, 10, 10, 12))
            val rowHeight = 24 * density
            val edge = 21 * density
            val rows = (height / rowHeight).toInt() - 1
            val at = lines.indexOfFirst { it.second == selected }
            val first = (at - rows / 2).coerceIn(0, maxOf(0, lines.size - rows))
            lines.drop(first).take(rows).forEachIndexed { i, (label, index) ->
                val y = rowHeight * (i + 1.2f)
                if (index == selected) {
                    fill.color = Color.argb(50, 255, 255, 255)
                    canvas.drawRoundRect(edge - 8 * density, y - rowHeight * 0.72f, width - edge + 8 * density, y + rowHeight * 0.28f, 5 * density, 5 * density, fill)
                }
                val heading = index < 0
                text.textSize = (if (heading) 10f else 13f) * scaled
                text.color = if (heading) ACCENT else if (index == selected) Color.WHITE else DIM
                canvas.drawText(if (heading) label.uppercase() else label, edge, y, text)
                if (index == playing) {
                    fill.color = LIVE_RED
                    canvas.drawCircle(width - edge, y - 4 * density, 3.5f * density, fill)
                }
            }
        }
    }

    companion object {
        private const val TAG = "TVHome"
        private const val KEY_FEED = "feed"
        private const val KEY_REFRESHED = "refreshed"
        private const val TICK_MS = 1000L
        private const val CARD_MS = 6000L
        private const val PULSE_MS = 900L
        private const val CURTAIN_MS = 700L
        private const val REFRESH_MS = 6 * 60 * 60 * 1000L
        private const val MENU_WIDTH_DP = 300
        private const val STATE_ENDED = 0
        private const val STATE_PLAYING = 1
        private val DIM = Color.argb(170, 255, 255, 255)
        private val ACCENT = Color.rgb(255, 180, 84)
        private val LIVE_RED = Color.rgb(235, 64, 52)
        private const val DESKTOP_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

        // Requests that are ads or tracking, not the video
        private val BLOCKED =
            listOf(
                "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
                "googletagmanager.com", "/pagead/", "/ptracking", "/api/stats/ads", "/api/stats/atr", "/api/stats/qoe", "/log_event", "/generate_204",
            )

        // Added to the player page. Hides everything in it except the video itself, whatever
        // YouTube calls its overlays this month, and reports the video and anything still showing
        private val PLAYER_EXTRAS =
            """
            <style>body *:not(video){background:transparent!important;color:transparent!important}
            body *:not(video):not(:has(video)){display:none!important}</style>
            <script>setInterval(function(){var v=document.querySelector('video');if(!v||!v.videoHeight)return;
            var left=[].slice.call(document.body.querySelectorAll('*')).filter(function(e){
              if(e===v||e.contains(v)||e.tagName==='SCRIPT'||e.tagName==='STYLE')return false;
              var r=e.getBoundingClientRect(),c=getComputedStyle(e);
              return r.width>0&&r.height>0&&c.display!=='none'&&c.visibility!=='hidden'&&c.opacity!=='0'});
            parent.postMessage({tvhome:v.videoWidth+'x'+v.videoHeight+' overlays '+left.length+' '+left.slice(0,4).map(function(e){return e.className||e.tagName}).join(' | ')},'*')},1500)</script>
            """.trimIndent()

        // Laid out at 1920 wide so YouTube offers full-resolution video, not a phone-sized stream
        private val PLAYER_PAGE =
            """
            <!doctype html><html><head><meta name="viewport" content="width=1920">
            <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{position:fixed;left:0;top:0;width:100%;height:100%}</style>
            </head><body><div id="p"></div>
            <script src="https://www.youtube.com/iframe_api"></script>
            <script>
            var player;
            addEventListener('message',function(e){if(e.data&&e.data.tvhome)Hub.quality(e.data.tvhome)});
            function onYouTubeIframeAPIReady(){
              player=new YT.Player('p',{width:'100%',height:'100%',host:'https://www.youtube-nocookie.com',
                playerVars:{autoplay:1,controls:0,disablekb:1,fs:0,iv_load_policy:3,modestbranding:1,playsinline:1,rel:0,origin:'https://local.tvhome'},
                events:{onReady:function(){player.unMute();player.setVolume(100);Hub.ready()},
                        onStateChange:function(e){Hub.state(e.data)},
                        onError:function(e){Hub.error(e.data)}}});
            }
            </script></body></html>
            """.trimIndent()
    }
}
