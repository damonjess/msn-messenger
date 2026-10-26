package com.limpe.tengerium.ui.call

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
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
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Live voice and video calls between two Tengerium devices.
 *
 * The MSNP server carries no call signalling and the SDK has no voice/video API, so the
 * call is set up over the chat channel itself ([CallProtocol]) and the media flows
 * peer-to-peer through WebRTC. Calls work on the same network out of the box; a TURN
 * relay configured in the settings is needed when the two devices cannot reach each
 * other directly (typical on mobile data).
 */
object CallManager {

    private const val TAG = "CallManager"
    private const val RING_TIMEOUT_MS = 45_000L
    private const val CONNECT_TIMEOUT_MS = 30_000L
    private const val FAILURE_HOLD_MS = 2_500L
    private const val NOTIFICATION_CHANNEL = "incoming_calls"
    private const val NOTIFICATION_ID = 0x0CA11
    private const val CAPTURE_WIDTH = 480
    private const val CAPTURE_HEIGHT = 640
    private const val CAPTURE_FPS = 24

    enum class Media { AUDIO, VIDEO }

    sealed class State {
        object Idle : State()
        data class Outgoing(val peer: String, val media: Media) : State()
        data class Incoming(val peer: String, val media: Media) : State()
        data class Connecting(val peer: String, val media: Media) : State()
        data class Active(val peer: String, val media: Media) : State()
        data class Finished(val peer: String?, val message: String) : State()
    }

    private class Call(
        val id: String,
        val peer: String,
        val media: Media,
        val outgoing: Boolean
    ) {
        var peerConnection: PeerConnection? = null
        var audioTrack: AudioTrack? = null
        var videoTrack: VideoTrack? = null
        var videoSource: VideoSource? = null
        var capturer: CameraVideoCapturer? = null
        var captureHelper: SurfaceTextureHelper? = null
        var remoteVideoTrack: VideoTrack? = null
        var mediaReady = false
        var remoteDescriptionSet = false
        var muted = false
        var cameraOn = true
        var speakerOn = media == Media.VIDEO
        val pendingCandidates = mutableListOf<IceCandidateInfo>()

        fun isVideo() = media == Media.VIDEO
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Set by the repository: sends one signalling marker to [peer]. */
    var transport: ((String, String) -> Unit)? = null

    private lateinit var appContext: Context
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sdpAssembler = SdpAssembler()

    private var factory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null
    private var call: Call? = null
    private var timeoutTask: Runnable? = null
    private var disconnectTask: Runnable? = null
    private var ringtone: Ringtone? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private var remoteRenderer: SurfaceViewRenderer? = null
    private var localRenderer: SurfaceViewRenderer? = null

    fun initialize(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    fun eglContext(): EglBase.Context = ensureEgl().eglBaseContext

    fun attachRenderers(remote: SurfaceViewRenderer?, local: SurfaceViewRenderer?) {
        remoteRenderer?.let { previous -> runCatching { call?.remoteVideoTrack?.removeSink(previous) } }
        localRenderer?.let { previous -> runCatching { call?.videoTrack?.removeSink(previous) } }
        remoteRenderer = remote
        localRenderer = local
        val current = call ?: return
        remote?.let { renderer -> runCatching { current.remoteVideoTrack?.addSink(renderer) } }
        local?.let { renderer -> runCatching { current.videoTrack?.addSink(renderer) } }
    }

    // ---- placing and answering calls ---------------------------------------------------

    /** Returns false when a call is already running. */
    fun startCall(peer: String, media: Media): Boolean {
        if (call != null) return false
        val normalized = peer.lowercase().trim()
        val started = Call(CallProtocol.callId(), normalized, media, outgoing = true)
        call = started
        _state.value = State.Outgoing(normalized, media)
        send(started, CallProtocol.invite(started.id, media == Media.VIDEO))
        scheduleTimeout(started, RING_TIMEOUT_MS, R.string.call_no_answer)
        Log.d(TAG, "Outgoing ${media.name.lowercase()} call ${started.id} to $normalized")
        return true
    }

    fun accept(): Boolean {
        val current = call ?: return false
        if (current.outgoing) return false
        stopRinging()
        cancelTimeout()
        send(current, CallProtocol.accept(current.id))
        _state.value = State.Connecting(current.peer, current.media)
        if (!prepareMedia(current)) return false
        createPeerConnection(current)
        scheduleTimeout(current, CONNECT_TIMEOUT_MS, R.string.call_failed)
        return true
    }

    fun decline() {
        val current = call ?: return
        send(current, CallProtocol.decline(current.id))
        finish(current, messageRes = null)
    }

    fun hangUp() {
        val current = call ?: return
        send(current, CallProtocol.end(current.id))
        finish(current, messageRes = R.string.call_ended)
    }

    // ---- in-call controls --------------------------------------------------------------

    fun isMuted(): Boolean = call?.muted ?: false

    fun toggleMute(): Boolean {
        val current = call ?: return false
        current.muted = !current.muted
        current.audioTrack?.setEnabled(!current.muted)
        return current.muted
    }

    fun isSpeakerOn(): Boolean = call?.speakerOn ?: false

    fun toggleSpeaker(): Boolean {
        val current = call ?: return false
        current.speakerOn = !current.speakerOn
        applyAudioRoute(current)
        return current.speakerOn
    }

    fun isCameraOn(): Boolean = call?.cameraOn ?: true

    fun toggleCamera(): Boolean {
        val current = call ?: return false
        current.cameraOn = !current.cameraOn
        current.videoTrack?.setEnabled(current.cameraOn)
        try {
            if (current.cameraOn) current.capturer?.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)
            else current.capturer?.stopCapture()
        } catch (e: Exception) {
            Log.w(TAG, "Could not change camera state", e)
        }
        return current.cameraOn
    }

    fun switchCamera() {
        runCatching { call?.capturer?.switchCamera(null) }
    }

    // ---- signalling --------------------------------------------------------------------

    /**
     * Handles one incoming chat message. Returns true when it was a call marker, so the
     * caller can keep it out of the conversation and the notifications.
     */
    fun onSignal(sender: String, target: String, text: String): Boolean {
        val signal = CallProtocol.parse(text) ?: return false
        val peer = sender.lowercase().trim()
        mainHandler.post { handleSignal(peer, target.lowercase().trim(), signal) }
        return true
    }

    private fun handleSignal(peer: String, target: String, signal: CallProtocol.Signal) {
        when (signal) {
            is CallProtocol.Signal.Invite -> handleInvite(peer, target, signal)
            is CallProtocol.Signal.Accept -> {
                val current = call ?: return
                if (!current.outgoing || current.id != signal.callId) return
                stopRinging()
                cancelTimeout()
                _state.value = State.Connecting(current.peer, current.media)
                if (!prepareMedia(current)) return
                createPeerConnection(current)
                createOffer(current)
                scheduleTimeout(current, CONNECT_TIMEOUT_MS, R.string.call_failed)
            }
            is CallProtocol.Signal.Decline -> {
                val current = call ?: return
                if (current.id != signal.callId) return
                finish(current, R.string.call_declined)
            }
            is CallProtocol.Signal.Busy -> {
                val current = call ?: return
                if (current.id != signal.callId) return
                finish(current, R.string.call_peer_busy)
            }
            is CallProtocol.Signal.End -> {
                val current = call ?: return
                if (current.id != signal.callId) return
                finish(current, R.string.call_ended)
            }
            is CallProtocol.Signal.Sdp -> {
                val current = call ?: return
                if (current.id != signal.callId) return
                val description = sdpAssembler.accept(signal) ?: return
                if (current.outgoing) applyRemoteAnswer(current, description)
                else applyRemoteOffer(current, description)
            }
            is CallProtocol.Signal.Ice -> {
                val current = call ?: return
                if (current.id != signal.callId) return
                val candidate = CallProtocol.decodeCandidate(signal.payload) ?: return
                addRemoteCandidate(current, candidate)
            }
        }
    }

    private fun handleInvite(peer: String, target: String, invite: CallProtocol.Signal.Invite) {
        // Calls are one-to-one only; invitations that arrive inside a group are ignored.
        if (target != peer) return
        val running = call
        if (running != null) {
            sendRaw(peer, if (running.id == invite.callId) CallProtocol.accept(invite.callId) else CallProtocol.busy(invite.callId))
            return
        }
        val media = if (invite.video) Media.VIDEO else Media.AUDIO
        val incoming = Call(invite.callId, peer, media, outgoing = false)
        call = incoming
        _state.value = State.Incoming(peer, media)
        startRinging()
        scheduleTimeout(incoming, RING_TIMEOUT_MS, R.string.call_no_answer)
        showIncomingCall(peer, media)
        Log.d(TAG, "Incoming ${media.name.lowercase()} call ${incoming.id} from $peer")
    }

    // ---- WebRTC ------------------------------------------------------------------------

    private fun ensureEgl(): EglBase {
        eglBase?.let { return it }
        val created = EglBase.create()
        eglBase = created
        return created
    }

    private fun ensureFactory(): PeerConnectionFactory? {
        factory?.let { return it }
        val context = if (::appContext.isInitialized) appContext else return null
        return try {
            if (factory == null) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
                val egl = ensureEgl()
                factory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                    .createPeerConnectionFactory()
            }
            factory
        } catch (e: Exception) {
            Log.e(TAG, "Could not create the WebRTC factory", e)
            null
        }
    }

    private fun prepareMedia(current: Call): Boolean {
        if (current.mediaReady) return true
        val peerFactory = ensureFactory()
        if (peerFactory == null) {
            finish(current, R.string.call_failed)
            return false
        }
        return try {
            current.audioTrack = peerFactory.createAudioTrack(
                "audio0",
                peerFactory.createAudioSource(MediaConstraints())
            ).apply { setEnabled(!current.muted) }

            if (current.isVideo()) {
                val capturer = createCameraCapturer()
                if (capturer == null) {
                    finish(current, R.string.call_failed)
                    return false
                }
                val helper = SurfaceTextureHelper.create("CaptureThread", eglContext())
                val source = peerFactory.createVideoSource(capturer.isScreencast)
                capturer.initialize(helper, appContext, source.capturerObserver)
                capturer.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)
                current.capturer = capturer
                current.captureHelper = helper
                current.videoSource = source
                current.videoTrack = peerFactory.createVideoTrack("video0", source).apply {
                    setEnabled(current.cameraOn)
                }
                localRenderer?.let { renderer -> current.videoTrack?.addSink(renderer) }
            }
            current.mediaReady = true
            applyAudioRoute(current)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not prepare call media", e)
            finish(current, R.string.call_failed)
            false
        }
    }

    private fun createPeerConnection(current: Call) {
        val peerFactory = ensureFactory() ?: return
        if (current.peerConnection != null) return
        val configuration = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val created = try {
            peerFactory.createPeerConnection(configuration, MediaConstraints(), object : PeerConnection.Observer {
                override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(dataChannel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}

                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate ?: return
                    mainHandler.post {
                        if (call === current) {
                            send(
                                current,
                                CallProtocol.ice(
                                    current.id,
                                    IceCandidateInfo(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
                                )
                            )
                        }
                    }
                }

                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                    when (newState) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> mainHandler.post {
                            cancelDisconnectWatchdog()
                            markActive(current)
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> mainHandler.post {
                            scheduleDisconnectWatchdog(current)
                        }
                        PeerConnection.IceConnectionState.FAILED -> mainHandler.post {
                            finish(current, R.string.call_failed)
                        }
                        else -> {}
                    }
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                    if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                        mainHandler.post { markActive(current) }
                    } else if (newState == PeerConnection.PeerConnectionState.FAILED) {
                        mainHandler.post { finish(current, R.string.call_failed) }
                    }
                }

                override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                    val track = receiver?.track()
                    if (track is VideoTrack) {
                        current.remoteVideoTrack = track
                        mainHandler.post {
                            if (call === current) {
                                remoteRenderer?.let { renderer -> runCatching { track.addSink(renderer) } }
                            }
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Could not create the peer connection", e)
            finish(current, R.string.call_failed)
            return
        }
        if (created == null) {
            Log.e(TAG, "The peer connection could not be created")
            finish(current, R.string.call_failed)
            return
        }
        val connection = created
        current.peerConnection = connection
        current.audioTrack?.let { connection.addTrack(it, listOf("stream0")) }
        current.videoTrack?.let { connection.addTrack(it, listOf("stream0")) }
    }

    private fun createOffer(current: Call) {
        val connection = current.peerConnection ?: return
        connection.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) {
                    finish(current, R.string.call_failed)
                    return
                }
                connection.setLocalDescription(this, sdp)
            }

            override fun onSetSuccess() {
                val local = connection.localDescription ?: return
                CallProtocol.sdp(current.id, local.description).forEach { marker -> send(current, marker) }
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Offer failed: $error")
                finish(current, R.string.call_failed)
            }
        }, MediaConstraints())
    }

    private fun applyRemoteOffer(current: Call, description: String) {
        val connection = current.peerConnection ?: return
        connection.setRemoteDescription(object : SdpAdapter() {
            override fun onSetSuccess() {
                current.remoteDescriptionSet = true
                flushCandidates(current)
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", current.isVideo().toString()))
                }
                connection.createAnswer(object : SdpAdapter() {
                    override fun onCreateSuccess(sdp: SessionDescription?) {
                        if (sdp == null) {
                            finish(current, R.string.call_failed)
                            return
                        }
                        connection.setLocalDescription(this, sdp)
                    }

                    override fun onSetSuccess() {
                        val local = connection.localDescription ?: return
                        CallProtocol.sdp(current.id, local.description).forEach { marker -> send(current, marker) }
                    }

                    override fun onCreateFailure(error: String?) {
                        Log.e(TAG, "Answer failed: $error")
                        finish(current, R.string.call_failed)
                    }
                }, constraints)
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Could not apply the offer: $error")
                finish(current, R.string.call_failed)
            }
        }, SessionDescription(SessionDescription.Type.OFFER, description))
    }

    private fun applyRemoteAnswer(current: Call, description: String) {
        val connection = current.peerConnection ?: return
        connection.setRemoteDescription(object : SdpAdapter() {
            override fun onSetSuccess() {
                current.remoteDescriptionSet = true
                flushCandidates(current)
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Could not apply the answer: $error")
                finish(current, R.string.call_failed)
            }
        }, SessionDescription(SessionDescription.Type.ANSWER, description))
    }

    private fun addRemoteCandidate(current: Call, candidate: IceCandidateInfo) {
        if (!current.remoteDescriptionSet || current.peerConnection == null) {
            if (current.pendingCandidates.size < 200) current.pendingCandidates.add(candidate)
            return
        }
        runCatching {
            current.peerConnection?.addIceCandidate(
                IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
            )
        }.onFailure { Log.w(TAG, "Could not add remote candidate", it) }
    }

    private fun flushCandidates(current: Call) {
        val connection = current.peerConnection ?: return
        current.pendingCandidates.forEach { candidate ->
            runCatching { connection.addIceCandidate(IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)) }
        }
        current.pendingCandidates.clear()
    }

    private fun markActive(current: Call) {
        if (call !== current) return
        cancelTimeout()
        stopRinging()
        if (_state.value is State.Active) return
        _state.value = State.Active(current.peer, current.media)
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val camera2 = Camera2Enumerator(appContext)
        camera2.deviceNames.firstOrNull { camera2.isFrontFacing(it) }?.let { name ->
            runCatching { camera2.createCapturer(name, null) }.getOrNull()?.let { return it }
        }
        val camera1 = Camera1Enumerator(true)
        camera1.deviceNames.firstOrNull { camera1.isFrontFacing(it) }?.let { name ->
            runCatching { camera1.createCapturer(name, null) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun iceServers(): List<PeerConnection.IceServer> {
        val prefs = com.limpe.tengerium.data.security.SecurePrefs(appContext)
        val servers = mutableListOf<PeerConnection.IceServer>()
        prefs.callStunServer.takeIf { it.isNotBlank() }?.let { url ->
            runCatching { servers.add(PeerConnection.IceServer.builder(url).createIceServer()) }
        }
        val turn = prefs.callTurnServer.trim()
        if (turn.isNotEmpty()) {
            runCatching {
                servers.add(
                    PeerConnection.IceServer.builder(turn)
                        .setUsername(prefs.callTurnUsername)
                        .setPassword(prefs.callTurnPassword)
                        .createIceServer()
                )
            }
        }
        return servers
    }

    // ---- finishing ---------------------------------------------------------------------

    private fun scheduleTimeout(current: Call, delayMs: Long, messageRes: Int) {
        cancelTimeout()
        val task = Runnable {
            if (call === current) {
                if (!current.outgoing) send(current, CallProtocol.decline(current.id))
                finish(current, messageRes)
            }
        }
        timeoutTask = task
        mainHandler.postDelayed(task, delayMs)
    }

    private fun cancelTimeout() {
        timeoutTask?.let { mainHandler.removeCallbacks(it) }
        timeoutTask = null
    }

    /** Media can drop without an explicit hang-up; end the call instead of showing a dead line. */
    private fun scheduleDisconnectWatchdog(current: Call) {
        if (disconnectTask != null) return
        val task = Runnable {
            if (call === current) finish(current, R.string.call_failed)
        }
        disconnectTask = task
        mainHandler.postDelayed(task, 25_000L)
    }

    private fun cancelDisconnectWatchdog() {
        disconnectTask?.let { mainHandler.removeCallbacks(it) }
        disconnectTask = null
    }

    private fun finish(current: Call, messageRes: Int?) {
        if (call !== current) return
        cancelTimeout()
        cancelDisconnectWatchdog()
        stopRinging()
        sdpAssembler.reset()
        call = null
        releaseMedia(current)
        releaseAudioFocus()
        if (messageRes == null) {
            _state.value = State.Idle
        } else {
            val message = appContext.getString(messageRes)
            _state.value = State.Finished(current.peer, message)
            mainHandler.postDelayed({
                if (_state.value is State.Finished) _state.value = State.Idle
            }, FAILURE_HOLD_MS)
        }
        clearIncomingNotification()
        Log.d(TAG, "Call ${current.id} ended")
    }

    private fun releaseMedia(current: Call) {
        remoteRenderer?.let { renderer -> runCatching { current.remoteVideoTrack?.removeSink(renderer) } }
        localRenderer?.let { renderer -> runCatching { current.videoTrack?.removeSink(renderer) } }
        runCatching { current.capturer?.stopCapture() }
        runCatching { current.capturer?.dispose() }
        runCatching { current.videoTrack?.dispose() }
        runCatching { current.videoSource?.dispose() }
        runCatching { current.captureHelper?.dispose() }
        runCatching { current.audioTrack?.dispose() }
        runCatching { current.peerConnection?.close() }
        runCatching { current.peerConnection?.dispose() }
        current.peerConnection = null
        current.audioTrack = null
        current.videoTrack = null
        current.videoSource = null
        current.capturer = null
        current.captureHelper = null
        current.remoteVideoTrack = null
        current.mediaReady = false
        applyAudioRoute(null)
    }

    // ---- audio routing -----------------------------------------------------------------

    private fun applyAudioRoute(current: Call?) {
        if (!::appContext.isInitialized) return
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (current == null) {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
                audioManager.mode = AudioManager.MODE_NORMAL
            } else {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = current.speakerOn
                requestAudioFocus(audioManager)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not apply the audio route", e)
        }
    }

    private fun requestAudioFocus(audioManager: AudioManager) {
        if (audioFocusRequest != null) return
        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setAcceptsDelayedFocusGain(false)
                    .build()
                audioManager.requestAudioFocus(request)
                audioFocusRequest = request
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not request audio focus", e)
        }
    }

    private fun releaseAudioFocus() {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(null)
            }
            audioFocusRequest = null
        }
        runCatching { audioManager?.mode = AudioManager.MODE_NORMAL }
    }

    // ---- ringing and notifications ------------------------------------------------------

    private fun startRinging() {
        stopRinging()
        runCatching {
            ringtone = RingtoneManager.getRingtone(appContext, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            ringtone?.play()
        }
    }

    private fun stopRinging() {
        runCatching { ringtone?.stop() }
        ringtone = null
    }

    private fun showIncomingCall(peer: String, media: Media) {
        if (AppForeground.visible) {
            val intent = Intent(appContext, CallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            runCatching { appContext.startActivity(intent) }
                .onFailure { postIncomingNotification(peer, media) }
        } else {
            postIncomingNotification(peer, media)
        }
    }

    private fun postIncomingNotification(peer: String, media: Media) {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL,
                    appContext.getString(R.string.call_notification_channel),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        val intent = Intent(appContext, CallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            appContext, NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ntficn)
            .setContentTitle(appContext.getString(R.string.call_incoming_notification, peer))
            .setContentText(
                appContext.getString(if (media == Media.VIDEO) R.string.call_incoming_video else R.string.call_incoming_voice)
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun clearIncomingNotification() {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }

    // ---- helpers -----------------------------------------------------------------------

    private fun send(current: Call, text: String) {
        sendRaw(current.peer, text)
    }

    private fun sendRaw(peer: String, text: String) {
        val target = peer.lowercase().trim()
        val sender = transport
        if (sender == null) {
            Log.w(TAG, "No signalling transport configured")
            return
        }
        runCatching { sender(target, text) }
            .onFailure { Log.w(TAG, "Could not send signalling marker", it) }
    }

    private abstract class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}
