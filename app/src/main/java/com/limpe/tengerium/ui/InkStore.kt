package com.limpe.tengerium.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.LruCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale

/**
 * Drawings are shown inline in the conversation instead of being sent as a file the
 * recipient has to save.
 *
 * The drawing itself travels inside the chat message as a compact stroke payload
 * ([InkCodec]), so it renders on the other device without any extra transfer. Messages
 * only carry a marker when a drawing is too detailed to fit, and those fall back to the
 * old peer-to-peer image attachment.
 */
object InkStore {

    private const val FOLDER = "ink"
    private const val PREFIX = "ink-"
    private const val MAX_BYTES = 4 * 1024 * 1024

    /**
     * Largest stroke payload embedded in a message. MSNP drops a MSG payload larger than
     * roughly 1.5 KB, and base64 inflates these bytes by a third, so this keeps the whole
     * message comfortably below that limit.
     */
    const val MAX_INLINE_BYTES = 850
    private const val MAX_INLINE_CHARS = 1200

    private val idPattern = Regex("^ink-[a-z0-9-]{4,48}$")
    private val markerPattern = Regex("\\[INK:(ink-[a-z0-9-]{4,48})(?::([A-Za-z0-9+/=_-]+))?]")

    private val cache = LruCache<String, Bitmap>(16)
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** MSNP11 SDK lowercases the whole transmitted filename, so IDs must already be lowercase. */
    fun newId(): String =
        "ink-${System.currentTimeMillis().toString(36)}-${java.util.UUID.randomUUID().toString().take(8)}"
            .lowercase(Locale.ROOT)

    /** The chat message for a drawing, carrying its [payload] inline when it fits. */
    fun marker(id: String, payload: ByteArray? = null): String {
        val normalized = id.lowercase(Locale.ROOT)
        if (payload == null || payload.isEmpty()) return "[INK:$normalized]"
        val encoded = Base64.encodeToString(payload, Base64.NO_WRAP or Base64.URL_SAFE)
        if (encoded.length > MAX_INLINE_CHARS) return "[INK:$normalized]"
        return "[INK:$normalized:$encoded]"
    }

    fun markerId(text: String): String? = markerPattern.find(text)?.groupValues?.get(1)?.lowercase(Locale.ROOT)

    /**
     * Returns the drawing id in [text] and stores the attached drawing on first sight, so
     * the conversation can render it. Returns null when the text carries no drawing.
     */
    fun processMarker(context: Context, text: String): String? {
        val match = markerPattern.find(text) ?: return null
        val id = match.groupValues[1].lowercase(Locale.ROOT)
        if (file(context, id) == null) {
            val encoded = match.groupValues.getOrNull(2)
            if (!encoded.isNullOrBlank()) {
                runCatching { storeInlinePayload(context, id, encoded) }
            }
        }
        return id
    }

    private fun storeInlinePayload(context: Context, id: String, encoded: String) {
        val payload = decodePayload(encoded) ?: return
        if (payload.size >= 2 && (payload[0].toInt() and 0xFF) == InkCodec.PNG_SIGNATURE) {
            // Inline PNG attachments written by older builds.
            storeBytes(context, id, payload)
            return
        }
        val drawing = InkCodec.decode(payload) ?: return
        val png = InkCodec.pngBytes(drawing, InkCodec.RENDER_WIDTH_PX) ?: return
        storeBytes(context, id, png)
    }

    private fun decodePayload(encoded: String): ByteArray? =
        runCatching { Base64.decode(encoded, Base64.NO_WRAP or Base64.URL_SAFE) }.getOrNull()
            ?: runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()

    fun fileName(id: String): String = "$id.png"

    /** The id of a drawing file name, or null when the file is not an inline drawing. */
    fun idFromFileName(name: String): String? {
        val base = name.substringAfterLast('/').substringAfterLast('\\').lowercase(Locale.ROOT)
        if (!base.startsWith(PREFIX) || !base.endsWith(".png")) return null
        return base.dropLast(4).takeIf { idPattern.matches(it) }
    }

    fun file(context: Context, id: String): File? {
        val normalized = id.lowercase(Locale.ROOT)
        if (!idPattern.matches(normalized)) return null
        val file = File(dir(context), fileName(normalized))
        return file.takeIf { it.isFile }
    }

    fun storeFile(context: Context, id: String, source: File): File? {
        val normalized = id.lowercase(Locale.ROOT)
        if (!idPattern.matches(normalized) || !source.isFile || source.length() == 0L) return null
        if (source.length() > MAX_BYTES || !isValidPng(source)) return null
        val target = File(dir(context), fileName(normalized))
        return try {
            if (source.absolutePath != target.absolutePath) source.copyTo(target, overwrite = true)
            cache.evictAll()
            bump()
            target
        } catch (_: Exception) {
            null
        }
    }

    fun storeBytes(context: Context, id: String, bytes: ByteArray): Boolean {
        val normalized = id.lowercase(Locale.ROOT)
        if (!idPattern.matches(normalized) || bytes.isEmpty() || bytes.size > MAX_BYTES) return false
        return try {
            val temp = File(dir(context), "${fileName(normalized)}.tmp")
            temp.writeBytes(bytes)
            if (!isValidPng(temp)) {
                temp.delete()
                false
            } else {
                temp.renameTo(File(dir(context), fileName(normalized)))
                cache.evictAll()
                bump()
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /** A bitmap scaled to fit [maxWidthPx], or null while the drawing is still missing. */
    fun bitmap(context: Context, id: String, maxWidthPx: Int): Bitmap? {
        val normalized = id.lowercase(Locale.ROOT)
        val file = file(context, normalized) ?: return null
        val key = "$normalized@$maxWidthPx"
        cache.get(key)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidthPx) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null
        val scaled = if (decoded.width > maxWidthPx) {
            val height = (decoded.height.toFloat() * maxWidthPx / decoded.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, maxWidthPx, height, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } else {
            decoded
        }
        cache.put(key, scaled)
        return scaled
    }

    fun bump() {
        // processMarker can store a drawing while a RecyclerView is binding a row; update the
        // revision afterwards so listeners never ask the list to redraw mid-bind.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            mainHandler.post { _revision.value = _revision.value + 1 }
        } else {
            _revision.value = _revision.value + 1
        }
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private fun isValidPng(file: File): Boolean {
        if (!file.isFile || file.length() < 24 || file.length() > MAX_BYTES) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0 &&
            bounds.outWidth <= 4096 && bounds.outHeight <= 4096
    }

    private fun dir(context: Context) = File(context.filesDir, FOLDER).apply { mkdirs() }
}
