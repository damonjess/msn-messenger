package com.limpe.tengerium

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limpe.tengerium.ui.games.GameKind
import com.limpe.tengerium.ui.games.GameManager
import com.limpe.tengerium.ui.games.GameProtocol
import com.limpe.tengerium.ui.games.Rps
import com.limpe.tengerium.ui.games.TicTacToe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Shared activities: the game rules, the signalling protocol, and a full session played
 * through [GameManager] with a stubbed transport.
 */
@RunWith(AndroidJUnit4::class)
class GameRulesTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val sent = mutableListOf<Pair<String, String>>()
    private val me = "me@escargot.chat"
    private val peer = "friend@escargot.chat"

    @Before
    fun setUp() {
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync {
            GameManager.initialize(context)
            GameManager.transport = { target, text ->
                synchronized(sent) { sent.add(target to text) }
            }
            if (GameManager.state.value !is GameManager.State.Idle) GameManager.leave()
        }
        instrumentation.waitForIdleSync()
        synchronized(sent) { sent.clear() }
    }

    // ---- rules -------------------------------------------------------------------------

    @Test
    fun ticTacToeFindsLinesDrawsAndIllegalMoves() {
        assertEquals('X', TicTacToe.winner("XXX------"))
        assertEquals('O', TicTacToe.winner("O--O--O--"))
        assertEquals('X', TicTacToe.winner("X---X---X"))
        assertNull(TicTacToe.winner("XOXOXO---"))

        val draw = "XOXXOOOXX"
        assertTrue(TicTacToe.isDraw(draw))
        assertNull(TicTacToe.winner(draw))

        val board = TicTacToe.move(TicTacToe.emptyBoard(), 4, 'X')
        assertNotNull(board)
        assertEquals('X', board!![4])
        assertNull("an occupied cell cannot be played", TicTacToe.move(board, 4, 'O'))
        assertNull("an off-board cell cannot be played", TicTacToe.move(board, 9, 'O'))
        assertNull("only X and O exist", TicTacToe.move(board, 0, 'Y'))
    }

    @Test
    fun rockPaperScissorsUsesTheUsualRules() {
        assertEquals(1, Rps.ROCK.outcomeAgainst(Rps.SCISSORS))
        assertEquals(1, Rps.SCISSORS.outcomeAgainst(Rps.PAPER))
        assertEquals(1, Rps.PAPER.outcomeAgainst(Rps.ROCK))
        assertEquals(-1, Rps.ROCK.outcomeAgainst(Rps.PAPER))
        assertEquals(0, Rps.PAPER.outcomeAgainst(Rps.PAPER))
    }

    @Test
    fun protocolRoundTripsAndIgnoresPlainMessages() {
        val invite = GameProtocol.parse(GameProtocol.invite("abc1234567", GameKind.TIC_TAC_TOE))
        assertTrue(invite is GameProtocol.Signal.Invite)
        assertEquals(GameKind.TIC_TAC_TOE, (invite as GameProtocol.Signal.Invite).kind)

        val move = GameProtocol.parse(GameProtocol.move("abc1234567", "4"))
        assertEquals("4", (move as GameProtocol.Signal.Move).move)

        assertNull(GameProtocol.parse("hey, fancy a game?"))
        assertNull(GameProtocol.parse("[CALL:abc1234567:ACC]"))
        assertNull("ids are validated", GameProtocol.parse("[GAME:AB:INV:TTT]"))
    }

    // ---- a whole session ---------------------------------------------------------------

    @Test
    fun sessionFollowsTurnsAndEndsWhenTheOpponentWins() {
        instrumentation.runOnMainSync { GameManager.startGame(peer, GameKind.TIC_TAC_TOE) }
        val inviteState = GameManager.state.value as GameManager.State.Invited
        assertTrue("the inviter knows it started the activity", inviteState.outgoing)

        val inviteText = synchronized(sent) { sent.first { it.second.contains(":INV:") }.second }
        val gameId = (GameProtocol.parse(inviteText) as GameProtocol.Signal.Invite).gameId
        deliver(GameProtocol.accept(gameId))

        val playing = GameManager.state.value as GameManager.State.Playing
        assertEquals('X', playing.myMark)
        assertTrue("the inviter opens", playing.myTurn)

        // My move reaches the opponent.
        instrumentation.runOnMainSync { GameManager.playCell(4) }
        assertTrue(synchronized(sent) { sent.any { it.second == GameProtocol.move(gameId, "4") } })
        val afterMine = GameManager.state.value as GameManager.State.Playing
        assertEquals('X', afterMine.board[4])
        assertFalse("it is now their turn", afterMine.myTurn)

        // A move out of turn is ignored.
        instrumentation.runOnMainSync { assertFalse(GameManager.playCell(0)) }

        deliver(GameProtocol.move(gameId, "0"))
        assertTrue((GameManager.state.value as GameManager.State.Playing).myTurn)

        instrumentation.runOnMainSync { GameManager.playCell(1) }
        deliver(GameProtocol.move(gameId, "3"))
        instrumentation.runOnMainSync { GameManager.playCell(2) }
        deliver(GameProtocol.move(gameId, "6"))

        val over = GameManager.state.value as GameManager.State.Over
        assertEquals(GameManager.Outcome.LOSS, over.outcome)
        assertEquals(0, over.myScore)
        assertEquals(1, over.theirScore)

        // Playing again keeps the opponent and clears the board.
        instrumentation.runOnMainSync { GameManager.rematch() }
        val rematch = GameManager.state.value as GameManager.State.Playing
        assertTrue(TicTacToe.isEmpty(rematch.board))
        assertEquals(0, rematch.myScore)

        instrumentation.runOnMainSync { GameManager.leave() }
        instrumentation.waitForIdleSync()
        assertTrue(GameManager.state.value is GameManager.State.Finished)
    }

    @Test
    fun decliningAnInviteEndsTheActivity() {
        instrumentation.runOnMainSync { GameManager.startGame(peer, GameKind.ROCK_PAPER_SCISSORS) }
        val inviteText = synchronized(sent) { sent.first { it.second.contains(":INV:") }.second }
        val gameId = (GameProtocol.parse(inviteText) as GameProtocol.Signal.Invite).gameId

        assertEquals(GameKind.ROCK_PAPER_SCISSORS, (GameManager.state.value as GameManager.State.Invited).kind)
        deliver(GameProtocol.decline(gameId))

        val finished = GameManager.state.value as GameManager.State.Finished
        assertEquals(GameManager.EndReason.DECLINED, finished.reason)
    }

    private fun deliver(text: String) {
        instrumentation.runOnMainSync { assertTrue(GameManager.onSignal(peer, me, text)) }
        instrumentation.waitForIdleSync()
    }
}
