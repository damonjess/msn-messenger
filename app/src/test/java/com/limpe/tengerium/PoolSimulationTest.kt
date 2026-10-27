package com.limpe.tengerium

import com.limpe.tengerium.ui.games.PoolTable
import org.junit.Assert.assertTrue
import org.junit.Test

class PoolSimulationTest {
    @Test
    fun fullPowerShotProducesBoundedPlaybackFrames() {
        val shot = PoolTable.shoot(PoolTable.rack(), angleDegrees = 0f, power = 1f)

        assertTrue("the shot should animate", shot.frames.size > 2)
        assertTrue("shot playback should stay under five seconds", shot.frames.size <= 302)
        assertTrue(
            "all final balls should remain on the table",
            shot.final.all { it.x in 0f..PoolTable.WIDTH && it.y in 0f..PoolTable.HEIGHT }
        )
    }
}
