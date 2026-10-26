package com.limpe.tengerium.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.limpe.tengerium.R
import java.io.File

/**
 * MSN Messenger's "voice clip": hold a button, talk for up to 15 seconds, send it as a file.
 * The clip is recorded as an AAC/M4A file in the cache directory.
 */
class VoiceClipDialog(
    context: Context,
    private val onSend: (File) -> Unit
) : Dialog(context) {

    private val handler = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var clipFile: File? = null
    private var startedAt = 0L
    private var recordedMs = 0L
    private var consumed = false

    private lateinit var timerText: TextView
    private lateinit var hintText: TextView
    private lateinit var recordButton: Button
    private lateinit var playButton: Button
    private lateinit var sendButton: Button

    private val ticker = object : Runnable {
        override fun run() {
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            updateClock(elapsed)
            if (elapsed >= MAX_MS) {
                stopRecording()
            } else {
                handler.postDelayed(this, 100)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_wink_dialog)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_msn_xp_titlebar)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        header.addView(TextView(context).apply {
            text = "Voice Clip"
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(context).apply {
            text = "msn"
            setTextColor(Color.parseColor("#DCE9FA"))
            textSize = 10f
        })
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
        }

        body.addView(TextView(context).apply {
            text = "Record a message (up to 15 seconds)"
            setTextColor(Color.parseColor("#1A1A1A"))
            textSize = 13f
        })

        timerText = TextView(context).apply {
            text = "0:00 / 0:15"
            setTextColor(Color.parseColor("#0A2A66"))
            textSize = 30f
            gravity = Gravity.CENTER
            setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(2))
        }
        body.addView(timerText)

        hintText = TextView(context).apply {
            text = "Tap Record, speak, then send."
            setTextColor(Color.parseColor("#555555"))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        }
        body.addView(hintText)

        recordButton = msnButton("●  Record").apply {
            setOnClickListener {
                if (recorder == null) startRecording() else stopRecording()
            }
        }
        body.addView(recordButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
            bottomMargin = dp(6)
        })

        val playbackRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        playButton = msnButton("▶  Play").apply {
            isEnabled = false
            alpha = 0.5f
            setOnClickListener { togglePlayback() }
        }
        playbackRow.addView(playButton, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
            marginEnd = dp(6)
        })
        sendButton = msnButton("Send").apply {
            isEnabled = false
            alpha = 0.5f
            setOnClickListener { sendClip() }
        }
        playbackRow.addView(sendButton, LinearLayout.LayoutParams(0, dp(38), 1f))
        body.addView(playbackRow)

        val cancel = TextView(context).apply {
            text = "Cancel"
            textSize = 12f
            setTextColor(Color.parseColor("#2C598A"))
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(2))
            setOnClickListener { dismiss() }
        }
        body.addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        setOnDismissListener { release() }
    }

    override fun onStart() {
        super.onStart()
        val width = (context.resources.displayMetrics.widthPixels * 0.92f).toInt()
        window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window?.setDimAmount(0.4f)
    }

    private fun msnButton(label: String) = Button(context).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        setTextColor(Color.parseColor("#002B66"))
        setBackgroundResource(R.drawable.bg_msn_button)
        minHeight = 0
        minWidth = 0
        stateListAnimator = null
        setPadding(0, 0, 0, 0)
    }

    /** Begin recording from the keyboard F2 shortcut. */
    fun beginRecording() {
        if (recorder == null) startRecording()
    }

    /** Stop a press-and-hold F2 recording, leaving the preview ready to play or send. */
    fun endRecording() {
        stopRecording()
    }

    private fun startRecording() {
        stopPlayback()
        clipFile?.delete()
        clipFile = null
        val file = File(context.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        val prepared = runCatching {
            val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            newRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(22_050)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        }
        if (prepared.isFailure) {
            runCatching { prepared.getOrNull()?.release() }
            file.delete()
            hintText.text = "Could not start the microphone"
            return
        }
        recorder = prepared.getOrNull()
        clipFile = file
        recordedMs = 0L
        startedAt = SystemClock.elapsedRealtime()
        timerText.setTextColor(Color.parseColor("#C62828"))
        recordButton.text = "■  Stop"
        hintText.text = "Recording… tap Stop when you are done."
        sendButton.isEnabled = false
        sendButton.alpha = 0.5f
        playButton.isEnabled = false
        playButton.alpha = 0.5f
        handler.post(ticker)
    }

    private fun stopRecording() {
        handler.removeCallbacks(ticker)
        val active = recorder ?: return
        recorder = null
        recordedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtMost(MAX_MS)
        val stopped = runCatching {
            active.stop()
        }
        active.release()
        updateClock(recordedMs)
        timerText.setTextColor(Color.parseColor("#0A2A66"))
        recordButton.text = "●  Record again"
        if (stopped.isFailure || recordedMs < 600L) {
            clipFile?.delete()
            clipFile = null
            hintText.text = "Too short — hold on a moment longer."
            playButton.isEnabled = false
            playButton.alpha = 0.5f
            sendButton.isEnabled = false
            sendButton.alpha = 0.5f
            return
        }
        hintText.text = "Ready to send."
        playButton.isEnabled = true
        playButton.alpha = 1f
        sendButton.isEnabled = true
        sendButton.alpha = 1f
    }

    private fun updateClock(elapsedMs: Long) {
        val totalSeconds = (elapsedMs / 1000).coerceAtMost(MAX_MS / 1000)
        timerText.text = String.format(java.util.Locale.US, "0:%02d / 0:%02d", totalSeconds, MAX_MS / 1000)
    }

    private fun togglePlayback() {
        if (player != null) {
            stopPlayback()
            return
        }
        val file = clipFile ?: return
        val newPlayer = MediaPlayer()
        val started = runCatching {
            newPlayer.setDataSource(file.absolutePath)
            newPlayer.prepare()
            newPlayer.start()
        }
        if (started.isFailure) {
            runCatching { newPlayer.release() }
            hintText.text = "Could not play that clip"
            return
        }
        player = newPlayer
        playButton.text = "■  Stop"
        newPlayer.setOnCompletionListener {
            stopPlayback()
        }
    }

    private fun stopPlayback() {
        val active = player ?: return
        player = null
        runCatching { active.stop() }
        active.release()
        playButton.text = "▶  Play"
    }

    private fun sendClip() {
        if (recorder != null) stopRecording()
        val file = clipFile ?: return
        if (!file.exists() || file.length() == 0L) return
        consumed = true
        stopPlayback()
        handler.removeCallbacks(ticker)
        onSend(file)
        dismiss()
    }

    private fun release() {
        handler.removeCallbacks(ticker)
        recorder?.let { active ->
            recorder = null
            runCatching { active.stop() }
            active.release()
        }
        stopPlayback()
        if (!consumed) clipFile?.delete()
        clipFile = null
    }

    private companion object {
        const val MAX_MS = 15_000L
    }
}
