package com.safemaster.lastasylum

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test

class FocusTrackerTest {
    private val anchor = FocusMark("12", 225f, 440f)
    private val marks = listOf(anchor, FocusMark("8",340f,670f),FocusMark("10",425f,830f),
        FocusMark("9",290f,1000f),FocusMark("14",410f,530f))
    private fun tracker() = FocusTracker(720,1600,anchor,marks)
    private fun pan(mark:FocusMark,d:FocusPoint) = mark.copy(x=mark.x+d.x,y=mark.y+d.y)
    private fun zoom(mark:FocusMark,p:FocusPoint,k:Float) = mark.copy(x=p.x+(mark.x-p.x)*k,y=p.y+(mark.y-p.y)*k)

    @Test fun clip03SquadPanelAndClip02TopBarMaskWholeLabels() {
        val t=tracker()
        assertFalse(t.readableBox(158,402,225,426))
        assertFalse(t.readableBox(210,153,242,178))
        assertFalse(t.readableBox(265,410,315,430))
        assertTrue(t.readableBox(291,741,410,765))
    }
    @Test fun alreadyCenteredBaseNeedsNoPan() {
        val a=FocusMark("12",360f,780f)
        assertNull(FocusTracker(720,1600,a,marks+a).nextPan())
    }
    @Test fun coveredTextCanTriggerAnotherShortPanBeforeZoom() {
        val inside = FocusMark("12", 325f, 760f)
        val t = FocusTracker(720,1600,inside,marks+inside)
        assertTrue(t.inReadingArea())
        assertNull(t.nextPan())
        val d = t.nextPan(forceCenter=true)!!
        assertTrue(d.x > 0 && d.y > 0)
        val center = t.readingCenter!!
        val atCenter = FocusMark("12",center.x,center.y)
        assertNull(FocusTracker(720,1600,atCenter,marks+atCenter).nextPan(forceCenter=true))
    }
    @Test fun denseOverviewKeepsAllSixtyBasesIncludingIntermittentBadges() {
        val grid = (0 until 60).map { FocusMark("12", 100f + it % 10 * 20, 500f + it / 10 * 35) }
        val first = grid.dropLast(1)
        val fresh = grid.drop(1).map { it.copy(x=it.x+2) }
        val merged = mergeOverviewCandidates(first,fresh,8f)
        assertEquals(60,merged.size)
        assertTrue(merged.containsAll(fresh))
        assertTrue(grid.first() in merged)
    }
    @Test fun overlayAtPreferredCenterChangesDestination() {
        val c=FocusMark("12",364f,784f)
        val t=FocusTracker(720,1600,c,marks+c,MapText("",318,757,417,800))
        assertFalse(t.inReadingArea())
        assertNotNull(t.nextPan())
        assertFalse(t.readableBox(350,766,380,803))
    }
    @Test fun goesToReadingAreaInShortVerifiedSteps() {
        val t=tracker()
        var current=marks
        var steps=0
        while(!t.inReadingArea() && steps<5) {
            val d=t.nextPan()!!
            assertTrue(abs(d.x)<=108.01f && abs(d.y)<=192.01f)
            current=current.map { pan(it,d) }
            assertTrue(t.afterPan(current,d))
            steps++
        }
        assertTrue(steps in 1..5)
        assertTrue(t.inReadingArea())
    }
    @Test fun unchangedFrameCannotProvePanOrZoom() {
        assertFalse(tracker().afterPan(marks,FocusPoint(90f,120f)))
        val t=tracker()
        assertFalse(t.afterZoom(marks,t.point,1.42f))
        assertNull(t.nextPan())
    }
    @Test fun noGuessWhenOnlyBadgeOrWrongDirectionVisible() {
        val d=FocusPoint(82f,132f)
        assertFalse(tracker().afterPan(listOf(pan(anchor,d)),d))
        assertFalse(tracker().afterPan(marks.map { pan(it,FocusPoint(-d.x,-d.y)) },d))
    }
    @Test fun zoomUsesFreshBadgeWithNeighbourhoodAndRestoresOriginalOnly() {
        val pivot=FocusPoint(anchor.x,anchor.y)
        val next=marks.map { pan(zoom(it,pivot,1.42f),FocusPoint(4f,13f)) }
        val t=tracker()
        assertTrue(t.afterZoom(next,pivot,1.42f))
        assertEquals(453f,t.point.y,.1f)
        assertFalse(t.restored(next)); assertTrue(t.restored(marks))
    }
    @Test fun ambiguousDuplicateNeighbourhoodRemainsUnconfirmed() {
        val d=FocusPoint(80f,125f)
        val a=marks.map { pan(it,d) }
        val b=a.map { pan(it,FocusPoint(18f,0f)) }
        assertFalse(tracker().afterPan(a+b,d))
    }
    @Test fun repeatedShortResponsesHaveFiniteBudget() {
        val t=tracker(); var current=marks
        repeat(5) {
            val d=t.nextPan()!!
            current=current.map { pan(it,FocusPoint(d.x*.16f,d.y*.16f)) }
            assertTrue(t.afterPan(current,d))
        }
        assertNull(t.nextPan())
    }
    @Test fun russianNameAndClanTagDoNotRequireLevel() {
        assertTrue(TargetReading.russian("1Буран1"))
        assertTrue(TargetReading.matches("[SBR] пивомен","Пивомен"))
        assertFalse(TargetReading.matches("Буранный","Буран"))
        assertFalse(TargetReading.russian("Doctor8"))
    }
    @Test fun sameNameCannotConfirmBothNearbyBadges() {
        val l=MapText("12",200,434,226,453)
        val r=MapText("19",244,434,270,453)
        val text=MapText("Буран",186,405,240,425)
        assertTrue(TargetReading.ownsName(l,text,listOf(l,r),720,1600))
        assertFalse(TargetReading.ownsName(r,text,listOf(l,r),720,1600))
        assertEquals(12,TargetReading.level(MapText("Ур. 12",0,0,20,12)))
    }
    @Test fun noisyRussianTextAndGamePanelCannotRejectCandidate() {
        val uncertain = MapText("Бурап",180,400,250,423,42f)
        assertFalse(TargetReading.reliableOther(uncertain))
        assertFalse(TargetReading.reliableOther(uncertain.copy(text="Точка сбора",confidence=96f)))
        assertTrue(TargetReading.reliableOther(uncertain.copy(text="пивомен",confidence=91f)))
    }
}
