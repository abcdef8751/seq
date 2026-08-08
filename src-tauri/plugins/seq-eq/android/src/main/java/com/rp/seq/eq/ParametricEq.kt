package com.rp.seq.eq

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Gapless RBJ peaking EQ — same algorithm as desktop pipewire_eq.rs */
class ParametricEq {
    @Volatile
    private var sampleRate = 48000f

    private val lock = Any()
    private var preamp = 1f
    private var bands: Array<Band> = emptyArray()
    private var sourceJson: String = ""

    fun setSampleRate(rate: Float) {
        if (rate <= 0f) return
        synchronized(lock) {
            if (abs(sampleRate - rate) < 0.5f) return
            sampleRate = rate
            rebuildCoeffs()
        }
    }

    fun applyJson(json: String) {
        synchronized(lock) {
            sourceJson = json
            val cfg = EqConfigJson.parse(json)
            preamp = 10f.pow(cfg.preamp.toFloat() / 20f)
            val old = bands
            bands = Array(cfg.bands.size) { i ->
                val b = cfg.bands[i]
                val active = isActiveKind(b.kind) && abs(b.gain) > 0.01
                val coeff = coeffs(b.kind, b.centerFreq, b.q, b.gain, sampleRate)
                if (i < old.size) {
                    old[i].coeff = coeff
                    old[i].active = active
                    old[i]
                } else {
                    Band(coeff, active)
                }
            }
        }
    }

    /** Interleaved stereo float process in-place-ish (input → output). */
    fun processFloat(input: FloatArray, output: FloatArray, n: Int) {
        val frames = minOf(n, input.size, output.size)
        synchronized(lock) {
            val p = preamp
            val bs = bands
            var i = 0
            while (i < frames) {
                var l = input[i] * p
                var r = if (i + 1 < frames) input[i + 1] * p else l
                for (band in bs) {
                    if (!band.active) continue
                    l = band.processL(l)
                    r = band.processR(r)
                }
                output[i] = l
                if (i + 1 < frames) output[i + 1] = r
                i += 2
            }
        }
    }

    private fun rebuildCoeffs() {
        if (sourceJson.isEmpty()) return
        val cfg = EqConfigJson.parse(sourceJson)
        preamp = 10f.pow(cfg.preamp.toFloat() / 20f)
        for (i in bands.indices) {
            if (i >= cfg.bands.size) break
            val b = cfg.bands[i]
            bands[i].coeff = coeffs(b.kind, b.centerFreq, b.q, b.gain, sampleRate)
            bands[i].active = isActiveKind(b.kind) && abs(b.gain) > 0.01
        }
    }

    private class Band(var coeff: Biquad, var active: Boolean) {
        var lx1 = 0f
        var lx2 = 0f
        var ly1 = 0f
        var ly2 = 0f
        var rx1 = 0f
        var rx2 = 0f
        var ry1 = 0f
        var ry2 = 0f

        fun processL(x: Float): Float {
            val c = coeff
            val y = c.b0 * x + c.b1 * lx1 + c.b2 * lx2 - c.a1 * ly1 - c.a2 * ly2
            lx2 = lx1; lx1 = x; ly2 = ly1; ly1 = y
            return y
        }

        fun processR(x: Float): Float {
            val c = coeff
            val y = c.b0 * x + c.b1 * rx1 + c.b2 * rx2 - c.a1 * ry1 - c.a2 * ry2
            rx2 = rx1; rx1 = x; ry2 = ry1; ry1 = y
            return y
        }
    }

    private data class Biquad(
        val b0: Float,
        val b1: Float,
        val b2: Float,
        val a1: Float,
        val a2: Float,
    )

    private fun isActiveKind(kind: String): Boolean =
        kind == "PK" || kind == "LSQ" || kind == "HSQ"

    private fun coeffs(kind: String, freq: Double, q: Double, gainDb: Double, rate: Float): Biquad {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freq / rate.toDouble()
        val cosw0 = cos(w0)
        if (kind == "PK") {
            val alpha = sin(w0) / (2.0 * q.coerceAtLeast(1e-3))
            val a0 = 1.0 + alpha / a
            return Biquad(
                b0 = ((1.0 + alpha * a) / a0).toFloat(),
                b1 = ((-2.0 * cosw0) / a0).toFloat(),
                b2 = ((1.0 - alpha * a) / a0).toFloat(),
                a1 = ((-2.0 * cosw0) / a0).toFloat(),
                a2 = ((1.0 - alpha / a) / a0).toFloat(),
            )
        }
        // Q-based shelves (RBJ "LSQ/HSQ"); clamp the sqrt argument like the desktop DSP.
        val inner = ((a + 1.0 / a) * (1.0 / q.coerceAtLeast(1e-3) - 1.0) + 2.0).coerceAtLeast(0.0)
        val alpha = sin(w0) / 2.0 * sqrt(inner)
        val sa = sqrt(a)
        val low = kind == "LSQ"
        val b0 = if (low) a * ((a + 1.0) - (a - 1.0) * cosw0 + 2.0 * sa * alpha) else a * ((a + 1.0) + (a - 1.0) * cosw0 + 2.0 * sa * alpha)
        val b1 = if (low) 2.0 * a * ((a - 1.0) - (a + 1.0) * cosw0) else -2.0 * a * ((a - 1.0) + (a + 1.0) * cosw0)
        val b2 = if (low) a * ((a + 1.0) - (a - 1.0) * cosw0 - 2.0 * sa * alpha) else a * ((a + 1.0) + (a - 1.0) * cosw0 - 2.0 * sa * alpha)
        val a0 = if (low) (a + 1.0) + (a - 1.0) * cosw0 + 2.0 * sa * alpha else (a + 1.0) - (a - 1.0) * cosw0 + 2.0 * sa * alpha
        val a1 = if (low) -2.0 * ((a - 1.0) + (a + 1.0) * cosw0) else 2.0 * ((a - 1.0) - (a + 1.0) * cosw0)
        val a2 = if (low) (a + 1.0) + (a - 1.0) * cosw0 - 2.0 * sa * alpha else (a + 1.0) - (a - 1.0) * cosw0 - 2.0 * sa * alpha
        return Biquad(
            b0 = (b0 / a0).toFloat(),
            b1 = (b1 / a0).toFloat(),
            b2 = (b2 / a0).toFloat(),
            a1 = (a1 / a0).toFloat(),
            a2 = (a2 / a0).toFloat(),
        )
    }
}

data class BandJson(
    val kind: String,
    val centerFreq: Double,
    val q: Double,
    val gain: Double,
)

data class EqConfigJson(
    val preamp: Double,
    val bands: List<BandJson>,
) {
    companion object {
        fun parse(json: String): EqConfigJson {
            // Minimal JSON parse without extra deps — expects serde shape from Rust
            val preamp = Regex(""""preamp"\s*:\s*(-?[0-9.eE+-]+)""")
                .find(json)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val bandBlocks = Regex("""\{[^{}]*"kind"[^{}]*\}""")
                .findAll(json)
                .map { it.value }
                .toList()
            val bands = bandBlocks.map { block ->
                fun num(key: String, default: Double): Double =
                    Regex(""""$key"\s*:\s*(-?[0-9.eE+-]+)""")
                        .find(block)?.groupValues?.get(1)?.toDoubleOrNull() ?: default
                fun str(key: String, default: String): String =
                    Regex(""""$key"\s*:\s*"([^"]+)"""")
                        .find(block)?.groupValues?.get(1) ?: default
                BandJson(
                    kind = str("kind", "PK"),
                    centerFreq = num("center_freq", 1000.0),
                    q = num("q", 1.0),
                    gain = num("gain", 0.0),
                )
            }
            return EqConfigJson(preamp, bands)
        }
    }
}
