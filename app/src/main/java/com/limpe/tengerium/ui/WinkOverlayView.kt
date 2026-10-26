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
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Full-screen overlay that plays a short, classic-MSN style animation for a [Wink]
 * on top of the chat window. It never intercepts touches and draws nothing when idle.
 *
 * Everything is drawn from the wink's emoji (plus a few helper glyphs), so no
 * extra assets are needed. Motion is smooth (no rapid flashing).
 */
class WinkOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private enum class Kind { CROSS, HOP, FLOAT, RAIN, PULSE, SPIN, UFO, PUNCH, POPUP, GLOW }

    private class Particle(
        val x: Float,
        val delay: Float,
        val speed: Float,
        val phase: Float,
        val size: Float,
        val glyph: String
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val beamPath = Path()

    private var animator: ValueAnimator? = null
    private var wink: Wink? = null
    private var kind = Kind.PULSE
    private var progress = 0f
    private var envelope = 1f
    private var particles: List<Particle> = emptyList()
    private var particleGlyphs: List<String> = emptyList()

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
        particleGlyphs = glyphsFor(w)
        particles = buildParticles()
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
        particles = emptyList()
        invalidate()
        if (notify) onFinished?.invoke()
    }

    override fun onDetachedFromWindow() {
        stopInternal(notify = false)
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- setup

    private fun kindFor(id: String) = when (id) {
        "run", "boy" -> Kind.CROSS
        "monkey", "frog", "pig" -> Kind.HOP
        "heart", "kiss", "smile" -> Kind.FLOAT
        "party" -> Kind.RAIN
        "disco" -> Kind.SPIN
        "alien" -> Kind.UFO
        "fist" -> Kind.PUNCH
        "nerd" -> Kind.POPUP
        "idea" -> Kind.GLOW
        else -> Kind.PULSE
    }

    private fun durationFor(k: Kind): Long = when (k) {
        Kind.CROSS -> 2600
        Kind.HOP -> 3000
        Kind.FLOAT -> 3600
        Kind.RAIN -> 3600
        Kind.PULSE -> 2800
        Kind.SPIN -> 3600
        Kind.UFO -> 4400
        Kind.PUNCH -> 2400
        Kind.POPUP -> 2800
        Kind.GLOW -> 3000
    }

    private fun glyphsFor(w: Wink): List<String> = when (w.id) {
        "heart" -> listOf("\uD83D\uDC97", "\uD83D\uDC96", "\u2764\uFE0F", "\uD83D\uDC95")
        "kiss" -> listOf("\uD83D\uDC8B", "\uD83D\uDC95", "\uD83D\uDE18")
        "smile" -> listOf("\uD83D\uDE04", "\uD83D\uDE0A", "\u2728")
        "party" -> listOf("\uD83C\uDF89", "\uD83C\uDF8A", "\uD83C\uDF88", "\u2728", "\u2B50")
        "hug" -> listOf("\uD83D\uDC97", "\uD83D\uDC96")
        "idea" -> listOf("\u2728", "\u2B50")
        "disco" -> listOf("\u2728", "\uD83C\uDFB6")
        "nerd" -> listOf("\u2728")
        else -> emptyList()
    }

    private fun particleCount(): Int = when (kind) {
        Kind.FLOAT -> 18
        Kind.RAIN -> 30
        Kind.PULSE -> if (particleGlyphs.isNotEmpty()) 10 else 0
        Kind.GLOW -> 12
        Kind.POPUP -> 8
        Kind.SPIN -> 8
        else -> 0
    }

    private fun buildParticles(): List<Particle> {
        val n = particleCount()
        if (n == 0 || particleGlyphs.isEmpty()) return emptyList()
        val rnd = Random(System.nanoTime())
        val base = min(width, height).toFloat()
        return List(n) {
            Particle(
                x = rnd.nextFloat(),
                delay = rnd.nextFloat() * 0.45f,
                speed = 0.8f + rnd.nextFloat() * 0.4f,
                phase = rnd.nextFloat() * (2 * PI).toFloat(),
                size = base * (0.07f + rnd.nextFloat() * 0.09f),
                glyph = particleGlyphs[rnd.nextInt(particleGlyphs.size)]
            )
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun seg(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun easeOut(t: Float) = 1f - (1f - t) * (1f - t)
    private fun easeInOut(t: Float) = t * t * (3f - 2f * t)
    private fun overshoot(t: Float): Float {
        val s = 1.70158f
        val u = t - 1f
        return u * u * ((s + 1f) * u + s) + 1f
    }

    private fun glyph(
        c: Canvas, g: String, cx: Float, cy: Float, size: Float,
        rot: Float = 0f, alpha: Float = 1f, sx: Float = 1f, sy: Float = 1f
    ) {
        if (size <= 0f) return
        paint.textSize = size
        paint.alpha = (alpha * envelope).coerceIn(0f, 1f).times(255).toInt()
        val dy = -(paint.ascent() + paint.descent()) / 2f
        c.save()
        c.translate(cx, cy)
        c.rotate(rot)
        c.scale(sx, sy)
        c.drawText(g, 0f, dy, paint)
        c.restore()
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val w = wink ?: return
        val t = progress
        envelope = min(seg(t, 0f, 0.04f), 1f - seg(t, 0.95f, 1f))

        when (kind) {
            Kind.CROSS -> drawCross(canvas, w, t)
            Kind.HOP -> drawHop(canvas, w, t)
            Kind.FLOAT -> drawFloat(canvas, w, t)
            Kind.RAIN -> drawRain(canvas, w, t)
            Kind.PULSE -> drawPulse(canvas, w, t)
            Kind.SPIN -> drawSpin(canvas, w, t)
            Kind.UFO -> drawUfo(canvas, w, t)
            Kind.PUNCH -> drawPunch(canvas, w, t)
            Kind.POPUP -> drawPopup(canvas, w, t)
            Kind.GLOW -> drawGlow(canvas, w, t)
        }
    }

    /** Runs across the screen with a bouncing stride and a trail of dust. */
    private fun drawCross(c: Canvas, w: Wink, t: Float) {
        val size = min(width, height) * 0.30f
        val x = lerp(-size, width + size, t)
        val bounce = abs(sin(t * PI * 10)).toFloat() * size * 0.45f
        val y = height * 0.55f - bounce
        for (i in 1..5) {
            glyph(c, "\uD83D\uDCA8", x - size * (0.55f + i * 0.42f), y + size * 0.28f,
                size * 0.35f, alpha = 0.6f - i * 0.1f)
        }
        glyph(c, w.emoji, x, y, size, rot = 8f * sin(t * PI * 20).toFloat())
    }

    /** Hops across the screen, squashing and stretching on each landing. */
    private fun drawHop(c: Canvas, w: Wink, t: Float) {
        val size = min(width, height) * 0.32f
        val hops = 4
        val phase = (t * hops) % 1f
        val x = lerp(size, width - size, t)
        val arc = sin(phase * PI).toFloat()
        val y = height * 0.62f - arc * height * 0.26f
        val land = (1f - arc).pow(4)
        glyph(c, w.emoji, x, y, size, rot = (phase - 0.5f) * 16f,
            sx = 1f + 0.22f * land, sy = 1f - 0.22f * land)
        // little shadow on the ground
        fill.color = Color.argb((60 * envelope).toInt(), 0, 0, 0)
        c.drawOval(x - size * 0.35f * (0.6f + 0.4f * land), height * 0.62f + size * 0.42f,
            x + size * 0.35f * (0.6f + 0.4f * land), height * 0.62f + size * 0.52f, fill)
    }

    /** Hearts / kisses / smiles drifting up the screen around a big pulsing centrepiece. */
    private fun drawFloat(c: Canvas, w: Wink, t: Float) {
        val big = min(width, height) * 0.5f
        val grow = overshoot(seg(t, 0f, 0.22f))
        val beat = 1f + 0.06f * sin(t * PI * 14).toFloat()
        val fade = 1f - seg(t, 0.78f, 1f)
        glyph(c, w.emoji, width / 2f, height / 2f, big, alpha = fade, sx = grow * beat, sy = grow * beat)
        drawFloaters(c, t, up = true)
    }

    private fun drawFloaters(c: Canvas, t: Float, up: Boolean) {
        for (p in particles) {
            val local = ((t - p.delay) / (1f - p.delay) * p.speed).coerceIn(0f, 1f)
            if (local <= 0f || local >= 1f) continue
            val y = if (up) lerp(height + p.size, -p.size, local) else lerp(-p.size, height + p.size, local)
            val x = p.x * width + sin(local * PI * 3 + p.phase).toFloat() * p.size * 0.6f
            val a = 1f - seg(local, 0.8f, 1f)
            val s = 1f + 0.1f * sin(local * 20f + p.phase)
            glyph(c, p.glyph, x, y, p.size, rot = sin(local * 8f + p.phase) * 12f, alpha = a, sx = s, sy = s)
        }
    }

    /** Confetti falling from the top, with a burst of the wink emoji at the start. */
    private fun drawRain(c: Canvas, w: Wink, t: Float) {
        val big = min(width, height) * 0.5f
        val pop = overshoot(seg(t, 0f, 0.18f))
        val fade = 1f - seg(t, 0.3f, 0.5f)
        if (fade > 0f) glyph(c, w.emoji, width / 2f, height * 0.4f, big * pop, alpha = fade)
        for (p in particles) {
            val local = ((t - p.delay) / (1f - p.delay) * p.speed).coerceIn(0f, 1f)
            if (local <= 0f || local >= 1f) continue
            val y = lerp(-p.size, height + p.size, local.pow(1.3f))
            val x = p.x * width + sin(local * PI * 4 + p.phase).toFloat() * p.size * 0.5f
            glyph(c, p.glyph, x, y, p.size, rot = local * 540f * (if (p.phase > 3f) 1f else -1f),
                alpha = 1f - seg(local, 0.9f, 1f))
        }
    }

    /** Pops in with a wobble, then shrinks away; optional hearts for hugs. */
    private fun drawPulse(c: Canvas, w: Wink, t: Float) {
        val size = min(width, height) * 0.55f
        val inScale = overshoot(seg(t, 0f, 0.2f))
        val outScale = 1f - easeInOut(seg(t, 0.78f, 1f))
        val wobble = if (t in 0.2f..0.8f) 9f * sin(t * PI * 10).toFloat() * (1f - seg(t, 0.2f, 0.8f)) else 0f
        val s = inScale * outScale
        glyph(c, w.emoji, width / 2f, height / 2f, size, rot = wobble, sx = s, sy = s)
        drawFloaters(c, t, up = true)
    }

    /** Spinning disc with slowly cycling colour wash and orbiting sparkles. */
    private fun drawSpin(c: Canvas, w: Wink, t: Float) {
        val hue = (t * 540f) % 360f
        fill.color = Color.HSVToColor((70 * envelope).toInt(), floatArrayOf(hue, 0.7f, 1f))
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)

        val size = min(width, height) * 0.45f
        val pulse = 1f + 0.1f * sin(t * PI * 6).toFloat()
        val inOut = easeInOut(seg(t, 0f, 0.15f)) * (1f - easeInOut(seg(t, 0.88f, 1f)))
        val cx = width / 2f
        val cy = height / 2f
        glyph(c, w.emoji, cx, cy, size, rot = t * 720f, sx = pulse * inOut, sy = pulse * inOut)

        val orbit = min(width, height) * 0.36f
        for ((i, p) in particles.withIndex()) {
            val ang = Math.toRadians((t * 360.0 * 2.0) + i * 45.0)
            val gx = cx + (cos(ang) * orbit).toFloat()
            val gy = cy + (sin(ang) * orbit).toFloat()
            glyph(c, p.glyph, gx, gy, p.size * 0.9f, rot = (t * 360f), alpha = inOut)
        }
    }

    /** A UFO swoops in, beams the character up and flies off. */
    private fun drawUfo(c: Canvas, w: Wink, t: Float) {
        val ufoGlyph = if (paint.hasGlyph("\uD83D\uDEF8")) "\uD83D\uDEF8" else "\uD83D\uDE80"
        val ufoSize = min(width, height) * 0.38f
        val alienSize = min(width, height) * 0.2f
        val cx = width / 2f
        val ufoRestY = height * 0.22f

        val enter = easeOut(seg(t, 0f, 0.28f))
        val exit = easeInOut(seg(t, 0.8f, 1f))
        val ufoY = lerp(-ufoSize, ufoRestY, enter) - exit * (ufoRestY + ufoSize * 1.4f)
        val ufoX = cx + sin(t * PI * 8).toFloat() * ufoSize * 0.06f

        val beamOn = t in 0.28f..0.78f
        val groundY = height * 0.86f
        if (beamOn) {
            val flicker = 0.30f + 0.08f * sin(t * PI * 12).toFloat()
            beamPath.reset()
            beamPath.moveTo(ufoX - ufoSize * 0.18f, ufoY + ufoSize * 0.3f)
            beamPath.lineTo(ufoX + ufoSize * 0.18f, ufoY + ufoSize * 0.3f)
            beamPath.lineTo(ufoX + ufoSize * 0.6f, groundY)
            beamPath.lineTo(ufoX - ufoSize * 0.6f, groundY)
            beamPath.close()
            fill.shader = null
            fill.color = Color.argb((255 * flicker * envelope).toInt(), 255, 245, 150)
            c.drawPath(beamPath, fill)
        }

        // the character: fades in at the bottom, rises up the beam, shrinks as it goes
        val appear = seg(t, 0.12f, 0.28f)
        val rise = easeInOut(seg(t, 0.34f, 0.74f))
        if (t < 0.74f && appear > 0f) {
            val ay = lerp(groundY - alienSize * 0.4f, ufoY + ufoSize * 0.3f, rise)
            val s = lerp(1f, 0.35f, rise)
            glyph(c, w.emoji, ufoX + sin(t * PI * 10).toFloat() * alienSize * 0.15f, ay, alienSize,
                rot = rise * 360f, alpha = appear, sx = s, sy = s)
        }
        glyph(c, ufoGlyph, ufoX, ufoY, ufoSize)
    }

    /** Fist flies at the screen, impact flash and a camera shake. */
    private fun drawPunch(c: Canvas, w: Wink, t: Float) {
        val base = min(width, height) * 0.4f
        val impact = 0.35f
        val approach = seg(t, 0f, impact)
        val scale = if (t < impact) lerp(0.15f, 3.2f, approach.pow(3)) else 3.2f - 0.25f * seg(t, impact, 0.8f)
        val shakeAmp = if (t in impact..0.7f) (1f - seg(t, impact, 0.7f)) * resources.displayMetrics.density * 14f else 0f
        val fade = 1f - seg(t, 0.72f, 1f)

        c.save()
        if (shakeAmp > 0f) {
            c.translate(sin(t * 160f) * shakeAmp, cos(t * 130f) * shakeAmp)
        }
        glyph(c, w.emoji, width / 2f, height / 2f, base, rot = -12f * approach, alpha = fade, sx = scale, sy = scale)
        if (t >= impact) {
            val boom = seg(t, impact, impact + 0.2f)
            glyph(c, "\uD83D\uDCA5", width / 2f, height / 2f, base * 2.2f * (0.6f + 0.6f * boom),
                alpha = (1f - boom) * fade)
        }
        c.restore()

        if (t >= impact) {
            val flash = (1f - seg(t, impact, impact + 0.25f)) * 0.55f
            fill.color = Color.argb((255 * flash * envelope).toInt(), 255, 255, 255)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        }
    }

    /** Slides up from the bottom, adjusts, then slides back down. */
    private fun drawPopup(c: Canvas, w: Wink, t: Float) {
        val size = min(width, height) * 0.5f
        val up = overshoot(seg(t, 0f, 0.28f))
        val down = easeInOut(seg(t, 0.76f, 1f))
        val restY = height * 0.5f
        val hiddenY = height + size
        val y = lerp(hiddenY, restY, up.coerceAtMost(1.15f)) + down * (hiddenY - restY)
        val adjust = if (t in 0.35f..0.7f) -10f * sin((t - 0.35f) / 0.35f * PI * 3).toFloat() else 0f
        glyph(c, w.emoji, width / 2f, y, size, rot = adjust)
        if (t in 0.35f..0.75f) {
            for ((i, p) in particles.withIndex()) {
                val ang = i * (2 * PI / particles.size)
                val r = size * 0.62f
                val a = (0.5f + 0.5f * sin(t * PI * 6 + i)).toFloat()
                glyph(c, p.glyph, width / 2f + (cos(ang) * r).toFloat(), y + (sin(ang) * r).toFloat(),
                    p.size, alpha = a)
            }
        }
    }

    /** A glowing idea: soft pulsing light with sparks. */
    private fun drawGlow(c: Canvas, w: Wink, t: Float) {
        val cx = width / 2f
        val cy = height * 0.45f
        val radius = min(width, height) * 0.8f
        val pulse = 0.5f + 0.5f * sin(t * PI * 5).toFloat()
        val a = (0.55f * pulse * envelope * easeInOut(seg(t, 0f, 0.15f))).coerceIn(0f, 1f)
        fill.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(Color.argb((255 * a).toInt(), 255, 236, 120), Color.argb(0, 255, 236, 120)),
            null, Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        fill.shader = null

        val size = min(width, height) * 0.5f
        val s = overshoot(seg(t, 0f, 0.22f)) * (1f - easeInOut(seg(t, 0.85f, 1f)))
        glyph(c, w.emoji, cx, cy, size, rot = 10f * sin(t * PI * 6).toFloat(), sx = s, sy = s)

        for ((i, p) in particles.withIndex()) {
            val local = seg(t, 0.15f + (i % 4) * 0.05f, 0.9f)
            if (local <= 0f || local >= 1f) continue
            val ang = i * (2 * PI / particles.size)
            val r = lerp(size * 0.45f, size * 1.1f, local)
            glyph(c, p.glyph, cx + (cos(ang) * r).toFloat(), cy + (sin(ang) * r).toFloat(),
                p.size * 0.9f, alpha = 1f - local)
        }
    }
}
