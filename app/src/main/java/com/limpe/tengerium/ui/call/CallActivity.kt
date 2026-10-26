package com.limpe.tengerium.ui.call

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.limpe.tengerium.R
import com.limpe.tengerium.databinding.ActivityCallBinding
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer

/**
 * Full-screen call UI: ringing, connecting and the active call with its controls.
 * All call state lives in [CallManager]; this activity only renders it.
 */
class CallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallBinding
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var localRenderer: SurfaceViewRenderer? = null
    private var pendingAction: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val action = pendingAction
        pendingAction = null
        if (grants.values.all { it }) {
            action?.invoke()
        } else {
            Toast.makeText(this, R.string.call_permission_needed, Toast.LENGTH_LONG).show()
            CallManager.decline()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CallManager.initialize(applicationContext)
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setUpRenderers()
        setUpControls()
        observeCall()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val state = CallManager.state.value
                when (state) {
                    is CallManager.State.Incoming -> CallManager.decline()
                    is CallManager.State.Idle -> finish()
                    else -> CallManager.hangUp()
                }
            }
        })

        if (CallManager.state.value is CallManager.State.Idle) finish()
    }

    private fun setUpRenderers() {
        val eglContext: EglBase.Context = CallManager.eglContext()
        remoteRenderer = binding.remoteVideo.also { it.init(eglContext, null) }
        localRenderer = binding.localVideo.also {
            it.init(eglContext, null)
            it.setMirror(true)
            it.setZOrderMediaOverlay(true)
        }
        CallManager.attachRenderers(remoteRenderer, localRenderer)
        (CallManager.state.value as? CallManager.State)?.let { updateVideoVisibility(it) }
    }

    private fun setUpControls() {
        binding.btnAccept.setOnClickListener {
            val media = (CallManager.state.value as? CallManager.State.Incoming)?.media ?: CallManager.Media.AUDIO
            withPermissions(media) {
                if (!CallManager.accept()) finish()
            }
        }
        binding.btnDecline.setOnClickListener {
            CallManager.decline()
            finish()
        }
        binding.btnEnd.setOnClickListener { CallManager.hangUp() }
        binding.btnMute.setOnClickListener { updateControls() }
        binding.btnSpeaker.setOnClickListener { CallManager.toggleSpeaker(); updateControls() }
        binding.btnCamera.setOnClickListener { CallManager.toggleCamera(); updateControls() }
        binding.btnFlip.setOnClickListener { CallManager.switchCamera() }
    }

    private fun observeCall() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CallManager.state.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: CallManager.State) {
        if (!::binding.isInitialized) return
        when (state) {
            is CallManager.State.Idle -> {
                finish()
                return
            }
            is CallManager.State.Incoming -> {
                binding.tvCallPeer.text = state.peer
                binding.tvCallStatus.text = getString(
                    if (state.media == CallManager.Media.VIDEO) R.string.call_incoming_video else R.string.call_incoming_voice
                )
                binding.layoutIncoming.visibility = View.VISIBLE
                binding.layoutActive.visibility = View.GONE
            }
            is CallManager.State.Outgoing -> {
                binding.tvCallPeer.text = state.peer
                binding.tvCallStatus.text = getString(R.string.call_outgoing_voice)
                binding.layoutIncoming.visibility = View.GONE
                binding.layoutActive.visibility = View.VISIBLE
            }
            is CallManager.State.Connecting -> {
                binding.tvCallPeer.text = state.peer
                binding.tvCallStatus.text = getString(R.string.call_connecting)
                binding.layoutIncoming.visibility = View.GONE
                binding.layoutActive.visibility = View.VISIBLE
            }
            is CallManager.State.Active -> {
                binding.tvCallPeer.text = state.peer
                binding.tvCallStatus.text = getString(R.string.call_active)
                binding.layoutIncoming.visibility = View.GONE
                binding.layoutActive.visibility = View.VISIBLE
            }
            is CallManager.State.Finished -> {
                binding.tvCallStatus.text = state.message
                binding.layoutIncoming.visibility = View.GONE
                binding.layoutActive.visibility = View.GONE
            }
        }
        updateVideoVisibility(state)
        updateControls()
    }

    private fun updateVideoVisibility(state: CallManager.State) {
        if (!::binding.isInitialized) return
        val videoCall = when (state) {
            is CallManager.State.Incoming -> state.media == CallManager.Media.VIDEO
            is CallManager.State.Outgoing -> state.media == CallManager.Media.VIDEO
            is CallManager.State.Connecting -> state.media == CallManager.Media.VIDEO
            is CallManager.State.Active -> state.media == CallManager.Media.VIDEO
            else -> false
        }
        val connected = state is CallManager.State.Connecting || state is CallManager.State.Active
        val showVideo = videoCall && connected
        binding.remoteVideo.visibility = if (showVideo) View.VISIBLE else View.GONE
        binding.localVideo.visibility = if (showVideo && CallManager.isCameraOn()) View.VISIBLE else View.GONE
        binding.tvCallStatus.setTextColor(if (showVideo) 0xFFFFFFFF.toInt() else 0xFFCFE0FF.toInt())
        binding.btnCamera.visibility = if (videoCall) View.VISIBLE else View.GONE
        binding.btnFlip.visibility = if (videoCall) View.VISIBLE else View.GONE
    }

    private fun updateControls() {
        binding.btnMute.text = getString(if (CallManager.isMuted()) R.string.call_unmute else R.string.call_mute)
        binding.btnSpeaker.alpha = if (CallManager.isSpeakerOn()) 1f else 0.6f
        binding.btnCamera.text = getString(
            if (CallManager.isCameraOn()) R.string.call_camera_off else R.string.call_camera_on
        )
        binding.localVideo.visibility = if (binding.remoteVideo.visibility == View.VISIBLE && CallManager.isCameraOn()) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun withPermissions(media: CallManager.Media, action: () -> Unit) {
        val required = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (media == CallManager.Media.VIDEO) required.add(Manifest.permission.CAMERA)
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            action()
        } else {
            pendingAction = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onDestroy() {
        CallManager.attachRenderers(null, null)
        remoteRenderer?.let { renderer ->
            runCatching { renderer.release() }
        }
        localRenderer?.let { renderer ->
            runCatching { renderer.release() }
        }
        remoteRenderer = null
        localRenderer = null
        super.onDestroy()
    }
}
