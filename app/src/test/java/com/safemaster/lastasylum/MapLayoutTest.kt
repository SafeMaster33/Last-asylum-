package com.safemaster.lastasylum

import org.junit.Assert.*
import org.junit.Test

class MapLayoutTest {
    // RGB observations from s19 (overview) and s87 (detailed) of recording 04, at 720×1600.
    private val positions=listOf(558 to 251,567 to 253,558 to 256,567 to 259,564 to 262,
        561 to 298,567 to 301,558 to 305,568 to 307,564 to 311,557 to 347,566 to 346,
        557 to 354,571 to 353,563 to 359)
    private val overview=listOf(0x5d9236,0x8cca4d,0x4c8127,0x8ccc51,0x76b447,
        0x5096c9,0x65aee1,0x4f8dbe,0x5daae2,0x579cd3,0xda4ae7,0xd853ec,0xde47ee,0xdb4fee,0xd449ec)
    private val detailed=listOf(0xa09292,0xabab63,0x814f2a,0xd9be63,0xffe07b,
        0xc6c8d5,0x767c94,0xc0c3d2,0x354656,0x3d4a5d,0xafa752,0xaca555,0xa56f33,0xf5e8a2,0xcb7b32)
    private fun detect(colors:List<Int>,multiplier:Int=1):MapLayout {
        val pixels=positions.zip(colors).toMap()
        return MapLayout.detect(720*multiplier,1600*multiplier) { x,y ->
            pixels[x/multiplier to y/multiplier] ?: 0
        }
    }

    @Test fun overviewAndDetailedFramesUseDifferentMasksOnTheSameDevice() {
        assertEquals(MapLayout.OVERVIEW,detect(overview))
        assertEquals(MapLayout.OVERVIEW,detect(overview,2))
        assertEquals(MapLayout.DETAIL_OR_UNKNOWN,detect(detailed))
        assertEquals(MapLayout.DETAIL_OR_UNKNOWN,detect(overview.take(10)+detailed.drop(10)))
    }

    @Test fun overviewKeepsExposedPinsAndExcludesItsOwnHud() {
        val a=FocusMark("15",557f,587f)
        val tracker=FocusTracker(720,1600,a,listOf(a),MapText("",12,267,228,342),MapLayout.OVERVIEW)
        assertTrue(tracker.readableBox(70,498,88,512))
        assertTrue(tracker.readableBox(125,1117,147,1158))
        assertFalse(tracker.readableBox(551,341,571,359))
        assertFalse(tracker.readableBox(612,1490,670,1543))
        assertFalse(tracker.readableBox(25,278,70,313))
        tracker.layout=MapLayout.DETAIL_OR_UNKNOWN
        assertFalse(tracker.readableBox(70,498,88,512))
    }
}
