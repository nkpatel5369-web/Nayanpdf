package npdf.scan

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Page layouts built from corrected captures: ID cards and book spreads. */
object Compose {

    /** A4 at 300 dpi. */
    const val A4_W = 2480
    const val A4_H = 3508
    private const val DPI = 300f

    /**
     * Both sides of an ID card on one A4 page at their real size (85.6 x 54 mm,
     * the ISO ID-1 card), front above back, so a print is a true-size copy.
     */
    fun idCardPage(front: Raster, back: Raster?): Raster {
        val page = Raster.filled(A4_W, A4_H, 0xFFFFFFFF.toInt())
        val cardW = (85.6f / 25.4f * DPI).roundToInt()
        val cardH = (53.98f / 25.4f * DPI).roundToInt()
        val gap = (12f / 25.4f * DPI).roundToInt()
        val top = (30f / 25.4f * DPI).roundToInt()
        fun place(src: Raster, y: Int) {
            // Landscape card: rotate a portrait capture so it lies like the card.
            val s0 = if (src.height > src.width) src.rotated(1) else src
            val s = min(cardW.toFloat() / s0.width, cardH.toFloat() / s0.height)
            val w = max(1, (s0.width * s).roundToInt()); val h = max(1, (s0.height * s).roundToInt())
            val img = s0.resize(w, h)
            val x = (A4_W - w) / 2
            val yy = y + (cardH - h) / 2
            for (j in 0 until h) System.arraycopy(img.px, j * w, page.px, (yy + j) * A4_W + x, w)
        }
        place(front, top)
        if (back != null) place(back, top + cardH + gap)
        return page
    }

    /** Splits a corrected book spread at [divider] (0..1 of the width) into left and right pages. */
    fun splitSpread(spread: Raster, divider: Float = 0.5f, rightFirst: Boolean = false): List<Raster> {
        val x = (spread.width * divider.coerceIn(0.2f, 0.8f)).roundToInt()
        val left = spread.crop(0, 0, x, spread.height)
        val right = spread.crop(x, 0, spread.width, spread.height)
        return if (rightFirst) listOf(right, left) else listOf(left, right)
    }
}
