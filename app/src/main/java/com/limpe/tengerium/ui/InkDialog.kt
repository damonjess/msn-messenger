package com.limpe.tengerium.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.limpe.tengerium.R
import java.io.File
import java.io.FileOutputStream

/**
 * MSN's "handwriting" tab: draw with a finger or stylus and send the drawing as a PNG.
 */
class InkDialog(
    context: Context,
    private val onSend: (File) -> Unit
) : Dialog(context) {

    private lateinit var canvasView: InkCanvasView
    private lateinit var hintText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_wink_dialog)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_msn_xp_titlebar)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        header.addView(TextView(context).apply {
            text = "Handwriting"
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(context).apply {
            text = "msn"
            setTextColor(Color.parseColor("#DCE9FA"))
            textSize = 10f
        })
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val tools = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val inkColors = intArrayOf(0xFF111111.toInt(), 0xFF1565C0.toInt(), 0xFFC62828.toInt(), 0xFF2E7D32.toInt(), 0xFF6A1B9A.toInt())
        val swatches = mutableListOf<View>()
        inkColors.forEach { color ->
            val swatch = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(dp(2), if (color == inkColors.first()) Color.parseColor("#333333") else Color.TRANSPARENT)
                }
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                    marginEnd = dp(6)
                }
                setOnClickListener {
                    canvasView.inkColor = color
                    swatches.forEach { other ->
                        (other.background?.mutate() as? GradientDrawable)?.setStroke(dp(2), Color.TRANSPARENT)
                        other.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    }
                    (background?.mutate() as? GradientDrawable)?.setStroke(dp(3), Color.parseColor("#333333"))
                    animate().scaleX(1.15f).scaleY(1.15f).setDuration(100).start()
                }
            }
            swatches.add(swatch)
            tools.addView(swatch)
        }
        tools.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        tools.addView(smallButton("Undo") { canvasView.undo() })
        tools.addView(smallButton("Clear") { canvasView.clearInk() }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(6)
        })
        body.addView(tools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })

        canvasView = InkCanvasView(context).apply {
            setBackgroundColor(Color.WHITE)
            inkColor = inkColors.first()
        }

        val frame = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#7F9DB9"))
            }
            addView(canvasView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        body.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (context.resources.displayMetrics.heightPixels * 0.40f).toInt()))

        hintText = TextView(context).apply {
            text = "Draw with your finger or a stylus."
            setTextColor(Color.parseColor("#555555"))
            textSize = 11f
            setPadding(0, dp(6), 0, dp(8))
        }
        body.addView(hintText)

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        actions.addView(Button(context).apply {
            text = "Cancel"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.parseColor("#002B66"))
            setBackgroundResource(R.drawable.bg_msn_button)
            minHeight = 0
            minWidth = 0
            stateListAnimator = null
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(6) })
        actions.addView(Button(context).apply {
            text = "Send drawing"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.parseColor("#002B66"))
            setBackgroundResource(R.drawable.bg_msn_button)
            minHeight = 0
            minWidth = 0
            stateListAnimator = null
            setOnClickListener { sendDrawing() }
        }, LinearLayout.LayoutParams(0, dp(40), 1.4f))
        body.addView(actions)

        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        val width = (context.resources.displayMetrics.widthPixels * 0.94f).toInt()
        window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window?.setDimAmount(0.4f)
    }

    private fun smallButton(label: String, action: () -> Unit) = Button(context).apply {
        text = label
        textSize = 10f
        isAllCaps = false
        setTextColor(Color.parseColor("#002B66"))
        setBackgroundResource(R.drawable.bg_msn_button)
        minHeight = 0
        minWidth = 0
        stateListAnimator = null
        setPadding(0, 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams((54 * context.resources.displayMetrics.density).toInt(), (28 * context.resources.displayMetrics.density).toInt())
        setOnClickListener { action() }
    }

    private fun sendDrawing() {
        if (canvasView.isEmpty()) {
            hintText.text = "Draw something first."
            return
        }
        val bitmap = canvasView.exportBitmap()
        val maxWidth = 480
        val scaled = if (bitmap.width > maxWidth) {
            val height = (bitmap.height.toFloat() * maxWidth / bitmap.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, maxWidth, height, true)
        } else {
            bitmap
        }
        val file = File(context.cacheDir, "ink-${System.currentTimeMillis()}.png")
        val saved = runCatching {
            FileOutputStream(file).use { output -> scaled.compress(Bitmap.CompressFormat.PNG, 100, output) }
        }
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        if (saved.isFailure || file.length() == 0L) {
            file.delete()
            hintText.text = "Could not save the drawing."
            return
        }
        onSend(file)
        dismiss()
    }

    /** A very small freehand canvas: each stroke keeps its own colour. */
    private class InkCanvasView(context: Context) : View(context) {

        private class Stroke(val paint: Paint, val path: Path)

        private val strokes = mutableListOf<Stroke>()
        private var active: Stroke? = null
        private val strokeWidth = 5f * context.resources.displayMetrics.density

        var inkColor: Int = Color.BLACK

        fun isEmpty(): Boolean = strokes.isEmpty()

        fun undo() {
            if (strokes.isEmpty()) return
            strokes.removeAt(strokes.size - 1)
            invalidate()
        }

        fun clearInk() {
            strokes.clear()
            active = null
            invalidate()
        }

        fun exportBitmap(): Bitmap {
            val width = width.coerceAtLeast(1)
            val height = height.coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            strokes.forEach { canvas.drawPath(it.path, it.paint) }
            return bitmap
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            strokes.forEach { canvas.drawPath(it.path, it.paint) }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val paint = Paint().apply {
                        isAntiAlias = true
                        style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        color = inkColor
                        strokeWidth = this@InkCanvasView.strokeWidth
                    }
                    val path = Path().apply {
                        moveTo(event.x, event.y)
                        lineTo(event.x + 0.1f, event.y + 0.1f)
                    }
                    active = Stroke(paint, path).also { strokes.add(it) }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    active?.path?.lineTo(event.x, event.y)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    active = null
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
