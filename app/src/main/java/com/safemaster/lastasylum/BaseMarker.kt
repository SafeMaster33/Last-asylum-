package com.safemaster.lastasylum

import kotlin.math.min
import kotlin.math.roundToInt

/** A positive sighting of a white, hollow, tapered overview pin immediately above a level. */
internal object BaseMarker {
    fun hasOverviewPin(badge: MapText, width: Int, height: Int, colorAt: (Int,Int)->Int): Boolean {
        if (width<100 || height<100 || TargetReading.level(badge)==null) return false
        val scale=min(width,height)/720f
        if (badge.x-20*scale<0 || badge.x+20*scale>=width-1 ||
            badge.y-54*scale<0 || badge.y>=height) return false
        fun white(x:Int,y:Int):Boolean {
            val c=colorAt((badge.x+x*scale).roundToInt(),(badge.y+y*scale).roundToInt())
            val r=c ushr 16 and 255; val g=c ushr 8 and 255; val b=c and 255
            return minOf(r,g,b)>=190 && maxOf(r,g,b)-minOf(r,g,b)<=40
        }
        // OCR reports either the digit box or the enclosing pill. Allow their centres to differ slightly.
        for (dx in -3..3 step 3) for (dy in -3..3 step 3) {
            if (white(dx,dy-35) || white(dx-2,dy-35) || white(dx+2,dy-35)) continue
            if (white(dx-10,dy-35) && white(dx+10,dy-35) && white(dx,dy-45) &&
                white(dx,dy-25) && white(dx,dy-19) &&
                !white(dx-10,dy-19) && !white(dx+10,dy-19)) return true
        }
        return false
    }
}
