package com.safemaster.lastasylum

import org.junit.Assert.*
import org.junit.Test

class BaseMarkerTest {
    // Five 48x64 map-only excerpts from the recording: two pins, wood, a skull and a field.
    private val icons=RgbFixture.load("base-marker-crops")
    private fun detect(index:Int, factor:Int=1):Boolean {
        val badge=MapText("15",116*factor,249*factor,130*factor,260*factor)
        return BaseMarker.hasOverviewPin(badge,720*factor,1600*factor) { x,y ->
            val px=x/factor-99
            val py=y/factor-200
            if(px in 0..47 && py in 0..63) icons.pixels[py*icons.width+index*48+px] else 0
        }
    }
    @Test fun recognizesTwoOverviewPinsAtNativeAndDoubleResolution() {
        assertTrue(detect(0)); assertTrue(detect(1)); assertTrue(detect(0,2))
    }
    @Test fun resourcesAndMobDoNotProveTheLevelBelongsToABase() {
        for(i in 2..4) assertFalse(detect(i))
    }
    @Test fun outsideScreenBoundsProducesNoPositiveEvidence() {
        assertFalse(BaseMarker.hasOverviewPin(MapText("15",2,5,10,13),720,1600) { _,_-> error("outside framebuffer") })
    }
}
