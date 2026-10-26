package com.limpe.tengerium.ui.games

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.limpe.tengerium.R
import com.limpe.tengerium.util.AppForeground
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared activities: two contacts play a lightweight game while chatting.
 *
 * Like calls, this needs nothing from the MSNP server — the invites and moves travel through
 * the conversation as [GameProtocol] markers and are intercepted before they reach the chat.
 * Play is turn based, so both phones stay in step: every move is only accepted from the
 * current opponent, for a free cell, and on that opponent's turn.
 */
object GameManager {

    private const val TAG = "GameManager"
    private const val INVITE_TIMEOUT_MS = 60_000L
    private const val FINISHED_HOLD_MS = 2_500L
    private const val NOTIFICATION_CHANNEL = "activity_invites"
    private const val NOTIFICATION_ID = 0x0CAB1
    private const val RPS_TARGET = 2

    enum class Outcome { WIN, LOSS, DRAW }

    enum class EndReason { DECLINED, BUSY, LEFT, NO_ANSWER, FAILED, CANCELLED }

    sealed class State {
        object Idle : State()
        data class Invited(val peer: String, val kind: GameKind, val outgoing: Boolean) : State()
        data class Playing(
            val peer: String,
            val kind: GameKind,
            val board: String,
            val myMark: Char,
            val turn: Char,
            val myScore: Int,
            val theirScore: Int,
            val myChoice: Rps?,
            val theirChoice: Rps?,
            val roundResolved: Boolean
        ) : State() {
            val myTurn: Boolean get() = turn == myMark
        }

        data class Four(
            val peer: String,
            val board: String,
            val myMark: Char,
            val turn: Char,
            val myScore: Int,
            val theirScore: Int
        ) : State() {
            val myTurn: Boolean get() = turn == myMark
        }

        data class Pool(
            val peer: String,
            val table: String,
            val myTurn: Boolean,
            val myGroup: Int,
            val theirGroup: Int,
            val myScore: Int,
            val theirScore: Int,
            val note: PoolNote?,
            val noteWasMine: Boolean
        ) : State()

        data class Over(
            val peer: String,
            val kind: GameKind,
            val myScore: Int,
            val theirScore: Int,
            val outcome: Outcome
        ) : State()

        data class Finished(val peer: String?, val reason: EndReason) : State()
    }

    private class Session(
        val id: String,
        val peer: String,
        val kind: GameKind,
        val outgoing: Boolean
    ) {
        var myMark: Char = 'X'
        var board: String = TicTacToe.emptyBoard()
        var turn: Char = 'X'
        var myScore = 0
        var theirScore = 0
        var myChoice: Rps? = null
        var theirChoice: Rps? = null
        var roundResolved = false
        var gamesPlayed = 0
        var active = false

        var fourBoard: String = ConnectFour.emptyBoard()
        var poolTable: String = PoolTable.encode(PoolTable.rack())
        var poolMyGroup: Int = PoolTable.OPEN
        var poolTheirGroup: Int = PoolTable.OPEN
        var poolMyTurn = true
        var poolNote: PoolNote? = null
        var poolNoteWasMine = true

        fun opponentMark(): Char = when (myMark) {
            'X' -> 'O'
            'O' -> 'X'
            'R' -> 'Y'
            else -> 'R'
        }

        fun starter(): Char = if (gamesPlayed % 2 == 0) 'X' else 'O'
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Set by the repository: sends one signalling marker to [peer]. */
    var transport: ((String, String) -> Unit)? = null

    private lateinit var appContext: Context
    private val mainHandler = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private var timeoutTask: Runnable? = null

    fun initialize(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    // ---- starting and joining ----------------------------------------------------------

    /** Returns false when an activity is already running. */
    fun startGame(peer: String, kind: GameKind): Boolean {
        if (session != null) return false
        val normalized = peer.lowercase().trim()
        if (!normalized.contains('@')) return false
        val started = Session(GameProtocol.gameId(), normalized, kind, outgoing = true)
        session = started
        _state.value = State.Invited(normalized, kind, outgoing = true)
        send(started, GameProtocol.invite(started.id, kind))
        scheduleTimeout(started)
        Log.d(TAG, "Invited $normalized to ${kind.code} (${started.id})")
        return true
    }

    fun accept(): Boolean {
        val current = session ?: return false
        if (current.outgoing || current.active) return false
        cancelTimeout()
        clearInviteNotification()
        send(current, GameProtocol.accept(current.id))
        beginRound(current)
        return true
    }

    fun decline() {
        val current = session ?: return
        cancelTimeout()
        send(current, GameProtocol.decline(current.id))
        finish(current, EndReason.DECLINED)
    }

    /** Withdraws an invitation that has not been joined yet. */
    fun cancelInvite() {
        val current = session ?: return
        cancelTimeout()
        send(current, GameProtocol.cancel(current.id))
        finish(current, EndReason.CANCELLED)
    }

    fun leave() {
        val current = session ?: return
        send(current, GameProtocol.end(current.id))
        finish(current, EndReason.LEFT)
    }

    // ---- moves -------------------------------------------------------------------------

    /** Tic-tac-toe: plays cell 0..8. */
    fun playCell(index: Int): Boolean {
        val current = session ?: return false
        if (!current.active || current.kind != GameKind.TIC_TAC_TOE) return false
        if (current.turn != current.myMark) return false
        val next = TicTacToe.move(current.board, index, current.myMark) ?: return false
        send(current, GameProtocol.move(current.id, index.toString()))
        current.board = next
        afterTicTacToeMove(current)
        return true
    }

    /** Rock-paper-scissors: locks in this round's throw. */
    fun choose(choice: Rps): Boolean {
        val current = session ?: return false
        if (!current.active || current.kind != GameKind.ROCK_PAPER_SCISSORS) return false
        if (current.myChoice != null || current.roundResolved) return false
        current.myChoice = choice
        send(current, GameProtocol.move(current.id, choice.code))
        resolveRpsIfReady(current)
        return true
    }

    /** Connect Four: drops a disc into a column. */
    fun dropDisc(column: Int): Boolean {
        val current = session ?: return false
        if (!current.active || current.kind != GameKind.CONNECT_FOUR) return false
        if (current.turn != current.myMark) return false
        val next = ConnectFour.drop(current.fourBoard, column, current.myMark) ?: return false
        send(current, GameProtocol.move(current.id, column.toString()))
        current.fourBoard = next
        afterConnectFourMove(current)
        return true
    }

    /**
     * Pool: takes a shot and returns the frames to play on this screen. The rules are worked
     * out once, here, and the resulting table is what the opponent receives.
     */
    fun takeShot(angleDegrees: Float, power: Float): List<List<PoolBall>>? {
        val current = session ?: return null
        if (!current.active || current.kind != GameKind.POOL || !current.poolMyTurn) return null
        val shot = PoolTable.shoot(PoolTable.decode(current.poolTable), angleDegrees, power)
        val resolution = PoolRules.resolve(current.poolTable, current.poolMyGroup, current.poolTheirGroup, shot)
        send(current, GameProtocol.shot(current.id, PoolWire.encode(resolution)))
        applyPoolResolution(current, resolution, mine = true)
        return shot.frames
    }

    /** Rock-paper-scissors: clears the table for the next throw. */
    fun nextRound(): Boolean {
        val current = session ?: return false
        if (!current.active || current.kind != GameKind.ROCK_PAPER_SCISSORS) return false
        if (!current.roundResolved) return false
        send(current, GameProtocol.nextRound(current.id))
        clearRound(current)
        emitPlaying(current)
        return true
    }

    /** Starts a fresh game in the same session, keeping the opponent. */
    fun rematch(): Boolean {
        val current = session ?: return false
        if (_state.value !is State.Over) return false
        send(current, GameProtocol.rematch(current.id))
        resetGame(current)
        emitForKind(current)
        return true
    }

    // ---- signalling --------------------------------------------------------------------

    /**
     * Handles one incoming chat message. Returns true when it was an activity marker, so the
     * caller keeps it out of the conversation and its notifications.
     */
    fun onSignal(sender: String, target: String, text: String): Boolean {
        val signal = GameProtocol.parse(text) ?: return false
        val peer = sender.lowercase().trim()
        val room = target.lowercase().trim()
        mainHandler.post { handleSignal(peer, room, signal) }
        return true
    }

    private fun handleSignal(peer: String, room: String, signal: GameProtocol.Signal) {
        when (signal) {
            is GameProtocol.Signal.Invite -> handleInvite(peer, room, signal)
            is GameProtocol.Signal.Accept -> {
                val current = session ?: return
                if (!current.outgoing || current.id != signal.gameId || current.active) return
                cancelTimeout()
                beginRound(current)
            }
            is GameProtocol.Signal.Decline -> {
                val current = session ?: return
                if (current.id != signal.gameId) return
                finish(current, EndReason.DECLINED)
            }
            is GameProtocol.Signal.Cancel -> {
                val current = session ?: return
                if (current.id != signal.gameId || current.active) return
                finish(current, EndReason.CANCELLED)
            }
            is GameProtocol.Signal.Busy -> {
                val current = session ?: return
                if (current.id != signal.gameId) return
                finish(current, EndReason.BUSY)
            }
            is GameProtocol.Signal.Move -> {
                val current = session ?: return
                if (current.id != signal.gameId || !current.active) return
                when (current.kind) {
                    GameKind.TIC_TAC_TOE -> applyOpponentCell(current, signal.move)
                    GameKind.CONNECT_FOUR -> applyOpponentColumn(current, signal.move)
                    else -> applyOpponentThrow(current, signal.move)
                }
            }
            is GameProtocol.Signal.Shot -> {
                val current = session ?: return
                if (current.id != signal.gameId || !current.active) return
                if (current.kind != GameKind.POOL || current.poolMyTurn) return
                val resolution = PoolWire.decode(signal.payload) ?: return
                applyPoolResolution(current, resolution, mine = false)
            }
            is GameProtocol.Signal.Rematch -> {
                val current = session ?: return
                if (current.id != signal.gameId) return
                if (_state.value !is State.Over) return
                resetGame(current)
                emitForKind(current)
            }
            is GameProtocol.Signal.NextRound -> {
                val current = session ?: return
                if (current.id != signal.gameId || !current.active) return
                clearRound(current)
                emitPlaying(current)
            }
            is GameProtocol.Signal.End -> {
                val current = session ?: return
                if (current.id != signal.gameId) return
                finish(current, EndReason.LEFT)
            }
        }
    }

    private fun handleInvite(peer: String, room: String, invite: GameProtocol.Signal.Invite) {
        // Activities are one-to-one; an invite sent inside a group is ignored.
        if (room != peer) return
        val running = session
        if (running != null) {
            sendRaw(peer, if (running.id == invite.gameId) GameProtocol.accept(invite.gameId) else GameProtocol.busy(invite.gameId))
            return
        }
        val incoming = Session(invite.gameId, peer, invite.kind, outgoing = false)
        session = incoming
        _state.value = State.Invited(peer, invite.kind, outgoing = false)
        scheduleTimeout(incoming)
        showInvite(peer, invite.kind)
        Log.d(TAG, "Incoming ${invite.kind.code} invite from $peer (${incoming.id})")
    }

    // ---- game flow ---------------------------------------------------------------------

    private fun beginRound(current: Session) {
        current.active = true
        current.myMark = when {
            current.kind == GameKind.CONNECT_FOUR -> if (current.outgoing) 'R' else 'Y'
            else -> if (current.outgoing) 'X' else 'O'
        }
        current.board = TicTacToe.emptyBoard()
        current.fourBoard = ConnectFour.emptyBoard()
        current.poolTable = PoolTable.encode(PoolTable.rack())
        current.poolMyGroup = PoolTable.OPEN
        current.poolTheirGroup = PoolTable.OPEN
        current.poolNote = null
        current.myChoice = null
        current.theirChoice = null
        current.roundResolved = false
        when (current.kind) {
            GameKind.CONNECT_FOUR -> {
                current.turn = 'R'
                emitFour(current)
            }
            GameKind.POOL -> {
                current.poolMyTurn = current.outgoing
                emitPool(current)
            }
            else -> {
                current.turn = current.starter()
                emitPlaying(current)
            }
        }
    }

    private fun resetGame(current: Session) {
        current.active = true
        current.gamesPlayed += 1
        current.myScore = 0
        current.theirScore = 0
        current.board = TicTacToe.emptyBoard()
        current.fourBoard = ConnectFour.emptyBoard()
        current.poolTable = PoolTable.encode(PoolTable.rack())
        current.poolMyGroup = PoolTable.OPEN
        current.poolTheirGroup = PoolTable.OPEN
        current.poolNote = null
        current.myChoice = null
        current.theirChoice = null
        current.roundResolved = false
        when (current.kind) {
            GameKind.CONNECT_FOUR -> current.turn = 'R'
            GameKind.POOL -> current.poolMyTurn =
                if (current.gamesPlayed % 2 == 1) !current.outgoing else current.outgoing
            else -> current.turn = current.starter()
        }
    }

    private fun emitForKind(current: Session) {
        when (current.kind) {
            GameKind.CONNECT_FOUR -> emitFour(current)
            GameKind.POOL -> emitPool(current)
            else -> emitPlaying(current)
        }
    }

    private fun clearRound(current: Session) {
        current.myChoice = null
        current.theirChoice = null
        current.roundResolved = false
    }

    private fun applyOpponentCell(current: Session, move: String) {
        val index = move.toIntOrNull() ?: return
        if (current.turn == current.myMark) return
        val next = TicTacToe.move(current.board, index, current.opponentMark()) ?: return
        current.board = next
        afterTicTacToeMove(current)
    }

    private fun afterTicTacToeMove(current: Session) {
        val winner = TicTacToe.winner(current.board)
        when {
            winner == current.myMark -> {
                current.myScore += 1
                emitOver(current, Outcome.WIN)
            }
            winner != null -> {
                current.theirScore += 1
                emitOver(current, Outcome.LOSS)
            }
            TicTacToe.isDraw(current.board) -> emitOver(current, Outcome.DRAW)
            else -> {
                current.turn = if (current.turn == current.myMark) current.opponentMark() else current.myMark
                emitPlaying(current)
            }
        }
    }

    private fun applyOpponentColumn(current: Session, move: String) {
        val column = move.toIntOrNull() ?: return
        if (current.turn == current.myMark) return
        val next = ConnectFour.drop(current.fourBoard, column, current.opponentMark()) ?: return
        current.fourBoard = next
        afterConnectFourMove(current)
    }

    private fun afterConnectFourMove(current: Session) {
        val winner = ConnectFour.winner(current.fourBoard)
        when {
            winner == current.myMark -> {
                current.myScore += 1
                emitOver(current, Outcome.WIN)
            }
            winner != null -> {
                current.theirScore += 1
                emitOver(current, Outcome.LOSS)
            }
            ConnectFour.isDraw(current.fourBoard) -> emitOver(current, Outcome.DRAW)
            else -> {
                current.turn = if (current.turn == current.myMark) current.opponentMark() else current.myMark
                emitFour(current)
            }
        }
    }

    /** Applies a resolved shot locally and mirrors it when it came from the opponent. */
    private fun applyPoolResolution(current: Session, resolution: PoolResolution, mine: Boolean) {
        if (mine) {
            current.poolMyGroup = resolution.shooterGroup
            current.poolTheirGroup = resolution.opponentGroup
            current.poolMyTurn = resolution.shooterContinues
        } else {
            current.poolMyGroup = resolution.opponentGroup
            current.poolTheirGroup = resolution.shooterGroup
            current.poolMyTurn = !resolution.shooterContinues
        }
        current.poolTable = resolution.table
        current.poolNote = resolution.note
        current.poolNoteWasMine = mine

        when (resolution.winner) {
            PoolResolution.SHOOTER -> {
                if (mine) current.myScore += 1 else current.theirScore += 1
                emitOver(current, if (mine) Outcome.WIN else Outcome.LOSS)
            }
            PoolResolution.OPPONENT -> {
                if (mine) current.theirScore += 1 else current.myScore += 1
                emitOver(current, if (mine) Outcome.LOSS else Outcome.WIN)
            }
            else -> emitPool(current)
        }
    }

    private fun applyOpponentThrow(current: Session, move: String) {
        if (current.kind != GameKind.ROCK_PAPER_SCISSORS) return
        if (current.theirChoice != null || current.roundResolved) return
        val choice = Rps.fromCode(move) ?: return
        current.theirChoice = choice
        resolveRpsIfReady(current)
    }

    private fun resolveRpsIfReady(current: Session) {
        val mine = current.myChoice ?: return
        val theirs = current.theirChoice ?: return
        when (mine.outcomeAgainst(theirs)) {
            1 -> current.myScore += 1
            -1 -> current.theirScore += 1
            0 -> {}
        }
        current.roundResolved = true
        when {
            current.myScore >= RPS_TARGET -> emitOver(current, Outcome.WIN)
            current.theirScore >= RPS_TARGET -> emitOver(current, Outcome.LOSS)
            else -> emitPlaying(current)
        }
    }

    private fun emitPlaying(current: Session) {
        if (session !== current) return
        _state.value = State.Playing(
            peer = current.peer,
            kind = current.kind,
            board = current.board,
            myMark = current.myMark,
            turn = current.turn,
            myScore = current.myScore,
            theirScore = current.theirScore,
            myChoice = current.myChoice,
            theirChoice = current.theirChoice,
            roundResolved = current.roundResolved
        )
    }

    private fun emitFour(current: Session) {
        if (session !== current) return
        _state.value = State.Four(
            peer = current.peer,
            board = current.fourBoard,
            myMark = current.myMark,
            turn = current.turn,
            myScore = current.myScore,
            theirScore = current.theirScore
        )
    }

    private fun emitPool(current: Session) {
        if (session !== current) return
        _state.value = State.Pool(
            peer = current.peer,
            table = current.poolTable,
            myTurn = current.poolMyTurn,
            myGroup = current.poolMyGroup,
            theirGroup = current.poolTheirGroup,
            myScore = current.myScore,
            theirScore = current.theirScore,
            note = current.poolNote,
            noteWasMine = current.poolNoteWasMine
        )
    }

    private fun emitOver(current: Session, outcome: Outcome) {
        if (session !== current) return
        _state.value = State.Over(
            peer = current.peer,
            kind = current.kind,
            myScore = current.myScore,
            theirScore = current.theirScore,
            outcome = outcome
        )
    }

    // ---- finishing ---------------------------------------------------------------------

    private fun scheduleTimeout(current: Session) {
        cancelTimeout()
        val task = Runnable {
            if (session === current) {
                send(current, GameProtocol.end(current.id))
                finish(current, EndReason.NO_ANSWER)
            }
        }
        timeoutTask = task
        mainHandler.postDelayed(task, INVITE_TIMEOUT_MS)
    }

    private fun cancelTimeout() {
        timeoutTask?.let { mainHandler.removeCallbacks(it) }
        timeoutTask = null
    }

    private fun finish(current: Session, reason: EndReason) {
        if (session !== current) return
        cancelTimeout()
        clearInviteNotification()
        session = null
        _state.value = State.Finished(current.peer, reason)
        mainHandler.postDelayed({
            if (_state.value is State.Finished) _state.value = State.Idle
        }, FINISHED_HOLD_MS)
        Log.d(TAG, "Activity ${current.id} ended: ${reason.name}")
    }

    // ---- invites -----------------------------------------------------------------------

    private fun showInvite(peer: String, kind: GameKind) {
        if (AppForeground.visible) {
            val intent = Intent(appContext, activityFor(kind))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            runCatching { appContext.startActivity(intent) }
                .onFailure { postInviteNotification(peer, kind) }
        } else {
            postInviteNotification(peer, kind)
        }
    }

    private fun postInviteNotification(peer: String, kind: GameKind) {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL,
                    appContext.getString(R.string.game_notification_channel),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        val intent = Intent(appContext, activityFor(kind))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            appContext, NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ntficn)
            .setContentTitle(appContext.getString(R.string.game_invite_notification_title, peer))
            .setContentText(appContext.getString(gameTitleRes(kind)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun clearInviteNotification() {
        if (!::appContext.isInitialized) return
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }

    fun gameTitleRes(kind: GameKind): Int = when (kind) {
        GameKind.TIC_TAC_TOE -> R.string.game_tic_tac_toe
        GameKind.ROCK_PAPER_SCISSORS -> R.string.game_rps
        GameKind.CONNECT_FOUR -> R.string.game_connect_four
        GameKind.POOL -> R.string.game_pool
    }

    fun gameDescriptionRes(kind: GameKind): Int = when (kind) {
        GameKind.TIC_TAC_TOE -> R.string.game_tic_tac_toe_desc
        GameKind.ROCK_PAPER_SCISSORS -> R.string.game_rps_desc
        GameKind.CONNECT_FOUR -> R.string.game_connect_four_desc
        GameKind.POOL -> R.string.game_pool_desc
    }

    /** The screen each game is played on. */
    fun activityFor(kind: GameKind): Class<out android.app.Activity> = when (kind) {
        GameKind.CONNECT_FOUR -> ConnectFourActivity::class.java
        GameKind.POOL -> PoolActivity::class.java
        else -> GameActivity::class.java
    }

    // ---- helpers -----------------------------------------------------------------------

    private fun send(current: Session, text: String) {
        sendRaw(current.peer, text)
    }

    private fun sendRaw(peer: String, text: String) {
        val target = peer.lowercase().trim()
        val sender = transport
        if (sender == null) {
            Log.w(TAG, "No activity transport configured")
            return
        }
        runCatching { sender(target, text) }
            .onFailure { Log.w(TAG, "Could not send activity marker", it) }
    }
}
