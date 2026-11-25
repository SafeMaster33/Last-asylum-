package com.safemaster.lastasylum

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.min
import kotlin.math.roundToInt

/** Worker-thread fallback. OCR receives an atlas of enlarged, contrasted digits with whitespace between rows. */
internal fun readLevelBadges(frame: Bitmap, layout: MapLayout, recognize: (Bitmap) -> List<MapText>): List<MapText> {
    val shrink = minOf(1f,720f/min(frame.width,frame.height),2400f/maxOf(frame.width,frame.height))
    val width = (frame.width*shrink).roundToInt().coerceAtLeast(1)
    val height = (frame.height*shrink).roundToInt().coerceAtLeast(1)
    val reduced = if (frame.width == width && frame.height == height) frame
                  else Bitmap.createScaledBitmap(frame,width,height,true)
    val pixels = IntArray(width*height)
    try { reduced.getPixels(pixels,0,width,0,0,width,height) }
    finally { if (reduced !== frame) reduced.recycle() }
    val result = mutableListOf<MapText>()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        textSize=34f
        color=Color.BLACK
    }
    for (chips in LevelBadges.locate(pixels,width,height,layout==MapLayout.OVERVIEW).chunked(12)) {
        val atlas = Bitmap.createBitmap(LevelBadges.atlasWidth,LevelBadges.rowHeight*2*chips.size,Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(atlas)
            canvas.drawColor(Color.WHITE)
            for ((index,chip) in chips.withIndex()) {
                val d = LevelBadges.digits(chip)
                val glyph = IntArray(d.w*d.h)
                for (variant in 0..1) {
                    for (y in 0 until d.h) for (x in 0 until d.w) {
                        val green = pixels[(d.top+y)*width+d.left+x] ushr 8 and 255
                        val gray = if (variant == 0) ((green-59)*3).coerceIn(0,255)
                                   else ((green-40)*2).coerceIn(0,255)
                        glyph[y*d.w+x] = Color.rgb(gray,gray,gray)
                    }
                    val row = (index*2+variant)*LevelBadges.rowHeight
                    canvas.drawText("Level",12f,row+47f,paint)
                    val bitmap = Bitmap.createBitmap(glyph,d.w,d.h,Bitmap.Config.ARGB_8888)
                    try {
                        val h = 44
                        val w = min((d.w*h.toFloat()/d.h).roundToInt(),LevelBadges.atlasWidth-LevelBadges.textStart-8)
                        canvas.drawBitmap(bitmap,null,Rect(LevelBadges.textStart,row+11,LevelBadges.textStart+w,row+11+h),paint)
                    } finally { bitmap.recycle() }
                }
            }
            result.addAll(LevelBadges.decode(chips,recognize(atlas)).map { t ->
                t.copy(left=(t.left*frame.width.toFloat()/width).roundToInt(),
                       top=(t.top*frame.height.toFloat()/height).roundToInt(),
                       right=(t.right*frame.width.toFloat()/width).roundToInt(),
                       bottom=(t.bottom*frame.height.toFloat()/height).roundToInt())
            })
        } finally { atlas.recycle() }
    }
    return result
}
