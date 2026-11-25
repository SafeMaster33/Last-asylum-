package com.safemaster.lastasylum

import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

internal data class MapText(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int,
                           val confidence: Float? = null) {
    val x get() = (left + right) * .5f
    val y get() = (top + bottom) * .5f
    val h get() = (bottom - top).coerceAtLeast(1)
}

internal object TargetReading {
    private val badge = Regex("""(?:ур(?:овень)?|lv[l]?|level)?[. :]*([1-9][0-9]?)""", RegexOption.IGNORE_CASE)
    fun level(t: MapText) = badge.matchEntire(t.text.trim())?.groupValues?.get(1)?.toInt()
    fun russian(name: String) = name.any { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.CYRILLIC }
    private fun norm(text: String) = text.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
    fun reliableOther(line: MapText): Boolean {
        val cleaned = line.text.trim()
        if (line.confidence != null && line.confidence < 80f) return false
        return cleaned.count(Char::isLetter) >= 2 && cleaned.none { it == '\uFFFD' } &&
            norm(cleaned) !in setOf("крепость", "точкасбора", "отряд", "сбор")
    }
    fun matches(actual: String, expected: String): Boolean {
        val wanted = norm(expected)
        if (wanted.isEmpty()) return false
        val nick = actual.trim()
        return norm(nick) == wanted || (nick.startsWith("[") && ']' in nick && norm(nick.substringAfter(']')) == wanted)
    }
    fun isBaseCaption(badge: MapText, label: MapText, badges: List<MapText>, width: Int, height: Int): Boolean {
        val gap=badge.y-label.y
        return reliableOther(label) && gap >= label.h*.5f && gap <= max(label.h,badge.h)*2.9f &&
            abs(badge.x-label.x) <= max(badge.h,label.h)*1.1f && ownsName(badge,label,badges,width,height)
    }
    fun ownsName(badge: MapText, name: MapText, badges: List<MapText>, width: Int, height: Int): Boolean {
        fun distance(b: MapText): Double {
            val dx = abs(b.x - name.x)
            val dy = abs(b.y - name.y)
            val unit = max(b.h, name.h)
            if (dx > minOf(width * .14f, (name.right - name.left) * .48f + unit * 1.5f) ||
                dy > minOf(height * .07f, unit * 3.3f)) return Double.POSITIVE_INFINITY
            return hypot(dx.toDouble(), dy * 1.25)
        }
        val d = distance(badge)
        return d.isFinite() && badges.none { it != badge && distance(it) <= d + max(badge.h, name.h) * .5 }
    }
}
