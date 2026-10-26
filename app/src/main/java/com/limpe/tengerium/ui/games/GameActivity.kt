package com.limpe.tengerium.ui.games

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.limpe.tengerium.R
import com.limpe.tengerium.databinding.ActivityGameBinding
import kotlinx.coroutines.launch

/**
 * The shared activity screen: an invite you can join, the tic-tac-toe board or the
 * rock-paper-scissors throws, and a rematch when the game is over.
 * All game state lives in [GameManager]; this activity only renders it.
 */
class GameActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGameBinding
    private var cells: List<android.widget.Button> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GameManager.initialize(applicationContext)
        binding = ActivityGameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cells = listOf(binding.cell0, binding.cell1, binding.cell2, binding.cell3, binding.cell4,
            binding.cell5, binding.cell6, binding.cell7, binding.cell8)

        cells.forEachIndexed { index, cell ->
            cell.setOnClickListener { GameManager.playCell(index) }
        }
        binding.btnAccept.setOnClickListener {
            if (!GameManager.accept()) finish()
        }
        binding.btnDecline.setOnClickListener {
            val invited = GameManager.state.value as? GameManager.State.Invited
            if (invited?.outgoing == true) GameManager.cancelInvite() else GameManager.decline()
            finish()
        }
        binding.btnRock.setOnClickListener { GameManager.choose(Rps.ROCK) }
        binding.btnPaper.setOnClickListener { GameManager.choose(Rps.PAPER) }
        binding.btnScissors.setOnClickListener { GameManager.choose(Rps.SCISSORS) }
        binding.btnNextRound.setOnClickListener { GameManager.nextRound() }
        binding.btnAgain.setOnClickListener { GameManager.rematch() }
        binding.btnLeave.setOnClickListener {
            GameManager.leave()
            finish()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (val state = GameManager.state.value) {
                    is GameManager.State.Invited ->
                        if (state.outgoing) GameManager.cancelInvite() else GameManager.decline()
                    is GameManager.State.Idle -> {}
                    else -> GameManager.leave()
                }
                finish()
            }
        })

        observeGame()
        if (GameManager.state.value is GameManager.State.Idle) finish()
    }

    private fun observeGame() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                GameManager.state.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: GameManager.State) {
        if (!::binding.isInitialized) return
        when (state) {
            is GameManager.State.Idle -> {
                finish()
                return
            }
            is GameManager.State.Invited -> {
                binding.tvGameKind.setText(GameManager.gameTitleRes(state.kind))
                binding.tvGamePeer.text = state.peer
                binding.tvGameStatus.text = if (state.outgoing) {
                    getString(R.string.game_waiting_invite, state.peer)
                } else {
                    getString(R.string.game_invite_body, state.peer)
                }
                binding.tvGameScore.visibility = View.GONE
                binding.gridBoard.visibility = View.GONE
                binding.layoutChoices.visibility = View.GONE
                binding.layoutInvite.visibility = View.VISIBLE
                binding.btnAccept.visibility = if (state.outgoing) View.GONE else View.VISIBLE
                binding.btnDecline.setText(if (state.outgoing) R.string.game_cancel else R.string.game_decline)
                binding.btnAgain.visibility = View.GONE
                binding.btnNextRound.visibility = View.GONE
                binding.btnLeave.visibility = View.GONE
            }
            is GameManager.State.Playing -> renderPlaying(state)
            is GameManager.State.Over -> {
                binding.tvGameKind.setText(GameManager.gameTitleRes(state.kind))
                binding.tvGamePeer.text = state.peer
                binding.tvGameStatus.text = when (state.outcome) {
                    GameManager.Outcome.WIN -> getString(R.string.game_you_won)
                    GameManager.Outcome.LOSS -> getString(R.string.game_they_won, state.peer)
                    GameManager.Outcome.DRAW -> getString(R.string.game_draw)
                }
                binding.tvGameScore.visibility = View.VISIBLE
                binding.tvGameScore.text = getString(R.string.game_score, state.myScore, state.theirScore)
                binding.layoutInvite.visibility = View.GONE
                binding.gridBoard.visibility = if (state.kind == GameKind.TIC_TAC_TOE) View.VISIBLE else View.GONE
                binding.layoutChoices.visibility = View.GONE
                binding.btnNextRound.visibility = View.GONE
                binding.btnAgain.visibility = View.VISIBLE
                binding.btnLeave.visibility = View.VISIBLE
                setBoardEnabled(false)
            }
            is GameManager.State.Four, is GameManager.State.Pool -> {
                // Those games render on their own screens.
                finish()
                return
            }
            is GameManager.State.Finished -> {
                binding.tvGameStatus.text = gameEndText(state)
                binding.layoutInvite.visibility = View.GONE
                binding.gridBoard.visibility = View.GONE
                binding.layoutChoices.visibility = View.GONE
                binding.btnAgain.visibility = View.GONE
                binding.btnNextRound.visibility = View.GONE
                binding.btnLeave.visibility = View.VISIBLE
            }
        }
    }

    private fun renderPlaying(state: GameManager.State.Playing) {
        binding.tvGameKind.setText(GameManager.gameTitleRes(state.kind))
        binding.tvGamePeer.text = state.peer
        binding.tvGameScore.visibility = View.VISIBLE
        binding.tvGameScore.text = getString(R.string.game_score, state.myScore, state.theirScore)
        binding.layoutInvite.visibility = View.GONE
        binding.btnAgain.visibility = View.GONE
        binding.btnLeave.visibility = View.VISIBLE

        when (state.kind) {
            GameKind.TIC_TAC_TOE -> {
                binding.gridBoard.visibility = View.VISIBLE
                binding.layoutChoices.visibility = View.GONE
                binding.btnNextRound.visibility = View.GONE
                binding.tvGameStatus.text = if (state.myTurn) {
                    getString(R.string.game_your_turn)
                } else {
                    getString(R.string.game_their_turn, state.peer)
                }
                drawBoard(state)
            }
            GameKind.ROCK_PAPER_SCISSORS -> {
                binding.gridBoard.visibility = View.GONE
                binding.layoutChoices.visibility = View.VISIBLE
                binding.btnNextRound.visibility = if (state.roundResolved) View.VISIBLE else View.GONE
                drawThrows(state)
            }
            else -> {
                // Connect Four and pool have their own screens.
                finish()
            }
        }
    }

    private fun drawBoard(state: GameManager.State.Playing) {
        val playable = state.myTurn && TicTacToe.winner(state.board) == null
        cells.forEachIndexed { index, cell ->
            val mark = state.board.getOrNull(index) ?: TicTacToe.EMPTY
            cell.text = if (mark == TicTacToe.EMPTY) "" else mark.toString()
            cell.setTextColor(
                when (mark) {
                    'X' -> 0xFFFFD54F.toInt()
                    'O' -> 0xFF4FC3F7.toInt()
                    else -> 0xFFFFFFFF.toInt()
                }
            )
            cell.isEnabled = playable && mark == TicTacToe.EMPTY
            cell.alpha = if (cell.isEnabled) 1f else 0.75f
        }
    }

    private fun drawThrows(state: GameManager.State.Playing) {
        val locked = state.myChoice != null || state.roundResolved
        binding.btnRock.isEnabled = !locked
        binding.btnPaper.isEnabled = !locked
        binding.btnScissors.isEnabled = !locked
        binding.btnRock.alpha = if (locked) 0.6f else 1f
        binding.btnPaper.alpha = if (locked) 0.6f else 1f
        binding.btnScissors.alpha = if (locked) 0.6f else 1f

        val mine = state.myChoice
        val theirs = state.theirChoice
        if (state.roundResolved && mine != null && theirs != null) {
            binding.tvChoices.visibility = View.VISIBLE
            binding.tvChoices.text = getString(
                R.string.game_throws,
                getString(throwName(mine)),
                getString(throwName(theirs))
            )
            binding.tvGameStatus.text = when (mine.outcomeAgainst(theirs)) {
                1 -> getString(R.string.game_round_won)
                -1 -> getString(R.string.game_round_lost, state.peer)
                else -> getString(R.string.game_round_draw)
            }
        } else {
            binding.tvChoices.visibility = View.GONE
            binding.tvGameStatus.text = when {
                mine == null -> getString(R.string.game_pick_throw)
                else -> getString(R.string.game_waiting_throw, state.peer)
            }
        }
    }

    private fun setBoardEnabled(enabled: Boolean) {
        cells.forEach { cell ->
            cell.isEnabled = enabled
            cell.alpha = 1f
        }
    }

    private fun throwName(choice: Rps): Int = when (choice) {
        Rps.ROCK -> R.string.game_rps_rock
        Rps.PAPER -> R.string.game_rps_paper
        Rps.SCISSORS -> R.string.game_rps_scissors
    }

    private fun gameEndText(state: GameManager.State.Finished): String = when (state.reason) {
        GameManager.EndReason.DECLINED -> getString(R.string.game_declined)
        GameManager.EndReason.BUSY -> getString(R.string.game_peer_busy)
        GameManager.EndReason.LEFT -> getString(R.string.game_left, state.peer ?: "")
        GameManager.EndReason.NO_ANSWER -> getString(R.string.game_no_answer)
        GameManager.EndReason.CANCELLED -> getString(R.string.game_cancelled)
        GameManager.EndReason.FAILED -> getString(R.string.game_failed)
    }
}
