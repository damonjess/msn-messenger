package com.limpe.tengerium.ui.games

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.limpe.tengerium.R
import com.limpe.tengerium.databinding.ActivityPoolBinding
import kotlinx.coroutines.launch

/** Eight-ball between two devices; all state lives in [GameManager]. */
class PoolActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPoolBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GameManager.initialize(applicationContext)
        binding = ActivityPoolBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnShoot.setOnClickListener { shoot() }
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

    private fun shoot() {
        val power = binding.powerBar.progress / 100f
        val frames = GameManager.takeShot(binding.poolTable.aimAngle, power) ?: return
        binding.btnShoot.isEnabled = false
        binding.poolTable.play(frames) {
            binding.btnShoot.isEnabled = false
            render(GameManager.state.value)
        }
        GameManager.state.value.let { render(it) }
    }

    private fun render(state: GameManager.State) {
        if (!::binding.isInitialized) return
        binding.tvGameKind.setText(R.string.game_pool)
        binding.btnAgain.visibility = View.GONE
        binding.layoutInvite.visibility = View.GONE
        binding.btnLeave.visibility = View.VISIBLE
        binding.btnShoot.visibility = View.VISIBLE

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
                binding.tvPoolGroups.visibility = View.GONE
                binding.poolTable.visibility = View.GONE
                binding.tvPoolNote.visibility = View.GONE
                binding.btnShoot.visibility = View.GONE
                binding.layoutPower.visibility = View.GONE
                binding.layoutInvite.visibility = View.VISIBLE
                binding.btnAccept.visibility = if (state.outgoing) View.GONE else View.VISIBLE
                binding.btnDecline.setText(if (state.outgoing) R.string.game_cancel else R.string.game_decline)
                binding.btnLeave.visibility = View.GONE
            }
            is GameManager.State.Pool -> {
                binding.tvGamePeer.text = state.peer
                binding.poolTable.visibility = View.VISIBLE
                binding.tvPoolGroups.visibility = View.VISIBLE
                binding.tvPoolNote.visibility = View.VISIBLE
                binding.layoutPower.visibility = View.VISIBLE
                binding.btnShoot.visibility = View.VISIBLE

                if (!binding.poolTable.isPlaying) {
                    binding.poolTable.balls = PoolTable.decode(state.table)
                }
                binding.poolTable.interactive = state.myTurn
                binding.btnShoot.isEnabled = state.myTurn && !binding.poolTable.isPlaying
                binding.powerBar.isEnabled = state.myTurn && !binding.poolTable.isPlaying

                binding.tvGameStatus.text = if (state.myTurn) {
                    getString(R.string.pool_your_turn)
                } else {
                    getString(R.string.pool_their_turn, state.peer)
                }
                binding.tvPoolGroups.text = groupsText(state)
                binding.tvPoolNote.text = noteText(state)
                binding.tvPoolNote.visibility = if (noteText(state).isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            is GameManager.State.Over -> {
                if (state.kind != GameKind.POOL) {
                    finish()
                    return
                }
                binding.tvGamePeer.text = state.peer
                if (!binding.poolTable.isPlaying) {
                    state.finalPoolTable?.let { binding.poolTable.balls = PoolTable.decode(it) }
                }
                binding.poolTable.interactive = false
                binding.btnShoot.isEnabled = false
                binding.powerBar.isEnabled = false
                binding.tvGameStatus.text = when (state.outcome) {
                    GameManager.Outcome.WIN -> getString(R.string.pool_win)
                    GameManager.Outcome.LOSS -> getString(R.string.pool_lose)
                    GameManager.Outcome.DRAW -> getString(R.string.game_draw)
                }
                binding.tvPoolGroups.text = getString(R.string.game_score, state.myScore, state.theirScore)
                binding.tvPoolNote.visibility = View.GONE
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
                binding.btnShoot.isEnabled = false
                binding.poolTable.interactive = false
                binding.btnLeave.visibility = View.VISIBLE
            }
            else -> finish()
        }
    }

    private fun groupsText(state: GameManager.State.Pool): String {
        if (state.myGroup == PoolTable.OPEN && state.theirGroup == PoolTable.OPEN) {
            return getString(R.string.pool_group_open)
        }
        return getString(
            R.string.pool_groups,
            groupName(state.myGroup),
            state.peer,
            groupName(state.theirGroup)
        )
    }

    private fun groupName(group: Int): String = getString(
        when (group) {
            PoolTable.SOLIDS -> R.string.pool_group_solids
            PoolTable.STRIPES -> R.string.pool_group_stripes
            else -> R.string.pool_group_open
        }
    )

    private fun noteText(state: GameManager.State.Pool): String? {
        val note = state.note ?: return null
        return if (state.noteWasMine) {
            when (note) {
                PoolNote.POT -> getString(R.string.pool_pot)
                PoolNote.FOUL -> getString(R.string.pool_foul)
                PoolNote.WIN -> getString(R.string.pool_win)
                PoolNote.LOSE -> getString(R.string.pool_lose)
                else -> getString(R.string.pool_miss)
            }
        } else {
            when (note) {
                PoolNote.POT -> getString(R.string.pool_their_pot, state.peer)
                PoolNote.FOUL -> getString(R.string.pool_their_foul, state.peer)
                PoolNote.WIN -> getString(R.string.pool_their_win, state.peer)
                PoolNote.LOSE -> getString(R.string.pool_their_lose, state.peer)
                PoolNote.TURN -> getString(R.string.pool_their_other, state.peer)
                else -> getString(R.string.pool_their_miss, state.peer)
            }
        }
    }
}
