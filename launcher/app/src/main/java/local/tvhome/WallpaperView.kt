package local.tvhome

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
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
 *
 * The photo on screen is also kept as the "boot photo": a screen-sized copy in the
 * app's private storage that is also set as the Android system wallpaper. The TV
 * shows the system wallpaper while it boots, and the slideshow opens on that same
 * photo, so starting up goes straight from the boot photo into the slideshow.
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
    // A photo is being decoded; skips wait until it's shown
    private var loading = false
    // The photo on screen (for the boot photo: the folder photo it was made from)
    private var current: File? = null
    private val bootPhoto = File(context.filesDir, "boot-photo.jpg")
    private val bootSource = File(context.filesDir, "boot-photo.source")

    private val advance = Runnable { showNext() }

    /** Called with each photo as it starts to show (e.g. to judge how bright it is). */
    var onPhoto: ((Bitmap) -> Unit)? = null

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
        // A boot photo whose original was taken out of the folder is dropped (and replaced
        // by the next photo shown). If the folder can't be read yet, as just after boot, keep it.
        if (bootPhoto.exists() && photos.isNotEmpty() && photos.none { it.path == bootSourcePath() }) {
            bootPhoto.delete()
            bootSource.delete()
        }
        // Keep showing the current photo on return, unless it has been taken out of the folder;
        // only load one if nothing is up yet
        when {
            front.drawable != null && (photos.isEmpty() || current == null || current in photos) -> scheduleNext()
            front.drawable == null && bootPhoto.exists() -> showBootPhoto()
            else -> showNext()
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacks(advance)
        front.animate().cancel()
    }

    /** Move on to the next photo now (the timer restarts from it). */
    fun next() {
        if (!running || loading) return
        handler.removeCallbacks(advance)
        showNext()
    }

    private fun scheduleNext() {
        handler.removeCallbacks(advance)
        if (running && photos.size > 1) handler.postDelayed(advance, seconds * 1000L)
    }

    private fun showNext() {
        // Pick up added/removed photos at the end of each cycle
        if (index + 1 >= photos.size) rescan()
        if (photos.isEmpty()) {
            // Straight after boot the photo folder can be unreadable for a while: keep looking
            handler.removeCallbacks(advance)
            if (running) handler.postDelayed(advance, RETRY_MS)
            return
        }
        index = (index + 1) % photos.size
        val file = photos[index]
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        loading = true
        decoder.execute {
            val bitmap = decode(file, width, height)
            handler.post {
                loading = false
                if (bitmap == null) {
                    scheduleNext()
                } else if (!running && front.drawable != null) {
                    bitmap.recycle()
                } else {
                    current = file
                    crossFadeTo(bitmap)
                    // First run: make a boot photo now rather than waiting for the TV to turn off
                    if (!bootPhoto.exists()) saveBootPhoto()
                }
            }
        }
    }

    /** Open on the boot photo, already fully shown, exactly as the system wallpaper had it. */
    private fun showBootPhoto() {
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        decoder.execute {
            val bitmap = decode(bootPhoto, width, height)
            handler.post {
                if (bitmap == null || front.drawable != null) {
                    bitmap?.recycle()
                    if (front.drawable == null) showNext()
                    return@post
                }
                onPhoto?.invoke(bitmap)
                front.setImageBitmap(bitmap)
                front.tag = bitmap
                front.alpha = 1f
                current = bootSourcePath()?.let { File(it) }
                if (zoom) startZoom()
                scheduleNext()
            }
        }
    }

    /**
     * Keep the photo on screen as the boot photo (private copy + system wallpaper).
     * Called when the TV turns off, so it's what shows next time the TV starts.
     */
    fun saveBootPhoto() {
        val file = current ?: return
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        decoder.execute {
            val photo = decode(file, width, height) ?: return@execute
            val screen = cropToScreen(photo, width, height)
            photo.recycle()
            try {
                val temp = File(bootPhoto.path + ".tmp")
                temp.outputStream().use { screen.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                temp.renameTo(bootPhoto)
                bootSource.writeText(file.path)
                WallpaperManager.getInstance(context).apply {
                    suggestDesiredDimensions(width, height)
                    setBitmap(screen)
                }
                Log.i(TAG, "Boot photo: ${file.name}")
            } catch (ex: Exception) {
                Log.e(TAG, "Could not save boot photo", ex)
            } finally {
                screen.recycle()
            }
        }
    }

    private fun crossFadeTo(bitmap: Bitmap) {
        onPhoto?.invoke(bitmap)
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
                if (zoom) startZoom()
            }.start()
        scheduleNext()
    }

    private fun bootSourcePath(): String? = if (bootSource.exists()) bootSource.readText().trim() else null

    private fun startZoom() {
        front
            .animate()
            .scaleX(ZOOM)
            .scaleY(ZOOM)
            .setDuration(seconds * 1000L)
            .setInterpolator(LinearInterpolator())
            .start()
    }

    private fun newImageView() =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

    companion object {
        private const val TAG = "TVHome"
        private const val FADE_MS = 1500L
        private const val RETRY_MS = 3000L
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
            } catch (ex: OutOfMemoryError) {
                Log.e(TAG, "Not enough memory to decode $file", ex)
                null
            }

        // Exactly screen-sized, cropped from the middle like the slideshow's CENTER_CROP
        private fun cropToScreen(
            photo: Bitmap,
            width: Int,
            height: Int,
        ): Bitmap {
            val scale = maxOf(width.toFloat() / photo.width, height.toFloat() / photo.height)
            val matrix =
                Matrix().apply {
                    setScale(scale, scale)
                    postTranslate((width - photo.width * scale) / 2f, (height - photo.height * scale) / 2f)
                }
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawBitmap(photo, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            }
        }
    }
}
