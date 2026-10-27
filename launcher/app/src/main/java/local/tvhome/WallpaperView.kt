package local.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Executors

/**
 * Full-screen slideshow of the photos in a folder: each photo slowly zooms
 * ("Ken Burns") and cross-fades into the next. Only runs while home is visible.
 */
class WallpaperView(
    context: Context,
) : FrameLayout(context) {
    private val front = newImageView()
    private val back = newImageView()
    private val handler = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor()

    private var photos: List<File> = emptyList()
    private var index = -1
    private var folder = ""
    private var shuffle = true
    private var seconds = 45
    private var zoom = true
    private var running = false

    private val advance = Runnable { showNext() }

    init {
        addView(back)
        addView(front)
    }

    fun configure(
        folder: String,
        seconds: Int,
        shuffle: Boolean,
        zoom: Boolean,
    ) {
        this.folder = folder
        this.shuffle = shuffle
        this.seconds = seconds
        this.zoom = zoom
        photos = emptyList()
        rescan()
    }

    /** Re-read the folder, keeping the current order unless photos were added or removed. */
    private fun rescan() {
        val found =
            File(folder)
                .walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in PHOTO_EXTENSIONS }
                .sortedBy { it.name.lowercase() }
                .toList()
        if (found.toSet() == photos.toSet()) return
        photos = if (shuffle) found.shuffled() else found
        index = -1
        Log.i(TAG, "Wallpaper: ${photos.size} photos in $folder")
    }

    fun start() {
        if (running) return
        running = true
        rescan()
        // Keep showing the current photo on return; only load one if nothing is up yet
        if (front.drawable == null) showNext() else scheduleNext()
    }

    fun stop() {
        running = false
        handler.removeCallbacks(advance)
        front.animate().cancel()
    }

    private fun scheduleNext() {
        handler.removeCallbacks(advance)
        if (running && photos.size > 1) handler.postDelayed(advance, seconds * 1000L)
    }

    private fun showNext() {
        // Pick up added/removed photos at the end of each cycle
        if (index + 1 >= photos.size) rescan()
        if (photos.isEmpty()) return
        index = (index + 1) % photos.size
        val file = photos[index]
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        decoder.execute {
            val bitmap = decode(file, width, height)
            handler.post {
                if (bitmap == null) {
                    scheduleNext()
                } else if (!running && front.drawable != null) {
                    bitmap.recycle()
                } else {
                    crossFadeTo(bitmap)
                }
            }
        }
    }

    private fun crossFadeTo(bitmap: Bitmap) {
        // The outgoing photo moves to the back layer; the new one fades in on top
        val old = (front.tag as? Bitmap)
        back.setImageBitmap(old)
        back.tag = old
        back.scaleX = front.scaleX
        back.scaleY = front.scaleY

        front.animate().cancel()
        front.setImageBitmap(bitmap)
        front.tag = bitmap
        front.alpha = 0f
        front.scaleX = 1f
        front.scaleY = 1f
        front
            .animate()
            .alpha(1f)
            .setDuration(FADE_MS)
            .withEndAction {
                // Free the photo that just finished fading out
                back.setImageDrawable(null)
                old?.recycle()
                back.tag = null
                if (zoom) {
                    front
                        .animate()
                        .scaleX(ZOOM)
                        .scaleY(ZOOM)
                        .setDuration(seconds * 1000L)
                        .setInterpolator(LinearInterpolator())
                        .start()
                }
            }.start()
        scheduleNext()
    }

    private fun newImageView() =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

    companion object {
        private const val TAG = "TVHome"
        private const val FADE_MS = 1500L
        private const val ZOOM = 1.08f
        private val PHOTO_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "avif")

        // Decode no larger than the screen (the TV has little RAM)
        fun decode(
            file: File,
            width: Int,
            height: Int,
        ): Bitmap? =
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            } catch (ex: Exception) {
                Log.e(TAG, "Could not decode $file", ex)
                null
            }
    }
}
