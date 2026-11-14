package com.splendor.cam

import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Pure-Kotlin RAW (DNG) processor — no Android classes, so the same code is
 * verified on the JVM in the sandbox and runs on the phone.
 *
 * Pipeline (exactly the reference pipeline in
 * ~/workspace/splendorcam-processing/ref/process.py, ported 1:1):
 *   1. DNG read: TIFF tags + 16-bit uncompressed RAW strips. White level
 *      1023 (10-bit data in a 16-bit container); black level ~64-65
 *      (RATIONAL tag); CFA BGGR front / GBRG back; AsShotNeutral per file.
 *   2. Merge: per-pixel MEAN of the burst frames in linear RAW domain.
 *      NO alignment — measured burst frame differences (Δmean < 0.35 DN)
 *      show no material motion between frames, so a plain mean is safe
 *      and cuts temporal noise ~√2 (√5 for a full 5-frame burst).
 *   3. Demosaic: bilinear on the Bayer pattern, per tile, black-level
 *      subtracted and white-level normalised to 0..1 linear light.
 *   4. Denoise: 3x3 box-blur blend on the merged linear camera-RGB
 *      (fraction = look.denoise), before white balance.
 *   5. White balance: gains = 1 / AsShotNeutral (green = 1), then the
 *      look's temp (R up, B down) and tint (G).
 *   6. Colour: camera RGB -> linear sRGB in TWO steps (round-2 fix, proven
 *      2026-11-14). ForwardMatrix1 (tag 50964) maps white-balanced camera
 *      RGB to XYZ D50 -- NOT to sRGB. Round 1 treated the FM output as
 *      linear sRGB directly, which renders the D50 white point
 *      (0.964, 1.000, 0.825) as a yellow-green tint: measured green cast
 *      +7 to +11 on all 4 baseline pairs, matching the on-device
 *      processed pic (+10.8), while the stock JPEGs measure ~0. FM2 has
 *      identical row sums (both matrices map to XYZ D50), so matrix
 *      interpolation cannot fix it. The correct chain is FM1 -> Bradford
 *      adaptation D50->D65 -> XYZ-to-sRGB (D65); the two constant
 *      matrices are pre-multiplied into M_XYZ2SRGB and combined with
 *      FM1 per file (single 3x3, exact: M @ D50-white = (1,1,1)). After
 *      the fix the cast measures -1.1..+0.1 on all 4 pairs, in the stock
 *      range. Negatives are clipped to 0 after the matrix.
 *   7. Exposure: automatic base gain = 0.30 / median(green photosites
 *      of the merged linear RAW), then the look's EV on top.
 *   8. Highlight rolloff (linear, above 0.75):
 *      y = 0.75 + (x - 0.75) / (1 + hl * (x - 0.75)); then clip 0..1.
 *   9. Gamma 2.2 to display space, then the look's extra display gamma,
 *      then its piecewise-linear tone curve, shadow lift, saturation
 *      about luma, and unsharp-mask sharpen (3x3 box blur).
 *  10. Orientation is NOT done here — the output tiles are in sensor
 *      orientation. The caller rotates: FRONT = 90 deg CCW + horizontal
 *      mirror, BACK = 90 deg CW (verified against the stock JPEGs).
 *
 * Memory: the merged RAW is one FloatArray (50MB back / 32MB front); the
 * caller streams frames into it one at a time, so a 5-frame burst never
 * sits in memory as 5 full RAWs. Tiles are 256 rows; peak extra ~40MB.
 */

data class DngInfo(
    val width: Int,
    val height: Int,
    val blackLevel: Int,
    val whiteLevel: Int,
    /** 2x2 Bayer pattern, row-major: [c(0,0), c(1,0), c(0,1), c(1,1)], 0=R, 1=G, 2=B. */
    val cfaPattern: IntArray,
    /** 3x3 row-major, white-balanced camera RGB -> linear sRGB (DNG ForwardMatrix1). */
    val forwardMatrix: FloatArray,
    /** Per-channel neutral (DNG AsShotNeutral), 3 rationals. */
    val asShotNeutral: FloatArray
)

object DngReader {

    private const val TAG_IMAGE_WIDTH = 256
    private const val TAG_IMAGE_LENGTH = 257
    private const val TAG_BITS_PER_SAMPLE = 258
    private const val TAG_COMPRESSION = 259
    private const val TAG_STRIP_OFFSETS = 273
    private const val TAG_ROWS_PER_STRIP = 278
    private const val TAG_STRIP_BYTE_COUNTS = 279
    private const val TAG_CFA_PATTERN = 33422
    private const val TAG_BLACK_LEVEL = 50714
    private const val TAG_WHITE_LEVEL = 50717
    private const val TAG_AS_SHOT_NEUTRAL = 50728
    private const val TAG_FORWARD_MATRIX_1 = 50964

    private class Reader(val data: ByteArray, val littleEndian: Boolean) {
        fun u16(off: Int): Int {
            val b0 = data[off].toInt() and 0xFF
            val b1 = data[off + 1].toInt() and 0xFF
            return if (littleEndian) b0 or (b1 shl 8) else (b0 shl 8) or b1
        }

        fun u32(off: Int): Int {
            val b0 = data[off].toInt() and 0xFF
            val b1 = data[off + 1].toInt() and 0xFF
            val b2 = data[off + 2].toInt() and 0xFF
            val b3 = data[off + 3].toInt() and 0xFF
            return if (littleEndian) b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
            else (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }

        fun i32(off: Int): Int = u32(off)
    }

    private data class IfdEntry(val tag: Int, val type: Int, val count: Int, val valueOff: Int)

    /** Reads the whole stream; returns (info, raw 16-bit samples row-major). */
    fun read(input: InputStream): Pair<DngInfo, ShortArray> {
        val bytes = readAll(input)
        require(bytes.size >= 8) { "file too small to be a DNG" }
        val littleEndian = when {
            bytes[0] == 0x49.toByte() && bytes[1] == 0x49.toByte() -> true
            bytes[0] == 0x4D.toByte() && bytes[1] == 0x4D.toByte() -> false
            else -> throw IllegalArgumentException("not a TIFF/DNG file")
        }
        val r = Reader(bytes, littleEndian)
        val magic = r.u16(2)
        require(magic == 42) { "bad TIFF magic: $magic" }
        val ifdOff = r.u32(4)
        val entries = readIfd(r, ifdOff)
        val get = { tag: Int -> entries[tag] }

        fun singleU32(tag: Int, default: Int): Int {
            val e = get(tag) ?: return default
            return when (e.type) {
                3 -> r.u16(e.valueOff) // SHORT stored inline
                4 -> r.u32(e.valueOff)
                else -> default
            }
        }

        fun u32Array(tag: Int): IntArray {
            val e = get(tag) ?: return IntArray(0)
            // TIFF rule: the value lives inline in the 4-byte field when it
            // fits, otherwise the field holds an offset to it.
            val typeSize = when (e.type) {
                1, 2 -> 1
                3 -> 2
                4 -> 4
                5, 10 -> 8
                else -> 1
            }
            val base = if (e.count * typeSize <= 4) e.valueOff else r.u32(e.valueOff)
            val out = IntArray(e.count)
            for (i in out.indices) {
                out[i] = when (e.type) {
                    3 -> r.u16(base + i * 2)
                    4 -> r.u32(base + i * 4)
                    else -> 0
                }
            }
            return out
        }

        /** Rational values (types 5 RATIONAL and 10 SRATIONAL) as floats;
         * also accepts SHORT/LONG tags, returned as floats. */
        fun rationalArray(tag: Int): FloatArray {
            val e = get(tag) ?: return FloatArray(0)
            val typeSize = when (e.type) {
                3 -> 2
                4 -> 4
                5, 10 -> 8
                else -> return FloatArray(0)
            }
            val base = if (e.count * typeSize <= 4) e.valueOff else r.u32(e.valueOff)
            val out = FloatArray(e.count)
            for (i in out.indices) {
                out[i] = when (e.type) {
                    3 -> r.u16(base + i * 2).toFloat()
                    4 -> r.u32(base + i * 4).toFloat()
                    5, 10 -> {
                        val num = r.i32(base + i * 8)
                        val den = r.i32(base + i * 8 + 4)
                        if (den != 0) num.toFloat() / den.toFloat() else 0f
                    }
                    else -> 0f
                }
            }
            return out
        }

        fun byteArray(tag: Int): ByteArray {
            val e = get(tag) ?: return ByteArray(0)
            val base = if (e.count <= 4) e.valueOff else r.u32(e.valueOff)
            return bytes.copyOfRange(base, base + e.count)
        }

        val width = singleU32(TAG_IMAGE_WIDTH, 0)
        val height = singleU32(TAG_IMAGE_LENGTH, 0)
        require(width > 0 && height > 0) { "DNG has no image size" }
        val bits = u32Array(TAG_BITS_PER_SAMPLE).firstOrNull() ?: 16
        require(bits == 16) { "only 16-bit DNGs are supported, got $bits" }
        val compression = singleU32(TAG_COMPRESSION, 1)
        require(compression == 1) { "only uncompressed DNGs are supported, got $compression" }

        val stripOffsets = u32Array(TAG_STRIP_OFFSETS)
        val stripCounts = u32Array(TAG_STRIP_BYTE_COUNTS)
        val rowsPerStrip = singleU32(TAG_ROWS_PER_STRIP, height)
        require(stripOffsets.isNotEmpty() && stripCounts.isNotEmpty()) { "DNG has no strips" }

        // Black level is a RATIONAL tag in these files (front 65, back 64);
        // all four CFA phases are equal, so the first value is used.
        val black = rationalArray(TAG_BLACK_LEVEL).firstOrNull()?.toInt() ?: 0
        val white = u32Array(TAG_WHITE_LEVEL).firstOrNull() ?: 65535
        val cfaBytes = byteArray(TAG_CFA_PATTERN)
        require(cfaBytes.size >= 4) { "DNG has no CFA pattern" }
        val cfa = IntArray(4) { cfaBytes[it].toInt() and 0xFF }
        val fwd = rationalArray(TAG_FORWARD_MATRIX_1)
        require(fwd.size == 9) { "DNG has no 3x3 ForwardMatrix1" }
        val neutral = rationalArray(TAG_AS_SHOT_NEUTRAL)
        val asShot = if (neutral.size == 3) neutral else floatArrayOf(1f, 1f, 1f)

        val info = DngInfo(width, height, black, white, cfa, fwd, asShot)
        val raw = ShortArray(width * height)
        var row = 0
        for (s in stripOffsets.indices) {
            val off = stripOffsets[s]
            val bytesInStrip = stripCounts[s]
            val rows = min(rowsPerStrip, height - row)
            val samples = min(bytesInStrip / 2, rows * width)
            for (i in 0 until samples) {
                raw[row * width + i] = r.u16(off + i * 2).toShort()
            }
            row += rows
            if (row >= height) break
        }
        return info to raw
    }

    private fun readIfd(r: Reader, off: Int): Map<Int, IfdEntry> {
        val n = r.u16(off)
        val map = LinkedHashMap<Int, IfdEntry>()
        for (i in 0 until n) {
            val e = off + 2 + i * 12
            val tag = r.u16(e)
            val type = r.u16(e + 2)
            val count = r.u32(e + 4)
            val valueOff = e + 8
            map[tag] = IfdEntry(tag, type, count, valueOff)
        }
        return map
    }

    private fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

object RawProcessor {

    /** One tile of ARGB output: x/y are the tile origin, argb is w*h row-major. */
    fun interface TileSink {
        fun onTile(x: Int, y: Int, w: Int, h: Int, argb: IntArray)
    }

    private const val TILE_ROWS = 256

    /**
     * Constant XYZ-D50 -> linear-sRGB matrix: Bradford chromatic adaptation
     * D50->D65 followed by the XYZ->sRGB (D65) matrix. ForwardMatrix1 maps
     * to XYZ D50, so the per-file camera->sRGB matrix is M_XYZ2SRGB x FM1.
     * Derived in the round-2 report (Bradford cone matrix + D50/D65 whites);
     * sanity: M @ (0.9642, 1.0, 0.8251) = (1, 1, 1).
     */
    private val M_XYZ2SRGB = floatArrayOf(
        3.1339686f, -1.6168703f, -0.4907131f,
        -0.9783911f, 1.9158704f, 0.0333878f,
        0.0720104f, -0.2289867f, 1.4053693f
    )

    /** 3x3 row-major matrix product: out = a x b. */
    private fun matMul(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (r in 0..2) for (c in 0..2) {
            out[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
        }
        return out
    }

    /**
     * Adds one frame's normalised linear RAW into [acc] (which the caller
     * zeroed first). After all frames, call [scaleAcc] with 1/n.
     * Streaming: the caller reads one DNG at a time, so a burst never sits
     * in memory as several full RAWs.
     */
    fun accumulate(acc: FloatArray, raw: ShortArray, info: DngInfo) {
        val invWhite = 1f / max(1, info.whiteLevel - info.blackLevel)
        for (i in raw.indices) {
            val v = (raw[i].toInt() and 0xFFFF) - info.blackLevel
            acc[i] += (if (v < 0) 0 else v) * invWhite
        }
    }

    fun scaleAcc(acc: FloatArray, scale: Float) {
        for (i in acc.indices) acc[i] *= scale
    }

    /** Full pipeline on a merged (mean) linear RAW, 0..1, row-major. */
    fun process(merged: FloatArray, info: DngInfo, look: Look, sink: TileSink): ProcessStats {
        val w = info.width
        val h = info.height
        require(merged.size == w * h) { "merged RAW size ${merged.size} != ${w}x$h" }
        // White-balance gains: 1 / AsShotNeutral, normalised so green = 1
        // (same as the reference: wb = (1/asn) / (1/asn[1])), then the
        // look's temp (R up, B down) and tint (G). Round-2 note: an
        // earlier version multiplied the green gain by asn[1] instead of
        // normalising it to 1.0; that was dormant here (asn green is
        // exactly 1.0 in every DNG this device writes) but diverged from
        // the reference, so it is corrected here.
        val wb = FloatArray(3)
        val n0 = info.asShotNeutral[0]; val n1 = info.asShotNeutral[1]; val n2 = info.asShotNeutral[2]
        val g0 = if (n0 > 0f) 1f / n0 else 1f
        val g2 = if (n2 > 0f) 1f / n2 else 1f
        val gn = if (n1 > 0f) n1 else 1f // green is 1.0 in these files
        wb[0] = g0 * gn * look.temp
        wb[1] = look.tint
        wb[2] = g2 * gn / look.temp

        // Colour matrix: ForwardMatrix1 maps to XYZ D50, so combine it
        // with M_XYZ2SRGB (XYZ D50 -> linear sRGB) per file. Round 1
        // used ForwardMatrix1 directly as sRGB — that was the green-cast
        // bug (round-2 report §2).
        val colourMatrix = matMul(M_XYZ2SRGB, info.forwardMatrix)

        // Automatic base exposure from the green photosites of the merged RAW.
        val greenMedian = greenMedian(merged, info)
        val baseGain = 0.30f / max(greenMedian, 1e-4f)
        val gain = baseGain * 2f.pow(look.ev)

        var y0 = 0
        while (y0 < h) {
            val tileH = min(TILE_ROWS, h - y0)
            val tile = demosaicTile(merged, info, 0, y0, w, tileH)
            finishTile(tile, w, tileH, colourMatrix, wb, look, gain, 0, y0, sink)
            y0 += tileH
        }
        return ProcessStats(baseGain, gain, greenMedian, wb)
    }

    data class ProcessStats(
        val baseGain: Float,
        val totalGain: Float,
        val greenMedian: Float,
        val wbGains: FloatArray
    )

    /** Median of the green photosite values (0..1) via a 256-bin histogram. */
    private fun greenMedian(merged: FloatArray, info: DngInfo): Float {
        val w = info.width
        val h = info.height
        val bins = IntArray(256)
        var total = 0L
        for (y in 0 until h) {
            val rowBase = y * w
            val gy = y and 1
            for (x in 0 until w) {
                if (info.cfaPattern[gy * 2 + (x and 1)] == 1) {
                    val v = merged[rowBase + x].coerceIn(0f, 1f)
                    bins[(v * 255f).toInt().coerceIn(0, 255)]++
                    total++
                }
            }
        }
        if (total == 0L) return 0.5f
        val half = total / 2
        var acc = 0L
        for (b in 0 until 256) {
            acc += bins[b]
            if (acc >= half) return (b + 0.5f) / 255f
        }
        return 1f
    }

    private fun cfaAt(info: DngInfo, x: Int, y: Int): Int {
        return info.cfaPattern[(y and 1) * 2 + (x and 1)]
    }

    /** Bilinear demosaic of one tile of the merged RAW. Output: linear RGB floats. */
    private fun demosaicTile(
        merged: FloatArray,
        info: DngInfo,
        x0: Int,
        y0: Int,
        w: Int,
        h: Int
    ): FloatArray {
        val out = FloatArray(w * h * 3)
        val fw = info.width
        val fh = info.height
        fun sample(x: Int, y: Int): Float {
            val cx = x.coerceIn(0, fw - 1)
            val cy = y.coerceIn(0, fh - 1)
            return merged[cy * fw + cx]
        }
        for (y in 0 until h) {
            val gy = y0 + y
            for (x in 0 until w) {
                val gx = x0 + x
                val c = cfaAt(info, gx, gy)
                val o = (y * w + x) * 3
                when (c) {
                    0 -> { // R photosite
                        out[o] = sample(gx, gy)
                        out[o + 1] = (sample(gx - 1, gy) + sample(gx + 1, gy) + sample(gx, gy - 1) + sample(gx, gy + 1)) * 0.25f
                        out[o + 2] = (sample(gx - 1, gy - 1) + sample(gx + 1, gy - 1) + sample(gx - 1, gy + 1) + sample(gx + 1, gy + 1)) * 0.25f
                    }
                    2 -> { // B photosite
                        out[o] = (sample(gx - 1, gy - 1) + sample(gx + 1, gy - 1) + sample(gx - 1, gy + 1) + sample(gx + 1, gy + 1)) * 0.25f
                        out[o + 1] = (sample(gx - 1, gy) + sample(gx + 1, gy) + sample(gx, gy - 1) + sample(gx, gy + 1)) * 0.25f
                        out[o + 2] = sample(gx, gy)
                    }
                    else -> { // G photosite: neighbours left/right are R on an R row, B on a B row
                        val leftRight = cfaAt(info, gx - 1, gy)
                        out[o + 1] = sample(gx, gy)
                        if (leftRight == 0) {
                            out[o] = (sample(gx - 1, gy) + sample(gx + 1, gy)) * 0.5f
                            out[o + 2] = (sample(gx, gy - 1) + sample(gx, gy + 1)) * 0.5f
                        } else {
                            out[o + 2] = (sample(gx - 1, gy) + sample(gx + 1, gy)) * 0.5f
                            out[o] = (sample(gx, gy - 1) + sample(gx, gy + 1)) * 0.5f
                        }
                    }
                }
            }
        }
        return out
    }

    /** Denoise, colour, exposure + rolloff, gamma, curve, lift, saturation, sharpen -> ARGB tile. */
    private fun finishTile(
        tile: FloatArray,
        w: Int,
        h: Int,
        fwd: FloatArray,
        wb: FloatArray,
        look: Look,
        gain: Float,
        tileX: Int,
        tileY: Int,
        sink: TileSink
    ) {
        val px = w * h
        // Denoise on linear camera-RGB (box-blur blend, before white balance).
        val clean = if (look.denoise > 0f) boxBlurBlend(tile, w, h, look.denoise) else tile
        val rgb = FloatArray(px * 3)
        for (i in 0 until px) {
            val r = clean[i * 3] * wb[0]
            val g = clean[i * 3 + 1] * wb[1]
            val b = clean[i * 3 + 2] * wb[2]
            var lr = fwd[0] * r + fwd[1] * g + fwd[2] * b
            var lg = fwd[3] * r + fwd[4] * g + fwd[5] * b
            var lb = fwd[6] * r + fwd[7] * g + fwd[8] * b
            lr = if (lr < 0f) 0f else lr
            lg = if (lg < 0f) 0f else lg
            lb = if (lb < 0f) 0f else lb
            // Exposure + highlight rolloff (linear, above 0.75).
            rgb[i * 3] = rolloff(lr * gain, look.hl)
            rgb[i * 3 + 1] = rolloff(lg * gain, look.hl)
            rgb[i * 3 + 2] = rolloff(lb * gain, look.hl)
        }
        // Gamma 2.2, then the look's extra display gamma, then its tone curve.
        for (i in 0 until px * 3) {
            var v = rgb[i].coerceIn(0f, 1f).toDouble().pow(1.0 / 2.2).toFloat()
            if (look.gamma != 1f) v = v.toDouble().pow(look.gamma.toDouble()).toFloat()
            v = look.curveAt(v)
            if (look.shadow > 0f) v += look.shadow * (1f - v) * (1f - v)
            rgb[i] = v.coerceIn(0f, 1f)
        }
        // Saturation about luma.
        if (look.sat != 1f) {
            for (i in 0 until px) {
                val r = rgb[i * 3]
                val g = rgb[i * 3 + 1]
                val b = rgb[i * 3 + 2]
                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                rgb[i * 3] = (luma + (r - luma) * look.sat).coerceIn(0f, 1f)
                rgb[i * 3 + 1] = (luma + (g - luma) * look.sat).coerceIn(0f, 1f)
                rgb[i * 3 + 2] = (luma + (b - luma) * look.sat).coerceIn(0f, 1f)
            }
        }
        // Unsharp mask (3x3 box blur, display space).
        val blurred = if (look.sharpen > 0f) boxBlur(rgb, w, h) else null
        val argb = IntArray(px)
        for (i in 0 until px) {
            var r = rgb[i * 3]
            var g = rgb[i * 3 + 1]
            var b = rgb[i * 3 + 2]
            if (blurred != null) {
                r = (r + look.sharpen * (r - blurred[i * 3])).coerceIn(0f, 1f)
                g = (g + look.sharpen * (g - blurred[i * 3 + 1])).coerceIn(0f, 1f)
                b = (b + look.sharpen * (b - blurred[i * 3 + 2])).coerceIn(0f, 1f)
            }
            val ri = (r * 255f + 0.5f).toInt()
            val gi = (g * 255f + 0.5f).toInt()
            val bi = (b * 255f + 0.5f).toInt()
            argb[i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
        }
        sink.onTile(tileX, tileY, w, h, argb)
    }

    private fun rolloff(x: Float, hl: Float): Float {
        if (x <= 0.75f) return x.coerceIn(0f, 1f)
        return (0.75f + (x - 0.75f) / (1f + hl * (x - 0.75f))).coerceIn(0f, 1f)
    }

    /** 3x3 box blur (edge-replicated by clamping), blended by [amount]. */
    private fun boxBlurBlend(src: FloatArray, w: Int, h: Int, amount: Float): FloatArray {
        val blur = boxBlur(src, w, h)
        val out = FloatArray(src.size)
        for (i in src.indices) out[i] = src[i] + (blur[i] - src[i]) * amount
        return out
    }

    /** 3x3 box blur (edge-replicated by clamping). */
    private fun boxBlur(src: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                for (c in 0..2) {
                    var acc = 0f
                    for (dy in -1..1) {
                        val ny = (y + dy).coerceIn(0, h - 1)
                        for (dx in -1..1) {
                            val nx = (x + dx).coerceIn(0, w - 1)
                            acc += src[(ny * w + nx) * 3 + c]
                        }
                    }
                    out[(y * w + x) * 3 + c] = acc / 9f
                }
            }
        }
        return out
    }
}
