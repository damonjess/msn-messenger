package com.limpe.tengerium.ui.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** The Connect Four board: seven columns, six rows, tap a column to drop a disc. */
class ConnectFourView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var board: String = ConnectFour.emptyBoard()
        set(value) {
            field = value
            invalidate()
        }

    var interactive: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var onColumn: ((Int) -> Unit)? = null

    private val boardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1565C0") }
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0B1220") }
    private val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E53935") }
    private val yellowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FDD835") }
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (width * ConnectFour.ROWS / ConnectFour.COLUMNS.toFloat()).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cell = width / ConnectFour.COLUMNS.toFloat()
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, cell * 0.14f, cell * 0.14f, boardPaint)

        val holeRadius = cell * 0.38f
        for (row in 0 until ConnectFour.ROWS) {
            for (column in 0 until ConnectFour.COLUMNS) {
                val cx = cell * (column + 0.5f)
                val cy = cell * (row + 0.5f)
                canvas.drawCircle(cx, cy, holeRadius, holePaint)
                when (ConnectFour.cell(board, row, column)) {
                    'R' -> canvas.drawCircle(cx, cy, holeRadius * 0.86f, redPaint)
                    'Y' -> canvas.drawCircle(cx, cy, holeRadius * 0.86f, yellowPaint)
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return false
        if (event.action == MotionEvent.ACTION_UP) {
            val cell = width / ConnectFour.COLUMNS.toFloat()
            val column = (event.x / cell).toInt().coerceIn(0, ConnectFour.COLUMNS - 1)
            performClick()
            onColumn?.invoke(column)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
