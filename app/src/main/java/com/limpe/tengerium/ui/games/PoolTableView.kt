package com.limpe.tengerium.ui.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The eight-ball table: felt, six pockets, numbered balls and an aim line.
 *
 * The shooter drags anywhere on the table to aim; a shot is played back frame by frame from
 * the simulation, so the balls roll the way the physics said they would.
 */
class PoolTableView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var balls: List<PoolBall> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var aimAngle: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var interactive: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var onAim: ((Float) -> Unit)? = null

    private var playback: List<List<PoolBall>> = emptyList()
    private var playbackIndex = 0
    private var onPlaybackEnd: (() -> Unit)? = null
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private val feltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0B6B3A") }
    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5D3A1A") }
    private val pocketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#05080C") }
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#101010")
        textAlign = Paint.Align.CENTER
    }
    private val railEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#2B1A0C")
    }
    private val aimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8F1FF")
        strokeWidth = 2.4f
        pathEffect = DashPathEffect(floatArrayOf(9f, 7f), 0f)
    }
    private val rect = RectF()

    private val ticker = object : Runnable {
        override fun run() {
            if (playback.isEmpty()) return
            playbackIndex++
            if (playbackIndex >= playback.size) {
                finishPlayback()
                return
            }
            balls = playback[playbackIndex]
            postDelayed(this, 16L)
        }
    }

    fun play(frames: List<List<PoolBall>>, onDone: () -> Unit) {
        if (frames.isEmpty()) {
            onDone()
            return
        }
        removeCallbacks(ticker)
        playback = frames
        playbackIndex = 0
        onPlaybackEnd = onDone
        balls = frames.first()
        interactive = false
        postDelayed(ticker, 16L)
    }

    fun stopPlayback() {
        removeCallbacks(ticker)
        playback = emptyList()
        onPlaybackEnd = null
        interactive = false
    }

    override fun onDetachedFromWindow() {
        stopPlayback()
        super.onDetachedFromWindow()
    }

    val isPlaying: Boolean get() = playback.isNotEmpty()

    private fun finishPlayback() {
        removeCallbacks(ticker)
        balls = playback.lastOrNull() ?: balls
        playback = emptyList()
        val done = onPlaybackEnd
        onPlaybackEnd = null
        done?.invoke()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (width * PoolTable.HEIGHT / PoolTable.WIDTH).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        scale = min(width / PoolTable.WIDTH, height / PoolTable.HEIGHT)
        offsetX = (width - PoolTable.WIDTH * scale) / 2f
        offsetY = (height - PoolTable.HEIGHT * scale) / 2f

        // wood rail then felt
        rect.set(offsetX - 8f * scale, offsetY - 8f * scale,
            offsetX + PoolTable.WIDTH * scale + 8f * scale,
            offsetY + PoolTable.HEIGHT * scale + 8f * scale)
        canvas.drawRoundRect(rect, 10f * scale, 10f * scale, railPaint)
        rect.set(offsetX, offsetY,
            offsetX + PoolTable.WIDTH * scale, offsetY + PoolTable.HEIGHT * scale)
        canvas.drawRect(rect, feltPaint)
        canvas.drawRect(rect, railEdgePaint)

        for ((px, py) in PoolTable.pockets()) {
            canvas.drawCircle(offsetX + px * scale, offsetY + py * scale, 32f * scale, pocketPaint)
        }

        for (ball in balls) {
            drawBall(canvas, ball)
        }

        if (interactive && !isPlaying) {
            val cue = balls.firstOrNull { it.id == PoolTable.CUE_ID } ?: return
            val cx = offsetX + cue.x * scale
            val cy = offsetY + cue.y * scale
            val length = 150f * scale
            val dx = cos(Math.toRadians(aimAngle.toDouble())).toFloat() * length
            val dy = sin(Math.toRadians(aimAngle.toDouble())).toFloat() * length
            canvas.drawLine(cx, cy, cx + dx, cy + dy, aimPaint)
        }
    }

    private fun drawBall(canvas: Canvas, ball: PoolBall) {
        val cx = offsetX + ball.x * scale
        val cy = offsetY + ball.y * scale
        val radius = PoolTable.RADIUS * scale * 1.05f
        val ballColor = colorOf(ball.id)

        if (ball.id == PoolTable.CUE_ID) {
            canvas.drawCircle(cx, cy, radius, whitePaint)
            return
        }

        // One colour per player: yellow for the low balls, red for the high ones.
        ballPaint.color = ballColor
        canvas.drawCircle(cx, cy, radius, ballPaint)

        // Every object ball carries its number, including the black 8.
        canvas.drawCircle(cx, cy, radius * 0.44f, whitePaint)
        numberPaint.textSize = radius * 0.72f
        canvas.drawText(ball.id.toString(), cx, cy + radius * 0.26f, numberPaint)
    }

    private fun colorOf(id: Int): Int = when {
        id == PoolTable.EIGHT -> Color.parseColor("#101010")
        PoolTable.groupOf(id) == PoolTable.SOLIDS -> Color.parseColor("#F2C200")
        PoolTable.groupOf(id) == PoolTable.STRIPES -> Color.parseColor("#D32020")
        else -> Color.WHITE
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive || isPlaying) return false
        val cue = balls.firstOrNull { it.id == PoolTable.CUE_ID } ?: return false
        val tx = (event.x - offsetX) / scale
        val ty = (event.y - offsetY) / scale
        if (event.action == MotionEvent.ACTION_MOVE || event.action == MotionEvent.ACTION_UP) {
            val angle = Math.toDegrees(atan2((ty - cue.y).toDouble(), (tx - cue.x).toDouble())).toFloat()
            aimAngle = angle
            onAim?.invoke(angle)
            if (event.action == MotionEvent.ACTION_UP) performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
