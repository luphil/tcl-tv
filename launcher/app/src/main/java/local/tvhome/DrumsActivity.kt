package local.tvhome

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Drum beats, opened from the metronome: a library of patterns and a beat generator, played on
 * sampled kits. Up/down choose a row, left/right change it, OK plays and stops (or presses the
 * row), number buttons type a tempo, Back leaves.
 */
class DrumsActivity : Activity() {
    private lateinit var page: DrumsView
    private lateinit var library: BeatLibrary
    private val engine = DrumEngine()
    private val handler = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private val metronomePrefs by lazy { getSharedPreferences("metronome", MODE_PRIVATE) }
    private val prefs by lazy { getSharedPreferences("drums", MODE_PRIVATE) }

    private var kits: List<String> = emptyList()
    private val loaded = HashMap<String, DrumKit>()
    private var kit = ""
    private var chosenKit: String? = null
    private var bpm = 100
    private var playing = false
    private val typed = StringBuilder()
    private val applyTyped = Runnable { commitTyped() }

    // Library
    private var saved = mutableListOf<BeatLibrary.Entry>()
    private var style = 0
    private var entry = 0

    // Generator
    private var generatorStyle = 0
    private var complexity = 5
    private var bars = 2
    private var swing = 0
    private var humanise = 3
    private var seed = Random.nextInt(1, 1_000_000)
    private val levels = BeatGenerator.GROUPS.keys.associateWith { DEFAULT_VOLUME }.toMutableMap()
    private val busy = BeatGenerator.defaultBusy(BeatGenerator.STYLES[0]).toMutableMap()
    private var editingBusy = true
    private var generated: Beat? = null

    private val styles get() = (if (saved.isNotEmpty()) listOf(SAVED) else emptyList()) + library.styles

    private fun inStyle(): List<BeatLibrary.Entry> =
        styles[style].let { name -> if (name == SAVED) saved else library.entries.filter { it.style == name } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val config = Config.load()
        library = BeatLibrary(this)
        kits = DrumKit.available()
        bpm = metronomePrefs.getInt(MetronomeActivity.KEY_BPM, bpm)
        humanise = prefs.getInt(KEY_HUMANISE, humanise)
        loadSaved()
        page = DrumsView(this, config.typeface(config.fontWeight))
        page.tabs = listOf("Library", "Generator")
        page.playingStep = { engine.playingStep }
        page.rowLevel = { role -> rowLevel(role) }
        setContentView(page)

        engine.bpm = bpm
        engine.humanise = humanise / 10f
        if (kits.isEmpty()) {
            page.status = "No drum kits on the TV: run drums-apply.sh"
        } else {
            loader.execute { DrumKit.load("perc")?.let { engine.percussion = it; warm(it) } }
            chosenKit = prefs.getString(KEY_KIT, null)?.takeIf { it in kits }
            useKit(chosenKit ?: inStyle().first().kit)
        }
        showPage()
    }

    override fun onPause() {
        super.onPause()
        stop()
        metronomePrefs.edit().putInt(MetronomeActivity.KEY_BPM, bpm).apply()
        prefs.edit().putInt(KEY_HUMANISE, humanise).apply()
    }

    override fun onDestroy() {
        loader.shutdown()
        super.onDestroy()
    }

    private fun warm(source: DrumKit) = BeatLibrary.ROLES.values.forEach { source.warm(it) }

    // Beats and styles suggest a kit, but one the viewer has picked always stays
    private fun suggestKit(id: String) {
        if (chosenKit == null) useKit(id)
    }

    private fun useKit(id: String) {
        val wanted = if (id in kits) id else kits.first()
        kit = wanted
        val ready = loaded[wanted]
        if (ready != null) {
            engine.kit = ready
            return
        }
        loader.execute {
            val fresh = DrumKit.load(wanted) ?: return@execute
            handler.post {
                loaded[wanted] = fresh
                if (kit == wanted) engine.kit = fresh
                page.invalidate()
            }
            warm(fresh)
        }
    }

    private fun kitName(id: String) = loaded[id]?.label ?: KIT_NAMES[id] ?: id

    private fun current(): Beat? = if (page.tab == TAB_LIBRARY) inStyle().getOrNull(entry)?.beat else generated

    // Rows for each tab; the first row is always the tabs themselves
    private fun rows(): List<DrumsView.Row> {
        val tabRow =
            DrumsView.Row("View", { page.tabs[page.tab] }, { switchTab() })
        val kitRow = DrumsView.Row("Kit", { kitName(kit) }, { step -> stepKit(step) })
        val tempoRow = DrumsView.Row("Tempo", { "${typed.ifEmpty { bpm.toString() }} BPM" }, { step -> setTempo(bpm + step) })
        val humanRow = DrumsView.Row("Human feel", { humanise.toString() }, { step -> setHumanise(humanise + step) })
        if (page.tab == TAB_LIBRARY) {
            return listOf(
                tabRow,
                DrumsView.Row("Style", { styles[style] }, { step -> stepStyle(step) }),
                DrumsView.Row("Beat", { "${entry + 1} of ${inStyle().size}" }, { step -> stepBeat(step) }),
                kitRow,
                tempoRow,
                humanRow,
            )
        }
        val drumRows =
            BeatGenerator.GROUPS.keys.map { group ->
                DrumsView.Row(group, { drumValue(group) }, { step -> changeDrum(group, step) }, { editingBusy = !editingBusy }, arrows = false)
            }
        return listOf(
            tabRow,
            DrumsView.Row("Style", { BeatGenerator.STYLES[generatorStyle].name }, { step -> stepGeneratorStyle(step) }),
            DrumsView.Row("Complexity", { complexity.toString() }, { step -> complexity = (complexity + step).coerceIn(1, 10); regenerate() }),
            DrumsView.Row("Length", { if (bars == 1) "1 bar" else "$bars bars" }, { step -> stepBars(step) }),
            DrumsView.Row("Swing", { swing.toString() }, { step -> swing = (swing + step).coerceIn(0, 10); regenerate() }),
            humanRow,
            kitRow,
            tempoRow,
        ) + drumRows +
            listOf(
                DrumsView.Row("Generate", { "New beat" }, press = { seed = Random.nextInt(1, 1_000_000); regenerate(); if (!playing) play() }),
                DrumsView.Row("Save", { "Keep this beat" }, press = { saveBeat() }),
            )
    }

    private fun showPage() {
        page.rows = rows()
        page.selected = page.selected.coerceIn(0, page.rows.size - 1)
        page.title = if (page.tab == TAB_LIBRARY) "Drums" else "Beat generator"
        page.beat = current()
        val list = if (page.tab == TAB_LIBRARY) inStyle() else emptyList()
        page.names = list.map { it.beat.name }
        page.chosen = entry
        page.status = if (kits.isEmpty()) page.status else if (playing) "Playing" else "Press OK to play"
        page.playing = playing
        val onDrum = page.rows.getOrNull(page.selected)?.label in BeatGenerator.GROUPS.keys
        page.hint =
            if (onDrum) {
                "OK  switch Busy / Volume     Up / Down  choose     Left / Right  change     Back  exit"
            } else {
                "OK  play / stop     Up / Down  choose     Left / Right  change     0-9  type a tempo     Back  exit"
            }
        page.invalidate()
    }

    private fun switchTab() {
        page.tab = 1 - page.tab
        if (page.tab == TAB_GENERATOR && generated == null) regenerate()
        suggestKit(if (page.tab == TAB_LIBRARY) inStyle()[entry].kit else BeatGenerator.STYLES[generatorStyle].kit)
        beatChanged()
    }

    private fun stepStyle(step: Int) {
        style = (style + step).mod(styles.size)
        entry = 0
        suggestKit(inStyle()[entry].kit)
        beatChanged()
    }

    private fun stepBeat(step: Int) {
        entry = (entry + step).mod(inStyle().size)
        suggestKit(inStyle()[entry].kit)
        beatChanged()
    }

    private fun stepKit(step: Int) {
        if (kits.isEmpty()) return
        val picked = kits[(kits.indexOf(kit) + step).mod(kits.size)]
        chosenKit = picked
        prefs.edit().putString(KEY_KIT, picked).apply()
        useKit(picked)
    }

    private fun stepGeneratorStyle(step: Int) {
        generatorStyle = (generatorStyle + step).mod(BeatGenerator.STYLES.size)
        val chosen = BeatGenerator.STYLES[generatorStyle]
        swing = (chosen.swing * 10).toInt()
        busy.putAll(BeatGenerator.defaultBusy(chosen))
        suggestKit(chosen.kit)
        regenerate()
    }

    private fun stepBars(step: Int) {
        val options = listOf(1, 2, 4)
        bars = options[(options.indexOf(bars) + step).coerceIn(0, options.size - 1)]
        regenerate()
    }

    // Both numbers, with arrows on the one that left/right will change
    private fun drumValue(group: String): String {
        val selected = page.rows.getOrNull(page.selected)?.label == group
        val busyText = busy.getValue(group).let { if (it == 0) "Off" else it.toString() }
        val volumeText = levels.getValue(group).toString()
        val left = if (selected && editingBusy) "‹ $busyText ›" else busyText
        val right = if (selected && !editingBusy) "‹ $volumeText ›" else volumeText
        return "Busy $left    Volume $right"
    }

    // Busy changes the beat; volume only how loud it is
    private fun changeDrum(
        group: String,
        step: Int,
    ) {
        if (editingBusy) {
            busy[group] = (busy.getValue(group) + step).coerceIn(0, 10)
            regenerate()
        } else {
            levels[group] = (levels.getValue(group) + step).coerceIn(0, 10)
            applyVolumes()
        }
    }

    private fun rowLevel(role: String): Float {
        if (page.tab != TAB_GENERATOR) return 1f
        val group = BeatGenerator.GROUPS.entries.firstOrNull { role in it.value }?.key ?: return 1f
        return levels.getValue(group) / 10f
    }

    private fun applyVolumes() {
        engine.volumes =
            if (page.tab == TAB_GENERATOR) {
                BeatGenerator.GROUPS.entries.flatMap { (group, roles) -> roles.map { it to levels.getValue(group) / DEFAULT_VOLUME.toFloat() } }.toMap()
            } else {
                emptyMap()
            }
    }

    private fun setHumanise(value: Int) {
        humanise = value.coerceIn(0, 10)
        engine.humanise = humanise / 10f
    }

    private fun regenerate() {
        generated =
            BeatGenerator.generate(
                BeatGenerator.Settings(BeatGenerator.STYLES[generatorStyle], complexity, bars, busy, swing / 10f, seed),
            )
        beatChanged()
    }

    private fun beatChanged() {
        applyVolumes()
        if (playing) current()?.let { engine.change(it) }
        showPage()
    }

    private fun toggle() {
        if (playing) stop() else play()
    }

    private fun play() {
        val beat = current() ?: return
        engine.bpm = bpm
        applyVolumes()
        engine.play(beat)
        playing = true
        showPage()
        page.invalidate()
    }

    private fun stop() {
        engine.stop()
        playing = false
        showPage()
    }

    private fun setTempo(value: Int) {
        bpm = value.coerceIn(MIN_BPM, MAX_BPM)
        engine.bpm = bpm
        showPage()
    }

    private fun typeDigit(digit: Char) {
        handler.removeCallbacks(applyTyped)
        typed.append(digit)
        showPage()
        if (typed.length == 3) commitTyped() else handler.postDelayed(applyTyped, TYPING_PAUSE_MS)
    }

    private fun commitTyped() {
        handler.removeCallbacks(applyTyped)
        if (typed.isEmpty()) return
        val value = typed.toString().toInt()
        typed.clear()
        setTempo(value)
    }

    private fun saveBeat() {
        val beat = generated ?: return
        val entry = BeatLibrary.Entry(SAVED, kit, Beat("Saved ${saved.size + 1}", beat.beats, beat.stepsPerBeat, beat.swing, beat.tracks))
        saved.add(entry)
        val list =
            JSONArray(
                saved.map { item ->
                    JSONObject()
                        .put("name", item.beat.name)
                        .put("kit", item.kit)
                        .put("beats", item.beat.beats)
                        .put("sub", item.beat.stepsPerBeat)
                        .put("swing", item.beat.swing.toDouble())
                        .put("tracks", JSONObject(item.beat.tracks.mapValues { (_, steps) -> JSONArray(steps.map { it.toDouble() }) }))
                },
            )
        prefs.edit().putString(KEY_SAVED, list.toString()).apply()
        page.status = "Saved to Library"
        page.invalidate()
    }

    private fun loadSaved() {
        val list = JSONArray(prefs.getString(KEY_SAVED, "[]"))
        saved =
            (0 until list.length())
                .map { i ->
                    val item = list.getJSONObject(i)
                    val tracks = item.getJSONObject("tracks")
                    val map = LinkedHashMap<String, FloatArray>()
                    tracks.keys().forEach { role ->
                        val steps = tracks.getJSONArray(role)
                        map[role] = FloatArray(steps.length()) { steps.getDouble(it).toFloat() }
                    }
                    BeatLibrary.Entry(SAVED, item.getString("kit"), Beat(item.getString("name"), item.getInt("beats"), item.getInt("sub"), item.getDouble("swing").toFloat(), map))
                }.toMutableList()
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            if (event.repeatCount == 0) typeDigit('0' + (keyCode - KeyEvent.KEYCODE_0))
            return true
        }
        val row = page.rows.getOrNull(page.selected)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                commitTyped()
                if (event.repeatCount == 0) row?.press?.invoke() ?: toggle()
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> change(row, -1, event)
            KeyEvent.KEYCODE_DPAD_RIGHT -> change(row, 1, event)
            KeyEvent.KEYCODE_DPAD_UP -> move(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(1)
            else -> return super.onKeyDown(keyCode, event)
        }
        showPage()
        return true
    }

    private fun move(by: Int) {
        commitTyped()
        page.selected = (page.selected + by).coerceIn(0, page.rows.size - 1)
    }

    // Only tempo and levels run on while the button is held
    private fun change(
        row: DrumsView.Row?,
        step: Int,
        event: KeyEvent,
    ) {
        commitTyped()
        if (event.repeatCount > 0 && row?.label != "Tempo") return
        row?.change?.invoke(step)
    }

    companion object {
        private const val SAVED = "Saved"
        private const val KEY_SAVED = "saved"
        private const val KEY_KIT = "kit"
        private const val KEY_HUMANISE = "humanise"
        private const val TAB_LIBRARY = 0
        private const val TAB_GENERATOR = 1
        private const val MIN_BPM = 30
        private const val MAX_BPM = 300
        private const val TYPING_PAUSE_MS = 1200L
        private const val DEFAULT_VOLUME = 8
        private val KIT_NAMES = mapOf("big" to "Big Rock Kit", "studio" to "Studio Kit", "vintage" to "Vintage 70s Kit", "brushes" to "Brushes", "808" to "TR-808 Style", "909" to "TR-909 Style", "trap" to "Trap 808")
    }
}
