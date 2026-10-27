package com.limpe.tengerium.ui.games

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** One ball on the table. Id 0 is the cue ball, 1-15 are the object balls. */
data class PoolBall(val id: Int, val x: Float, val y: Float)

/** Where a ball ended up: the result of one shot, as both phones see it. */
data class PoolShot(
    val frames: List<List<PoolBall>>,
    val potted: List<Int>,
    val cuePotted: Boolean
) {
    val final: List<PoolBall> get() = frames.lastOrNull() ?: emptyList()
}

enum class PoolNote { BREAK, POT, MISS, FOUL, WIN, LOSE, TURN }

/** Outcome of one shot, already agreed between the two phones. */
data class PoolResolution(
    val table: String,
    val shooterGroup: Int,
    val opponentGroup: Int,
    val shooterContinues: Boolean,
    val winner: Int,
    val note: PoolNote
) {
    companion object {
        const val NOBODY = 0
        const val SHOOTER = 1
        const val OPPONENT = 2
    }
}

/**
 * A compact eight-ball table: balls, pockets, a rack, and enough physics for a shot to
 * feel like billiards without any native maths library.
 *
 * Only the player taking the shot runs the simulation; the resulting positions are then
 * sent to the other phone, so the two devices can never drift apart on float rounding.
 */
object PoolTable {

    const val WIDTH = 1000f
    const val HEIGHT = 540f
    /** Balls are drawn larger than life so they stay readable on a phone. */
    const val RADIUS = 24f
    const val CUE_ID = 0
    const val EIGHT = 8

    const val OPEN = 0
    const val SOLIDS = 1
    const val STRIPES = 2

    private const val POCKET_RADIUS = 32f
    private const val HEAD_X = 250f
    private const val RACK_X = 700f
    private const val ROLLING_FRICTION = 0.992f
    private const val REST_SPEED = 6f
    private const val MAX_SIMULATION_STEPS = 1_200
    private const val FRAME_INTERVAL_STEPS = 4

    // Inset a little from the corners so a pocket reads as a pocket instead of being clipped.
    private val POCKETS = listOf(
        Pair(12f, 12f), Pair(WIDTH / 2f, 9f), Pair(WIDTH - 12f, 12f),
        Pair(12f, HEIGHT - 12f), Pair(WIDTH / 2f, HEIGHT - 9f), Pair(WIDTH - 12f, HEIGHT - 12f)
    )

    fun pockets(): List<Pair<Float, Float>> = POCKETS

    fun groupOf(ballId: Int): Int = when (ballId) {
        in 1..7 -> SOLIDS
        in 9..15 -> STRIPES
        else -> OPEN
    }

    fun groupBallIds(group: Int): List<Int> = when (group) {
        SOLIDS -> (1..7).toList()
        STRIPES -> (9..15).toList()
        else -> emptyList()
    }

    /** The opening rack: cue on the head spot, fifteen balls in a triangle. */
    fun rack(): List<PoolBall> {
        val balls = mutableListOf(PoolBall(CUE_ID, HEAD_X, HEIGHT / 2f))
        // The classic rack: yellow and red alternating, 8 in the middle of the third row.
        //   R
        //   R Y
        //   Y 8 R
        //   R Y R Y
        //   Y Y R Y R
        val order = listOf(
            9,          // row 1
            10, 1,      // row 2
            2, 8, 11,   // row 3
            12, 3, 13, 4,          // row 4
            5, 6, 14, 7, 15        // row 5
        )
        var index = 0
        for (row in 0..4) {
            val x = RACK_X + row * (RADIUS * 1.74f)
            for (slot in 0..row) {
                val y = HEIGHT / 2f + (slot - row / 2f) * (RADIUS * 2.02f)
                balls.add(PoolBall(order[index], x, y))
                index++
            }
        }
        return balls
    }

    fun encode(balls: List<PoolBall>): String =
        balls.joinToString("|") { ball ->
            "${ball.id}:${ball.x.toInt()}:${ball.y.toInt()}"
        }

    fun decode(table: String): List<PoolBall> {
        if (table.isBlank()) return rack()
        return table.split('|').mapNotNull { part ->
            val bits = part.split(':')
            if (bits.size != 3) return@mapNotNull null
            val id = bits[0].toIntOrNull() ?: return@mapNotNull null
            val x = bits[1].toIntOrNull()?.toFloat() ?: return@mapNotNull null
            val y = bits[2].toIntOrNull()?.toFloat() ?: return@mapNotNull null
            PoolBall(id, x, y)
        }
    }

    fun nameOf(ballId: Int): String = if (ballId == CUE_ID) "cue" else ballId.toString()

    /**
     * Rolls the cue ball at [angleDegrees] with [power] in 0..1 and records the whole shot
     * so the shooter's screen can play it back. Balls that drop are reported in [PoolShot].
     */
    fun shoot(balls: List<PoolBall>, angleDegrees: Float, power: Float): PoolShot {
        val working = balls.map { it.copy() }.toMutableList()
        val potted = mutableListOf<Int>()
        val frames = mutableListOf<List<PoolBall>>()

        val cue = working.firstOrNull { it.id == CUE_ID }
        if (cue == null) return PoolShot(listOf(working.toList()), potted, true)

        val speed = 180f + power.coerceIn(0f, 1f) * 1250f
        val radians = Math.toRadians(angleDegrees.toDouble())
        val velocity = HashMap<Int, FloatArray>()
        working.forEach { velocity[it.id] = floatArrayOf(0f, 0f) }
        velocity[CUE_ID] = floatArrayOf((cos(radians) * speed).toFloat(), (sin(radians) * speed).toFloat())

        val stepSeconds = 1f / 240f
        var steps = 0
        var cuePotted = false

        frames.add(working.toList())
        while (steps < MAX_SIMULATION_STEPS) {
            steps++
            for (ball in working.toList()) {
                val v = velocity[ball.id] ?: continue
                if (v[0] == 0f && v[1] == 0f) continue
                if (hypot(v[0], v[1]) <= REST_SPEED) {
                    v[0] = 0f
                    v[1] = 0f
                    continue
                }
                val nextX = ball.x + v[0] * stepSeconds
                val nextY = ball.y + v[1] * stepSeconds
                var placed = PoolBall(ball.id, nextX, nextY)

                if (pocketAt(placed.x, placed.y)) {
                    working.removeAll { it.id == ball.id }
                    velocity[ball.id] = floatArrayOf(0f, 0f)
                    if (ball.id == CUE_ID) cuePotted = true else potted.add(ball.id)
                    continue
                }

                var vx = v[0]
                var vy = v[1]
                if (placed.x < RADIUS) {
                    placed = placed.copy(x = RADIUS)
                    vx = abs(vx) * 0.82f
                } else if (placed.x > WIDTH - RADIUS) {
                    placed = placed.copy(x = WIDTH - RADIUS)
                    vx = -abs(vx) * 0.82f
                }
                if (placed.y < RADIUS) {
                    placed = placed.copy(y = RADIUS)
                    vy = abs(vy) * 0.82f
                } else if (placed.y > HEIGHT - RADIUS) {
                    placed = placed.copy(y = HEIGHT - RADIUS)
                    vy = -abs(vy) * 0.82f
                }
                velocity[ball.id] = floatArrayOf(vx * ROLLING_FRICTION, vy * ROLLING_FRICTION)
                val index = working.indexOfFirst { it.id == ball.id }
                if (index >= 0) working[index] = placed
            }

            collide(working, velocity)
            var moving = false
            velocity.values.forEach { v ->
                if (hypot(v[0], v[1]) <= REST_SPEED) {
                    v[0] = 0f
                    v[1] = 0f
                } else {
                    moving = true
                }
            }
            if (!moving) break
            if (steps % FRAME_INTERVAL_STEPS == 0) frames.add(working.toList())
        }

        frames.add(working.toList())
        return PoolShot(frames, potted.distinct(), cuePotted)
    }

    /** Spot for a re-spotted cue ball after a scratch, avoiding the balls still on the table. */
    fun respotCue(balls: List<PoolBall>): PoolBall {
        val candidates = mutableListOf(Pair(HEAD_X, HEIGHT / 2f))
        for (dx in 0..8) {
            for (dy in listOf(-1, 1)) {
                candidates.add(Pair(HEAD_X + dx * RADIUS * 1.2f, HEIGHT / 2f + dy * dx * RADIUS * 1.2f))
            }
        }
        val spot = candidates.firstOrNull { (x, y) ->
            balls.none { it.id != CUE_ID && hypot(it.x - x, it.y - y) < RADIUS * 2.2f }
        } ?: candidates.first()
        return PoolBall(CUE_ID, spot.first, spot.second)
    }

    private fun pocketAt(x: Float, y: Float): Boolean =
        POCKETS.any { (px, py) -> hypot(x - px, y - py) <= POCKET_RADIUS }

    private fun collide(working: MutableList<PoolBall>, velocity: HashMap<Int, FloatArray>) {
        for (i in working.indices) {
            for (j in i + 1 until working.size) {
                val a = working[i]
                val b = working[j]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val distance = sqrt(dx * dx + dy * dy)
                if (distance <= 0f || distance >= RADIUS * 2f) continue

                val nx = dx / distance
                val ny = dy / distance
                val overlap = RADIUS * 2f - distance
                working[i] = a.copy(x = a.x - nx * overlap / 2f, y = a.y - ny * overlap / 2f)
                working[j] = b.copy(x = b.x + nx * overlap / 2f, y = b.y + ny * overlap / 2f)

                val va = velocity[a.id] ?: continue
                val vb = velocity[b.id] ?: continue
                val relative = (vb[0] - va[0]) * nx + (vb[1] - va[1]) * ny
                if (relative > 0f) continue

                val impulse = -(1f + 0.96f) * relative / 2f
                velocity[a.id] = floatArrayOf(va[0] - impulse * nx, va[1] - impulse * ny)
                velocity[b.id] = floatArrayOf(vb[0] + impulse * nx, vb[1] + impulse * ny)
            }
        }
    }
}

/**
 * A resolved shot as it travels to the other phone: base64 of the table, whose turn follows,
 * the groups and the outcome. One chat message carries the whole shot.
 */
object PoolWire {

    private const val SEPARATOR = '~'

    fun encode(resolution: PoolResolution): String {
        val raw = listOf(
            resolution.table,
            if (resolution.shooterContinues) "1" else "0",
            resolution.shooterGroup.toString(),
            resolution.opponentGroup.toString(),
            resolution.winner.toString(),
            resolution.note.name
        ).joinToString(SEPARATOR.toString())
        return android.util.Base64.encodeToString(
            raw.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE
        )
    }

    fun decode(payload: String): PoolResolution? = runCatching {
        val raw = String(
            android.util.Base64.decode(payload, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE),
            Charsets.UTF_8
        )
        val parts = raw.split(SEPARATOR)
        if (parts.size < 6) return@runCatching null
        PoolResolution(
            table = parts[0],
            shooterContinues = parts[1] == "1",
            shooterGroup = parts[2].toInt(),
            opponentGroup = parts[3].toInt(),
            winner = parts[4].toInt(),
            note = PoolNote.valueOf(parts[5])
        )
    }.getOrNull()
}

/**
 * The simplified eight-ball rules used by the app: open table until the first ball drops,
 * the shooter keeps the table while they pot their own group, potting the 8 too early loses
 * the game, and a scratch hands the table over with the cue ball re-spotted.
 */
object PoolRules {

    fun resolve(
        tableBefore: String,
        shooterGroup: Int,
        opponentGroup: Int,
        shot: PoolShot
    ): PoolResolution {
        var remaining = shot.final.map { it.copy() }
        var cuePotted = shot.cuePotted
        if (cuePotted) {
            remaining = remaining.filter { it.id != PoolTable.CUE_ID }
            remaining = remaining + PoolTable.respotCue(remaining)
        }

        var mine = shooterGroup
        var theirs = opponentGroup

        // Open table: the first object ball potted decides the groups.
        if (mine == PoolTable.OPEN) {
            val first = shot.potted.firstOrNull { it != PoolTable.EIGHT }
            if (first != null) {
                mine = PoolTable.groupOf(first)
                theirs = if (mine == PoolTable.SOLIDS) PoolTable.STRIPES else PoolTable.SOLIDS
            }
        }

        val eightPotted = shot.potted.contains(PoolTable.EIGHT)
        val ownLeft = if (mine == PoolTable.OPEN) 1 else
            remaining.count { PoolTable.groupOf(it.id) == mine }

        if (eightPotted) {
            val cleared = mine != PoolTable.OPEN && ownLeft == 0
            val won = cleared && !cuePotted
            return PoolResolution(
                table = PoolTable.encode(remaining.filter { it.id != PoolTable.EIGHT }),
                shooterGroup = mine,
                opponentGroup = theirs,
                shooterContinues = false,
                winner = if (won) PoolResolution.SHOOTER else PoolResolution.OPPONENT,
                note = if (won) PoolNote.WIN else PoolNote.LOSE
            )
        }

        val ownPotted = shot.potted.any { it != PoolTable.EIGHT && PoolTable.groupOf(it) == mine }
        val foul = cuePotted
        val continues = !foul && (ownPotted || (shooterGroup == PoolTable.OPEN && shot.potted.isNotEmpty()))

        return PoolResolution(
            table = PoolTable.encode(remaining),
            shooterGroup = mine,
            opponentGroup = theirs,
            shooterContinues = continues,
            winner = PoolResolution.NOBODY,
            note = when {
                foul -> PoolNote.FOUL
                ownPotted -> PoolNote.POT
                shot.potted.isNotEmpty() -> PoolNote.TURN
                else -> PoolNote.MISS
            }
        )
    }
}
