package com.safemaster.lastasylum

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.floor
import kotlin.math.roundToInt

class VisualRegistrationTest {
    private val width = 360
    private val height = 720
    private val center = FocusPoint(width*.5f,height*.5f)
    private val overlay = MapText("Старт",210,112,335,173)
    private fun texture(x: Float,y: Float, seed: Int): Int {
        fun hash(a:Int,b:Int):Int {
            var h=a*73856093 xor b*19349669 xor seed
            h=(h xor (h ushr 13))*1274126177
            return (h ushr 23) and 255
        }
        fun noise(step:Int): Float {
            val ax=floor(x/step).toInt(); val ay=floor(y/step).toInt()
            val fx=x/step-ax; val fy=y/step-ay
            val top=hash(ax,ay)*(1f-fx)+hash(ax+1,ay)*fx
            val bottom=hash(ax,ay+1)*(1f-fx)+hash(ax+1,ay+1)*fx
            return top*(1f-fy)+bottom*fy
        }
        return (noise(17)*.65f+noise(8)*.35f).roundToInt()
    }
    private fun image(t:MapTransform=MapTransform(1f,0f,0f), seed:Int=1) =
        VisualRegistration.capture(width,height,overlay) { x,y ->
            // Same panel in both views, while game coordinates change.
            if (y<height*.12 || x>210 && x<335 && y>112 && y<173) texture(x.toFloat(),y.toFloat(),772)
            else texture((x-t.dx)/t.scale,(y-t.dy)/t.scale,seed)
        }
    private fun close(expected: MapTransform, actual: MapTransform?) {
        assertNotNull(actual)
        actual!!
        assertTrue("support ${actual.support}", actual.support >= 5 && actual.regions>=4)
        assertEquals(expected.scale,actual.scale,.035f)
        val want=expected.map(center); val got=actual.map(center)
        assertEquals(want.x,got.x,6f); assertEquals(want.y,got.y,6f)
    }
    @Test fun measuresPanFromMapPixelsWithStaticHud() {
        val t=MapTransform(1f,46f,-39f)
        close(t,VisualRegistration.estimate(image(),image(t)))
    }
    @Test fun unchangedViewHasPositiveIdentityEvidence() {
        close(MapTransform(1f,0f,0f),VisualRegistration.estimate(image(),image()))
    }
    @Test fun missingSceneAndUniformMapWithStaticOverlayHaveNoMeasurement() {
        assertNull(VisualRegistration.estimate(image(),image(seed=981),true))
        val blank=VisualRegistration.capture(width,height,overlay) { x,y ->
            if (x in 210..335 && y in 112..173) texture(x.toFloat(),y.toFloat(),403) else 70
        }
        assertNull(VisualRegistration.estimate(blank,blank))
    }
    @Test fun periodicPatternCannotEstablishWhichCopyMoved() {
        fun grid(dx:Int)=VisualRegistration.capture(width,height,overlay) { x,y ->
            if ((Math.floorDiv(x-dx,10)+y/15)%2==0) 30 else 185
        }
        assertNull(VisualRegistration.estimate(grid(0),grid(20),true))
    }
    @Test fun measuresZoomAndRestorationAboutAnOffCenterPivot() {
        val t=MapTransform(1.47f,-84f,-145f)
        val before=image(); val after=image(t)
        close(t,VisualRegistration.estimate(before,after,true))
        val inverse=MapTransform(1f/t.scale,-t.dx/t.scale,-t.dy/t.scale)
        close(inverse,VisualRegistration.estimate(after,before,true))
    }
}
