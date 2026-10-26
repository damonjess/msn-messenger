package com.limpe.tengerium.ui.games

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.limpe.tengerium.R
import com.limpe.tengerium.databinding.ActivityGamesBinding
import com.limpe.tengerium.databinding.ItemGameBinding

/**
 * The games list opened from a conversation: pick a game and invite the person you are
 * chatting with. The activity is only a lobby — the game itself lives in its own screen.
 */
class GamesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PEER = "peer"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GameManager.initialize(applicationContext)
        val binding = ActivityGamesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val peer = intent.getStringExtra(EXTRA_PEER).orEmpty().lowercase().trim()
        binding.tvLobbySubtitle.text = if (peer.contains('@')) {
            getString(R.string.game_lobby_subtitle, peer)
        } else {
            getString(R.string.game_lobby_no_contact)
        }

        for (kind in GameKind.values()) {
            val row = ItemGameBinding.inflate(LayoutInflater.from(this), binding.gameList, false)
            row.tvGameName.setText(GameManager.gameTitleRes(kind))
            row.tvGameDescription.setText(GameManager.gameDescriptionRes(kind))
            row.btnPlay.isEnabled = peer.contains('@')
            row.btnPlay.setOnClickListener { play(peer, kind) }
            binding.gameList.addView(row.root)
        }
    }

    private fun play(peer: String, kind: GameKind) {
        if (!peer.contains('@')) {
            Toast.makeText(this, R.string.game_one_to_one, Toast.LENGTH_LONG).show()
            return
        }
        if (!GameManager.startGame(peer, kind)) {
            Toast.makeText(this, R.string.game_busy, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, GameManager.activityFor(kind)))
        finish()
    }
}
