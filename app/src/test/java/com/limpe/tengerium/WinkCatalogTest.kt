package com.limpe.tengerium

import com.limpe.tengerium.ui.WinkCatalog
import com.limpe.tengerium.util.WinkSynth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WinkCatalogTest {
    @Test
    fun dancingBoyIsSelectableAndRoundTripsAsAMessageMarker() {
        val dance = WinkCatalog.byId(WinkCatalog.DANCE)
        assertNotNull(dance)
        assertEquals(dance, WinkCatalog.fromMessage(WinkCatalog.marker(dance!!)))
        assertTrue(WinkSynth.supports(WinkCatalog.DANCE))
    }

    @Test
    fun videoReferencedScenesUseAnimationInsteadOfUnrelatedStickerArt() {
        assertNull(WinkCatalog.artworkRes(WinkCatalog.KISS))
        assertNull(WinkCatalog.artworkRes(WinkCatalog.LAUGH))
        assertNull(WinkCatalog.artworkRes(WinkCatalog.BALLOON))
    }
}
