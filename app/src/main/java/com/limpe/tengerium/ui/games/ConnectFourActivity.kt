package com.limpe.tengerium.ui.games

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.limpe.tengerium.R
import com.limpe.tengerium.databinding.ActivityConnectFourBinding
import kotlinx.coroutines.launch

/** Connect Four between two devices; all state lives in [GameManager]. */
class ConnectFourActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectFourBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GameManager.initialize(applicationContext)
        binding = ActivityConnectFourBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.fourBoard.onColumn = { column -> GameManager.dropDisc(column) }
        binding.btnAccept.setOnClickListener {
            if (!GameManager.accept()) finish()
        }
        binding.btnDecline.setOnClickListener {
            val invited = GameManager.state.value as? GameManager.State.Invited
            if (invited?.outgoing == true) GameManager.cancelInvite() else GameManager.decline()
            finish()
        }
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

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                GameManager.state.collect { state -> render(state) }
            }
        }
        if (GameManager.state.value is GameManager.State.Idle) finish()
    }

    private fun render(state: GameManager.State) {
        if (!::binding.isInitialized) return
        binding.tvGameKind.setText(R.string.game_connect_four)
        binding.btnAgain.visibility = View.GONE
        binding.layoutInvite.visibility = View.GONE
        binding.btnLeave.visibility = View.VISIBLE

        when (state) {
            is GameManager.State.Idle -> {
                finish()
                return
            }
            is GameManager.State.Invited -> {
                binding.tvGamePeer.text = state.peer
                binding.tvGameStatus.text = if (state.outgoing) {
                    getString(R.string.game_waiting_invite, state.peer)
                } else {
                    getString(R.string.game_invite_body, state.peer)
                }
                binding.tvGameScore.visibility = View.GONE
                binding.fourBoard.visibility = View.GONE
                binding.fourBoard.interactive = false
                binding.layoutInvite.visibility = View.VISIBLE
                binding.btnAccept.visibility = if (state.outgoing) View.GONE else View.VISIBLE
                binding.btnDecline.setText(if (state.outgoing) R.string.game_cancel else R.string.game_decline)
                binding.btnLeave.visibility = View.GONE
            }
            is GameManager.State.Four -> {
                binding.tvGamePeer.text = state.peer
                binding.fourBoard.visibility = View.VISIBLE
                binding.fourBoard.board = state.board
                binding.fourBoard.interactive = state.myTurn
                binding.tvGameStatus.text = if (state.myTurn) {
                    getString(R.string.game_your_turn)
                } else {
                    getString(R.string.game_their_turn, state.peer)
                }
                binding.tvGameScore.visibility = View.VISIBLE
                binding.tvGameScore.text = getString(R.string.game_score, state.myScore, state.theirScore)
            }
            is GameManager.State.Over -> {
                if (state.kind != GameKind.CONNECT_FOUR) {
                    finish()
                    return
                }
                binding.tvGamePeer.text = state.peer
                binding.fourBoard.interactive = false
                binding.tvGameStatus.text = when (state.outcome) {
                    GameManager.Outcome.WIN -> getString(R.string.game_you_won)
                    GameManager.Outcome.LOSS -> getString(R.string.game_they_won, state.peer)
                    GameManager.Outcome.DRAW -> getString(R.string.game_draw)
                }
                binding.tvGameScore.visibility = View.VISIBLE
                binding.tvGameScore.text = getString(R.string.game_score, state.myScore, state.theirScore)
                binding.btnAgain.visibility = View.VISIBLE
            }
            is GameManager.State.Finished -> {
                binding.tvGameStatus.text = when (state.reason) {
                    GameManager.EndReason.DECLINED -> getString(R.string.game_declined)
                    GameManager.EndReason.BUSY -> getString(R.string.game_peer_busy)
                    GameManager.EndReason.LEFT -> getString(R.string.game_left, state.peer ?: "")
                    GameManager.EndReason.NO_ANSWER -> getString(R.string.game_no_answer)
                    GameManager.EndReason.CANCELLED -> getString(R.string.game_cancelled)
                    GameManager.EndReason.FAILED -> getString(R.string.game_failed)
                }
                binding.fourBoard.interactive = false
                binding.btnLeave.visibility = View.VISIBLE
            }
            else -> finish()
        }
    }
}
