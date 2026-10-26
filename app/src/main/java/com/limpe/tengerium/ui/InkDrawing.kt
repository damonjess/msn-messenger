package com.limpe.tengerium.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import java.io.ByteArrayOutputStream
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** One pen stroke of a drawing: its colour and the points the finger visited. */
class InkStroke(val color: Int, val points: List<PointF>)

/** A whole drawing in the coordinates of the canvas it was drawn on. */
class InkDrawing(
    val strokes: List<InkStroke>,
    val width: Int,
    val height: Int
) {
    val isEmpty: Boolean get() = strokes.all { it.points.isEmpty() }
}

/**
 * Drawings travel inside the chat message as a compact stroke list instead of an
 * image attachment.
 *
 * MSNP file transfers are peer-to-peer: the recipient has to reach the sender's
 * device directly, which fails across mobile networks and NATs, so the drawing
 * never arrived and the chat showed "(image not downloaded)". The stroke payload
 * below fits in a single MSG line for a normal doodle, so the drawing is always
 * delivered together with the message itself.
 *
 * Wire format (base64 encoded, version 1):
 * ```
 * byte      version
 * uint16 le width, height of the drawing canvas
 * byte      stroke count
 * per stroke:
 *   uint16 le colour (RGB565)
 *   byte      point count
 *   point *   x, y quantised to 0..255
 * ```
 */
object InkCodec {

    /** Version byte of the stroke payload. */
    const val VERSION = 1

    /** PNG payloads written by older builds start with these bytes. */
    const val PNG_SIGNATURE = 0x89

    /** Width used when the drawing is rasterised for display, in pixels. */
    const val RENDER_WIDTH_PX = 640

    private const val GRID = 255
    private const val MAX_STROKES = 255
    private const val MAX_POINTS_PER_STROKE = 255
    private const val MAX_CANVAS_SIDE = 4096

    /** Stroke width relative to the rendered image width. */
    private const val STROKE_WIDTH_RATIO = 0.011f

    /**
     * Point spacing tried while encoding, in source pixels. A drawing that is too
     * detailed for the message limit is thinned more and more until it fits, so the
     * shape survives while the message stays small.
     */
    private val THINNING_STEPS = floatArrayOf(
        0f, 1.5f, 2.5f, 4f, 6f, 9f, 13f, 19f, 27f, 38f, 55f, 80f, 115f, 165f
    )

    /** Encodes [drawing] into at most [maxBytes], or null when it cannot be made small enough. */
    fun encode(drawing: InkDrawing, maxBytes: Int): ByteArray? {
        if (drawing.isEmpty || drawing.width <= 0 || drawing.height <= 0) return null
        val strokes = drawing.strokes.filter { it.points.isNotEmpty() }.take(MAX_STROKES)
        if (strokes.isEmpty()) return null
        for (minDistance in THINNING_STEPS) {
            val bytes = serialize(drawing.width, drawing.height, strokes, minDistance)
            if (bytes.size <= maxBytes) return bytes
        }
        return null
    }

    /** Reads a stroke payload produced by [encode]; null for anything malformed. */
    fun decode(bytes: ByteArray): InkDrawing? {
        if (bytes.size < 8 || (bytes[0].toInt() and 0xFF) != VERSION) return null
        var index = 1

        fun readUShort(): Int {
            if (index + 1 >= bytes.size) return -1
            val value = (bytes[index].toInt() and 0xFF) or ((bytes[index + 1].toInt() and 0xFF) shl 8)
            index += 2
            return value
        }

        val width = readUShort()
        val height = readUShort()
        if (width <= 0 || height <= 0 || width > MAX_CANVAS_SIDE || height > MAX_CANVAS_SIDE) return null
        if (index >= bytes.size) return null
        val strokeCount = bytes[index++].toInt() and 0xFF
        if (strokeCount == 0 || strokeCount > MAX_STROKES) return null

        val strokes = ArrayList<InkStroke>(strokeCount)
        for (strokeIndex in 0 until strokeCount) {
            val color = readUShort()
            if (color < 0 || index >= bytes.size) return null
            val pointCount = bytes[index++].toInt() and 0xFF
            if (pointCount == 0) continue
            if (index + pointCount * 2 > bytes.size) return null
            val points = ArrayList<PointF>(pointCount)
            for (pointIndex in 0 until pointCount) {
                val x = bytes[index++].toInt() and 0xFF
                val y = bytes[index++].toInt() and 0xFF
                points.add(
                    PointF(
                        x.toFloat() / GRID * width,
                        y.toFloat() / GRID * height
                    )
                )
            }
            strokes.add(InkStroke(argbFromRgb565(color), points))
        }
        if (strokes.isEmpty()) return null
        return InkDrawing(strokes, width, height)
    }

    /** Rasterises [drawing] on a white background at [widthPx]. */
    fun render(drawing: InkDrawing, widthPx: Int = RENDER_WIDTH_PX): Bitmap? {
        val sourceWidth = drawing.width.coerceAtLeast(1)
        val sourceHeight = drawing.height.coerceAtLeast(1)
        val width = widthPx.coerceIn(48, 2048)
        val height = ((width.toFloat() * sourceHeight / sourceWidth).roundToInt()).coerceIn(1, 2048)
        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) {
            return null
        }
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val scaleX = width.toFloat() / sourceWidth
        val scaleY = height.toFloat() / sourceHeight
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = max(2f, width * STROKE_WIDTH_RATIO)
        }

        drawing.strokes.forEach { stroke ->
            if (stroke.points.isEmpty()) return@forEach
            paint.color = stroke.color
            if (stroke.points.size == 1) {
                val point = stroke.points[0]
                canvas.drawCircle(point.x * scaleX, point.y * scaleY, paint.strokeWidth / 2f, paint)
            } else {
                val path = Path()
                val first = stroke.points[0]
                path.moveTo(first.x * scaleX, first.y * scaleY)
                for (index in 1 until stroke.points.size) {
                    val point = stroke.points[index]
                    path.lineTo(point.x * scaleX, point.y * scaleY)
                }
                canvas.drawPath(path, paint)
            }
        }
        return bitmap
    }

    /** A PNG of [drawing], ready for [InkStore.storeBytes]. */
    fun pngBytes(drawing: InkDrawing, widthPx: Int = RENDER_WIDTH_PX): ByteArray? {
        val bitmap = render(drawing, widthPx) ?: return null
        return try {
            ByteArrayOutputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) null else output.toByteArray()
            }
        } catch (_: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    private fun serialize(width: Int, height: Int, strokes: List<InkStroke>, minDistance: Float): ByteArray {
        val canvasWidth = width.coerceIn(1, MAX_CANVAS_SIDE)
        val canvasHeight = height.coerceIn(1, MAX_CANVAS_SIDE)
        val scaleX = GRID.toFloat() / canvasWidth
        val scaleY = GRID.toFloat() / canvasHeight

        val output = ByteArrayOutputStream()
        output.write(VERSION)
        writeUShort(output, canvasWidth)
        writeUShort(output, canvasHeight)
        output.write(strokes.size)
        strokes.forEach { stroke ->
            val points = limitPoints(thin(stroke.points, minDistance))
            writeUShort(output, rgb565(stroke.color))
            output.write(points.size)
            points.forEach { point ->
                output.write((point.x * scaleX).roundToInt().coerceIn(0, GRID))
                output.write((point.y * scaleY).roundToInt().coerceIn(0, GRID))
            }
        }
        return output.toByteArray()
    }

    private fun writeUShort(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xFF)
        output.write((value shr 8) and 0xFF)
    }

    /** Drops points that are closer than [minDistance] to the previous kept point. */
    private fun thin(points: List<PointF>, minDistance: Float): List<PointF> {
        if (points.size <= 2 || minDistance <= 0f) return points
        val kept = ArrayList<PointF>(points.size)
        kept.add(points[0])
        var last = points[0]
        for (index in 1 until points.size) {
            val point = points[index]
            if (hypot(point.x - last.x, point.y - last.y) >= minDistance) {
                kept.add(point)
                last = point
            }
        }
        val end = points[points.size - 1]
        if (kept.last().x != end.x || kept.last().y != end.y) kept.add(end)
        return kept
    }

    /** Evenly samples a stroke down to the per-stroke wire limit, keeping both ends. */
    private fun limitPoints(points: List<PointF>): List<PointF> {
        if (points.size <= MAX_POINTS_PER_STROKE) return points
        val limited = ArrayList<PointF>(MAX_POINTS_PER_STROKE)
        val step = (points.size - 1).toFloat() / (MAX_POINTS_PER_STROKE - 1)
        for (index in 0 until MAX_POINTS_PER_STROKE) {
            limited.add(points[(index * step).roundToInt().coerceIn(0, points.size - 1)])
        }
        return limited
    }

    private fun rgb565(color: Int): Int =
        (((color shr 16) and 0xFF) shr 3 shl 11) or
            (((color shr 8) and 0xFF) shr 2 shl 5) or
            ((color and 0xFF) shr 3)

    private fun argbFromRgb565(value: Int): Int {
        val red = ((value shr 11) and 0x1F) * 255 / 31
        val green = ((value shr 5) and 0x3F) * 255 / 63
        val blue = (value and 0x1F) * 255 / 31
        return Color.rgb(red, green, blue)
    }
}
