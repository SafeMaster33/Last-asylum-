package com.safemaster.lastasylum

import org.junit.Assert.*
import org.junit.Test

class CameraReturnTest {
    private val center = FocusPoint(360f,780f)

    @Test fun correctsObservedResidualUsingMeasuredPanResponse() {
        val d = returnAdjustment(MapTransform(1f,42f,-36f),center,720,1600,1.5f)!!.pan!!
        assertEquals(-28f,d.x,.1f)
        assertEquals(24f,d.y,.1f)
    }
    @Test fun restoresScaleBeforeResidualTranslation() {
        val correction = returnAdjustment(MapTransform(1.1f,-36f,-78f),center,720,1600,1f)!!
        assertNull(correction.pan)
        assertEquals(1f/1.1f,correction.factor,.001f)
    }
    @Test fun doesNotSendBlindCorrectionForADifferentView() {
        assertNull(returnAdjustment(MapTransform(1f,600f,950f),center,720,1600,1f))
        assertNull(returnAdjustment(MapTransform(1f,0f,0f),center,720,1600,1f))
    }
    @Test fun similarColoursWithoutMatchingLandmarksCannotChainUndo() {
        val old=listOf(FocusMark("13",79f,505f),FocusMark("15",328f,922f),FocusMark("9",405f,730f))
        assertFalse(matchingLandmarks(old,old.map { it.copy(x=it.x+60f) },720,1600))
        assertFalse(matchingLandmarks(old,old.take(2),720,1600))
        assertTrue(matchingLandmarks(old,old.map { it.copy(x=it.x+2f,y=it.y-3f) },720,1600))
    }
    @Test fun detailedLevelNeedsCaptionDirectlyAboveItsOwnBadge() {
        val base=MapText("9",319,805,357,825)
        val caption=MapText("Doctor8ryX6M5I",275,780,404,799)
        assertTrue(TargetReading.isBaseCaption(base,caption,listOf(base),720,1600))
        assertFalse(TargetReading.isBaseCaption(base,caption.copy(text="4617/10000"),listOf(base),720,1600))
        assertFalse(TargetReading.isBaseCaption(base,caption.copy(left=379,right=508),listOf(base),720,1600))
    }
}
