package com.safemaster.lastasylum

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class LevelBadgesTest {
    @Test fun detectsLevelShapesFromTwoScalesButIgnoresTimerAndEmptyPill() {
        // Anonymized 48×28 crops from clip 04 at 00:19 and 01:27. Last two rows
        // contain the event countdown and a copy of a pill with its digits removed.
        val source=RgbFixture.load("level-badge-crops")
        val width=720
        val height=1100
        val pixels=IntArray(width*height) { 0xff4d744b.toInt() }
        for(i in 0 until source.height/28) {
            for (row in 0 until 28) {
                val start=(i*28+row)*source.width
                source.pixels.copyInto(pixels,(200+i*72+row)*width+200,start,start+source.width)
            }
        }
        val chips=LevelBadges.locate(pixels,width,height)
        for(i in 0..6) {
            assertTrue("Missing video crop $i",chips.any {
                abs((it.left+it.right)/2-224)<=3 && abs((it.top+it.bottom)/2-(214+i*72))<=3
            })
        }
        assertEquals(7,chips.size)
    }

    @Test fun onlyMatchingNumericReadingsInBothRowsProduceALevel() {
        val chips=listOf(LevelBadges.Chip(200,465,236,485),LevelBadges.Chip(330,788,366,808))
        fun word(row:Int,text:String) = MapText(text,127,row*66+13,175,row*66+52,91f)
        val scan=listOf(word(0,"15"),word(1,"Level 15"),word(2,"9"),word(3,"8"))
        assertEquals(listOf(MapText("15",200,465,236,485)),LevelBadges.decode(chips,scan))
        assertTrue(LevelBadges.decode(chips,listOf(word(0,"15"),word(1,"Level 15").copy(confidence=20f))).isEmpty())
    }

    @Test fun conflictingReadingsAtOneBadgeDoNotRemoveNearbyDistinctTargets() {
        val local=MapText("15",202,300,238,320)
        val disagree=MapText("13",208,302,231,317)
        val neighbour=MapText("15",245,299,267,319)
        val nick=MapText("Буран",190,276,245,294)
        assertEquals(listOf(neighbour,nick),LevelBadges.merge(listOf(disagree,neighbour,nick),listOf(local)))
        assertEquals(listOf(neighbour,nick,local),LevelBadges.merge(listOf(disagree.copy(text="15"),neighbour,nick),listOf(local)))
    }
}
