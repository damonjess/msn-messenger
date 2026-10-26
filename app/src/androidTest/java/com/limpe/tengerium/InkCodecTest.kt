package com.limpe.tengerium

import android.graphics.PointF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limpe.tengerium.ui.InkCodec
import com.limpe.tengerium.ui.InkDrawing
import com.limpe.tengerium.ui.InkStore
import com.limpe.tengerium.ui.InkStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin

/**
 * A drawing has to survive the trip inside a chat message: it must fit the MSNP
 * payload limit, and the marker alone must be enough for the receiving device to
 * render it (the old peer-to-peer image transfer is what left "(image not downloaded)").
 */
@RunWith(AndroidJUnit4::class)
class InkCodecTest {

    private fun handwriting(): InkDrawing {
        val strokes = ArrayList<InkStroke>()
        val colors = intArrayOf(0xFF111111.toInt(), 0xFF1565C0.toInt(), 0xFFC62828.toInt())
        for (strokeIndex in 0 until 3) {
            val points = ArrayList<PointF>()
            for (index in 0 until 600) {
                val progress = index / 599f
                points.add(
                    PointF(
                        120f + progress * 840f,
                        400f + strokeIndex * 90f + sin(progress * 18f + strokeIndex) * 70f
                    )
                )
            }
            strokes.add(InkStroke(colors[strokeIndex], points))
        }
        return InkDrawing(strokes, 1080, 800)
    }

    @Test
    fun drawingFitsInOneMessageAndRendersFromTheMarker() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val drawing = handwriting()

        val payload = InkCodec.encode(drawing, InkStore.MAX_INLINE_BYTES)
        assertNotNull("a normal doodle must fit in one message", payload)
        assertTrue(payload!!.size <= InkStore.MAX_INLINE_BYTES)

        val id = InkStore.newId()
        val marker = InkStore.marker(id, payload)
        assertTrue("marker must stay below the MSNP MSG payload limit", marker.length < 1300)
        assertEquals(id, InkStore.markerId(marker))

        val decoded = InkCodec.decode(payload)
        assertNotNull("payload must decode", decoded)
        assertEquals(drawing.width, decoded!!.width)
        assertEquals(drawing.height, decoded.height)
        assertEquals(drawing.strokes.size, decoded.strokes.size)

        val rendered = InkCodec.render(decoded)
        assertNotNull(rendered)
        val expectedHeight = 640 * drawing.height / drawing.width
        assertTrue(
            "aspect ratio must be kept (${rendered!!.height} vs $expectedHeight)",
            kotlin.math.abs(rendered.height - expectedHeight) <= 2
        )
        rendered.recycle()

        // Receiving device: the message alone has to produce a stored, renderable drawing.
        assertEquals(id, InkStore.processMarker(context, marker))
        val file = InkStore.file(context, id)
        assertNotNull("drawing must be stored from the message", file)
        assertNotNull("stored drawing must render in the chat", InkStore.bitmap(context, id, 500))
        file!!.delete()
    }
}
