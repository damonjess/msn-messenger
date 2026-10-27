package com.limpe.tengerium

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.limpe.tengerium.ui.games.ConnectFour
import com.limpe.tengerium.ui.games.PoolBall
import com.limpe.tengerium.ui.games.PoolNote
import com.limpe.tengerium.ui.games.PoolResolution
import com.limpe.tengerium.ui.games.PoolRules
import com.limpe.tengerium.ui.games.PoolShot
import com.limpe.tengerium.ui.games.PoolTable
import com.limpe.tengerium.ui.games.PoolWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Connect Four and the eight-ball table: rules, layout and the shot payload. */
@RunWith(AndroidJUnit4::class)
class PoolRulesTest {

    // ---- connect four -------------------------------------------------------------------

    @Test
    fun connectFourDropsToTheLowestFreeRow() {
        val empty = ConnectFour.emptyBoard()
        assertEquals(5, ConnectFour.landingRow(empty, 3))

        var board = ConnectFour.drop(empty, 3, 'R')!!
        assertEquals('R', ConnectFour.cell(board, 5, 3))
        board = ConnectFour.drop(board, 3, 'Y')!!
        assertEquals('Y', ConnectFour.cell(board, 4, 3))
        assertEquals(3, ConnectFour.landingRow(board, 3))

        assertNull(ConnectFour.drop(empty, 7, 'R'))
        assertNull(ConnectFour.drop(empty, -1, 'R'))
        assertNull(ConnectFour.drop(empty, 0, 'Z'))
    }

    @Test
    fun connectFourFindsFourInARowEverywhere() {
        fun boardOf(discs: List<Pair<Int, Int>>, mark: Char): String {
            var board = ConnectFour.emptyBoard()
            for ((row, column) in discs) {
                val cells = board.toCharArray()
                cells[row * ConnectFour.COLUMNS + column] = mark
                board = String(cells)
            }
            return board
        }

        assertEquals('R', ConnectFour.winner(boardOf(listOf(5 to 0, 5 to 1, 5 to 2, 5 to 3), 'R')))
        assertEquals('Y', ConnectFour.winner(boardOf(listOf(5 to 6, 4 to 6, 3 to 6, 2 to 6), 'Y')))
        assertEquals('R', ConnectFour.winner(boardOf(listOf(5 to 0, 4 to 1, 3 to 2, 2 to 3), 'R')))
        assertEquals('Y', ConnectFour.winner(boardOf(listOf(2 to 6, 3 to 5, 4 to 4, 5 to 3), 'Y')))
        assertNull(ConnectFour.winner(boardOf(listOf(5 to 0, 5 to 1, 5 to 2), 'R')))
        assertFalse(ConnectFour.isDraw(ConnectFour.emptyBoard()))
    }

    // ---- pool ---------------------------------------------------------------------------

    @Test
    fun rackHasSixteenBallsAndRoundTripsThroughTheWire() {
        val rack = PoolTable.rack()
        assertEquals(16, rack.size)
        assertNotNull(rack.firstOrNull { it.id == PoolTable.CUE_ID })
        assertNotNull(rack.firstOrNull { it.id == PoolTable.EIGHT })

        val encoded = PoolTable.encode(rack)
        val decoded = PoolTable.decode(encoded)
        assertEquals(rack.size, decoded.size)
        assertEquals(rack.first().id, decoded.first().id)
        assertTrue(PoolTable.encode(decoded).isNotEmpty())
    }

    @Test
    fun theRackMatchesTheClassicSetup() {
        val objects = PoolTable.rack().filter { it.id != PoolTable.CUE_ID }
        assertEquals(15, objects.size)
        assertEquals("black ball in the middle of the third row", 8, objects[4].id)
        assertEquals(7, objects.count { PoolTable.groupOf(it.id) == PoolTable.SOLIDS })
        assertEquals(7, objects.count { PoolTable.groupOf(it.id) == PoolTable.STRIPES })

        // The rack reads apex first, then down each column: R | R Y | Y 8 R | R Y R Y | Y Y R Y R.
        val expected = listOf(
            PoolTable.STRIPES,
            PoolTable.STRIPES, PoolTable.SOLIDS,
            PoolTable.SOLIDS, PoolTable.OPEN, PoolTable.STRIPES,
            PoolTable.STRIPES, PoolTable.SOLIDS, PoolTable.STRIPES, PoolTable.SOLIDS,
            PoolTable.SOLIDS, PoolTable.SOLIDS, PoolTable.STRIPES, PoolTable.SOLIDS, PoolTable.STRIPES
        )
        assertEquals(expected, objects.map { PoolTable.groupOf(it.id) })
    }

    @Test
    fun aShotMovesBallsAndRecordsFrames() {
        val rack = PoolTable.rack()
        val shot = PoolTable.shoot(rack, 0f, 1f)
        assertTrue("the cue ball should travel", shot.frames.size > 2)
        assertTrue("a full-power shot must not create an excessively long animation", shot.frames.size <= 302)
        val moved = shot.final.count { ball ->
            val before = rack.firstOrNull { it.id == ball.id } ?: return@count true
            kotlin.math.hypot(ball.x - before.x, ball.y - before.y) > 20f
        }
        assertTrue("the shot should move the balls", moved >= 2)
        assertTrue("nothing can leave the table", shot.final.all {
            it.x >= 0f && it.x <= PoolTable.WIDTH && it.y >= 0f && it.y <= PoolTable.HEIGHT
        })
    }

    @Test
    fun pottingOnAnOpenTableAssignsGroupsAndKeepsTheTable() {
        val rack = PoolTable.rack()
        val after = rack.filter { it.id != 1 }
        val resolution = PoolRules.resolve(
            tableBefore = PoolTable.encode(rack),
            shooterGroup = PoolTable.OPEN,
            opponentGroup = PoolTable.OPEN,
            shot = PoolShot(listOf(after), potted = listOf(1), cuePotted = false)
        )
        assertEquals(PoolTable.SOLIDS, resolution.shooterGroup)
        assertEquals(PoolTable.STRIPES, resolution.opponentGroup)
        assertTrue(resolution.shooterContinues)
        assertEquals(PoolNote.POT, resolution.note)
        assertEquals(PoolResolution.NOBODY, resolution.winner)
        assertNull(PoolTable.decode(resolution.table).firstOrNull { it.id == 1 })
    }

    @Test
    fun missingPassesTheTableAndScratchingIsAFoul() {
        val rack = PoolTable.rack()
        val missed = PoolRules.resolve(
            PoolTable.encode(rack), PoolTable.SOLIDS, PoolTable.STRIPES,
            PoolShot(listOf(rack), potted = emptyList(), cuePotted = false)
        )
        assertFalse(missed.shooterContinues)
        assertEquals(PoolNote.MISS, missed.note)

        val scratched = PoolRules.resolve(
            PoolTable.encode(rack), PoolTable.SOLIDS, PoolTable.STRIPES,
            PoolShot(listOf(rack.filter { it.id != PoolTable.CUE_ID }), potted = emptyList(), cuePotted = true)
        )
        assertEquals(PoolNote.FOUL, scratched.note)
        assertFalse(scratched.shooterContinues)
        assertNotNull("the cue ball is re-spotted", PoolTable.decode(scratched.table).firstOrNull { it.id == PoolTable.CUE_ID })
    }

    @Test
    fun pottingTheEightEarlyLosesAndFinishingYourGroupWins() {
        val rack = PoolTable.rack()
        val early = PoolRules.resolve(
            PoolTable.encode(rack), PoolTable.SOLIDS, PoolTable.STRIPES,
            PoolShot(listOf(rack.filter { it.id != PoolTable.EIGHT }), potted = listOf(PoolTable.EIGHT), cuePotted = false)
        )
        assertEquals(PoolResolution.OPPONENT, early.winner)
        assertEquals(PoolNote.LOSE, early.note)

        // Only the 8 and a cue ball left: potting the 8 wins.
        val cleared = listOf(PoolBall(PoolTable.CUE_ID, 200f, 200f), PoolBall(PoolTable.EIGHT, 400f, 200f))
        val win = PoolRules.resolve(
            PoolTable.encode(cleared), PoolTable.SOLIDS, PoolTable.STRIPES,
            PoolShot(listOf(cleared.filter { it.id != PoolTable.EIGHT }), potted = listOf(PoolTable.EIGHT), cuePotted = false)
        )
        assertEquals(PoolResolution.SHOOTER, win.winner)
        assertEquals(PoolNote.WIN, win.note)
    }

    @Test
    fun shotPayloadSurvivesTheWire() {
        val rack = PoolTable.rack()
        val resolution = PoolRules.resolve(
            PoolTable.encode(rack), PoolTable.OPEN, PoolTable.OPEN,
            PoolShot(listOf(rack.filter { it.id != 2 }), potted = listOf(2), cuePotted = false)
        )
        val encoded = PoolWire.encode(resolution)
        assertTrue("payload fits one chat message", encoded.length < 700)
        val decoded = PoolWire.decode(encoded)
        assertNotNull(decoded)
        assertEquals(resolution.table, decoded!!.table)
        assertEquals(resolution.shooterGroup, decoded.shooterGroup)
        assertEquals(resolution.opponentGroup, decoded.opponentGroup)
        assertEquals(resolution.note, decoded.note)
        assertNull(PoolWire.decode("not-base64-@@"))
    }
}
