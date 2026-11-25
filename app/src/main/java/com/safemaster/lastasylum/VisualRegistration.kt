package com.safemaster.lastasylum

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class MapTransform(
    val scale: Float, val dx: Float, val dy: Float,
    val support: Int = 0, val regions: Int = 0
) {
    fun map(p: FocusPoint) = FocusPoint(scale * p.x + dx, scale * p.y + dy)
}

/** Matches distributed, distinctive map corners. Gestures do not enter the measurement. */
internal object VisualRegistration {
    internal data class Corner(val x: Int, val y: Int, val score: Int)
    internal data class Signature(val first: Long, val second: Long)
    internal class Frame(
        val width: Int, val height: Int, val cols: Int, val rows: Int, internal val pixels: IntArray
    ) {
        internal val corners by lazy { corners(this) }
        internal val signatures by lazy { describe(this, 1f) }
    }
    private data class Match(val x: Float, val y: Float, val u: Float, val v: Float, val distance: Int)
    private data class Fit(val model: MapTransform, val inliers: List<Match>, val error: Float)

    fun gridSize(width: Int, height: Int): Pair<Int, Int> {
        val ratio = min(1f, min(144f / min(width, height), 320f / max(width, height)))
        return max(1, (width * ratio).roundToInt()) to max(1, (height * ratio).roundToInt())
    }

    fun capture(width: Int, height: Int, overlay: MapText? = null, layout: MapLayout = MapLayout.DETAIL_OR_UNKNOWN,
                grayAt: (Int, Int) -> Int): Frame {
        val (cols, rows) = gridSize(width, height)
        val gray = IntArray(cols * rows) { -1 }
        val masked = FocusTracker(width, height, FocusMark("", 0f, 0f), emptyList(), overlay, layout)
        for (y in 0 until rows) for (x in 0 until cols) {
            val sx = ((x + .5f) * width / cols).toInt()
            val sy = ((y + .5f) * height / rows).toInt()
            if (masked.readableBox(sx-2, sy-2, sx+2, sy+2)) gray[y*cols+x] = grayAt(sx,sy).coerceIn(0,255)
        }
        // Smooth high-frequency game sprites and sharpened OCR edges before sampling descriptors.
        val smooth = IntArray(gray.size) { -1 }
        for (y in 1 until rows-1) for (x in 1 until cols-1) {
            val i = y*cols+x
            val p = gray[i]; val l = gray[i-1]; val r = gray[i+1]; val u = gray[i-cols]; val d = gray[i+cols]
            if (p>=0 && l>=0 && r>=0 && u>=0 && d>=0) smooth[i] = (p*4+l+r+u+d)/8
        }
        return Frame(width, height, cols, rows, smooth)
    }

    private fun corners(f: Frame): List<Corner> {
        val scores = IntArray(f.pixels.size)
        val p = f.pixels
        for (y in 3 until f.rows-3) for (x in 3 until f.cols-3) {
            var xx = 0; var yy = 0; var xy = 0; var valid = true
            for (oy in -1..1) for (ox in -1..1) {
                val i = (y+oy)*f.cols+x+ox
                val l=p[i-1]; val r=p[i+1]; val u=p[i-f.cols]; val d=p[i+f.cols]
                if (l<0 || r<0 || u<0 || d<0) { valid=false; continue }
                val gx=r-l; val gy=d-u
                xx+=gx*gx; yy+=gy*gy; xy+=gx*gy
            }
            if (valid && xx+yy > 300) {
                // det/trace favours corners over single-direction edges.
                scores[y*f.cols+x] = ((xx.toLong()*yy-xy.toLong()*xy)/(xx+yy)).toInt()
            }
        }
        val candidates = mutableListOf<Corner>()
        for (y in 3 until f.rows-3) for (x in 3 until f.cols-3) {
            val index=y*f.cols+x
            val score=scores[index]
            if (score < 70) continue
            var peak=true
            for (oy in -1..1) for (ox in -1..1) {
                val j=index+oy*f.cols+ox
                if (scores[j]>score || (j<index && scores[j]==score)) peak=false
            }
            if (peak) candidates.add(Corner(x,y,score))
        }
        val across=(f.cols+13)/14
        val quotas=IntArray(across*((f.rows+13)/14))
        val blocked=BooleanArray(p.size)
        val result=ArrayList<Corner>()
        for (c in candidates.sortedByDescending { it.score }) {
            val tile=c.y/14*across+c.x/14
            if (quotas[tile]>=3 || blocked[c.y*f.cols+c.x]) continue
            result.add(c); quotas[tile]++
            for (dy in -3..3) for (dx in -3..3) blocked[(c.y+dy)*f.cols+c.x+dx]=true
            if (result.size==360) break
        }
        return result
    }

    // Compare 128 deterministic pairs in each patch. A comparison encodes local contrast
    // rather than the absolute colour, which can change between zoom levels.
    private val offsets=IntArray(128*4).also { a ->
        val rng=java.util.Random(674191L)
        for (i in a.indices) a[i]=rng.nextInt(7)+rng.nextInt(7)-6
    }
    private fun describe(f: Frame, radius: Float): Array<Signature?> {
        val diffs=IntArray(offsets.size/2) { i ->
            (offsets[2*i]*radius).roundToInt()+(offsets[2*i+1]*radius).roundToInt()*f.cols
        }
        val margin=(6*radius).roundToInt()+1
        return Array(f.corners.size) { index ->
            val c=f.corners[index]
            if (c.x !in margin until f.cols-margin || c.y !in margin until f.rows-margin) return@Array null
            val center=c.y*f.cols+c.x
            var first=0L; var second=0L; var contrast=0
            for (k in 0 until 128) {
                val a=f.pixels[center+diffs[2*k]]; val b=f.pixels[center+diffs[2*k+1]]
                if (a<0 || b<0) return@Array null
                if (abs(a-b)>4) contrast++
                if (a>b) {
                    if (k<64) first=first or (1L shl k) else second=second or (1L shl (k-64))
                }
            }
            if (contrast>=27) Signature(first,second) else null
        }
    }

    private fun matches(before: Frame, after: Frame, zoom: Boolean, repeated: Boolean = false): List<Match> {
        val candidates=mutableListOf(after.signatures)
        if (zoom) for (r in floatArrayOf(.50f,.69f,.84f,1.22f,1.46f,1.77f,2.12f)) candidates.add(describe(after,r))
        val distances=IntArray(after.corners.size)
        val result=mutableListOf<Match>()
        val sx=before.width.toFloat()/before.cols
        val sy=before.height.toFloat()/before.rows
        for ((i,signature) in before.signatures.withIndex()) {
            if (signature==null) continue
            distances.fill(129)
            for (set in candidates) for (j in set.indices) {
                val other=set[j] ?: continue
                val d=java.lang.Long.bitCount(signature.first xor other.first)+java.lang.Long.bitCount(signature.second xor other.second)
                if (d<distances[j]) distances[j]=d
            }
            var index=-1; var best=129
            for (j in distances.indices) if (distances[j]<best) { best=distances[j]; index=j }
            if (best>37) continue
            val a=before.corners[i]
            if (repeated) {
                val limit=min(best+11,35)
                val count=distances.count { it<=limit }
                if (count !in 1..18) continue
                for (j in distances.indices) if (distances[j]<=limit) {
                    val b=after.corners[j]
                    result.add(Match((a.x+.5f)*sx,(a.y+.5f)*sy,(b.x+.5f)*sx,(b.y+.5f)*sy,distances[j]))
                }
                continue
            }
            val b=after.corners[index]
            var second=129
            for (j in distances.indices) {
                val c=after.corners[j]
                if ((abs(c.x-b.x)>4 || abs(c.y-b.y)>4) && distances[j]<second) second=distances[j]
            }
            if (second-best<5 || best>.84f*second) continue
            result.add(Match((a.x+.5f)*sx,(a.y+.5f)*sy,(b.x+.5f)*sx,(b.y+.5f)*sy,best))
        }
        // A single landmark in the new view can contribute at most one vote.
        return if(repeated) result else result.sortedBy { it.distance }.distinctBy { it.u to it.v }
    }

    /** Returns only measurements supported across independent image regions, including identity. */
    fun estimate(before: Frame, after: Frame, zoom: Boolean = false): MapTransform? {
        if (before.width!=after.width || before.height!=after.height) return null
        return distinctiveFit(before,after,zoom) ?: constellationFit(before,after,zoom)
    }
    private fun distinctiveFit(before: Frame, after: Frame, zoom: Boolean): MapTransform? {
        val pairs=matches(before,after,zoom)
        if (pairs.size<5) return null
        val unit=min(before.width,before.height).toFloat()
        val tolerance=max(before.width.toFloat()/before.cols*2.1f, 2f)
        val tol2=tolerance*tolerance
        val required=max(5,(pairs.size*.23f).roundToInt())
        val proposals=mutableListOf<Fit>()
        val tried=HashSet<Triple<Int,Int,Int>>()
        fun propose(model:MapTransform) {
            val key=Triple((model.scale*100).roundToInt(),(model.dx/tolerance*2).roundToInt(),(model.dy/tolerance*2).roundToInt())
            if (!tried.add(key)) return
            var count=0
            for (m in pairs) if (error2(m,model)<tol2) count++
            if (count<required) return
            val inliers=pairs.filter { error2(it,model)<tol2 }
            if (inliers.maxOf { it.x } - inliers.minOf { it.x } < unit*.18f ||
                inliers.maxOf { it.y } - inliers.minOf { it.y } < unit*.18f) return
            val regions=inliers.distinctBy { (it.x/(unit*.16f)).toInt() to (it.y/(unit*.16f)).toInt() }.size
            if (regions<4) return
            proposals.add(Fit(model.copy(support=count,regions=regions),inliers,inliers.sumOf { error2(it,model).toDouble() }.toFloat()/count))
        }
        val seeds=pairs.take(50)
        for (a in seeds) propose(MapTransform(1f,a.u-a.x,a.v-a.y))
        if (zoom) for (i in seeds.indices) for (j in 0 until i) {
            val a=seeds[i]; val b=seeds[j]
            val vx=b.x-a.x; val vy=b.y-a.y
            val norm=vx*vx+vy*vy
            if (norm<unit*unit*.025f) continue
            val scale=(vx*(b.u-a.u)+vy*(b.v-a.v))/norm
            if (scale !in .45f..2.25f) continue
            val model=MapTransform(scale,a.u-scale*a.x,a.v-scale*a.y)
            if (error2(b,model)<tol2) propose(model)
        }
        val best=proposals.maxWithOrNull(compareBy<Fit> { it.inliers.size }.thenBy { -it.error }) ?: return null
        // Distinct camera hypotheses describing comparable amounts of the scene are ambiguous.
        val support=best.inliers.toHashSet()
        if (proposals.any { alt -> alt.inliers.size >= support.size*.78f &&
            alt.inliers.count(support::contains) < support.size*.40f }) return null
        return refined(best.inliers,zoom,best.model.regions)
    }
    private fun refined(set: List<Match>, zoom: Boolean, regions: Int): MapTransform {
        val x=set.map { it.x }.average().toFloat(); val y=set.map { it.y }.average().toFloat()
        val u=set.map { it.u }.average().toFloat(); val v=set.map { it.v }.average().toFloat()
        var dot=0f; var norm=0f
        for (m in set) { dot+=(m.x-x)*(m.u-u)+(m.y-y)*(m.v-v); norm+=(m.x-x)*(m.x-x)+(m.y-y)*(m.y-y) }
        val scale=if (zoom) dot/norm else 1f
        return MapTransform(scale,u-scale*x,v-scale*y,set.size,regions)
    }

    /** The same white pin can occur many times. Accumulate votes for the whole configuration;
     * accepting one patch's best location would silently switch to an unrelated base. */
    private fun constellationFit(before: Frame, after: Frame, zoom: Boolean): MapTransform? {
        if (periodic(before) || periodic(after)) return null
        val pairs=matches(before,after,zoom,true)
        if (pairs.size<8) return null
        val unit=min(before.width,before.height).toFloat()
        val bin=unit*.030f
        val maxError2=bin*bin*.80f
        val scales=if (zoom) listOf(1f)+(0..45).map { .45f+it*.04f } else listOf(1f)
        val fits=mutableListOf<Fit>()
        for (scale in scales) {
            val buckets=HashMap<Int,Int>()
            for (m in pairs) {
                val bx=((m.u-scale*m.x)/bin).roundToInt()
                val by=((m.v-scale*m.y)/bin).roundToInt()
                val key=(bx shl 16) or (by and 65535)
                buckets[key]=1+(buckets[key] ?: 0)
            }
            for ((key,_) in buckets.entries.filter { it.value>=5 }.sortedByDescending { it.value }.take(4)) {
                var model=MapTransform(scale, (key shr 16)*bin,key.toShort()*bin)
                var local=emptyList<Match>()
                repeat(2) {
                    // Use each source and destination feature once, taking its closest residual.
                    val nearby=pairs.filter { error2(it,model)<maxError2 }.sortedBy { error2(it,model) }
                    val source=HashSet<Pair<Float,Float>>(); val dest=HashSet<Pair<Float,Float>>()
                    local=nearby.filter { m ->
                        val a=m.x to m.y; val b=m.u to m.v
                        if (a in source || b in dest) false else { source.add(a); dest.add(b); true }
                    }
                    if(local.size>=6) model=refined(local,zoom,0)
                }
                if (local.size<8 || model.scale !in .45f..2.25f) continue
                if (local.maxOf { it.x }-local.minOf { it.x } < unit*.24f ||
                    local.maxOf { it.y }-local.minOf { it.y } < unit*.24f) continue
                val regions=local.distinctBy { (it.x/(unit*.16f)).toInt() to (it.y/(unit*.16f)).toInt() }.size
                if (regions>=5) fits.add(Fit(model.copy(regions=regions),local,0f))
            }
        }
        val best=fits.maxByOrNull { it.inliers.size } ?: return null
        val support=best.inliers.toHashSet()
        if (fits.any { it.inliers.size>=support.size*.72f && it.inliers.count(support::contains)<support.size*.38f }) return null
        val model=best.model
        // Validate the winning geometry on original pixels in the two views. Under a LOD
        // change unrelated repeated buildings can still form a plausible geometric pattern.
        var checked=0; var agreed=0
        val sx=before.cols.toFloat()/before.width; val sy=before.rows.toFloat()/before.height
        for (c in before.corners) {
            var n=0; var a=0f; var b=0f; var aa=0f; var bb=0f; var ab=0f
            for (dy in -4..4 step 2) for (dx in -4..4 step 2) {
                val x=c.x+dx; val y=c.y+dy
                val u=((x+.5f)*model.scale+model.dx*sx-.5f).roundToInt()
                val v=((y+.5f)*model.scale+model.dy*sy-.5f).roundToInt()
                if (x !in 0 until before.cols || y !in 0 until before.rows ||
                    u !in 0 until after.cols || v !in 0 until after.rows) continue
                val p=before.pixels[y*before.cols+x]; val q=after.pixels[v*after.cols+u]
                if (p<0 || q<0) continue
                n++; a+=p; b+=q; aa+=p*p; bb+=q*q; ab+=p*q
            }
            if (n<20) continue
            val av=aa-a*a/n; val bv=bb-b*b/n
            if (av<200) continue
            checked++
            if (bv>150 && ab-a*b/n>.50f*sqrt(av*bv)) agreed++
        }
        return if (agreed>=6 && agreed>=checked*.40f) model else null
    }

    // A tiled image can yield an apparently exact identity or pan for many distinct maps.
    // Test whether one common displacement reproduces almost every textured patch.
    private fun periodic(frame: Frame): Boolean {
        val points=frame.corners.filterIndexed { i,_ -> i%4==0 }
        for (period in 4..32) for (axis in 0..3) {
            val dx=if(axis==1) 0 else period
            val dy=when(axis) { 0->0; 3->-period; else->period }
            var tried=0
            var same=0
            for(c in points) {
                val x=c.x+dx; val y=c.y+dy
                if (x !in 3 until frame.cols-3 || y !in 3 until frame.rows-3) continue
                var sum=0; var n=0
                for(v in -2..2 step 2) for(u in -2..2 step 2) {
                    val a=frame.pixels[(c.y+v)*frame.cols+c.x+u]
                    val b=frame.pixels[(y+v)*frame.cols+x+u]
                    if (a>=0 && b>=0) { sum+=abs(a-b); n++ }
                }
                if(n>=7) { tried++; if(sum<=n*3) same++ }
            }
            if (tried>=8 && same>=tried*.84f) return true
        }
        return false
    }
    private fun error2(m: Match, t: MapTransform): Float {
        val x=t.scale*m.x+t.dx-m.u; val y=t.scale*m.y+t.dy-m.v
        return x*x+y*y
    }
}
