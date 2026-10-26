package com.limpe.tengerium.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Movie
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern

/**
 * Custom MSN-style emoticons: a user-chosen code mapped to a small image (GIFs animate).
 * The code stays in the message text, so other clients see the plain code.
 */
object CustomEmoticons {

    data class Entry(val code: String, val fileName: String) {
        fun file(context: Context) = File(dir(context), fileName)
    }

    private const val PREFS = "custom_emoticons"
    private const val KEY = "entries"
    private const val FOLDER = "custom_emoticons"
    private const val MAX_BYTES = 2L * 1024L * 1024L
    private const val MAX_ENTRIES = 40

    private val movieCache = HashMap<String, Movie?>()
    private val bitmapCache = HashMap<String, Bitmap?>()

    private fun dir(context: Context) = File(context.filesDir, FOLDER).apply { mkdirs() }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun entries(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val code = item.optString("code")
                val fileName = item.optString("file")
                if (code.isBlank() || fileName.isBlank()) null else Entry(code, fileName)
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("code", it.code).put("file", it.fileName)) }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    fun isCodeAvailable(context: Context, code: String): Boolean {
        val cleaned = code.trim()
        if (entries(context).any { it.code.equals(cleaned, ignoreCase = true) }) return false
        return Emoticons.all.none { emoticon -> emoticon.codes.any { it.equals(cleaned, ignoreCase = true) } }
    }

    /** Copies the picked image into app storage. Returns an error message, or null on success. */
    fun add(context: Context, rawCode: String, uri: Uri): String? {
        val code = rawCode.trim()
        if (code.length < 2) return "Enter a code with at least 2 characters"
        if (code.length > 24) return "Keep the code under 24 characters"
        if (code.any { it.isWhitespace() } || code.any { it in "[]{}<>" }) {
            return "The code cannot contain spaces or brackets"
        }
        if (!isCodeAvailable(context, code)) return "\"$code\" is already used"
        if (entries(context).size >= MAX_ENTRIES) return "You can keep up to $MAX_ENTRIES custom emoticons"

        val appContext = context.applicationContext
        val resolver = appContext.contentResolver
        val mime = resolver.getType(uri).orEmpty()
        val extension = when {
            mime.contains("gif") -> "gif"
            mime.contains("png") -> "png"
            mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
            mime.contains("webp") -> "webp"
            else -> "img"
        }
        val fileName = "${System.currentTimeMillis()}-${(100..999).random()}.$extension"
        val target = File(dir(appContext), fileName)

        return try {
            val input = resolver.openInputStream(uri) ?: return "Could not open that image"
            input.use { source ->
                target.outputStream().use { sink ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) {
                            target.delete()
                            return "Custom emoticons must be under 2 MB"
                        }
                        sink.write(buffer, 0, read)
                    }
                }
            }
            if (!isImage(target)) {
                target.delete()
                return "That file is not a supported image"
            }
            save(appContext, entries(appContext) + Entry(code, fileName))
            null
        } catch (error: Exception) {
            target.delete()
            "Could not save that image"
        }
    }

    fun remove(context: Context, code: String) {
        val appContext = context.applicationContext
        val existing = entries(appContext)
        val entry = existing.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: return
        entry.file(appContext).delete()
        movieCache.remove(entry.file(appContext).absolutePath)
        bitmapCache.remove(entry.file(appContext).absolutePath)
        save(appContext, existing - entry)
    }

    private fun isImage(file: File): Boolean {
        if (file.length() == 0L) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) return true
        return Movie.decodeFile(file.absolutePath) != null
    }

    /** A drawable for pickers and previews. */
    fun preview(context: Context, entry: Entry, sizePx: Int): Drawable? =
        drawableFor(context, entry, sizePx)

    private fun drawableFor(context: Context, entry: Entry, sizePx: Int): Drawable? {
        val file = entry.file(context)
        if (!file.exists()) return null
        val path = file.absolutePath
        val movie = if (movieCache.containsKey(path)) movieCache[path] else Movie.decodeFile(path).also { movieCache[path] = it }
        if (movie != null && movie.width() > 0 && movie.height() > 0) {
            val height = (sizePx.toFloat() * movie.height() / movie.width()).toInt().coerceAtLeast(1)
            return MovieDrawable(movie).apply { setBounds(0, 0, sizePx, height) }
        }
        val bitmap = if (bitmapCache.containsKey(path)) bitmapCache[path] else decodeSampled(path, sizePx).also { bitmapCache[path] = it }
        if (bitmap == null) return null
        val height = (sizePx.toFloat() * bitmap.height / bitmap.width).toInt().coerceAtLeast(1)
        return BitmapDrawable(context.resources, bitmap).apply { setBounds(0, 0, sizePx, height) }
    }

    private fun decodeSampled(path: String, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Draws custom emoticon images over their codes. */
    fun apply(context: Context, text: CharSequence, textSizePx: Float): CharSequence {
        if (text.isEmpty()) return text
        val entries = entries(context)
        if (entries.isEmpty()) return text
        val byCode = entries.associateBy { it.code.lowercase() }
        val alternatives = byCode.keys.sortedByDescending { it.length }
            .joinToString("|") { Pattern.quote(it) }
        val pattern = Pattern.compile(
            "(?<![^\\s(\\[{\"'])(?:$alternatives)(?![^\\s.,!?;])",
            Pattern.CASE_INSENSITIVE
        )
        val size = (textSizePx * 1.3f).toInt().coerceAtLeast(12)
        val matcher = pattern.matcher(text)
        var result: SpannableStringBuilder? = null
        while (matcher.find()) {
            val entry = byCode[matcher.group().lowercase()] ?: continue
            val drawable = drawableFor(context, entry, size) ?: continue
            if (result == null) result = SpannableStringBuilder(text)
            result.setSpan(
                ImageSpan(drawable, ImageSpan.ALIGN_BASELINE),
                matcher.start(), matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return result ?: text
    }

    /** Animated GIF drawable; Movie loops forever. */
    private class MovieDrawable(private val movie: Movie) : Drawable() {

        private val startedAt = SystemClock.uptimeMillis()
        private val tick = Runnable { invalidateSelf() }

        override fun draw(canvas: Canvas) {
            val width = movie.width()
            val height = movie.height()
            if (width <= 0 || height <= 0) return
            val duration = movie.duration()
            val time = if (duration > 0) ((SystemClock.uptimeMillis() - startedAt) % duration).toInt() else 0
            movie.setTime(time)
            canvas.save()
            canvas.scale(bounds.width().toFloat() / width, bounds.height().toFloat() / height)
            movie.draw(canvas, 0f, 0f)
            canvas.restore()
            if (duration > 0) {
                unscheduleSelf(tick)
                scheduleSelf(tick, SystemClock.uptimeMillis() + 50)
            }
        }

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
