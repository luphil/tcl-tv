package local.tvhome

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager

/**
 * Every chord and scale, opened with a code typed on the homescreen. Left/right step through
 * notes or chord types, up/down change the view, OK changes position (or scale page), Back leaves.
 */
class ChordBookActivity : Activity() {
    private lateinit var page: ChordBookView
    private lateinit var book: ChordBook
    private val prefs by lazy { getSharedPreferences("chordbook", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val config = Config.load()
        book = ChordBook(this)
        page = ChordBookView(this, book, config.typeface(config.fontWeight))
        page.mode = ChordBookView.Mode.entries.firstOrNull { it.name == prefs.getString(KEY_MODE, null) } ?: page.mode
        page.note = prefs.getInt(KEY_NOTE, 0).coerceIn(book.notes.indices)
        page.type = prefs.getInt(KEY_TYPE, 0).coerceIn(book.chords.indices)
        page.position = ChordBook.Position.entries.firstOrNull { it.name == prefs.getString(KEY_POSITION, null) } ?: page.position
        setContentView(page)
    }

    override fun onPause() {
        super.onPause()
        prefs
            .edit()
            .putString(KEY_MODE, page.mode.name)
            .putInt(KEY_NOTE, page.note)
            .putInt(KEY_TYPE, page.type)
            .putString(KEY_POSITION, page.position.name)
            .apply()
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> step(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> step(1)
            KeyEvent.KEYCODE_DPAD_UP -> if (event.repeatCount == 0) changeMode(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (event.repeatCount == 0) changeMode(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (event.repeatCount == 0) nextPage()
            else -> return super.onKeyDown(keyCode, event)
        }
        page.invalidate()
        return true
    }

    private fun step(by: Int) {
        if (page.mode == ChordBookView.Mode.BY_TYPE) {
            page.type = (page.type + by).mod(book.chords.size)
        } else {
            page.note = (page.note + by).mod(book.notes.size)
        }
    }

    private fun changeMode(by: Int) {
        val modes = ChordBookView.Mode.entries
        page.mode = modes[(page.mode.ordinal + by).mod(modes.size)]
    }

    private fun nextPage() {
        if (page.mode == ChordBookView.Mode.SCALES) {
            page.scalePage = (page.scalePage + 1) % page.scalePages
        } else {
            val positions = ChordBook.Position.entries
            page.position = positions[(page.position.ordinal + 1) % positions.size]
        }
    }

    companion object {
        private const val KEY_MODE = "mode"
        private const val KEY_NOTE = "note"
        private const val KEY_TYPE = "type"
        private const val KEY_POSITION = "position"
    }
}
