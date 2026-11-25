package com.safemaster.lastasylum

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Game level pills use a light buff background and centered dark digits at both map scales. */
internal object LevelBadges {
    data class Chip(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val w get() = right-left
        val h get() = bottom-top
    }

    private fun buff(rgb: Int): Boolean {
        val r = rgb ushr 16 and 255
        val g = rgb ushr 8 and 255
        val b = rgb and 255
        return r in 140..207 && r-g in 2..25 && g-b in 8..39
    }

    fun locate(pixels: IntArray, width: Int, height: Int, overview: Boolean = false): List<Chip> {
        require(pixels.size == width * height)
        val scale = min(width,height) / 720f
        if (scale <= 0) return emptyList()
        val step = (scale*2).roundToInt().coerceAtLeast(1)
        val cols = width/step
        val rows = height/step
        val mask = BooleanArray(cols*rows)
        val start = (rows*(if(overview) .03f else .105f)).toInt().coerceAtLeast(1)
        val endRow = (rows*(if(overview) .985f else .93f)).toInt().coerceAtMost(rows-1)
        for (y in start until endRow) {
            for (x in 1 until cols-1) {
                mask[y*cols+x] = buff(pixels[(y*step+step/2)*width + x*step+step/2])
            }
        }
        val queue = IntArray(mask.size)
        val chips = mutableListOf<Chip>()
        for (seed in mask.indices) {
            if (!mask[seed]) continue
            var minX = seed % cols
            var maxX = minX
            var minY = seed / cols
            var maxY = minY
            var read = 0
            var end = 1
            mask[seed] = false
            queue[0] = seed
            while (read < end) {
                val p = queue[read++]
                val x = p % cols
                val y = p / cols
                minX = minOf(minX,x); maxX = maxOf(maxX,x)
                minY = minOf(minY,y); maxY = maxOf(maxY,y)
                // The outer row and column of the mask are always clear.
                for (k in 0..3) {
                    val n = when(k) { 0 -> p-1; 1 -> p+1; 2 -> p-cols; else -> p+cols }
                    if (mask[n]) { mask[n]=false; queue[end++]=n }
                }
            }
            val chip = Chip(minX*step,minY*step,(maxX+1)*step,(maxY+1)*step)
            if (chip.w / scale !in 26f..46f || chip.h / scale !in 12f..25f ||
                chip.w.toFloat()/chip.h !in 1.40f..2.75f ||
                end*step*step < chip.w*chip.h*.44f) continue
            val region = digits(chip)
            var ink = 0
            for (y in region.top until region.bottom) for (x in region.left until region.right) {
                if ((pixels[y*width+x] ushr 8 and 255) < 106) ink++
            }
            if (ink.toFloat()/(region.w*region.h) in .06f.. .53f) chips.add(chip)
        }
        return chips
    }

    fun digits(c: Chip) = Chip(c.left+(c.w*.18f).roundToInt(),c.top+(c.h*.13f).roundToInt(),
                               c.right-(c.w*.18f).roundToInt(),c.bottom-(c.h*.13f).roundToInt())

    const val rowHeight = 66
    const val textStart = 118
    const val atlasWidth = 242
    /** Keep a level only if both gray contrast variants preserve the same readable digits. */
    fun decode(chips: List<Chip>, observations: List<MapText>): List<MapText> {
        val rows = Array(chips.size*2) { mutableSetOf<Int>() }
        for (o in observations) {
            if (o.top < 0 || o.bottom<=o.top || o.right<=textStart || o.right > atlasWidth) continue
            val row = (o.y/rowHeight).toInt()
            if (row !in rows.indices || o.top/rowHeight != row || (o.bottom-1)/rowHeight != row) continue
            if (o.confidence != null && o.confidence < 65f) continue
            TargetReading.level(o)?.let { rows[row].add(it) }
        }
        return chips.mapIndexedNotNull { index,c ->
            val level = rows[2*index].singleOrNull() ?: return@mapIndexedNotNull null
            if (rows[2*index+1].singleOrNull() != level) return@mapIndexedNotNull null
            MapText(level.toString(),c.left,c.top,c.right,c.bottom)
        }
    }

    fun merge(lines: List<MapText>, local: List<MapText>): List<MapText> {
        if (local.isEmpty()) return lines
        val occupied = mutableSetOf<MapText>()
        val verified = mutableListOf<MapText>()
        for (chip in local) {
            val textLevel = TargetReading.level(chip) ?: continue
            val atChip = lines.filter { t ->
                TargetReading.level(t) != null && abs(t.y-chip.y) < chip.h*.65f &&
                    abs(t.x-chip.x) < (chip.right-chip.left)*.53f
            }
            occupied.addAll(atChip)
            if (atChip.all { TargetReading.level(it) == textLevel }) verified.add(chip)
        }
        return lines.filterNot { it in occupied } + verified
    }
}
