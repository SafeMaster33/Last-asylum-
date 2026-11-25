package com.safemaster.lastasylum

import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal data class FocusPoint(val x: Float, val y: Float)
internal data class FocusMark(val label: String, val x: Float, val y: Float)

internal fun cameraAligned(visual: MapTransform, width: Int, height: Int): Boolean {
    if (abs(visual.scale-1f) > .026f) return false
    // A pivot alone can stay put across a zoom. Check opposite corners of the map.
    return listOf(.16f,.84f).all { fraction ->
        val p = FocusPoint(width*fraction,height*fraction)
        val q = visual.map(p)
        hypot(q.x-p.x,q.y-p.y) <= min(width,height)*.019f
    }
}

/** Union of two unswiped views. Use fresh positions for unambiguous one-to-one matches. */
internal fun mergeOverviewCandidates(first: List<FocusMark>, fresh: List<FocusMark>, tolerance: Float): List<FocusMark> {
    fun near(a: FocusMark, b: FocusMark) =
        a.label == b.label && hypot(a.x - b.x, a.y - b.y) < tolerance

    val missing = first.filter { earlier ->
        val matching = fresh.singleOrNull { near(earlier, it) }
        matching == null || first.count { near(it, matching) } != 1
    }
    return (fresh + missing).distinct().sortedWith(
        compareBy<FocusMark> { it.label != "@name" }.thenBy { it.y }.thenBy { it.x }
    )
}

/**
 * Coordinates always refer to the captured display. The caller supplies map observations from
 * one frame, including neighbours with levels other than the requested level. A camera command
 * is a search hint; only agreement between observed landmarks can advance the tracked point.
 */
internal class FocusTracker(
    private val width: Int,
    private val height: Int,
    initial: FocusMark,
    marks: List<FocusMark>,
    var overlay: MapText? = null,
    var layout: MapLayout = MapLayout.DETAIL_OR_UNKNOWN
) {
    private val unit = min(width, height).toFloat()
    private val origin = initial
    private val initialMap = marks.toList()
    private val initialLayout = layout
    private var anchor = initial
    private var lastMap = initialMap
    private var lastLayout = layout
    private var pans = 0
    private var lost = false
    var predicted = false
        private set
    private var projectedNeighbours = emptyList<FocusMark>()
    val point: FocusPoint get() = FocusPoint(anchor.x, anchor.y)

    private data class Mask(val left: Float, val top: Float, val right: Float, val bottom: Float)
    // Fixed panels visible when the detailed game HUD opens. The service also masks its
    // draggable accessibility overlay at its actual position.
    private val detailedPanels = listOf(
        Mask(0f, 0f, 1f, .12f),         // resources and system bar
        Mask(0f, .12f, .385f, .37f),    // expanded squad panel
        Mask(.685f, .08f, 1f, .35f),    // event shortcuts
        Mask(0f, .45f, .105f, .55f),    // left drawer tab
        Mask(.925f, .34f, 1f, .43f),    // right drawer tab
        Mask(0f, .58f, .165f, .85f),    // movement and search controls
        Mask(.715f, .59f, 1f, .87f),    // star, alliance, mail, bag
        Mask(0f, .84f, 1f, 1f)         // quest and chat panels
    )
    // Top controls scale with the screen width. Side tabs and bottom switch are edge-anchored.
    private val verticalUnit = width / (720f * height)
    private val overviewPanels = listOf(
        Mask(0f, 0f, 1f, 45*verticalUnit),
        Mask(0f, 0f, .15f, 181*verticalUnit),
        Mask(.27f, 44*verticalUnit, .73f, 156*verticalUnit),
        Mask(.744f, 46*verticalUnit, 1f, 382*verticalUnit),
        Mask(.927f, .335f, 1f, .425f),
        Mask(0f, .46f, .055f, .54f),
        Mask(.94f, .786f, 1f, .838f),
        Mask(.75f, .91f, 1f, 1f)
    )

    fun readableBox(left: Int, top: Int, right: Int, bottom: Int) =
        readableBox(left,top,right,bottom,layout)

    private fun readableBox(left: Int, top: Int, right: Int, bottom: Int, scene: MapLayout): Boolean {
        if (right <= left || bottom <= top || left < width * .02f || right > width * .98f ||
            top < height * .02f || bottom > height * .98f) return false
        val floatingButton = overlay
        if (floatingButton != null && left < floatingButton.right && right > floatingButton.left &&
            top < floatingButton.bottom && bottom > floatingButton.top) return false
        val panels = if(scene==MapLayout.OVERVIEW) overviewPanels else detailedPanels
        return panels.none { left < it.right * width && right > it.left * width &&
            top < it.bottom * height && bottom > it.top * height }
    }

    private fun readable(mark: FocusMark, scene: MapLayout): Boolean =
        readableBox(mark.x.toInt()-1, mark.y.toInt()-1, mark.x.toInt()+1, mark.y.toInt()+1, scene)

    private fun hasRoom(x:Float, y:Float) = readableBox((x-width*.12f).toInt(),(y-height*.06f).toInt(),
        (x+width*.12f).toInt(),(y+height*.03f).toInt())
    val readingCenter: FocusPoint? get() = listOf(.505f to .49f,.60f to .55f,.43f to .58f,
        .52f to .63f,.55f to .41f).map { (x,y) -> FocusPoint(x*width,y*height) }
        .firstOrNull { hasRoom(it.x,it.y) }
    fun inReadingArea(): Boolean = !lost && anchor.x in width * .40f..width * .65f &&
        anchor.y in height * .39f..height * .65f && hasRoom(anchor.x,anchor.y)

    /** Limit both length and count, including unsuccessful attempts across successive zooms. */
    fun nextPan(forceCenter: Boolean = false): FocusPoint? {
        if (lost || predicted || pans >= 5 || (!forceCenter && inReadingArea())) return null
        val center = readingCenter ?: return null
        val dx = center.x - anchor.x
        val dy = center.y - anchor.y
        if (hypot(dx, dy) < unit * .018f) return null
        val fraction = min(1f, min(width * .15f / abs(dx), height * .12f / abs(dy)))
        pans++
        return FocusPoint(dx * fraction, dy * fraction)
    }

    fun afterPan(marks: List<FocusMark>, requested: FocusPoint, visual: MapTransform? = null): Boolean {
        val length = hypot(requested.x, requested.y)
        if (lost || predicted || length < 1f) return false
        if (visual != null) {
            val position = visual.map(point)
            val dx = position.x - anchor.x
            val dy = position.y - anchor.y
            val forward = (dx * requested.x + dy * requested.y) / length
            val sideways = abs(dx * requested.y - dy * requested.x) / length
            if (abs(visual.scale-1f) > .055f || forward !in max(3f,length*.1f)..length*2.6f ||
                sideways > max(unit*.045f,forward*.48f)) return lose()
            return reacquire(marks,visual,zoom=false)
        }
        val candidates = marks.filter { sameLabel(it, anchor) }.filter { mark ->
            val dx = mark.x - anchor.x
            val dy = mark.y - anchor.y
            val forward = (dx * requested.x + dy * requested.y) / length
            val sideways = abs(dx * requested.y - dy * requested.x) / length
            forward in length * .12f..length * 1.8f && sideways <= unit * .06f
        }
        return advance(marks, candidates, listOf(1f), unit * .034f, 0)
    }

    fun afterZoom(marks: List<FocusMark>, pivot: FocusPoint, factor: Float, visual: MapTransform? = null): Boolean {
        if (lost || predicted || !factor.isFinite() || factor <= 0 || factor == 1f) return false
        if (visual != null) {
            val applied = (visual.scale - 1f) / (factor - 1f)
            val atPivot = visual.map(pivot)
            if (applied !in .12f..2.7f || abs(atPivot.x-pivot.x) > width*.14f ||
                abs(atPivot.y-pivot.y) > height*.12f) return lose()
            return reacquire(marks,visual,zoom=true)
        }
        val scales = listOf(.45f, .75f, 1f, 1.35f, 1.75f).map { (1f + (factor - 1f) * it).coerceIn(.35f, 3f) }
        val candidates = marks.filter { sameLabel(it, anchor) }.filter { mark ->
            scales.any { scale ->
                val x = pivot.x + (anchor.x - pivot.x) * scale
                val y = pivot.y + (anchor.y - pivot.y) * scale
                // A level pin can reappear below a newly shown building.
                abs(mark.x - x) <= width * .12f && abs(mark.y - y) <= height * .09f
            }
        }
        return advance(marks, candidates, scales, unit * .067f, if (factor > 1) 1 else -1)
    }

    /** Compare against the original view, even after a lost match. Never re-use the last ROI. */
    fun restored(marks: List<FocusMark>, visual: MapTransform? = null): Boolean {
        if (visual != null) return cameraAligned(visual,width,height)
        val candidates = marks.filter { sameLabel(it, origin) && distance(it, origin) < unit * .027f }
        val matches = candidates.filter {
            bestEvidence(origin, initialMap, initialLayout, it, marks, listOf(1f), unit * .025f, 0) != null
        }
        return matches.size == 1
    }

    private fun lose(): Boolean {
        lost = true
        predicted = false
        return false
    }

    /** Retry observation of the same completed gesture; the caller bounds retries per gesture. */
    fun retryUnmatchedFrame() {
        if (lost && !predicted) lost = false
    }

    private fun numeric(mark: FocusMark) = mark.label.toIntOrNull() != null

    private fun reacquire(marks: List<FocusMark>, visual: MapTransform, zoom: Boolean): Boolean {
        val expected = visual.map(point)
        if (expected.x !in 0f..width.toFloat() || expected.y !in 0f..height.toFloat()) return lose()
        val horizontal = unit * if (zoom) .059f else .035f
        val vertical = unit * if (zoom) .087f else .035f
        fun error(a:FocusPoint,b:FocusPoint) = hypot((a.x-b.x)/horizontal,(a.y-b.y)/vertical)
        fun at(m:FocusMark) = FocusPoint(m.x,m.y)
        val neighbours = lastMap.filter { it != anchor && numeric(it)==numeric(anchor) }.map { mark ->
            val p = visual.map(at(mark))
            FocusMark(mark.label,p.x,p.y)
        }
        val nearby = marks.distinct().filter { numeric(it)==numeric(anchor) && error(at(it),expected)<=1f }
        val same = nearby.filter { sameLabel(it,anchor) }
        if (same.size > 1) return lose()
        val seen = same.singleOrNull()
        if (seen != null) {
            val residual = error(at(seen),expected)
            // Check both directions. A neighbour may vanish in just one of the two frames.
            if (nearby.any { it!=seen && error(at(it),expected) <= residual+.25f } ||
                neighbours.any { error(at(it),at(seen)) <= residual+.25f }) return lose()
            anchor = seen
        } else {
            // A change of detail level can replace the small badge with a name. Never chain
            // such predictions. Only a new, unambiguous reading can complete this inspection.
            if (!zoom || !numeric(anchor) || nearby.isNotEmpty() ||
                neighbours.any { error(at(it),expected)<=1f } || !hasRoom(expected.x,expected.y)) return lose()
            predicted = true
            projectedNeighbours = neighbours
            anchor = anchor.copy(x=expected.x,y=expected.y)
        }
        lastMap = marks.toList()
        lastLayout = layout
        return true
    }

    /** Only validates this frame's reading. The caller needs two consecutive matching frames. */
    fun predictedName(lines: List<MapText>, expected: String, badges: List<MapText>): MapText? {
        if (!predicted || lost) return null
        val name = lines.filter { TargetReading.matches(it.text,expected) && readableBox(it.left,it.top,it.right,it.bottom) &&
            abs(it.x-anchor.x)<=unit*.09f && abs(it.y-anchor.y)<=unit*.115f }.distinct().singleOrNull() ?: return null
        val atTarget = badges.filter { abs(it.x-anchor.x)<=unit*.059f && abs(it.y-anchor.y)<=unit*.087f }
        if (atTarget.size>1 || atTarget.any { TargetReading.level(it)?.toString()!=anchor.label }) return null
        val half = (unit*.018f).toInt().coerceAtLeast(2)
        fun box(m:FocusMark) = MapText(m.label,m.x.toInt()-half,m.y.toInt()-half,m.x.toInt()+half,m.y.toInt()+half)
        val reference = atTarget.singleOrNull() ?: box(anchor)
        val others = badges + projectedNeighbours.map(::box)
        return name.takeIf { TargetReading.ownsName(reference,it,others,width,height) }
    }

    private fun advance(
        marks: List<FocusMark>, candidates: List<FocusMark>, scales: List<Float>, tolerance: Float, zoomSign: Int
    ): Boolean {
        val ranked = candidates.distinct().mapNotNull { mark ->
            bestEvidence(anchor, lastMap, lastLayout, mark, marks, scales, tolerance, zoomSign)?.let { mark to it }
        }.sortedWith(compareByDescending<Pair<FocusMark, Evidence>> { it.second.count }.thenBy { it.second.error })
        val first = ranked.firstOrNull()
        val runnerUp = ranked.getOrNull(1)
        if (first == null || (runnerUp != null && first.second.count - runnerUp.second.count < 2 &&
                runnerUp.second.error - first.second.error < tolerance * .40f)) {
            lost = true
            return false
        }
        anchor = first.first
        lastMap = marks.toList()
        lastLayout = layout
        return true
    }

    private data class Evidence(val count: Int, val error: Float)

    private fun bestEvidence(
        before: FocusMark, oldFrame: List<FocusMark>, oldLayout: MapLayout, candidate: FocusMark, frame: List<FocusMark>,
        scales: List<Float>, tolerance: Float, zoomSign: Int
    ): Evidence? {
        // A nickname and badge of the same building must never count as independent landmarks.
        val neighbours = mutableListOf<FocusMark>()
        for (m in oldFrame.sortedBy { distance(it, before) }) {
            if (m.label.isBlank() || !readable(m,oldLayout) || distance(m, before) < unit * .13f ||
                abs(m.x-before.x) > width * .7f || abs(m.y-before.y) > height * .55f) continue
            if (neighbours.none { distance(it, m) < unit * .095f }) neighbours.add(m)
            if (neighbours.size == 18) break
        }
        if (neighbours.size < 2) return null
        val observed = frame.filter { it != candidate && readable(it,layout) }.groupBy { key(it) }
        var best: Evidence? = null
        for (scale in scales) {
            val used = mutableListOf<FocusMark>()
            var error = 0f
            var dot = 0f
            var norm = 0f
            for (n in neighbours) {
                val dx = n.x - before.x
                val dy = n.y - before.y
                val x = candidate.x + dx * scale
                val y = candidate.y + dy * scale
                val nearest = observed[key(n)].orEmpty().filter { mark ->
                    used.none { distance(mark, it) < unit * .065f }
                }.minByOrNull { hypot(it.x - x, it.y - y) } ?: continue
                val residual = hypot(nearest.x - x, nearest.y - y)
                if (residual > tolerance) continue
                used.add(nearest)
                error += residual
                dot += dx * (nearest.x - candidate.x) + dy * (nearest.y - candidate.y)
                norm += dx * dx + dy * dy
            }
            if (used.size < 2 || (zoomSign != 0 && (dot / norm - 1f) * zoomSign < .08f)) continue
            val evidence = Evidence(used.size, error / used.size)
            if (best == null || evidence.count > best.count ||
                (evidence.count == best.count && evidence.error < best.error)) best = evidence
        }
        return best
    }

    private fun key(m: FocusMark) = m.label.trim().lowercase(Locale.ROOT)
    private fun sameLabel(a: FocusMark, b: FocusMark) = a.label.isNotBlank() && key(a) == key(b)
    private fun distance(a: FocusMark, b: FocusMark) = hypot(a.x - b.x, a.y - b.y)
}
