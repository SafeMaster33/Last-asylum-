package com.safemaster.lastasylum

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

internal data class ReturnAdjustment(val pan: FocusPoint? = null, val factor: Float = 1f)

/** Reciprocal label matches in distant positions can verify an intermediate view when texture is sparse. */
internal fun matchingLandmarks(old:List<FocusMark>,fresh:List<FocusMark>,width:Int,height:Int):Boolean {
    val unit=min(width,height)
    fun near(a:FocusMark,b:FocusMark)=a.label==b.label && hypot(a.x-b.x,a.y-b.y)<unit*.016f
    val matched=old.filter { a ->
        val b=fresh.singleOrNull { near(a,it) }
        b!=null && old.count { near(it,b) }==1
    }
    return matched.size>=3 && matched.size>=min(old.size,fresh.size)*.65f &&
        matched.distinctBy { it.label }.size>=2 && matched.any { a ->
            matched.any { b -> hypot(a.x-b.x,a.y-b.y)>unit*.33f }
        }
}

/** Registration maps the desired overview into the currently observed frame. */
internal fun returnAdjustment(view: MapTransform, pivot: FocusPoint, width: Int, height: Int, gain: Float): ReturnAdjustment? {
    if (!view.scale.isFinite() || !view.dx.isFinite() || !view.dy.isFinite() ||
        view.scale !in .77f..1.30f || gain !in .35f..3f) return null
    val displaced = view.map(pivot)
    val dx = (pivot.x - displaced.x) / gain
    val dy = (pivot.y - displaced.y) / gain
    if (abs(displaced.x - pivot.x) > width * .30f || abs(displaced.y - pivot.y) > height * .25f) return null
    if (abs(view.scale - 1f) > .018f) return ReturnAdjustment(factor = (1f / view.scale).coerceIn(.82f,1.22f))
    if (hypot(dx, dy) < 2f) return null
    val limit = min(1f, min(width * .13f / abs(dx), height * .10f / abs(dy)))
    return ReturnAdjustment(FocusPoint(dx * limit, dy * limit))
}
