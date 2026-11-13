package com.splendor.cam

/**
 * The 7 target looks Splendor picked (2026-11-13), tuned on the 4
 * indoor-night baseline pairs by the reference processor
 * (~/workspace/splendorcam-processing/ref/process.py). The numbers below
 * are the measured sets from baseline-analysis.md §4 — not guesses.
 *
 *  - [temp]: white-balance warmth (>1 = warmer: R up, B down).
 *  - [tint]: green multiplier.
 *  - [ev]: extra exposure in stops on top of the auto base gain
 *    (base gain = 0.30 / green median of the merged RAW).
 *  - [curve]: 5 (in, out) control points, display space 0..1, applied
 *    piecewise-linear after gamma 2.2.
 *  - [gamma]: extra display-space gamma after gamma 2.2 (1 = none).
 *  - [sat]: saturation multiplier about luma.
 *  - [hl]: highlight-rolloff strength above 0.75 linear.
 *  - [shadow]: black lift.
 *  - [sharpen]: unsharp-mask amount (3x3 box blur).
 *  - [denoise]: fraction of 3x3-blurred linear RGB blended in (0 = none).
 */
data class Look(
    val id: String,
    val displayName: String,
    val temp: Float,
    val tint: Float,
    val ev: Float,
    /** 5 (in, out) control points, flattened: x0,y0,x1,y1,...,x4,y4. */
    val curve: FloatArray,
    val gamma: Float,
    val sat: Float,
    val hl: Float,
    val shadow: Float,
    val sharpen: Float,
    val denoise: Float
) {
    /** Piecewise-linear tone curve (same as numpy.interp over the points). */
    fun curveAt(x: Float): Float {
        val v = x.coerceIn(0f, 1f)
        var i = 0
        while (i < 4 && v > curve[i * 2 + 2]) i++
        val x0 = curve[i * 2]
        val y0 = curve[i * 2 + 1]
        val x1 = curve[i * 2 + 2]
        val y1 = curve[i * 2 + 3]
        if (x1 <= x0) return y1
        val t = (v - x0) / (x1 - x0)
        return y0 + (y1 - y0) * t
    }
}

object Looks {
    val NATURAL = Look(
        id = "natural",
        displayName = "iPhone Natural",
        temp = 1.00f, tint = 1.00f, ev = 0.0f,
        curve = floatArrayOf(0f, 0f, 0.25f, 0.23f, 0.5f, 0.50f, 0.75f, 0.78f, 1f, 1f),
        gamma = 1.00f, sat = 1.05f, hl = 0.50f, shadow = 0.02f, sharpen = 0.35f, denoise = 0.35f
    )
    val NIGHT = Look(
        id = "night",
        displayName = "iPhone Night",
        temp = 1.02f, tint = 1.00f, ev = 0.7f,
        curve = floatArrayOf(0f, 0.02f, 0.25f, 0.30f, 0.5f, 0.55f, 0.75f, 0.80f, 1f, 1f),
        gamma = 0.95f, sat = 1.00f, hl = 0.70f, shadow = 0.08f, sharpen = 0.25f, denoise = 0.65f
    )
    val PORTRAIT = Look(
        id = "portrait",
        displayName = "iPhone Portrait",
        temp = 1.06f, tint = 0.99f, ev = 0.3f,
        curve = floatArrayOf(0f, 0.01f, 0.25f, 0.25f, 0.5f, 0.51f, 0.75f, 0.78f, 1f, 1f),
        gamma = 1.00f, sat = 1.00f, hl = 0.60f, shadow = 0.03f, sharpen = 0.15f, denoise = 0.55f
    )
    val VIVID = Look(
        id = "vivid",
        displayName = "Vivid",
        temp = 1.02f, tint = 1.00f, ev = 0.0f,
        curve = floatArrayOf(0f, 0f, 0.25f, 0.20f, 0.5f, 0.50f, 0.75f, 0.82f, 1f, 1f),
        gamma = 1.00f, sat = 1.45f, hl = 0.40f, shadow = 0.00f, sharpen = 0.50f, denoise = 0.25f
    )
    val GOLDEN = Look(
        id = "golden",
        displayName = "Warm Golden",
        temp = 1.18f, tint = 1.00f, ev = 0.15f,
        curve = floatArrayOf(0f, 0f, 0.25f, 0.24f, 0.5f, 0.51f, 0.75f, 0.78f, 1f, 1f),
        gamma = 1.02f, sat = 1.15f, hl = 0.40f, shadow = 0.03f, sharpen = 0.30f, denoise = 0.30f
    )
    val STUDIO = Look(
        id = "studio",
        displayName = "Studio HD",
        temp = 0.98f, tint = 1.01f, ev = 0.2f,
        curve = floatArrayOf(0f, 0f, 0.20f, 0.20f, 0.5f, 0.52f, 0.80f, 0.82f, 1f, 1f),
        gamma = 1.00f, sat = 1.08f, hl = 0.50f, shadow = 0.00f, sharpen = 0.60f, denoise = 0.50f
    )
    val FILM = Look(
        id = "film",
        displayName = "Soft Film",
        temp = 1.05f, tint = 1.00f, ev = 0.1f,
        curve = floatArrayOf(0f, 0.06f, 0.25f, 0.30f, 0.5f, 0.53f, 0.75f, 0.78f, 1f, 0.97f),
        gamma = 1.08f, sat = 0.85f, hl = 0.80f, shadow = 0.06f, sharpen = 0.10f, denoise = 0.60f
    )

    val ALL: List<Look> = listOf(NATURAL, NIGHT, PORTRAIT, VIVID, GOLDEN, STUDIO, FILM)

    fun byId(id: String): Look = ALL.firstOrNull { it.id == id } ?: NATURAL

    /** Sensible default per scene: natural for daylight, night for low light.
     * The app picks night when the burst ISO is high (>= 800) — the indoor
     * baselines are ISO 975–1607, so night is the default there. */
    fun defaultFor(isLowLight: Boolean): Look = if (isLowLight) NIGHT else NATURAL
}
