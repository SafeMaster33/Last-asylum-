package com.safemaster.lastasylum

import org.junit.Assert.*
import org.junit.Test

class FocusRecoveryTest {
    private val edge = FocusMark("13",79f,505f)
    private val central = FocusMark("15",328f,922f)
    private fun centerTracker(vararg others: FocusMark) = FocusTracker(720,1600,central,listOf(central,*others))
    private fun zoom(center: FocusMark, scale: Float) =
        MapTransform(scale,center.x*(1-scale),center.y*(1-scale))
    private fun name(x: Int = 328, y: Int = 889, text:String = "[SBR] пивомен") =
        MapText(text,x-42,y-10,x+42,y+10)

    @Test fun edgeThirteenWithNoOcrNeighboursReacquiredFromMeasuredPan() {
        val tracker=FocusTracker(720,1600,edge,listOf(edge))
        val command=FocusPoint(108f,105f)
        val observed=FocusMark("13",206f,636f)
        assertTrue(tracker.afterPan(listOf(observed),command,MapTransform(1f,125f,128f)))
        assertEquals(FocusPoint(206f,636f),tracker.point)
        assertFalse(tracker.predicted)
    }

    @Test fun centralFifteenWithPrintedBadgeReacquiredFromMeasuredZoom() {
        val t=centerTracker()
        val moved=central.copy(y=950f)
        assertTrue(t.afterZoom(listOf(moved),t.point,1.42f,zoom(central,1.35f)))
        assertFalse(t.predicted)
        assertEquals(950f,t.point.y,.01f)
    }

    @Test fun stationaryOrWrongDirectionOrExcessivePanDoNotRebind() {
        listOf(MapTransform(1f,0f,0f),MapTransform(1f,-108f,-105f),MapTransform(1f,400f,400f)).forEach { camera ->
            val t=FocusTracker(720,1600,edge,listOf(edge))
            assertFalse(t.afterPan(listOf(edge),FocusPoint(108f,105f),camera))
        }
        val t=centerTracker()
        assertFalse(t.afterZoom(listOf(central),t.point,1.42f,MapTransform(1f,0f,0f)))
    }

    @Test fun duplicateOrConflictingLevelCannotStandInForTheZoomedTarget() {
        val near=central.copy(x=344f)
        val t=centerTracker()
        assertFalse(t.afterZoom(listOf(central,near),t.point,1.42f,zoom(central,1.4f)))
        val oldRival=centerTracker(near)
        assertFalse(oldRival.afterZoom(listOf(central.copy(x=348f)),oldRival.point,1.42f,zoom(central,1.4f)))
        val wrong=centerTracker()
        assertFalse(wrong.afterZoom(listOf(central.copy(label="16")),wrong.point,1.42f,zoom(central,1.4f)))
    }

    @Test fun hiddenBadgeAllowsExactFreshNameButCannotIssueAnotherCameraCommand() {
        val t=centerTracker()
        assertTrue(t.afterZoom(emptyList(),t.point,1.42f,zoom(central,1.31f)))
        assertTrue(t.predicted)
        assertNull(t.nextPan(forceCenter=true))
        assertFalse(t.afterZoom(emptyList(),t.point,1.42f,zoom(central,1.31f)))
        assertNull(t.predictedName(emptyList(),"пивомен",emptyList()))
        assertNull(t.predictedName(listOf(name(text="пивомек")),"пивомен",emptyList()))
        assertNull(t.predictedName(listOf(name(470)),"пивомен",emptyList()))
        assertEquals(name(),t.predictedName(listOf(name()),"пивомен",emptyList()))
    }

    @Test fun freshAndPreviouslySeenNeighboursCanVetoAnAmbiguousName() {
        val t=centerTracker(FocusMark("8",365f,870f))
        assertTrue(t.afterZoom(emptyList(),t.point,1.42f,zoom(central,1.4f)))
        assertNull(t.predictedName(listOf(name(360,865)),"пивомен",emptyList()))
        val fresh=MapText("9",320,906,340,925)
        assertNull(t.predictedName(listOf(name()),"пивомен",listOf(fresh)))
        assertNull(t.predictedName(listOf(name(),name(y=884)),"пивомен",emptyList()))
    }

    @Test fun restoringIsASeparateMapMeasurementEvenWhenOriginalBadgeIsMissing() {
        val t=centerTracker()
        assertTrue(t.restored(emptyList(),MapTransform(1f,3f,-4f)))
        assertFalse(t.restored(listOf(central),MapTransform(1f,0f,42f)))
        assertFalse(t.restored(listOf(central),zoom(central,1.22f)))
        assertFalse(t.restored(emptyList()))
    }

    @Test fun freshFrameRetriesSameCompletedPanFromOriginalAnchor() {
        val t=FocusTracker(720,1600,edge,listOf(edge))
        val command=FocusPoint(108f,105f)
        val measured=MapTransform(1f,125f,128f)
        assertFalse(t.afterPan(emptyList(),command,measured))
        assertEquals(FocusPoint(79f,505f),t.point)
        t.retryUnmatchedFrame()
        assertTrue(t.afterPan(listOf(edge.copy(x=204f,y=633f)),command,measured))
        val predicted=centerTracker()
        assertTrue(predicted.afterZoom(emptyList(),predicted.point,1.42f,zoom(central,1.31f)))
        predicted.retryUnmatchedFrame()
        assertNull(predicted.nextPan(forceCenter=true))
    }
}
