package com.safemaster.lastasylum

/** The overview has a three-colour map legend. Other screens use the conservative HUD mask. */
internal enum class MapLayout {
    OVERVIEW, DETAIL_OR_UNKNOWN;

    companion object {
        // Five positions within each of the green territory, blue alliance and magenta guild icons.
        private val legend = arrayOf(
            intArrayOf(558,251, 567,253, 558,256, 567,259, 564,262),
            intArrayOf(561,298, 567,301, 558,305, 568,307, 564,311),
            intArrayOf(557,347, 566,346, 557,354, 571,353, 563,359)
        )

        fun detect(width: Int, height: Int, colorAt: (Int, Int) -> Int): MapLayout {
            if (width < 120 || height < 1 || width.toFloat()/height !in .38f.. .60f) return DETAIL_OR_UNKNOWN
            val scale = width/720f
            for ((icon, points) in legend.withIndex()) {
                var hits=0
                for (i in points.indices step 2) {
                    val color = colorAt((points[i]*scale+.5f).toInt(),(points[i+1]*scale+.5f).toInt())
                    val r=color ushr 16 and 255
                    val g=color ushr 8 and 255
                    val b=color and 255
                    val matches = when(icon) {
                        0 -> g>=122 && g-r>=29 && r-b>=31
                        1 -> b>=132 && b-g>=24 && g-r>=37
                        else -> r>=148 && b>=145 && r-g>=59 && b-g>=54
                    }
                    if(matches) hits++
                }
                if(hits<3) return DETAIL_OR_UNKNOWN
            }
            return OVERVIEW
        }
    }
}
