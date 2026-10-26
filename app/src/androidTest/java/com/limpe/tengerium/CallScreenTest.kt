package com.limpe.tengerium

import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limpe.tengerium.ui.call.CallActivity
import com.limpe.tengerium.ui.call.CallManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opens the call screen for an outgoing call. This also proves the bundled WebRTC
 * library and its EGL context initialise on the device, which the video renderers need.
 */
@RunWith(AndroidJUnit4::class)
class CallScreenTest {

    @Test
    fun outgoingCallScreenStartsAndEnds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        CallManager.initialize(context)
        CallManager.transport = { _, _ -> }

        assertTrue(CallManager.startCall("peer@example.com", CallManager.Media.VIDEO))
        assertTrue(CallManager.state.value is CallManager.State.Outgoing)

        ActivityScenario.launch(CallActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val status = activity.findViewById<TextView>(R.id.tvCallStatus)
                assertEquals(activity.getString(R.string.call_outgoing_voice), status.text.toString())
            }
        }

        CallManager.hangUp()
        val state = CallManager.state.value
        assertTrue(
            "call should be finishing, was $state",
            state is CallManager.State.Finished || state is CallManager.State.Idle
        )
    }
}
