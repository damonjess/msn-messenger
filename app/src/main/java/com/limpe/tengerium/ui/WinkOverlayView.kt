package com.limpe.tengerium.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Full-screen overlay that plays a short, classic-MSN style animation for a [Wink]
 * on top of the chat window. It never intercepts touches and draws nothing when idle.
 *
 * The thirteen classic winks are drawn from scratch (cartoon characters, props and
 * effects) so they look the same on every device regardless of the emoji font.
 * Sparkles and other particles are seeded once per playback, so every frame is
 * deterministic and nothing is allocated for randomness while drawing.
 */
class WinkOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private enum class Kind {
        GUITAR, KISS, LAUGH, KNOCK, SLAP, PEEK, ICECREAM,
        BALLOON, PIG, UFO, FROG, HEART, IDEA, PULSE
    }

    private enum class Hair { SHORT, LONG, BOB, BEANIE, NONE }

    private enum class Face { SMILE, GRIN, LAUGH, SOB, TONGUE, SHOCK, WIDE, CALM }

    private class Spark(
        val x: Float, val y: Float, val vx: Float, val vy: Float,
        val size: Float, val delay: Float, val spin: Float, val life: Float
    )

    private class Shard(
        val vx: Float, val vy: Float, val size: Float, val spin: Float, val kind: Int
    )

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val path2 = Path()
    private val rect = RectF()

    private var animator: ValueAnimator? = null
    private var wink: Wink? = null
    private var kind = Kind.PULSE
    private var progress = 0f
    private var envelope = 1f
    private var sparks: List<Spark> = emptyList()
    private var shards: List<Shard> = emptyList()
    private var trailGlyphs: List<String> = emptyList()

    /** Called when an animation finishes or is stopped. */
    var onFinished: (() -> Unit)? = null

    val isPlaying: Boolean get() = animator?.isRunning == true

    fun play(w: Wink) {
        if (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) return
        if (width == 0 || height == 0) {
            if (isAttachedToWindow) post { play(w) }
            return
        }
        stopInternal(notify = false)

        wink = w
        kind = kindFor(w.id)
        trailGlyphs = glyphsFor(w)
        sparks = buildSparks(26)
        shards = buildShards()
        progress = 0f

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationFor(kind)
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) stopInternal(notify = true)
                }
            })
            start()
        }
    }

    fun stop() = stopInternal(notify = true)

    private fun stopInternal(notify: Boolean) {
        animator?.let {
            it.removeAllListeners()
            it.cancel()
        }
        animator = null
        wink = null
        sparks = emptyList()
        shards = emptyList()
        invalidate()
        if (notify) onFinished?.invoke()
    }

    override fun onDetachedFromWindow() {
        stopInternal(notify = false)
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- setup

    private fun kindFor(id: String): Kind = when (id) {
        WinkCatalog.GUITAR -> Kind.GUITAR
        WinkCatalog.KISS -> Kind.KISS
        WinkCatalog.LAUGH -> Kind.LAUGH
        WinkCatalog.KNOCK -> Kind.KNOCK
        WinkCatalog.SLAP -> Kind.SLAP
        WinkCatalog.PEEK -> Kind.PEEK
        WinkCatalog.ICECREAM -> Kind.ICECREAM
        WinkCatalog.BALLOON -> Kind.BALLOON
        WinkCatalog.PIG -> Kind.PIG
        WinkCatalog.UFO -> Kind.UFO
        WinkCatalog.FROG -> Kind.FROG
        WinkCatalog.HEART -> Kind.HEART
        WinkCatalog.IDEA -> Kind.IDEA
        else -> Kind.PULSE
    }

    private fun durationFor(k: Kind): Long = when (k) {
        Kind.GUITAR -> 3200
        Kind.KISS -> 2400
        Kind.LAUGH -> 3600
        Kind.KNOCK -> 2400
        Kind.SLAP -> 3200
        Kind.PEEK -> 2800
        Kind.ICECREAM -> 3600
        Kind.BALLOON -> 2800
        Kind.PIG -> 3400
        Kind.UFO -> 4200
        Kind.FROG -> 3600
        Kind.HEART -> 3400
        Kind.IDEA -> 3000
        Kind.PULSE -> 2800
    }

    /** Emoji trail used only by retired winks that have no classic animation. */
    private fun glyphsFor(w: Wink): List<String> = when (w.id) {
        "party" -> listOf("\uD83C\uDF89", "\uD83C\uDF8A", "\u2728")
        "run" -> listOf("\uD83D\uDCA8")
        "monkey" -> listOf("\uD83C\uDF4C", "\u2728")
        else -> listOf(w.emoji)
    }

    private fun buildSparks(count: Int): List<Spark> {
        val rnd = Random(System.nanoTime())
        return List(count) {
            val angle = rnd.nextFloat() * 2f * PI.toFloat()
            Spark(
                x = 0.1f + rnd.nextFloat() * 0.8f,
                y = 0.1f + rnd.nextFloat() * 0.8f,
                vx = cos(angle) * (0.25f + rnd.nextFloat() * 0.7f),
                vy = sin(angle) * (0.25f + rnd.nextFloat() * 0.7f),
                size = 0.012f + rnd.nextFloat() * 0.03f,
                delay = rnd.nextFloat() * 0.35f,
                spin = (rnd.nextFloat() - 0.5f) * 720f,
                life = 0.45f + rnd.nextFloat() * 0.5f
            )
        }
    }

    private fun buildShards(): List<Shard> {
        val rnd = Random(System.nanoTime())
        return List(14) {
            val angle = rnd.nextFloat() * 2f * PI.toFloat()
            Shard(
                vx = cos(angle) * (0.25f + rnd.nextFloat() * 0.6f),
                vy = sin(angle) * (0.25f + rnd.nextFloat() * 0.6f) - 0.35f,
                size = 0.012f + rnd.nextFloat() * 0.022f,
                spin = (rnd.nextFloat() - 0.5f) * 900f,
                kind = if (rnd.nextBoolean()) 0 else 1
            )
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun seg(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun easeOut(t: Float) = 1f - (1f - t) * (1f - t)
    private fun easeIn(t: Float) = t * t
    private fun easeInOut(t: Float) = t * t * (3f - 2f * t)
    private fun overshoot(t: Float): Float {
        val s = 1.70158f
        val u = t - 1f
        return u * u * ((s + 1f) * u + s) + 1f
    }
    private fun alphaOf(a: Float) = (a * envelope).coerceIn(0f, 1f)
    private fun shade(color: Int, f: Float) = Color.rgb(
        (Color.red(color) * f).toInt().coerceIn(0, 255),
        (Color.green(color) * f).toInt().coerceIn(0, 255),
        (Color.blue(color) * f).toInt().coerceIn(0, 255)
    )

    private fun fillCircle(c: Canvas, x: Float, y: Float, r: Float, color: Int, alpha: Float = 1f) {
        if (r <= 0f || alpha <= 0f) return
        fill.color = color
        fill.alpha = (255 * alphaOf(alpha)).toInt()
        c.drawCircle(x, y, r, fill)
    }

    private fun ring(
        c: Canvas, x: Float, y: Float, r: Float, color: Int, width: Float, alpha: Float = 1f
    ) {
        if (r <= 0f || width <= 0f || alpha <= 0f) return
        stroke.color = color
        stroke.strokeWidth = width
        stroke.alpha = (255 * alphaOf(alpha)).toInt()
        c.drawCircle(x, y, r, stroke)
    }

    private fun oval(
        c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int, alpha: Float = 1f
    ) {
        if (r <= l || b <= t || alpha <= 0f) return
        fill.color = color
        fill.alpha = (255 * alphaOf(alpha)).toInt()
        rect.set(l, t, r, b)
        c.drawOval(rect, fill)
    }

    private fun ringOval(
        c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, alpha: Float = 1f
    ) {
        if (r <= l || b <= t || alpha <= 0f) return
        stroke.color = color
        stroke.strokeWidth = width
        stroke.alpha = (255 * alphaOf(alpha)).toInt()
        rect.set(l, t, r, b)
        c.drawOval(rect, stroke)
    }

    private fun rrect(
        c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int, alpha: Float = 1f
    ) {
        if (r <= l || b <= t || alpha <= 0f) return
        fill.color = color
        fill.alpha = (255 * alphaOf(alpha)).toInt()
        rect.set(l, t, r, b)
        c.drawRoundRect(rect, radius, radius, fill)
    }

    private fun line(
        c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float, alpha: Float = 1f
    ) {
        if (width <= 0f || alpha <= 0f) return
        stroke.color = color
        stroke.strokeWidth = width
        stroke.alpha = (255 * alphaOf(alpha)).toInt()
        c.drawLine(x1, y1, x2, y2, stroke)
    }

    private fun fillPath(c: Canvas, p: Path, color: Int, alpha: Float = 1f) {
        if (alpha <= 0f) return
        fill.color = color
        fill.alpha = (255 * alphaOf(alpha)).toInt()
        c.drawPath(p, fill)
    }

    private fun strokePath(c: Canvas, p: Path, color: Int, width: Float, alpha: Float = 1f) {
        if (alpha <= 0f || width <= 0f) return
        stroke.color = color
        stroke.strokeWidth = width
        stroke.alpha = (255 * alphaOf(alpha)).toInt()
        c.drawPath(p, stroke)
    }

    private fun arc(
        c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float,
        start: Float, sweep: Float, color: Int, width: Float, alpha: Float = 1f
    ) {
        if (alpha <= 0f) return
        stroke.color = color
        stroke.strokeWidth = width
        stroke.alpha = (255 * alphaOf(alpha)).toInt()
        rect.set(cx - rx, cy - ry, cx + rx, cy + ry)
        c.drawArc(rect, start, sweep, false, stroke)
    }

    private fun text(
        c: Canvas, s: String, x: Float, y: Float, size: Float, alpha: Float = 1f
    ) {
        if (size <= 0f || alpha <= 0f) return
        textPaint.textSize = size
        textPaint.alpha = (255 * alphaOf(alpha)).toInt()
        val dy = -(textPaint.ascent() + textPaint.descent()) / 2f
        c.drawText(s, x, y + dy, textPaint)
    }

    private fun sparkle(c: Canvas, x: Float, y: Float, size: Float, color: Int, rot: Float, alpha: Float = 1f) {
        if (size <= 0f || alpha <= 0f) return
        c.save()
        c.translate(x, y)
        c.rotate(rot)
        path.reset()
        path.moveTo(0f, -size)
        path.quadTo(size * 0.16f, -size * 0.16f, size, 0f)
        path.quadTo(size * 0.16f, size * 0.16f, 0f, size)
        path.quadTo(-size * 0.16f, size * 0.16f, -size, 0f)
        path.quadTo(-size * 0.16f, -size * 0.16f, 0f, -size)
        path.close()
        fillPath(c, path, color, alpha)
        c.restore()
    }

    private fun drawHeartShape(
        c: Canvas, cx: Float, cy: Float, size: Float, color: Int, rot: Float, alpha: Float = 1f
    ) {
        if (size <= 0f || alpha <= 0f) return
        c.save()
        c.translate(cx, cy)
        c.rotate(rot)
        path.reset()
        path.moveTo(0f, size * 0.75f)
        path.cubicTo(-size * 1.35f, -size * 0.15f, -size * 0.55f, -size * 1.0f, 0f, -size * 0.35f)
        path.cubicTo(size * 0.55f, -size * 1.0f, size * 1.35f, -size * 0.15f, 0f, size * 0.75f)
        path.close()
        fillPath(c, path, color, alpha)
        c.restore()
    }

    /** A small person silhouette, used for the UFO's abductee. */
    private fun silhouette(c: Canvas, x: Float, y: Float, h: Float, color: Int, alpha: Float) {
        fillCircle(c, x, y - h * 0.52f, h * 0.16f, color, alpha)
        rrect(c, x - h * 0.13f, y - h * 0.36f, x + h * 0.13f, y + h * 0.08f, h * 0.07f, color, alpha)
        line(c, x - h * 0.08f, y + h * 0.06f, x - h * 0.15f, y + h * 0.3f, color, h * 0.06f, alpha)
        line(c, x + h * 0.08f, y + h * 0.06f, x + h * 0.15f, y + h * 0.3f, color, h * 0.06f, alpha)
    }

    private fun note(c: Canvas, x: Float, y: Float, size: Float, color: Int, alpha: Float) {
        oval(c, x - size * 0.38f, y - size * 0.18f, x + size * 0.06f, y + size * 0.14f, color, alpha)
        line(c, x + size * 0.02f, y - size * 0.05f, x + size * 0.02f, y - size * 1.0f, color, size * 0.11f, alpha)
        line(c, x + size * 0.02f, y - size * 1.0f, x + size * 0.4f, y - size * 0.82f, color, size * 0.1f, alpha)
    }

    // ---------------------------------------------------------------- cartoon people

    private fun head(
        c: Canvas, cx: Float, cy: Float, r: Float,
        skinTone: Int = SKIN,
        hairColor: Int = HAIR_BROWN,
        hairStyle: Hair = Hair.SHORT,
        face: Face = Face.SMILE,
        glasses: Boolean = false,
        blink: Boolean = false,
        lookX: Float = 0f,
        lookY: Float = 0f,
        tears: Float = 0f,
        tongue: Float = 0f,
        hatColor: Int = Color.rgb(228, 74, 74),
        alpha: Float = 1f
    ) {
        if (r <= 0f || alpha <= 0f) return
        val dark = shade(skinTone, 0.82f)

        // ears
        fillCircle(c, cx - r * 0.99f, cy + r * 0.12f, r * 0.19f, dark, alpha)
        fillCircle(c, cx + r * 0.99f, cy + r * 0.12f, r * 0.19f, dark, alpha)

        // hair behind the head
        if (hairStyle == Hair.LONG) {
            rrect(c, cx - r * 1.14f, cy - r * 0.95f, cx + r * 1.14f, cy + r * 1.8f, r * 0.5f, hairColor, alpha)
        } else if (hairStyle == Hair.BOB) {
            rrect(c, cx - r * 1.1f, cy - r * 0.9f, cx + r * 1.1f, cy + r * 0.95f, r * 0.52f, hairColor, alpha)
        }

        fillCircle(c, cx, cy, r, skinTone, alpha)

        // hair / hat on top
        if (hairStyle != Hair.NONE) {
            when (hairStyle) {
                Hair.BEANIE -> {
                    rect.set(cx - r * 1.04f, cy - r * 1.06f, cx + r * 1.04f, cy + r * 1.04f)
                    c.drawArc(rect, 180f, 180f, true, fill.also {
                        it.color = hatColor; it.alpha = (255 * alphaOf(alpha)).toInt()
                    })
                    rrect(c, cx - r * 1.06f, cy - r * 0.22f, cx + r * 1.06f, cy + r * 0.02f, r * 0.12f,
                        shade(hatColor, 0.8f), alpha)
                    fillCircle(c, cx, cy - r * 1.14f, r * 0.17f, shade(hatColor, 0.92f), alpha)
                }
                else -> {
                    rect.set(cx - r * 1.02f, cy - r * 1.04f, cx + r * 1.02f, cy + r * 0.92f)
                    c.drawArc(rect, 180f, 180f, true, fill.also {
                        it.color = hairColor; it.alpha = (255 * alphaOf(alpha)).toInt()
                    })
                    if (hairStyle == Hair.SHORT) {
                        oval(c, cx - r * 0.55f, cy - r * 0.82f, cx + r * 0.62f, cy - r * 0.28f, hairColor, alpha)
                    }
                }
            }
        }

        val eyeY = cy - r * 0.08f
        val eyeDx = r * 0.36f
        val eyeRx = r * 0.17f
        val eyeRy = r * 0.19f
        val ink = Color.rgb(38, 28, 24)

        // eyes
        if (blink) {
            line(c, cx - eyeDx - eyeRx, eyeY, cx - eyeDx + eyeRx, eyeY, ink, r * 0.07f, alpha)
            line(c, cx + eyeDx - eyeRx, eyeY, cx + eyeDx + eyeRx, eyeY, ink, r * 0.07f, alpha)
        } else if (face == Face.LAUGH) {
            arc(c, cx - eyeDx, eyeY + eyeRy * 0.15f, eyeRx, eyeRy, 200f, 140f, ink, r * 0.07f, alpha)
            arc(c, cx + eyeDx, eyeY + eyeRy * 0.15f, eyeRx, eyeRy, 200f, 140f, ink, r * 0.07f, alpha)
        } else if (face == Face.SOB) {
            arc(c, cx - eyeDx, eyeY - eyeRy * 0.1f, eyeRx, eyeRy, 200f, 140f, ink, r * 0.07f, alpha)
            arc(c, cx + eyeDx, eyeY - eyeRy * 0.1f, eyeRx, eyeRy, 200f, 140f, ink, r * 0.07f, alpha)
        } else {
            oval(c, cx - eyeDx - eyeRx, eyeY - eyeRy, cx - eyeDx + eyeRx, eyeY + eyeRy, Color.WHITE, alpha)
            oval(c, cx + eyeDx - eyeRx, eyeY - eyeRy, cx + eyeDx + eyeRx, eyeY + eyeRy, Color.WHITE, alpha)
            val pupilR = r * 0.085f
            val px = lookX * r * 0.06f
            val py = lookY * r * 0.06f
            fillCircle(c, cx - eyeDx + px, eyeY + py, pupilR, ink, alpha)
            fillCircle(c, cx + eyeDx + px, eyeY + py, pupilR, ink, alpha)
            fillCircle(c, cx - eyeDx + px + pupilR * 0.4f, eyeY + py - pupilR * 0.4f, pupilR * 0.3f, Color.WHITE, alpha)
            fillCircle(c, cx + eyeDx + px + pupilR * 0.4f, eyeY + py - pupilR * 0.4f, pupilR * 0.3f, Color.WHITE, alpha)
            if (face == Face.SHOCK) {
                ring(c, cx - eyeDx, eyeY, eyeRx * 1.15f, ink, r * 0.04f, alpha)
                ring(c, cx + eyeDx, eyeY, eyeRx * 1.15f, ink, r * 0.04f, alpha)
            }
        }

        // brows
        if (face == Face.SOB) {
            line(c, cx - eyeDx - eyeRx, eyeY - eyeRy * 1.5f, cx - eyeDx + eyeRx, eyeY - eyeRy * 2.1f, ink, r * 0.06f, alpha)
            line(c, cx + eyeDx + eyeRx, eyeY - eyeRy * 1.5f, cx + eyeDx - eyeRx, eyeY - eyeRy * 2.1f, ink, r * 0.06f, alpha)
        } else if (face == Face.SHOCK || face == Face.WIDE) {
            line(c, cx - eyeDx - eyeRx, eyeY - eyeRy * 2.1f, cx - eyeDx + eyeRx, eyeY - eyeRy * 2.3f, ink, r * 0.055f, alpha)
            line(c, cx + eyeDx - eyeRx, eyeY - eyeRy * 2.3f, cx + eyeDx + eyeRx, eyeY - eyeRy * 2.1f, ink, r * 0.055f, alpha)
        } else {
            line(c, cx - eyeDx - eyeRx * 0.8f, eyeY - eyeRy * 1.7f, cx - eyeDx + eyeRx * 0.8f, eyeY - eyeRy * 1.9f, ink, r * 0.05f, alpha * 0.8f)
            line(c, cx + eyeDx - eyeRx * 0.8f, eyeY - eyeRy * 1.9f, cx + eyeDx + eyeRx * 0.8f, eyeY - eyeRy * 1.7f, ink, r * 0.05f, alpha * 0.8f)
        }

        // glasses
        if (glasses) {
            val frame = Color.rgb(50, 62, 80)
            ring(c, cx - eyeDx, eyeY, eyeRx * 1.35f, frame, r * 0.045f, alpha)
            ring(c, cx + eyeDx, eyeY, eyeRx * 1.35f, frame, r * 0.045f, alpha)
            line(c, cx - eyeDx + eyeRx * 1.35f, eyeY, cx + eyeDx - eyeRx * 1.35f, eyeY, frame, r * 0.04f, alpha)
            line(c, cx - eyeDx - eyeRx * 1.35f, eyeY, cx - r * 1.02f, eyeY - r * 0.08f, frame, r * 0.04f, alpha)
            line(c, cx + eyeDx + eyeRx * 1.35f, eyeY, cx + r * 1.02f, eyeY - r * 0.08f, frame, r * 0.04f, alpha)
        }

        // nose
        line(c, cx, cy + r * 0.12f, cx - r * 0.07f, cy + r * 0.22f, dark, r * 0.045f, alpha * 0.8f)

        val mouthY = cy + r * 0.42f
        when (face) {
            Face.SMILE, Face.TONGUE -> {
                arc(c, cx, mouthY - r * 0.15f, r * 0.34f, r * 0.3f, 25f, 130f, ink, r * 0.075f, alpha)
                if (face == Face.TONGUE || tongue > 0f) {
                    val len = r * (0.22f + tongue * 0.55f)
                    oval(c, cx - r * 0.19f, mouthY - r * 0.06f, cx + r * 0.19f, mouthY - r * 0.06f + len,
                        Color.rgb(232, 96, 124), alpha)
                }
            }
            Face.GRIN, Face.WIDE -> {
                oval(c, cx - r * 0.4f, mouthY - r * 0.26f, cx + r * 0.4f, mouthY + r * 0.3f, Color.rgb(70, 26, 26), alpha)
                oval(c, cx - r * 0.33f, mouthY - r * 0.2f, cx + r * 0.33f, mouthY - r * 0.03f, Color.WHITE, alpha)
                oval(c, cx - r * 0.2f, mouthY + r * 0.1f, cx + r * 0.2f, mouthY + r * 0.26f, Color.rgb(232, 96, 124), alpha)
            }
            Face.LAUGH -> {
                oval(c, cx - r * 0.44f, mouthY - r * 0.3f, cx + r * 0.44f, mouthY + r * 0.34f, Color.rgb(64, 22, 22), alpha)
                oval(c, cx - r * 0.28f, mouthY + r * 0.04f, cx + r * 0.28f, mouthY + r * 0.32f, Color.rgb(232, 96, 124), alpha)
            }
            Face.SOB -> {
                oval(c, cx - r * 0.3f, mouthY - r * 0.16f, cx + r * 0.3f, mouthY + r * 0.32f, Color.rgb(74, 26, 30), alpha)
                oval(c, cx - r * 0.18f, mouthY + r * 0.1f, cx + r * 0.18f, mouthY + r * 0.3f, Color.rgb(210, 90, 110), alpha)
            }
            Face.SHOCK -> {
                oval(c, cx - r * 0.18f, mouthY - r * 0.14f, cx + r * 0.18f, mouthY + r * 0.26f, Color.rgb(70, 26, 26), alpha)
            }
            Face.CALM -> line(c, cx - r * 0.2f, mouthY, cx + r * 0.2f, mouthY, ink, r * 0.06f, alpha)
        }

        if (tears > 0f) {
            val tearColor = Color.rgb(120, 196, 244)
            val drop = r * 0.12f * tears
            fillCircle(c, cx - eyeDx - r * 0.05f, eyeY + r * 0.38f, drop, tearColor, alpha)
            fillCircle(c, cx + eyeDx + r * 0.05f, eyeY + r * 0.42f, drop * 0.9f, tearColor, alpha)
        }
    }

    private fun torso(
        c: Canvas, cx: Float, top: Float, w: Float, h: Float, color: Int, alpha: Float = 1f
    ) {
        if (h <= 0f || w <= 0f || alpha <= 0f) return
        path.reset()
        path.moveTo(cx - w * 0.5f, top + h)
        path.lineTo(cx - w * 0.5f, top + h * 0.22f)
        path.quadTo(cx - w * 0.5f, top, cx - w * 0.16f, top)
        path.lineTo(cx + w * 0.16f, top)
        path.quadTo(cx + w * 0.5f, top, cx + w * 0.5f, top + h * 0.22f)
        path.lineTo(cx + w * 0.5f, top + h)
        path.close()
        fillPath(c, path, color, alpha)
    }

    private fun guitar(c: Canvas, x: Float, y: Float, len: Float, angle: Float, alpha: Float) {
        c.save()
        c.translate(x, y)
        c.rotate(angle)
        oval(c, -len * 0.26f, -len * 0.18f, len * 0.24f, len * 0.18f, Color.rgb(168, 34, 30), alpha)
        ringOval(c, -len * 0.26f, -len * 0.18f, len * 0.24f, len * 0.18f, Color.rgb(94, 16, 14), len * 0.012f, alpha)
        oval(c, -len * 0.2f, -len * 0.09f, len * 0.06f, len * 0.02f, Color.rgb(70, 14, 12), alpha)
        fillCircle(c, -len * 0.03f, -len * 0.02f, len * 0.05f, Color.rgb(30, 22, 18), alpha)
        rrect(c, len * 0.18f, -len * 0.045f, len * 1.0f, len * 0.045f, len * 0.02f, Color.rgb(126, 82, 44), alpha)
        rrect(c, len * 0.97f, -len * 0.075f, len * 1.12f, len * 0.075f, len * 0.02f, Color.rgb(62, 40, 28), alpha)
        line(c, len * 0.16f, -len * 0.03f, len * 1.02f, -len * 0.03f, Color.rgb(214, 214, 224), len * 0.004f, alpha * 0.9f)
        line(c, len * 0.16f, len * 0.0f, len * 1.02f, len * 0.0f, Color.rgb(214, 214, 224), len * 0.004f, alpha * 0.9f)
        line(c, len * 0.16f, len * 0.03f, len * 1.02f, len * 0.03f, Color.rgb(214, 214, 224), len * 0.004f, alpha * 0.9f)
        c.restore()
    }

    private fun amp(c: Canvas, x: Float, y: Float, w: Float, h: Float, dent: Float, alpha: Float) {
        rrect(c, x - w / 2f, y - h, x + w / 2f, y + h * 0.25f, h * 0.08f, Color.rgb(40, 40, 46), alpha)
        rrect(c, x - w * 0.4f, y - h * 0.85f, x + w * 0.4f, y + h * 0.1f, h * 0.06f, Color.rgb(70, 70, 78), alpha)
        for (i in 0 until 3) {
            for (j in 0 until 4) {
                val dx = x - w * 0.3f + i * w * 0.3f
                val dy = y - h * 0.7f + j * h * 0.2f
                fillCircle(c, dx, dy, h * 0.055f, Color.rgb(22, 22, 26), alpha)
            }
        }
        fillCircle(c, x + w * 0.33f, y - h * 0.06f, h * 0.045f, Color.rgb(230, 200, 60), alpha)
        fillCircle(c, x + w * 0.33f, y + h * 0.02f, h * 0.045f, Color.rgb(230, 200, 60), alpha)
        if (dent > 0f) {
            ringOval(c, x - w * 0.4f + dent * w * 0.2f, y - h * 0.85f, x + w * 0.4f - dent * w * 0.2f,
                y + h * 0.1f, Color.rgb(140, 140, 150), h * 0.03f, dent * 0.8f)
        }
    }

    private fun lips(c: Canvas, cx: Float, cy: Float, w: Float, h: Float, alpha: Float) {
        path.reset()
        path.moveTo(cx - w / 2f, cy)
        path.cubicTo(cx - w * 0.34f, cy - h * 0.72f, cx - w * 0.13f, cy - h * 0.62f, cx, cy - h * 0.22f)
        path.cubicTo(cx + w * 0.13f, cy - h * 0.62f, cx + w * 0.34f, cy - h * 0.72f, cx + w / 2f, cy)
        path.cubicTo(cx + w * 0.3f, cy + h * 0.76f, cx - w * 0.3f, cy + h * 0.76f, cx - w / 2f, cy)
        path.close()
        fillPath(c, path, Color.rgb(214, 22, 52), alpha)
        strokePath(c, path, Color.rgb(128, 8, 26), w * 0.014f, alpha * 0.8f)
        oval(c, cx - w * 0.2f, cy + h * 0.12f, cx - w * 0.04f, cy + h * 0.28f, Color.WHITE, alpha * 0.45f)
        arc(c, cx, cy + h * 0.04f, w * 0.2f, h * 0.06f, 20f, 140f, Color.rgb(150, 10, 30), w * 0.01f, alpha * 0.8f)
    }

    private fun fly(c: Canvas, x: Float, y: Float, size: Float, wing: Float, alpha: Float) {
        fillCircle(c, x, y, size * 0.42f, Color.rgb(32, 32, 38), alpha)
        fillCircle(c, x - size * 0.18f, y - size * 0.1f, size * 0.18f, Color.rgb(60, 60, 70), alpha)
        oval(c, x - size * 0.9f, y - size * (0.75f + wing * 0.5f), x - size * 0.1f, y - size * 0.05f,
            Color.argb(210, 220, 235, 255), alpha)
        oval(c, x + size * 0.1f, y - size * (0.75f + (1f - wing) * 0.5f), x + size * 0.9f, y - size * 0.05f,
            Color.argb(210, 220, 235, 255), alpha)
        fillCircle(c, x + size * 0.22f, y - size * 0.06f, size * 0.08f, Color.rgb(200, 40, 40), alpha)
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val w = wink ?: return
        val t = progress
        envelope = min(seg(t, 0f, 0.035f), 1f - seg(t, 0.96f, 1f))

        when (kind) {
            Kind.GUITAR -> drawGuitar(canvas, w, t)
            Kind.KISS -> drawKiss(canvas, w, t)
            Kind.LAUGH -> drawLaugh(canvas, w, t)
            Kind.KNOCK -> drawKnock(canvas, w, t)
            Kind.SLAP -> drawSlap(canvas, w, t)
            Kind.PEEK -> drawPeek(canvas, w, t)
            Kind.ICECREAM -> drawIceCream(canvas, w, t)
            Kind.BALLOON -> drawBalloon(canvas, w, t)
            Kind.PIG -> drawPig(canvas, w, t)
            Kind.UFO -> drawUfo(canvas, w, t)
            Kind.FROG -> drawFrog(canvas, w, t)
            Kind.HEART -> drawHeart(canvas, w, t)
            Kind.IDEA -> drawIdea(canvas, w, t)
            Kind.PULSE -> drawPulse(canvas, w, t)
        }
    }

    // 1 ------------------------------------------------------------ Guitar Smash

    private fun drawGuitar(c: Canvas, w: Wink, t: Float) {
        val u = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height * 0.55f
        val scale = u / 700f

        val appear = overshoot(seg(t, 0f, 0.18f))
        if (appear <= 0.01f) return

        val headR = u * 0.058f
        val headCy = cy - u * 0.17f
        val hipY = cy + u * 0.13f
        val footY = cy + u * 0.3f
        val impact = t >= 0.64f
        val smash = if (impact) 1f else 0f
        val shakeAmp = if (impact) (1f - seg(t, 0.64f, 0.82f)) * u * 0.02f else 0f

        c.save()
        c.translate(cx + sin(t * 90f) * shakeAmp, cy + cos(t * 110f) * shakeAmp * 0.6f)
        c.translate(-cx, -cy)
        c.scale(appear, appear)

        // green stage circle, like the original wink (scales in with the rest)
        fillCircle(c, cx, cy - u * 0.02f, u * 0.40f, Color.rgb(14, 46, 26), 0.9f)
        ring(c, cx, cy - u * 0.02f, u * 0.40f, Color.rgb(96, 214, 110), u * 0.014f)
        ring(c, cx, cy - u * 0.02f, u * 0.365f, Color.rgb(52, 132, 62), u * 0.006f, 0.9f)

        val skinTone = Color.rgb(250, 205, 160)
        val jeans = Color.rgb(40, 56, 96)
        val shirt = Color.rgb(30, 30, 36)
        val hairColor = Color.rgb(35, 25, 22)

        // legs
        line(c, cx - u * 0.03f, hipY, cx - u * 0.06f, footY, jeans, u * 0.022f)
        line(c, cx + u * 0.03f, hipY, cx + u * 0.07f, footY, jeans, u * 0.022f)
        oval(c, cx - u * 0.08f, footY - u * 0.012f, cx - u * 0.01f, footY + u * 0.02f, Color.rgb(24, 24, 30))
        oval(c, cx + u * 0.05f, footY - u * 0.012f, cx + u * 0.12f, footY + u * 0.02f, Color.rgb(24, 24, 30))

        // torso
        torso(c, cx, cy - u * 0.11f, u * 0.13f, u * 0.26f, shirt)
        fillCircle(c, cx, headCy - headR * 0.72f, headR * 0.95f, hairColor)  // hair back

        // head (wild rocker hair)
        head(c, cx, headCy, headR, skinTone, hairColor, Hair.LONG, Face.GRIN)
        line(c, cx - headR, headCy + headR * 0.4f, cx - headR * 1.25f, headCy + headR * 1.4f, hairColor, headR * 0.45f)
        line(c, cx + headR, headCy + headR * 0.4f, cx + headR * 1.25f, headCy + headR * 1.4f, hairColor, headR * 0.45f)

        // arms + guitar
        val guitarLen = u * 0.42f
        val raise = easeInOut(seg(t, 0.32f, 0.5f))
        val slamDown = easeIn(seg(t, 0.54f, 0.62f))
        val guitarAngle = lerp(-16f - 12f * sin(t * 55f).toFloat(), -152f, raise) + slamDown * 190f
        val guitarX = if (impact) cx + u * 0.12f else cx + u * 0.1f
        val guitarY = lerp(cy + u * 0.05f, cy - u * 0.2f, raise) + slamDown * u * 0.24f

        if (!impact) {
            // left hand on the neck, right hand strumming
            line(c, cx - u * 0.07f, cy - u * 0.07f, guitarX + guitarLen * 0.55f, guitarY - u * 0.04f,
                skinTone, u * 0.016f)
            line(c, cx + u * 0.07f, cy - u * 0.07f, guitarX - guitarLen * 0.1f,
                guitarY + u * 0.05f + sin(t * 70f) * u * 0.012f, skinTone, u * 0.016f)
            guitar(c, guitarX, guitarY, guitarLen, guitarAngle, 1f)
            if (!impact) {
                sparkle(c, guitarX - guitarLen * 0.2f, guitarY - guitarLen * 0.25f, u * 0.02f,
                    Color.rgb(255, 240, 150), t * 500f, 0.7f)
            }
        } else {
            // after the smash: empty hands raised in triumph
            line(c, cx - u * 0.07f, cy - u * 0.09f, cx - u * 0.14f, cy - u * 0.24f, skinTone, u * 0.016f)
            line(c, cx + u * 0.07f, cy - u * 0.09f, cx + u * 0.14f, cy - u * 0.24f, skinTone, u * 0.016f)
        }

        amp(c, cx + u * 0.24f, cy + u * 0.28f, u * 0.28f, u * 0.2f, smash, 1f)

        // flying pieces of the guitar
        if (impact) {
            val p = seg(t, 0.64f, 1f)
            for ((i, s) in shards.withIndex()) {
                val fly = p * (0.7f + i * 0.02f)
                val x = cx + u * 0.12f + s.vx * u * fly
                val y = cy + u * 0.16f + s.vy * u * fly + u * 0.9f * fly * fly
                val size = s.size * u
                c.save()
                c.translate(x, y)
                c.rotate(s.spin * p)
                path.reset()
                path.moveTo(-size, -size * 0.4f)
                path.lineTo(size, -size * 0.2f)
                path.lineTo(size * 0.2f, size * 0.6f)
                path.close()
                fillPath(c, path, if (s.kind == 0) Color.rgb(168, 34, 30) else Color.rgb(126, 82, 44),
                    (1f - p) * 1.2f)
                c.restore()
            }
            // impact burst
            val burst = seg(t, 0.64f, 0.8f)
            if (burst < 1f) {
                sparkle(c, cx + u * 0.2f, cy + u * 0.14f, u * 0.13f * (1f - burst), Color.rgb(255, 245, 180), 20f, 1f - burst)
                ring(c, cx + u * 0.2f, cy + u * 0.14f, u * 0.06f + burst * u * 0.3f,
                    Color.rgb(255, 250, 210), u * 0.008f, 1f - burst)
            }
        }

        c.restore()
    }

    // 2 ------------------------------------------------------------------- Kiss

    private fun drawKiss(c: Canvas, w: Wink, t: Float) {
        val u = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height * 0.48f

        // soft pink glow
        val glow = 0.35f * (0.7f + 0.3f * sin(t * PI * 3).toFloat())
        fill.alpha = 255
        fill.shader = RadialGradient(
            cx, cy, u * 0.75f,
            intArrayOf(Color.argb((230 * glow).toInt(), 255, 150, 190), Color.argb(0, 255, 150, 190)),
            null, Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        fill.shader = null

        val enter = overshoot(seg(t, 0f, 0.24f))
        val leave = easeIn(seg(t, 0.82f, 1f))
        val lipW = u * 0.62f * (0.35f + 0.65f * enter)
        val lipH = u * 0.34f * (0.35f + 0.65f * enter)

        // pucker: the lips draw together and push forward
        val pucker = if (t in 0.4f..0.7f) sin((t - 0.4f) / 0.3f * PI).toFloat() else 0f
        val drawW = lipW * (1f - 0.32f * pucker)
        val drawH = lipH * (1f + 0.18f * pucker)

        val bob = sin(t * PI * 5).toFloat() * u * 0.012f
        val inX = lerp(width + lipW, cx, easeOut(seg(t, 0f, 0.28f)))
        val inY = lerp(cy + u * 0.3f, cy, easeOut(seg(t, 0f, 0.28f))) + bob
        val rot = lerp(-28f, 0f, easeOut(seg(t, 0f, 0.3f))) + sin(t * PI * 2).toFloat() * 3f
        val lipX = inX + leave * u * 0.9f
        val lipY = inY - leave * u * 1.0f
        val lipAlpha = 1f - leave

        lips(c, lipX, lipY, drawW, drawH, lipAlpha)

        // the blown kiss: a small pair of lips that flies at the viewer
        val fly = seg(t, 0.58f, 0.82f)
        if (fly > 0f && fly < 1f) {
            val k = easeIn(fly)
            lips(c, lerp(cx, cx + u * 0.1f, k), lerp(cy, cy + u * 0.32f, k),
                u * 0.16f * (0.35f + k * 2.4f), u * 0.09f * (0.35f + k * 2.4f), (1f - k) * 0.95f)
        }

        // hearts blown toward the viewer, floating clear of the lips
        for (i in 0 until 8) {
            val local = ((t - 0.55f - i * 0.05f) / 0.9f).coerceIn(0f, 1f)
            if (local <= 0f || local >= 1f) continue
            val angle = (-PI / 2f).toFloat() + (i - 3.5f) * 0.3f
            val spread = u * (0.32f + local * 0.22f)
            val x = cx + cos(angle) * spread
            val y = cy + sin(angle) * spread - local * u * 0.04f
            drawHeartShape(c, x, y, u * (0.032f + 0.01f * (i % 3)), Color.rgb(255, 64, 116),
                sin(t * PI.toFloat() * 4f + i).toFloat() * 14f, (1f - local) * 0.95f)
        }
    }

    // 3 ---------------------------------------------------------- Laughing Woman

    private fun drawLaugh(c: Canvas, w: Wink, t: Float) {
        val u = min(width, height).toFloat()
        val cx = width / 2f
        val groundY = height * 0.84f

        val appear = overshoot(seg(t, 0f, 0.16f))
        if (appear <= 0.01f) return
        val fall = easeInOut(seg(t, 0.18f, 0.44f))
        val roll = if (t > 0.44f) sin(t * PI * 9).toFloat() else 0f
        val tilt = fall * 84f + roll * 7f * seg(t, 0.44f, 0.54f)
        val bob = if (t > 0.44f) abs(sin(t * PI * 12)).toFloat() else 0f
        val fade = 1f - seg(t, 0.9f, 1f)

        val headR = u * 0.075f
        val bodyH = u * 0.21f
        val skinTone = Color.rgb(248, 202, 158)
        val hairColor = Color.rgb(96, 52, 30)
        val top = Color.rgb(196, 44, 124)
        val topDark = Color.rgb(150, 28, 96)

        c.save()
        c.translate(cx - u * 0.2f * fall, groundY)
        c.rotate(tilt)
        c.scale(appear, appear)
        c.translate(0f, -bob * u * 0.012f)

        // legs (kicking once she is on her back)
        val kick = if (t > 0.44f) sin(t * PI * 14).toFloat() else 0f
        val legColor = Color.rgb(52, 62, 100)
        line(c, -u * 0.045f, -u * 0.07f, -u * 0.075f - kick * u * 0.035f, -u * 0.005f, legColor, u * 0.026f)
        line(c, u * 0.045f, -u * 0.07f, u * 0.075f + kick * u * 0.035f, -u * 0.005f, legColor, u * 0.026f)
        oval(c, -u * 0.115f - kick * u * 0.035f, -u * 0.03f, -u * 0.055f - kick * u * 0.035f,
            u * 0.012f, Color.rgb(40, 40, 52))
        oval(c, u * 0.055f + kick * u * 0.035f, -u * 0.03f, u * 0.115f + kick * u * 0.035f,
            u * 0.012f, Color.rgb(40, 40, 52))

        // colourful top
        torso(c, 0f, -u * 0.03f - bodyH, u * 0.16f, bodyH * 0.8f, top, fade)
        for (i in 0 until 3) {
            val y = -u * 0.05f - bodyH + i * bodyH * 0.3f
            line(c, -u * 0.075f, y, u * 0.075f, y, topDark, u * 0.012f, fade * 0.9f)
        }

        // arms clutching the belly, shaking with laughter
        val clutch = sin(t * PI * 20).toFloat() * u * 0.008f
        line(c, -u * 0.07f, -bodyH * 0.92f, -u * 0.1f, -bodyH * 0.55f, skinTone, u * 0.02f, fade)
        line(c, -u * 0.1f, -bodyH * 0.55f, u * 0.015f + clutch, -bodyH * 0.42f, skinTone, u * 0.02f, fade)
        line(c, u * 0.07f, -bodyH * 0.92f, u * 0.1f, -bodyH * 0.6f, skinTone, u * 0.02f, fade)
        line(c, u * 0.1f, -bodyH * 0.6f, -u * 0.005f + clutch, -bodyH * 0.45f, skinTone, u * 0.02f, fade)

        // head thrown back, laughing
        val laughTilt = (sin(t * PI * 16).toFloat() * 6f) + fall * 8f
        c.save()
        c.translate(0f, -u * 0.03f - bodyH - headR * 0.85f)
        c.rotate(laughTilt)
        head(c, 0f, 0f, headR, skinTone, hairColor, Hair.BOB, Face.LAUGH, tears = 1f, alpha = fade)
        c.restore()



        c.restore()

        // tears of laughter flying off
        for ((i, s) in sparks.withIndex()) {
            if (i % 3 != 0) continue
            val local = ((t - 0.42f - s.delay) / 0.5f).coerceIn(0f, 1f)
            if (local <= 0f || local >= 1f) continue
            val x = cx + s.vx * u * 0.35f * local
            val y = groundY - u * 0.36f + s.vy * u * 0.35f * local + u * 0.25f * local * local
            oval(c, x - u * 0.008f, y - u * 0.013f, x + u * 0.008f, y + u * 0.013f,
                Color.rgb(140, 208, 248), (1f - local) * 0.9f)
        }
    }

    // 4 --------------------------------------------------------------- Knocking

    private fun drawKnock(c: Canvas, w: Wink, t: Float) {
        val u = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height * 0.46f
        val bangs = floatArrayOf(0.05f, 0.29f, 0.53f, 0.77f)
        val bangLen = 0.17f

        var shake = 0f
        var lastBang = -1
        for ((i, b) in bangs.withIndex()) {
            if (t >= b) lastBang = i
            if (t in b..(b + bangLen)) {
                shake = (1f - seg(t, b, b + bangLen)).pow(2) * u * 0.045f
            }
        }

        // the inside of the monitor is a little darker than the page
        fill.alpha = 255
        fill.shader = RadialGradient(
            cx, cy, u * 0.95f,
            intArrayOf(Color.argb(0, 6, 10, 18), Color.argb(120, 6, 10, 18)),
            null, Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        fill.shader = null

        c.save()
        c.translate(sin(t * 150f) * shake, cos(t * 120f) * shake)

        // cracks in the glass, one set per bang
        if (lastBang >= 0) {
            for (i in 0..lastBang) {
                val bx = cx + ((i % 2) * 2 - 1) * u * 0.06f
                val by = cy + (i % 3 - 1) * u * 0.05f
                val seen = if (i < lastBang) 1f else seg(t, bangs[i], bangs[i] + 0.08f)
                for (k in 0 until 6) {
                    val a = (k * 60f + i * 17f)
                    val len = u * (0.1f + 0.06f * ((k * 7 + i * 3) % 5))
                    val rad = Math.toRadians(a.toDouble())
                    val ex = bx + (cos(rad) * len).toFloat()
                    val ey = by + (sin(rad) * len).toFloat()
                    path.reset()
                    path.moveTo(bx, by)
                    path.lineTo((bx + ex) / 2f + u * 0.012f, (by + ey) / 2f - u * 0.008f)
                    path.lineTo(ex, ey)
                    strokePath(c, path, Color.argb(190, 220, 232, 245), u * 0.004f, seen)
                }
            }
        }

        // the fist
        var fistScale = 0.9f
        var fistAlpha = 0f
        for (b in bangs) {
            if (t < b - 0.08f) continue
            if (t <= b) {
                val k = seg(t, b - 0.08f, b)
                fistScale = lerp(0.9f, 2.5f, easeIn(k))
                fistAlpha = k
            } else if (t < b + 0.12f) {
                val k = seg(t, b, b + 0.12f)
                fistScale = lerp(2.5f, 1.15f, easeOut(k))
                fistAlpha = 1f - k * 0.5f
            } else {
                fistScale = 1.15f
                fistAlpha = 0.5f * (1f - seg(t, b + 0.12f, b + 0.3f))
            }
        }
        if (fistAlpha > 0f) drawFist(c, cx, cy, u * 0.26f * fistScale, -14f, fistAlpha)

        // impact rings and sparks
        for (b in bangs) {
            val k = seg(t, b, b + 0.28f)
            if (k <= 0f || k >= 1f) continue
            ring(c, cx, cy, u * 0.05f + k * u * 0.42f, Color.rgb(255, 236, 170), u * 0.008f, (1f - k) * 0.9f)
            for (i in 0 until 6) {
                val a = i * 60f + b * 200f
                val rad = Math.toRadians(a.toDouble())
                val d = u * (0.08f + k * 0.3f)
                sparkle(c, cx + (cos(rad) * d).toFloat(), cy + (sin(rad) * d).toFloat(),
                    u * 0.02f * (1f - k), Color.rgb(255, 250, 200), a, 1f - k)
            }
        }

        c.restore()
    }

    private fun drawFist(c: Canvas, cx: Float, cy: Float, size: Float, angle: Float, alpha: Float) {
        c.save()
        c.translate(cx, cy)
        c.rotate(angle)
        c.scale(size / 100f, size / 100f)
        val skinTone = Color.rgb(246, 196, 150)
        val edge = Color.rgb(196, 138, 96)

        // wrist
        rrect(c, -5f, -23f, 92f, 34f, 18f, skinTone, alpha)
        // palm
        rrect(c, -42f, -36f, 42f, 44f, 21f, skinTone, alpha)
        // knuckles
        fillCircle(c, -34f, -26f, 14f, skinTone, alpha)
        fillCircle(c, -36f, -5f, 15f, skinTone, alpha)
        fillCircle(c, -36f, 16f, 14.5f, skinTone, alpha)
        fillCircle(c, -31f, 35f, 13f, skinTone, alpha)
        // thumb folded over the fingers
        rrect(c, -30f, -50f, 24f, -22f, 13f, skinTone, alpha)
        // creases
        line(c, 6f, -26f, 6f, -8f, edge, 4f, alpha * 0.6f)
        line(c, 4f, 2f, 4f, 20f, edge, 4f, alpha * 0.5f)
        line(c, -20f, 38f, 22f, 38f, edge, 3.5f, alpha * 0.55f)
        c.restore()
    }

    // 5 ------------------------------------------------------- Smiley Slap Dance

    private fun drawSlap(c: Canvas, w: Wink, t: Float) {
        val u = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height * 0.5f
        val r = u * 0.19f

        val appear = overshoot(seg(t, 0f, 0.14f))
        if (appear <= 0.01f) return
        val pop = 1f - easeIn(seg(t, 0.86f, 0.98f))

        val beatCount = 10f
        val beatPos = t * beatCount
        val beat = (beatPos % 1f)
        val beatIndex = beatPos.toInt()
        val bounce = abs(sin(beat * PI)).toFloat()
        val squash = 1f - 0.12f * exp(-beat * 6f)
        val dance = sin(t * PI * 4).toFloat() * u * 0.03f
        val isClap = beatIndex % 2 == 0

        c.save()
        c.translate(cx + dance, cy - bounce * u * 0.03f)
        c.scale(appear * pop, appear * pop)

        // the classic yellow smiley
        fill.alpha = 255
        fill.alpha = (255 * envelope).toInt()
        fill.shader = RadialGradient(
            -r * 0.3f, -r * 0.4f, r * 1.5f,
            intArrayOf(Color.rgb(255, 246, 130), Color.rgb(252, 205, 22)),
            null, Shader.TileMode.CLAMP
        )
        c.save()
        c.scale(1f + (1f - squash) * 0.6f, squash)
        c.drawCircle(0f, 0f, r, fill)
        fill.shader = null
        c.restore()
        ring(c, 0f, 0f, r, Color.rgb(206, 156, 18), r * 0.035f)

        // eyes (squeeze shut on each slap)
        val eyeY = -r * 0.16f
        val eyeDx = r * 0.33f
        if (!isClap && beat > 0.55f) {
            arc(c, -eyeDx, eyeY + r * 0.03f, r * 0.13f, r * 0.12f, 200f, 140f, Color.rgb(70, 50, 20), r * 0.06f)
            arc(c, eyeDx, eyeY + r * 0.03f, r * 0.13f, r * 0.12f, 200f, 140f, Color.rgb(70, 50, 20), r * 0.06f)
        } else {
            oval(c, -eyeDx - r * 0.09f, eyeY - r * 0.16f, -eyeDx + r * 0.09f, eyeY + r * 0.16f, Color.rgb(58, 42, 18))
            oval(c, eyeDx - r * 0.09f, eyeY - r * 0.16f, eyeDx + r * 0.09f, eyeY + r * 0.16f, Color.rgb(58, 42, 18))
            fillCircle(c, -eyeDx + r * 0.03f, eyeY - r * 0.05f, r * 0.03f, Color.WHITE)
            fillCircle(c, eyeDx + r * 0.03f, eyeY - r * 0.05f, r * 0.03f, Color.WHITE)
        }

        // big grin
        val mouthOpen = r * (0.1f + 0.08f * bounce)
        arc(c, 0f, r * 0.12f, r * 0.42f, mouthOpen, 20f, 140f, Color.rgb(72, 48, 18), r * 0.06f)

        // hands and arms, drawn over the face so they stay visible
        val handR = r * 0.19f
        val close = sin(beat * PI).toFloat()
        var lhx = -r * 1.2f
        var lhy = r * (0.5f + 0.22f * bounce)
        var rhx = r * 1.2f
        var rhy = r * (0.5f + 0.22f * bounce)
        if (isClap) {
            lhx = -lerp(r * 1.0f, r * 0.18f, close)
            rhx = lerp(r * 1.0f, r * 0.18f, close)
            lhy = r * 0.52f
            rhy = r * 0.52f
        }
        line(c, -r * 0.8f, r * 0.12f, lhx, lhy, Color.rgb(240, 196, 40), r * 0.13f)
        line(c, r * 0.8f, r * 0.12f, rhx, rhy, Color.rgb(240, 196, 40), r * 0.13f)
        fillCircle(c, lhx, lhy, handR, Color.rgb(255, 217, 60))
        fillCircle(c, rhx, rhy, handR, Color.rgb(255, 217, 60))
        ring(c, lhx, lhy, handR, Color.rgb(198, 150, 20), r * 0.02f)
        ring(c, rhx, rhy, handR, Color.rgb(198, 150, 20), r * 0.02f)

        // little feet, spread apart under the face
        oval(c, -r * 0.52f, r * 0.94f, -r * 0.1f, r * 1.14f, Color.rgb(238, 192, 38))
        oval(c, r * 0.1f, r * 0.94f, r * 0.52f, r * 1.14f, Color.rgb(238, 192, 38))
        ringOval(c, -r * 0.52f, r * 0.94f, -r * 0.1f, r * 1.14f, Color.rgb(198, 150, 20), r * 0.018f)
        ringOval(c, r * 0.1f, r * 0.94f, r * 0.52f, r * 1.14f, Color.rgb(198, 150, 20), r * 0.018f)

        // contact sparkles
        if (beat > 0.35f && beat < 0.85f) {
            val k = seg(beat, 0.35f, 0.85f)
            val sx = if (isClap) 0f else r * 1.2f
            val sy = if (isClap) r * 0.55f else r * 0.62f
            sparkle(c, sx, sy, r * 0.16f * (1f - k), Color.WHITE, 15f, (1f - k) * 0.9f)
            if (!isClap) sparkle(c, -sx, sy, r * 0.16f * (1f - k), Color.WHITE, -15f, (1f - k) * 0.9f)
        }

        c.restore()

        // final pop
        val fin = seg(t, 0.88f, 1f)
        if (fin > 0f && fin < 1f) {
            for (i in 0 until 8) {
                val rad = Math.toRadians((i * 45).toDouble())
                val d = u * (0.12f + fin * 0.28f)
                sparkle(c, cx + (cos(rad) * d).toFloat(), cy + (sin(rad) * d).toFloat(),
                    u * 0.03f * (1f - fin), Color.rgb(255, 240, 150), i * 40f, 1f - fin)
            }
        }
    }

    // 6 ------------------------------------------------------------ Boy Peeking

    private fun drawPeek(c: Canvas, w: Wink, t: Float) {
        val u = min(width, height).toFloat()
        val cx = width / 2f
        val bottom = height.toFloat()
        val headR = u * 0.105f
        val edgeY = height * 0.9f

        val rise = overshoot(seg(t, 0f, 0.3f))
        val duck = easeIn(seg(t, 0.84f, 1f))
        val restY = edgeY - headR * 0.8f
        val hiddenY = edgeY + headR * 1.2f
        val y = lerp(hiddenY, restY, rise) + duck * (hiddenY - restY)

        val scan = sin(t * PI * 3.2f).toFloat()
        val blink = t in 0.44f..0.48f
        val tongue = if (t in 0.56f..0.8f) {
            val k = seg(t, 0.56f, 0.66f)
            k * (1f - seg(t, 0.76f, 0.82f)) * (1f + 0.12f * sin(t * PI * 26).toFloat())
        } else 0f

        // shoulders behind the head
        torso(c, cx, y + headR * 0.7f, headR * 1.7f, headR * 1.4f, Color.rgb(60, 120, 190), 1f)

        head(c, cx, y, headR, Color.rgb(250, 205, 160), Color.rgb(96, 64, 36), Hair.SHORT,
            if (tongue > 0f) Face.TONGUE else Face.SMILE,
            blink = blink, lookX = scan, lookY = -0.2f, tongue = tongue)

        // the window edge he is holding on to, in front of his body
        rrect(c, 0f, edgeY, width.toFloat(), bottom, u * 0.02f, Color.rgb(196, 208, 224))
        line(c, 0f, edgeY, width.toFloat(), edgeY, Color.WHITE, u * 0.006f, 0.9f)
        line(c, 0f, edgeY + u * 0.006f, width.toFloat(), edgeY + u * 0.006f,
            Color.rgb(150, 164, 182), u * 0.003f, 0.8f)

        // hands gripping the edge
        val grip = if (t in 0.1f..0.85f) 1f else 0f
        if (grip > 0f) {
            for (side in intArrayOf(-1, 1)) {
                val hx = cx + side * headR * 1.05f
                val hy = edgeY
                rrect(c, hx - u * 0.024f, hy - u * 0.04f, hx + u * 0.024f, hy + u * 0.01f,
                    u * 0.012f, Color.rgb(250, 205, 160))
                for (i in 0 until 3) {
                    val fx = hx - u * 0.017f + i * u * 0.017f
                    rrect(c, fx - u * 0.006f, hy - u * 0.05f, fx + u * 0.006f, hy - u * 0.012f,
                        u * 0.005f, Color.rgb(242, 190, 145))
                }
            }
        }

        // dust puff when he ducks away
        if (duck > 0.2f) {
            for (i in 0 until 4) {
                val side = if (i % 2 == 0) -1 else 1
                fillCircle(c, cx + side * headR * (0.8f + i * 0.2f), bottom - u * 0.02f,
                    u * 0.02f * (0.6f + duck), Color.rgb(210, 220, 232), (1f - duck) * 0.55f)
            }
        }
    }

    // 7 ------------------------------------------------------- Dropped Ice Cream

    private fun drawIceCream(c: Canvas, w: Wink, t: Float) {
        val u = min(width, height).toFloat()
        val cx = width / 2f
        val floorY = height * 0.82f
        val headR = u * 0.072f

        // floor
        line(c, width * 0.12f, floorY, width * 0.88f, floorY, Color.argb(160, 60, 80, 110), u * 0.006f, 0.8f)

        val appear = overshoot(seg(t, 0f, 0.14f))
        val sob = if (t > 0.56f) sin(t * PI * 18).toFloat() else 0f
        val bodyY = height * 0.53f

        c.save()
        c.translate(cx, bodyY + sob * u * 0.006f)
        c.scale(appear, appear)

        val skinTone = Color.rgb(250, 206, 162)
        val shirt = Color.rgb(66, 148, 96)
        val shorts = Color.rgb(52, 66, 104)

        // legs
        line(c, -u * 0.02f, u * 0.12f, -u * 0.04f, u * 0.24f, shorts, u * 0.024f)
        line(c, u * 0.02f, u * 0.12f, u * 0.045f, u * 0.24f, shorts, u * 0.024f)
        line(c, -u * 0.04f, u * 0.24f, -u * 0.05f, u * 0.28f, skinTone, u * 0.018f)
        line(c, u * 0.045f, u * 0.24f, u * 0.055f, u * 0.28f, skinTone, u * 0.018f)

        // torso
        torso(c, 0f, -u * 0.1f, u * 0.13f, u * 0.22f, shirt)

        // arms: holds the cone, then covers his face while crying
        val crying = t > 0.56f
        val coneHandX = u * 0.15f
        val coneHandY = -u * 0.02f
        if (crying) {
            line(c, -u * 0.06f, -u * 0.08f, -u * 0.04f, -u * 0.14f + sob * u * 0.006f, skinTone, u * 0.017f)
            line(c, u * 0.06f, -u * 0.08f, u * 0.04f, -u * 0.14f + sob * u * 0.006f, skinTone, u * 0.017f)
        } else {
            line(c, -u * 0.06f, -u * 0.07f, coneHandX - u * 0.02f, coneHandY + u * 0.03f, skinTone, u * 0.017f)
            line(c, u * 0.06f, -u * 0.07f, coneHandX, coneHandY, skinTone, u * 0.017f)
        }

        // head (glasses), happy then sobbing
        val face = when {
            t < 0.5f -> Face.GRIN
            t < 0.62f -> Face.SHOCK
            else -> Face.SOB
        }
        head(c, 0f, -u * 0.17f, headR, skinTone, Color.rgb(64, 44, 30), Hair.SHORT, face,
            glasses = true, tears = if (crying) 1f else 0f)

        // the ice cream cone
        val coneTopY = coneHandY - u * 0.055f
        val wobble = sin(t * PI * 9).toFloat() * 4f
        val dropStart = 0.36f
        val dropHit = 0.55f
        val falling = t in dropStart..dropHit

        if (!crying || t < 0.62f) {
            c.save()
            c.translate(coneHandX, coneTopY)
            c.rotate(if (falling) wobble * 2f else wobble)
            // cone
            path.reset()
            path.moveTo(-u * 0.045f, 0f)
            path.lineTo(u * 0.045f, 0f)
            path.lineTo(0f, u * 0.125f)
            path.close()
            fillPath(c, path, Color.rgb(210, 158, 86))
            line(c, -u * 0.045f, 0f, u * 0.045f, 0f, Color.rgb(176, 126, 62), u * 0.005f)
            line(c, -u * 0.03f, u * 0.025f, u * 0.02f, u * 0.07f, Color.rgb(166, 116, 54), u * 0.005f)
            line(c, u * 0.03f, u * 0.025f, -u * 0.02f, u * 0.07f, Color.rgb(166, 116, 54), u * 0.005f)
            c.restore()
        }

        // scoop: sits on the cone, slides, then falls and splats
        val scoopSize = u * 0.055f
        val floorLocalY = floorY - bodyY
        val splatX = u * 0.14f
        if (t < dropHit) {
            val slide = seg(t, dropStart, dropHit)
            val sx = coneHandX + u * 0.012f * slide
            val sy = lerp(coneTopY - scoopSize * 0.8f, floorLocalY - scoopSize, easeIn(slide))
            fillCircle(c, sx, sy, scoopSize, Color.rgb(255, 196, 214))
            fillCircle(c, sx - scoopSize * 0.25f, sy - scoopSize * 0.35f, scoopSize * 0.55f, Color.rgb(255, 226, 236))
            fillCircle(c, sx + scoopSize * 0.3f, sy - scoopSize * 0.2f, scoopSize * 0.14f, Color.rgb(214, 96, 140))
            fillCircle(c, sx - scoopSize * 0.15f, sy + scoopSize * 0.2f, scoopSize * 0.12f, Color.rgb(120, 196, 140))
        } else {
            // splat on the floor (local to the boy's group)
            val spread = easeOut(seg(t, dropHit, dropHit + 0.14f))
            oval(c, splatX - scoopSize * (1.2f + spread), floorLocalY - scoopSize * 0.3f,
                splatX + scoopSize * (1.2f + spread), floorLocalY + scoopSize * 0.3f,
                Color.rgb(255, 196, 214), 1f)
            oval(c, splatX - scoopSize * 0.8f, floorLocalY - scoopSize * 0.42f,
                splatX + scoopSize * 0.8f, floorLocalY + scoopSize * 0.1f,
                Color.rgb(255, 226, 236), 0.8f)
            fillCircle(c, splatX - scoopSize * 1.5f, floorLocalY, scoopSize * 0.28f, Color.rgb(255, 196, 214))
            fillCircle(c, splatX + scoopSize * 1.45f, floorLocalY - scoopSize * 0.1f, scoopSize * 0.24f, Color.rgb(255, 196, 214))
            // sprinkles in the puddle
            fillCircle(c, splatX - scoopSize * 0.45f, floorLocalY - scoopSize * 0.1f, scoopSize * 0.1f, Color.rgb(120, 196, 140))
            fillCircle(c, splatX + scoopSize * 0.35f, floorLocalY + scoopSize * 0.05f, scoopSize * 0.09f, Color.rgb(214, 96, 140))
            // little splash drops
            for (i in 0 until 5) {
                val side = if (i % 2 == 0) -1 else 1
                fillCircle(c, splatX + side * scoopSize * (1.2f + i * 0.25f),
                    floorLocalY - scoopSize * (0.45f - i * 0.05f), scoopSize * 0.11f, Color.rgb(255, 210, 224), 1f)
            }
        }

        c.restore()

        // tears streaming
        if (t > 0.62f) {
            val k = seg(t, 0.62f, 1f)
            for (i in 0 until 6) {
                val side = if (i % 2 == 0) -1 else 1
                val phase = ((k * 2.2f + i * 0.18f) % 1f)
                val x = cx + side * headR * 0.5f
                val y = bodyY - u * 0.18f + phase * u * 0.12f
                oval(c, x - u * 0.011f, y - u * 0.02f, x + u * 0.011f, y + u * 0.02f,
                    Color.rgb(132, 202, 244), (1f - phase) * 0.95f)
            }
        }
    }

    // 8 ---------------------------------------------------------- Water Balloon

    private fun drawBalloon(c: Canvas, w: Wink, t: Float) {
        val u = min(width, height).toFloat()
        val cx = width / 2f
        val headR = u * 0.078f
        val bodyY = height * 0.56f
        val impactT = 0.55f

        val appear = overshoot(seg(t, 0f, 0.14f))
        val soaked = t >= impactT
        val stagger = if (soaked) -u * 0.02f * (1f - seg(t, impactT, impactT + 0.18f)) else 0f
        val wobble = if (soaked) sin(t * PI * 16).toFloat() * 4f * (1f - seg(t, impactT, 0.95f)) else 0f

        c.save()
        c.translate(cx + stagger, bodyY)
        c.scale(appear, appear)
        c.rotate(wobble)

        val skinTone = Color.rgb(248, 204, 160)
        val shirt = Color.rgb(226, 148, 52)
        val hatColor = Color.rgb(58, 132, 196)

        // legs + torso
        line(c, -u * 0.02f, u * 0.12f, -u * 0.045f, u * 0.24f, Color.rgb(56, 70, 110), u * 0.024f)
        line(c, u * 0.02f, u * 0.12f, u * 0.05f, u * 0.24f, Color.rgb(56, 70, 110), u * 0.024f)
        torso(c, 0f, -u * 0.1f, u * 0.13f, u * 0.22f, shirt)

        // arms: relaxed, then flung up from the shock
        if (soaked) {
            line(c, -u * 0.06f, -u * 0.07f, -u * 0.13f, -u * 0.14f, skinTone, u * 0.017f)
            line(c, u * 0.06f, -u * 0.07f, u * 0.13f, -u * 0.14f, skinTone, u * 0.017f)
        } else {
            line(c, -u * 0.06f, -u * 0.07f, -u * 0.1f, u * 0.02f, skinTone, u * 0.017f)
            line(c, u * 0.06f, -u * 0.07f, u * 0.1f, u * 0.02f, skinTone, u * 0.017f)
        }

        head(c, 0f, -u * 0.17f, headR, skinTone, Color.rgb(88, 58, 34), Hair.BEANIE,
            if (soaked) Face.SHOCK else Face.GRIN,
            lookX = if (soaked) 0f else 0.4f, lookY = if (soaked) 0f else -1f,
            hatColor = hatColor)

        // water dripping off his face
        if (soaked) {
            val k = seg(t, impactT, 1f)
            for (i in 0 until 5) {
                val phase = ((k * 2f + i * 0.2f) % 1f)
                val x = -headR * 0.7f + i * headR * 0.35f
                val y = -u * 0.19f + phase * u * 0.12f
                oval(c, x - u * 0.007f, y - u * 0.012f, x + u * 0.007f, y + u * 0.012f,
                    Color.rgb(96, 176, 240), (1f - phase) * 0.9f)
            }
        }
        c.restore()

        // the balloon
        val faceX = cx
        val faceY = bodyY - u * 0.17f
        if (t < impactT) {
            val p = seg(t, 0f, impactT)
            val bx = lerp(-u * 0.25f, faceX, easeIn(p))
            val by = lerp(-u * 0.2f, faceY, p * p) - sin(p * PI.toFloat()) * u * 0.1f
            val ballR = u * 0.085f
            // motion trail
            for (i in 1..3) {
                fillCircle(c, bx - (1f - p) * i * u * 0.06f, by - (1f - p) * i * u * 0.03f,
                    ballR * (0.5f - i * 0.1f), Color.argb(90, 110, 190, 250), 0.7f)
            }
            fill.shader = RadialGradient(
                bx - ballR * 0.3f, by - ballR * 0.35f, ballR * 1.6f,
                intArrayOf(Color.rgb(150, 214, 255), Color.rgb(46, 132, 224)),
                null, Shader.TileMode.CLAMP
            )
            c.drawCircle(bx, by, ballR, fill)
            fill.shader = null
            fillCircle(c, bx - ballR * 0.35f, by - ballR * 0.4f, ballR * 0.18f, Color.WHITE, 0.8f)
            rrect(c, bx - ballR * 0.1f, by - ballR * 1.14f, bx + ballR * 0.1f, by - ballR * 0.95f,
                ballR * 0.06f, Color.rgb(70, 150, 230))
        } else {
            // splash
            val k = seg(t, impactT, impactT + 0.45f)
            if (k < 1f) {
                ring(c, faceX, faceY, u * 0.04f + k * u * 0.34f, Color.rgb(190, 230, 255), u * 0.01f, (1f - k) * 0.9f)
                ring(c, faceX, faceY, u * 0.02f + k * u * 0.22f, Color.WHITE, u * 0.006f, (1f - k) * 0.7f)
                for ((i, s) in sparks.withIndex()) {
                    val local = ((k - s.delay * 0.4f) / 0.6f).coerceIn(0f, 1f)
                    if (local <= 0f) continue
                    val x = faceX + s.vx * u * 0.55f * local
                    val y = faceY + s.vy * u * 0.55f * local + u * 0.5f * local * local
                    fillCircle(c, x, y, u * s.size * 0.9f, Color.rgb(120, 196, 250), (1f - local) * 0.9f)
                }
            }
            // blue flash on impact
            val flash = (1f - seg(t, impactT, impactT + 0.15f)) * 0.25f
            fill.color = Color.argb((255 * flash).toInt(), 150, 210, 255)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        }
    }

    // 9 ----------------------------------------------------------- Dancing Pig

    private fun drawPig(c: Canvas, w: Wink, t: Float) {
        val u = min(width, height).toFloat()
        val cx = width / 2f
        val groundY = height * 0.78f

        val appear = overshoot(seg(t, 0f, 0.14f))
        if (appear <= 0.01f) return

        val hopPhase = (t * 6f) % 1f
        val hop = abs(sin(hopPhase * PI)).toFloat()
        val x = cx + sin(t * PI * 2).toFloat() * u * 0.1f
        val y = groundY - hop * u * 0.05f
        val spin = seg(t, 0.86f, 1f)
        val pop = 1f - easeIn(seg(t, 0.95f, 1f))

        // spotlight
        fill.alpha = 255
        fill.shader = RadialGradient(
            cx, groundY - u * 0.15f, u * 0.45f,
            intArrayOf(Color.argb(70, 255, 244, 190), Color.argb(0, 255, 244, 190)),
            null, Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        fill.shader = null

        val pink = Color.rgb(246, 158, 182)
        val pinkDark = Color.rgb(220, 118, 148)
        val pinkLight = Color.rgb(255, 202, 214)

        c.save()
        c.translate(x, y)
        c.rotate(spin * 360f)
        c.scale(appear * pop * (1f + (1f - hop) * 0.05f), appear * pop * (1f - (1f - hop) * 0.06f))

        val bodyR = u * 0.115f

        // body
        fill.alpha = 255
        fill.shader = RadialGradient(
            -bodyR * 0.3f, -bodyR * 0.2f, bodyR * 2.2f,
            intArrayOf(pinkLight, pink),
            null, Shader.TileMode.CLAMP
        )
        c.drawCircle(0f, bodyR * 0.45f, bodyR, fill)
        fill.shader = null
        ring(c, 0f, bodyR * 0.45f, bodyR, pinkDark, u * 0.006f)

        // curly tail
        path.reset()
        path.moveTo(-bodyR * 0.9f, bodyR * 0.55f)
        path.cubicTo(-bodyR * 1.7f, bodyR * 0.3f, -bodyR * 1.15f, -bodyR * 0.25f, -bodyR * 1.5f, -bodyR * 0.15f)
        strokePath(c, path, pinkDark, u * 0.013f)

        // dancing legs, below the body
        val kickL = sin(t * PI * 12).toFloat()
        val kickR = -kickL
        line(c, -bodyR * 0.5f, bodyR * 1.25f, -bodyR * 0.7f + kickL * bodyR * 0.28f, bodyR * 1.78f,
            pinkDark, u * 0.032f)
        line(c, bodyR * 0.5f, bodyR * 1.25f, bodyR * 0.7f + kickR * bodyR * 0.28f, bodyR * 1.78f,
            pinkDark, u * 0.032f)
        oval(c, -bodyR * 0.85f + kickL * bodyR * 0.28f, bodyR * 1.72f, -bodyR * 0.4f + kickL * bodyR * 0.28f,
            bodyR * 1.94f, pinkDark)
        oval(c, bodyR * 0.4f + kickR * bodyR * 0.28f, bodyR * 1.72f, bodyR * 0.85f + kickR * bodyR * 0.28f,
            bodyR * 1.94f, pinkDark)

        // arms pumping
        val pump = sin(t * PI * 12).toFloat()
        line(c, -bodyR * 0.72f, bodyR * 0.3f, -bodyR * 1.25f, bodyR * 0.02f - pump * bodyR * 0.45f,
            pink, u * 0.03f)
        line(c, bodyR * 0.72f, bodyR * 0.3f, bodyR * 1.25f, bodyR * 0.02f + pump * bodyR * 0.45f,
            pink, u * 0.03f)
        fillCircle(c, -bodyR * 1.25f, bodyR * 0.02f - pump * bodyR * 0.45f, bodyR * 0.15f, pinkDark)
        fillCircle(c, bodyR * 1.25f, bodyR * 0.02f + pump * bodyR * 0.45f, bodyR * 0.15f, pinkDark)

        // head
        val headCy = -bodyR * 0.65f
        val headR = bodyR * 0.72f
        fillCircle(c, 0f, headCy, headR, pink)
        // ears
        path.reset()
        path.moveTo(-headR * 0.7f, headCy - headR * 0.55f)
        path.lineTo(-headR * 1.05f, headCy - headR * 1.05f - pump * headR * 0.1f)
        path.lineTo(-headR * 0.3f, headCy - headR * 0.85f)
        path.close()
        fillPath(c, path, pinkDark)
        path.reset()
        path.moveTo(headR * 0.7f, headCy - headR * 0.55f)
        path.lineTo(headR * 1.05f, headCy - headR * 1.05f + pump * headR * 0.1f)
        path.lineTo(headR * 0.3f, headCy - headR * 0.85f)
        path.close()
        fillPath(c, path, pinkDark)

        // eyes
        fillCircle(c, -headR * 0.32f, headCy - headR * 0.12f, headR * 0.12f, Color.rgb(48, 34, 34))
        fillCircle(c, headR * 0.32f, headCy - headR * 0.12f, headR * 0.12f, Color.rgb(48, 34, 34))
        fillCircle(c, -headR * 0.28f, headCy - headR * 0.17f, headR * 0.04f, Color.WHITE)
        fillCircle(c, headR * 0.36f, headCy - headR * 0.17f, headR * 0.04f, Color.WHITE)

        // snout
        oval(c, -headR * 0.38f, headCy + headR * 0.18f, headR * 0.38f, headCy + headR * 0.55f, pinkDark)
        fillCircle(c, -headR * 0.14f, headCy + headR * 0.36f, headR * 0.07f, Color.rgb(150, 70, 90))
        fillCircle(c, headR * 0.14f, headCy + headR * 0.36f, headR * 0.07f, Color.rgb(150, 70, 90))
        // smile
        arc(c, 0f, headCy + headR * 0.35f, headR * 0.55f, headR * 0.28f, 30f, 120f,
            Color.rgb(150, 70, 90), u * 0.006f)

        c.restore()

        // music notes drifting up
        for (i in 0 until 3) {
            val local = ((t * 1.1f + i * 0.3f) % 1f)
            val nx = cx + (if (i % 2 == 0) -1 else 1) * u * (0.17f + i * 0.02f) + sin(local * PI.toFloat() * 3f) * u * 0.02f
            val ny = groundY - u * 0.28f - local * u * 0.3f
            note(c, nx, ny, u * 0.05f, Color.rgb(90, 70, 130), (1f - local) * 0.85f)
        }
    }

    // 10 -------------------------------------------------------------------- UFO

    private fun drawUfo(c: Canvas, w: Wink, t: Float) {
        val u = min(width, height).toFloat()
        val cx = width / 2f
        val hoverY = height * 0.24f
        val groundY = height * 0.88f

        val enter = easeOut(seg(t, 0f, 0.24f))
        val exit = easeIn(seg(t, 0.8f, 1f))
        val saucerX = lerp(-u * 0.35f, cx, enter) + exit * u * 1.1f
        val saucerY = lerp(-u * 0.3f, hoverY, enter) + sin(t * PI * 9).toFloat() * u * 0.012f
        val tilt = if (exit > 0f) -18f * exit else 0f

        val beamOn = t in 0.26f..0.78f
        if (beamOn) {
            val beamAlpha = min(seg(t, 0.26f, 0.3f), 1f - seg(t, 0.72f, 0.78f))
            val flicker = 0.22f + 0.05f * sin(t * PI * 20).toFloat()
            val topW = u * 0.09f
            val bottomW = u * 0.3f
            path.reset()
            path.moveTo(saucerX - topW, saucerY + u * 0.03f)
            path.lineTo(saucerX + topW, saucerY + u * 0.03f)
            path.lineTo(saucerX + bottomW, groundY)
            path.lineTo(saucerX - bottomW, groundY)
            path.close()
            fill.color = Color.argb((255 * flicker * beamAlpha).toInt(), 96, 255, 120)
            c.drawPath(path, fill)
            // light bands inside the beam
            for (i in 1..3) {
                val k = ((t * 0.8f + i * 0.25f) % 1f)
                val y = lerp(saucerY, groundY, k)
                val halfW = lerp(topW, bottomW, k)
                line(c, saucerX - halfW * 0.85f, y, saucerX + halfW * 0.85f, y,
                    Color.rgb(200, 255, 190), u * 0.006f, beamAlpha * (1f - k) * 0.8f)
            }
            // dust motes rising in the tractor beam
            for ((i, s) in sparks.withIndex()) {
                if (i % 2 == 0) continue
                val local = ((t - 0.28f - s.delay * 0.8f) / 0.5f) % 1f
                val bx = saucerX + s.vx * u * 0.22f * (1f - local * 0.5f)
                val by = lerp(groundY, saucerY + u * 0.04f, local)
                fillCircle(c, bx, by, u * s.size * 0.8f, Color.rgb(210, 255, 190), (1f - local) * 0.9f)
            }
            // the (very small) abductee
            val rise = seg(t, 0.38f, 0.7f)
            if (rise > 0f && rise < 1f) {
                silhouette(c, saucerX, lerp(groundY - u * 0.05f, saucerY + u * 0.05f, easeInOut(rise)),
                    u * 0.1f, Color.rgb(238, 255, 236), 0.9f * (1f - rise * 0.5f))
            }
        }

        // saucer
        c.save()
        c.translate(saucerX, saucerY)
        c.rotate(tilt)

        // dome
        fill.alpha = 255
        fill.shader = RadialGradient(0f, -u * 0.06f, u * 0.09f,
            intArrayOf(Color.rgb(210, 250, 255), Color.rgb(96, 190, 220)), null, Shader.TileMode.CLAMP)
        rect.set(-u * 0.075f, -u * 0.075f, u * 0.075f, u * 0.02f)
        c.drawArc(rect, 180f, 180f, true, fill)
        fill.shader = null
        fillCircle(c, -u * 0.025f, -u * 0.05f, u * 0.015f, Color.WHITE, 0.7f)

        // hull
        fill.alpha = 255
        fill.shader = RadialGradient(0f, -u * 0.01f, u * 0.22f,
            intArrayOf(Color.rgb(226, 234, 244), Color.rgb(140, 156, 176)), null, Shader.TileMode.CLAMP)
        c.drawOval(RectF(-u * 0.16f, -u * 0.015f, u * 0.16f, u * 0.06f), fill)
        fill.shader = null
        ringOval(c, -u * 0.16f, -u * 0.015f, u * 0.16f, u * 0.06f, Color.rgb(96, 110, 128), u * 0.006f)
        oval(c, -u * 0.08f, -u * 0.008f, u * 0.08f, u * 0.012f, Color.rgb(120, 136, 158), 0.8f)

        // blinking lights
        val lightColors = intArrayOf(
            Color.rgb(255, 96, 96), Color.rgb(120, 220, 255),
            Color.rgb(255, 220, 110), Color.rgb(140, 255, 160)
        )
        for (i in 0 until 4) {
            val lx = -u * 0.105f + i * u * 0.07f
            val on = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * PI * 8 + i * 1.4f)).toFloat()
            fillCircle(c, lx, u * 0.022f, u * 0.011f, lightColors[i], on)
        }
        // antenna
        line(c, 0f, -u * 0.075f, 0f, -u * 0.105f, Color.rgb(150, 164, 180), u * 0.005f)
        fillCircle(c, 0f, -u * 0.108f, u * 0.008f, Color.rgb(255, 224, 120),
            (0.4f + 0.6f * abs(sin(t * PI * 6))).toFloat())

        c.restore()

        // sound ripples from the saucer
        if (beamOn) {
            for (i in 0 until 3) {
                val k = ((t * 1.4f + i * 0.3f) % 1f)
                ring(c, saucerX, saucerY + u * 0.02f, u * 0.16f + k * u * 0.12f,
                    Color.rgb(170, 230, 255), u * 0.005f, (1f - k) * 0.45f)
            }
        }
    }

    // 11 -------------------------------------------------------- Frog Eating Fly

    private fun drawFrog(c: Canvas, w: Wink, t: Float) {
        val u = min(width, height).toFloat()
        val cx = width / 2f
        val pondY = height * 0.78f
        val frogY = pondY - u * 0.01f
        val flyKill = 0.62f

        // water + lily pad
        oval(c, cx - u * 0.5f, pondY - u * 0.02f, cx + u * 0.5f, pondY + u * 0.16f,
            Color.rgb(70, 150, 210), 0.35f)
        oval(c, cx - u * 0.2f, pondY - u * 0.05f, cx + u * 0.2f, pondY + u * 0.05f, Color.rgb(64, 148, 76))
        path.reset()
        path.moveTo(cx, pondY)
        path.lineTo(cx + u * 0.17f, pondY + u * 0.01f)
        path.lineTo(cx + u * 0.12f, pondY + u * 0.05f)
        path.lineTo(cx, pondY + u * 0.02f)
        path.close()
        fillPath(c, path, Color.rgb(30, 90, 42), 0.65f)

        // the fly
        val flyX = cx + sin(t * PI * 6.3).toFloat() * u * 0.24f + sin(t * PI * 2.1).toFloat() * u * 0.07f
        val flyY = height * 0.36f + cos(t * PI * 4.7).toFloat() * u * 0.09f
        val flyAlive = t < flyKill
        if (flyAlive) {
            fly(c, flyX, flyY, u * 0.042f, abs(sin(t * PI * 60)).toFloat(), 1f)
        }

        // frog body
        val green = Color.rgb(96, 178, 84)
        val greenDark = Color.rgb(62, 130, 58)
        val greenLight = Color.rgb(150, 214, 118)
        val gulp = seg(t, 0.62f, 0.72f)
        val croak = seg(t, 0.78f, 0.98f)
        val puff = 1f + 0.12f * sin(croak * PI).toFloat() + 0.1f * sin(gulp * PI).toFloat()

        c.save()
        c.translate(cx, frogY)
        c.scale(puff, puff * (1f + 0.04f * sin(t * PI * 20).toFloat() * (1f - croak)))

        val bodyR = u * 0.14f
        oval(c, -bodyR * 1.25f, -bodyR * 0.55f, bodyR * 1.25f, bodyR * 0.65f, green)
        ringOval(c, -bodyR * 1.25f, -bodyR * 0.55f, bodyR * 1.25f, bodyR * 0.65f, greenDark, u * 0.005f)
        // belly
        oval(c, -bodyR * 0.7f, -bodyR * 0.05f, bodyR * 0.7f, bodyR * 0.55f, greenLight, 0.9f)

        // eyes on top, tracking the fly
        val lookX = ((flyX - cx) / (u * 0.3f)).coerceIn(-1f, 1f)
        val lookY = ((flyY - frogY) / (u * 0.3f)).coerceIn(-1f, 1f)
        for (side in intArrayOf(-1, 1)) {
            val ex = side * bodyR * 0.55f
            val ey = -bodyR * 0.72f
            fillCircle(c, ex, ey, bodyR * 0.34f, green)
            ring(c, ex, ey, bodyR * 0.34f, greenDark, u * 0.005f)
            if (croak > 0.4f) {
                arc(c, ex, ey, bodyR * 0.16f, bodyR * 0.14f, 200f, 140f, Color.rgb(40, 60, 30), u * 0.012f)
            } else {
                fillCircle(c, ex + lookX * bodyR * 0.1f, ey + lookY * bodyR * 0.08f, bodyR * 0.13f, Color.WHITE)
                fillCircle(c, ex + lookX * bodyR * 0.14f, ey + lookY * bodyR * 0.1f, bodyR * 0.07f, Color.rgb(38, 32, 24))
            }
        }

        // mouth
        if (croak > 0f) {
            val open = bodyR * (0.06f + 0.2f * sin(croak * PI).toFloat())
            oval(c, -bodyR * 0.55f, bodyR * 0.02f, bodyR * 0.55f, bodyR * 0.02f + open * 2f, Color.rgb(90, 40, 46))
            arc(c, 0f, bodyR * 0.1f, bodyR * 0.5f, bodyR * 0.22f, 20f, 140f, Color.rgb(40, 70, 36), u * 0.008f)
        } else {
            arc(c, 0f, bodyR * 0.06f, bodyR * 0.52f, bodyR * 0.26f, 25f, 130f, Color.rgb(40, 70, 36), u * 0.009f)
        }

        // front legs, outside the body so they read as legs
        line(c, -bodyR * 0.85f, bodyR * 0.25f, -bodyR * 1.22f, bodyR * 0.6f, greenDark, u * 0.022f)
        line(c, bodyR * 0.85f, bodyR * 0.25f, bodyR * 1.22f, bodyR * 0.6f, greenDark, u * 0.022f)
        oval(c, -bodyR * 1.42f, bodyR * 0.55f, -bodyR * 1.02f, bodyR * 0.78f, green)
        oval(c, bodyR * 1.02f, bodyR * 0.55f, bodyR * 1.42f, bodyR * 0.78f, green)

        c.restore()

        // tongue whip
        if (t > 0.56f && t < 0.68f) {
            val k = seg(t, 0.56f, 0.62f)
            val retract = seg(t, 0.62f, 0.68f)
            val mouthX = cx + u * 0.01f
            val mouthY = frogY - u * 0.005f
            val tipX = lerp(mouthX, flyX, k)
            val tipY = lerp(mouthY, flyY, k)
            if (retract > 0f) {
                val backX = lerp(flyX, mouthX, retract)
                val backY = lerp(flyY, mouthY, retract)
                line(c, mouthX, mouthY, backX, backY, Color.rgb(236, 120, 140), u * 0.016f)
                fillCircle(c, backX, backY, u * 0.02f, Color.rgb(240, 140, 160))
            } else {
                line(c, mouthX, mouthY, tipX, tipY, Color.rgb(236, 120, 140), u * 0.016f)
                fillCircle(c, tipX, tipY, u * 0.022f, Color.rgb(240, 140, 160))
            }
            sparkle(c, flyX, flyY, u * 0.03f, Color.rgb(255, 255, 220), 20f, 0.9f)
        }

        // croak ripples in the water
        if (croak > 0f) {
            for (i in 0 until 2) {
                val k = ((croak + i * 0.35f) % 1f)
                ringOval(c, cx - u * (0.2f + k * 0.3f), pondY - u * 0.02f + k * u * 0.03f,
                    cx + u * (0.2f + k * 0.3f), pondY + u * 0.06f + k * u * 0.03f,
                    Color.rgb(180, 230, 255), u * 0.005f, (1f - k) * 0.7f)
            }
        }
    }

    // 12 -------------------------------------------------------- Sparkling Heart

    private fun heartX(angle: Float): Float {
        val s = sin(angle.toDouble())
        return (16.0 * s * s * s / 17.0).toFloat()
    }

    private fun heartY(angle: Float): Float {
        val a = angle.toDouble()
        val y = 13.0 * cos(a) - 5.0 * cos(2 * a) - 2.0 * cos(3 * a) - cos(4 * a)
        return (-y / 17.0).toFloat()
    }

    private fun drawHeart(c: Canvas, w: Wink, t: Float) {
        val u = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height * 0.48f
        val radius = u * 0.26f
        val progressDraw = easeInOut(seg(t, 0.04f, 0.66f))
        val pulse = 1f + 0.05f * sin(t * PI * 8).toFloat()
        val finish = seg(t, 0.66f, 0.8f)

        // rose glow behind the drawing
        if (progressDraw > 0f) {
            val glowA = 0.5f * min(1f, progressDraw * 2f) * envelope
            fill.alpha = 255
            fill.shader = RadialGradient(
                cx, cy, radius * 1.9f,
                intArrayOf(Color.argb((200 * glowA).toInt(), 255, 120, 180), Color.argb(0, 255, 120, 180)),
                null, Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
            fill.shader = null
        }

        // filled heart once the outline closes
        if (progressDraw >= 0.98f) {
            path2.reset()
            for (i in 0..120) {
                val a = i / 120f * 2f * PI.toFloat()
                val x = cx + heartX(a) * radius
                val y = cy + heartY(a) * radius
                if (i == 0) path2.moveTo(x, y) else path2.lineTo(x, y)
            }
            path2.close()
            c.save()
            c.translate(cx, cy)
            c.scale(pulse, pulse)
            c.translate(-cx, -cy)
            fillPath(c, path2, Color.rgb(255, 120, 170), 0.2f + 0.15f * finish)
            c.restore()
        }

        // sparkle trail drawing the outline
        val samples = 110
        val headIdx = (progressDraw * samples).toInt()
        for (i in 0..headIdx) {
            val angle = (i.toFloat() / samples) * 2f * PI.toFloat()
            val x = cx + heartX(angle) * radius
            val y = cy + heartY(angle) * radius
            val age = (progressDraw * samples - i) / samples
            val fade = (1f - age * 0.9f).coerceIn(0f, 1f)
            val core = radius * 0.018f * (0.5f + 0.5f * (1f - age))
            fillCircle(c, x, y, core * 2.6f, Color.rgb(255, 150, 200), fade * 0.35f)
            fillCircle(c, x, y, core, Color.rgb(255, 230, 245), fade)
            if (i % 7 == 0) {
                sparkle(c, x, y, radius * 0.03f * (0.6f + fade * 0.8f), Color.rgb(255, 240, 250),
                    i * 23f + t * 180f, fade * 0.9f)
            }
        }

        // the wand / nozzle of fairy dust
        if (progressDraw < 1f) {
            val a = progressDraw * 2f * PI.toFloat()
            val x = cx + heartX(a) * radius
            val y = cy + heartY(a) * radius
            sparkle(c, x, y, radius * 0.09f, Color.rgb(255, 255, 255), t * 900f, 1f)
            sparkle(c, x, y, radius * 0.14f, Color.rgb(255, 170, 210), -t * 600f, 0.6f)
        }

        // glitter floating around the finished heart
        if (finish > 0f) {
            for ((i, s) in sparks.withIndex()) {
                if (i % 2 == 0) continue
                val local = ((finish * 1.6f - s.delay * 0.5f) / 0.9f).coerceIn(0f, 1f)
                if (local <= 0f || local >= 1f) continue
                val x = cx + s.x * width * 0.7f + s.vx * u * 0.05f
                val y = cy + (s.y - 0.5f) * height * 0.55f
                sparkle(c, x, y, u * s.size, Color.rgb(255, 235, 250), s.spin, (1f - local) * 0.9f)
            }
        }
    }

    // 13 ---------------------------------------------------------------- New Idea

    private fun drawIdea(c: Canvas, w: Wink, t: Float) {
        val u = min(width, height).toFloat()
        val cx = width / 2f
        val headR = u * 0.08f
        val headY = height * 0.68f

        val appear = overshoot(seg(t, 0f, 0.14f))
        if (appear <= 0.01f) return
        val on = t >= 0.44f
        val onK = seg(t, 0.44f, 0.54f)
        val fade = 1f - seg(t, 0.92f, 1f)

        // warm light once the bulb is on
        if (on) {
            val glow = (0.45f + 0.1f * sin(t * PI * 6).toFloat()) * onK
            fill.alpha = 255
            fill.shader = RadialGradient(
                cx, headY - u * 0.24f, u * 0.6f,
                intArrayOf(Color.argb((210 * glow).toInt(), 255, 236, 140), Color.argb(0, 255, 236, 140)),
                null, Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
            fill.shader = null
        }

        // shoulders
        c.save()
        c.translate(cx, headY)
        c.scale(appear, appear)
        torso(c, 0f, headR * 0.85f, headR * 1.9f, headR * 1.5f, Color.rgb(62, 108, 178), fade)
        val face = when {
            t < 0.3f -> Face.CALM
            t < 0.44f -> Face.SHOCK
            else -> Face.GRIN
        }
        head(c, 0f, 0f, headR, Color.rgb(250, 206, 162), Color.rgb(84, 56, 34), Hair.SHORT, face,
            lookY = if (t < 0.44f) -1f else 0f, alpha = fade)
        c.restore()

        // bulb drops in and hangs above the head
        val bulbY = lerp(-u * 0.2f, headY - u * 0.27f, overshoot(seg(t, 0.06f, 0.36f)))
        val bulbR = u * 0.075f
        val glassColor = if (on) Color.rgb(255, 236, 122) else Color.rgb(206, 216, 226)

        // rays
        if (on) {
            val rayA = onK * fade
            for (i in 0 until 12) {
                val ang = Math.toRadians((i * 30 + t * 40).toDouble())
                val inner = bulbR * 1.45f
                val outer = bulbR * (1.75f + 0.3f * (0.5f + 0.5f * sin(t * PI * 8 + i)).toFloat())
                line(c, cx + (cos(ang) * inner).toFloat(), bulbY + (sin(ang) * inner).toFloat(),
                    cx + (cos(ang) * outer).toFloat(), bulbY + (sin(ang) * outer).toFloat(),
                    Color.rgb(255, 226, 110), u * 0.007f, rayA * 0.85f)
            }
        }

        // glass
        fill.alpha = 255
        fill.shader = RadialGradient(
            cx - bulbR * 0.3f, bulbY - bulbR * 0.35f, bulbR * 2f,
            intArrayOf(if (on) Color.rgb(255, 252, 200) else Color.rgb(232, 238, 244), glassColor),
            null, Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, bulbY, bulbR, fill)
        fill.shader = null
        ring(c, cx, bulbY, bulbR, if (on) Color.rgb(226, 180, 40) else Color.rgb(150, 164, 178), u * 0.007f)

        // filament
        val filamentColor = if (on) Color.rgb(255, 255, 230) else Color.rgb(150, 160, 172)
        path.reset()
        path.moveTo(cx - bulbR * 0.3f, bulbY + bulbR * 0.25f)
        path.lineTo(cx - bulbR * 0.14f, bulbY - bulbR * 0.2f)
        path.lineTo(cx, bulbY + bulbR * 0.08f)
        path.lineTo(cx + bulbR * 0.14f, bulbY - bulbR * 0.2f)
        path.lineTo(cx + bulbR * 0.3f, bulbY + bulbR * 0.25f)
        strokePath(c, path, filamentColor, u * 0.006f)
        if (on) {
            fillCircle(c, cx, bulbY - bulbR * 0.05f, bulbR * 0.32f, Color.rgb(255, 255, 220), 0.35f * onK)
        }
        // highlight
        fillCircle(c, cx - bulbR * 0.35f, bulbY - bulbR * 0.4f, bulbR * 0.16f, Color.WHITE, 0.6f)

        // screw base
        rrect(c, cx - bulbR * 0.36f, bulbY + bulbR * 0.9f, cx + bulbR * 0.36f, bulbY + bulbR * 1.6f,
            bulbR * 0.06f, Color.rgb(176, 182, 192))
        for (i in 0 until 3) {
            val y = bulbY + bulbR * (1.0f + i * 0.18f)
            line(c, cx - bulbR * 0.36f, y, cx + bulbR * 0.36f, y, Color.rgb(130, 138, 150), u * 0.005f)
        }

        // switch-on sparks
        if (on && onK < 1f) {
            for (i in 0 until 6) {
                val ang = Math.toRadians((i * 60).toDouble())
                val d = u * (0.08f + onK * 0.18f)
                sparkle(c, cx + (cos(ang) * d).toFloat(), bulbY + (sin(ang) * d).toFloat(),
                    u * 0.028f * (1f - onK), Color.rgb(255, 255, 200), i * 37f, 1f)
            }
        }
    }

    // ------------------------------------------------------------ retired winks

    private fun drawPulse(c: Canvas, w: Wink, t: Float) {
        val size = min(width, height) * 0.5f
        val inScale = overshoot(seg(t, 0f, 0.2f))
        val outScale = 1f - easeInOut(seg(t, 0.78f, 1f))
        val wobble = if (t in 0.2f..0.8f) 9f * sin(t * PI * 10).toFloat() * (1f - seg(t, 0.2f, 0.8f)) else 0f
        val s = inScale * outScale
        if (s > 0f && trailGlyphs.isNotEmpty()) {
            c.save()
            c.translate(width / 2f, height / 2f)
            c.rotate(wobble)
            c.scale(s, s)
            text(c, w.emoji, 0f, 0f, size)
            c.restore()
        }
        for ((i, s2) in sparks.withIndex()) {
            if (i % 3 != 0) continue
            val local = ((t - s2.delay * 0.3f) / 0.7f).coerceIn(0f, 1f)
            if (local <= 0f || local >= 1f) continue
            text(c, trailGlyphs[i % trailGlyphs.size],
                s2.x * width, lerp(height + size * 0.2f, height * 0.1f, local),
                size * 0.16f, (1f - local) * 0.8f)
        }
    }

    private companion object {
        val SKIN = Color.rgb(250, 206, 162)
        val HAIR_BROWN = Color.rgb(94, 62, 36)
    }
}
