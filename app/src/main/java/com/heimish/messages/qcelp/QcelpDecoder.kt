/*
 * QCELP (PureVoice, TIA/EIA/IS-733) decoder — Kotlin port of FFmpeg's libavcodec/qcelpdec.c, qcelpdata.h and the
 * CELP/ACELP helpers it uses (celp_filters.c, acelp_filters.c, acelp_vectors.c, lsp.c).
 * Original: Copyright (c) 2007 Reynaldo H. Verdejo Pinochet (FFmpeg merging by Kenan Gillet), and the FFmpeg authors.
 * Port: Copyright (c) 2026 Heimish Messages.
 *
 * This file is free software; you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation; either version 2.1 of the License, or (at your
 * option) any later version.
 *
 * This file is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for
 * more details. You should have received a copy of the GNU Lesser General Public License along with this file; if
 * not, write to the Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */
package com.heimish.messages.qcelp

import com.heimish.messages.qcelp.QcelpTables as T
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One QCELP-13K stream decoder (stateful: feed frames in order). Each frame gives 160 samples (20 ms at 8 kHz).
 * Rates: 4 = full (34 payload bytes), 3 = half (16), 2 = quarter (7), 1 = eighth (3), 0 = blank (0); anything that
 * can't be decoded is treated as an erasure, exactly like FFmpeg.
 */
class QcelpDecoder {
    companion object {
        const val I_F_Q = -1; const val SILENCE = 0; const val RATE_OCTAVE = 1; const val RATE_QUARTER = 2
        const val RATE_HALF = 3; const val RATE_FULL = 4
        const val SAMPLES_PER_FRAME = 160

        /** Payload size (without the rate byte) per rate, as in the QCP rate map. */
        val PAYLOAD_BYTES = intArrayOf(0, 3, 7, 16, 34)

        /** FFmpeg's buf_size2bitrate (a packet size INCLUDING the rate byte). */
        fun rateForPacketSize(size: Int): Int = when (size) { 35 -> RATE_FULL; 17 -> RATE_HALF; 8 -> RATE_QUARTER; 4 -> RATE_OCTAVE; 1 -> SILENCE; else -> I_F_Q }

        private val POW_0_775 = floatArrayOf(0.775000f, 0.600625f, 0.465484f, 0.360750f, 0.279582f, 0.216676f, 0.167924f, 0.130141f, 0.100859f, 0.078166f)
        private val POW_0_625 = floatArrayOf(0.625000f, 0.390625f, 0.244141f, 0.152588f, 0.095367f, 0.059605f, 0.037253f, 0.023283f, 0.014552f, 0.009095f)
    }

    private var bitrate = SILENCE
    private val frame = IntArray(T.FRAME_BYTES)
    private var erasureCount = 0
    private var octaveCount = 0
    private val prevLspf = FloatArray(10) { (it + 1) / 11.0f }
    private val predictorLspf = FloatArray(10)
    private val pitchSynthesisFilterMem = FloatArray(303)
    private val pitchPreFilterMem = FloatArray(303)
    private val rndFirFilterMem = FloatArray(180)
    private val formantMem = FloatArray(170)
    private var lastCodebookGain = 0f
    private val prevG1 = IntArray(2)
    private var prevBitrate = SILENCE
    private val pitchGain = FloatArray(4)
    private val pitchLag = IntArray(4)
    private var first16bits = 0
    private val postfilterSynthMem = FloatArray(10)
    private var postfilterAgcMem = 0f
    private var postfilterTiltMem = 0f

    /** Counts of frames decoded per rate (index rate+1, so [0] = erasures). */
    val rateCounts = IntArray(6)

    /**
     * Decodes one packet [len] bytes at [off] in [buf] — the payload WITHOUT the rate byte (as the QCP demuxer hands
     * it), or with it (35/17/8/4/1 bytes). Writes 160 float samples (about −1…1) to [out] at [outOff].
     */
    fun decodeFrame(buf: ByteArray, off: Int, len: Int, out: FloatArray, outOff: Int) {
        val v = FloatArray(160)
        try {
            decode(buf, off, len, v)
        } catch (e: RuntimeException) {
            // Never seen; keeps a damaged frame from killing the whole note.
            v.fill(0f)
        }
        System.arraycopy(v, 0, out, outOff, 160)
    }

    private fun u8(b: Byte) = b.toInt() and 0xFF

    private fun decode(buf: ByteArray, off0: Int, len0: Int, outbuffer: FloatArray) {
        var off = off0
        var len = len0
        val quantizedLspf = FloatArray(10)
        val lpc = FloatArray(10)
        val gain = FloatArray(16)
        var erasure = false

        // determine_bitrate
        var br = rateForPacketSize(len)
        if (br >= 0) {
            val claimed = u8(buf[off])
            if (br > claimed) br = claimed
            else if (br < claimed) br = I_F_Q
            if (br != I_F_Q) { off++; len-- }
        } else {
            br = rateForPacketSize(len + 1)
        }
        bitrate = br
        if (bitrate == I_F_Q) erasure = true

        if (!erasure && bitrate == RATE_OCTAVE) {
            first16bits = (u8(buf[off]) shl 8) or u8(buf[off + 1])
            if (first16bits == 0xFFFF) erasure = true
        }

        if (!erasure && bitrate > SILENCE) {
            frame.fill(0)
            val bm = T.BITMAPS[bitrate]
            var bitPos = 0
            fun getBits(n: Int): Int {
                var r = 0
                for (k in 0 until n) {
                    val byte = off + (bitPos shr 3)
                    val bit = if (byte < off + len) (u8(buf[byte]) shr (7 - (bitPos and 7))) and 1 else 0
                    r = (r shl 1) or bit
                    bitPos++
                }
                return r
            }
            var k = 0
            while (k < bm.size) {
                frame[bm[k]] = (frame[bm[k]] or (getBits(bm[k + 2]) shl bm[k + 1])) and 0xFF
                k += 3
            }
            if (frame[T.RESERVED] != 0) erasure = true
            else if (bitrate == RATE_QUARTER && codebookSanityCheckForRateQuarter()) erasure = true
            else if (bitrate >= RATE_HALF) {
                for (i in 0 until 4) if (frame[T.PFRAC + i] != 0 && frame[T.PLAG + i] >= 124) { erasure = true; break }
            }
        }

        if (!erasure) {
            decodeGainAndIndex(gain)
            computeSvector(gain, outbuffer)
            if (decodeLspf(quantizedLspf) < 0) erasure = true
            else applyPitchFilters(outbuffer)
        }
        if (erasure) {
            bitrate = I_F_Q
            erasureCount++
            decodeGainAndIndex(gain)
            computeSvector(gain, outbuffer)
            decodeLspf(quantizedLspf)
            applyPitchFilters(outbuffer)
        } else {
            erasureCount = 0
        }
        if (erasureCount > 255) erasureCount = 255 // uint8_t in FFmpeg saturates differently; keeps the >=4 branch
        rateCounts[bitrate + 1]++

        for (i in 0 until 4) {
            interpolateLpc(quantizedLspf, lpc, i)
            lpSynthesis(formantMem, 10 + i * 40, lpc, outbuffer, i * 40, 40, 10)
        }
        postfilter(outbuffer, lpc)
        System.arraycopy(formantMem, 160, formantMem, 0, 10)
        System.arraycopy(quantizedLspf, 0, prevLspf, 0, 10)
        prevBitrate = bitrate
    }

    private fun decodeLspf(lspf: FloatArray): Int {
        if (bitrate == RATE_OCTAVE || bitrate == I_F_Q) {
            val predictors = if (prevBitrate != RATE_OCTAVE && prevBitrate != I_F_Q) prevLspf else predictorLspf
            val smooth: Float
            if (bitrate == RATE_OCTAVE) {
                octaveCount = min(octaveCount + 1, 255)
                for (i in 0 until 10) {
                    val x = ((if (frame[T.LSPV + i] != 0) T.LSP_SPREAD_FACTOR else -T.LSP_SPREAD_FACTOR) +
                        predictors[i] * T.LSP_OCTAVE_PREDICTOR + (i + 1) * ((1 - T.LSP_OCTAVE_PREDICTOR) / 11)).toFloat()
                    predictorLspf[i] = x; lspf[i] = x
                }
                smooth = if (octaveCount < 10) .875f else 0.1f
            } else {
                var erasureCoeff = T.LSP_OCTAVE_PREDICTOR.toFloat()
                if (erasureCount > 1) erasureCoeff = (erasureCoeff * if (erasureCount < 4) 0.9 else 0.7).toFloat()
                for (i in 0 until 10) {
                    val x = ((i + 1) * (1 - erasureCoeff) / 11 + erasureCoeff * predictors[i])
                    predictorLspf[i] = x; lspf[i] = x
                }
                smooth = 0.125f
            }
            lspf[0] = max(lspf[0].toDouble(), T.LSP_SPREAD_FACTOR).toFloat()
            for (i in 1 until 10) lspf[i] = max(lspf[i].toDouble(), lspf[i - 1] + T.LSP_SPREAD_FACTOR).toFloat()
            lspf[9] = min(lspf[9].toDouble(), 1.0 - T.LSP_SPREAD_FACTOR).toFloat()
            for (i in 9 downTo 1) lspf[i - 1] = min(lspf[i - 1].toDouble(), lspf[i] - T.LSP_SPREAD_FACTOR).toFloat()
            for (i in 0 until 10) lspf[i] = smooth * lspf[i] + (1.0f - smooth) * prevLspf[i]
        } else {
            octaveCount = 0
            var tmp = 0.0f
            for (i in 0 until 5) {
                val vq = T.LSPVQ[i]; val idx = frame[T.LSPV + i]
                tmp = (tmp + vq[idx * 2] * 0.0001).toFloat(); lspf[2 * i] = tmp
                tmp = (tmp + vq[idx * 2 + 1] * 0.0001).toFloat(); lspf[2 * i + 1] = tmp
            }
            if (bitrate == RATE_QUARTER) {
                if (lspf[9] <= .70 || lspf[9] >= .97) return -1
                for (i in 3 until 10) if (abs(lspf[i] - lspf[i - 2]) < .08) return -1
            } else {
                if (lspf[9] <= .66 || lspf[9] >= .985) return -1
                for (i in 4 until 10) if (abs(lspf[i] - lspf[i - 4]) < .0931) return -1
            }
        }
        return 0
    }

    private fun decodeGainAndIndex(gain: FloatArray) {
        val g1 = IntArray(16)
        if (bitrate >= RATE_QUARTER) {
            val n = when (bitrate) { RATE_FULL -> 16; RATE_HALF -> 4; else -> 5 }
            for (i in 0 until n) {
                g1[i] = 4 * frame[T.CBGAIN + i]
                if (bitrate == RATE_FULL && ((i + 1) and 3) == 0) {
                    g1[i] += ((g1[i - 1] + g1[i - 2] + g1[i - 3]) / 3 - 6).coerceIn(0, 32)
                }
                gain[i] = T.G12GA[g1[i]]
                if (frame[T.CBSIGN + i] != 0) {
                    gain[i] = -gain[i]
                    frame[T.CINDEX + i] = (frame[T.CINDEX + i] - 89) and 127
                }
            }
            prevG1[0] = g1[n - 2]
            prevG1[1] = g1[n - 1]
            lastCodebookGain = T.G12GA[g1[n - 1]]
            if (bitrate == RATE_QUARTER) {
                gain[7] = gain[4]
                gain[6] = (0.4 * gain[3] + 0.6 * gain[4]).toFloat()
                gain[5] = gain[3]
                gain[4] = (0.8 * gain[2] + 0.2 * gain[3]).toFloat()
                gain[3] = (0.2 * gain[1] + 0.8 * gain[2]).toFloat()
                gain[2] = gain[1]
                gain[1] = (0.6 * gain[0] + 0.4 * gain[1]).toFloat()
            }
        } else if (bitrate != SILENCE) {
            val n: Int
            if (bitrate == RATE_OCTAVE) {
                g1[0] = 2 * frame[T.CBGAIN] + ((prevG1[0] + prevG1[1]) / 2 - 5).coerceIn(0, 54)
                n = 8
            } else {
                g1[0] = prevG1[1]
                when (erasureCount) { 1 -> {}; 2 -> g1[0] -= 1; 3 -> g1[0] -= 2; else -> g1[0] -= 6 }
                if (g1[0] < 0) g1[0] = 0
                n = 4
            }
            g1[0] = g1[0].coerceAtMost(60)
            val slope = (0.5 * (T.G12GA[g1[0]] - lastCodebookGain) / n).toFloat()
            for (i in 1..n) gain[i - 1] = lastCodebookGain + slope * i
            lastCodebookGain = gain[n - 1]
            prevG1[0] = prevG1[1]
            prevG1[1] = g1[0]
        }
    }

    private fun codebookSanityCheckForRateQuarter(): Boolean {
        var prevDiff = 0
        for (i in 1 until 5) {
            val diff = frame[T.CBGAIN + i] - frame[T.CBGAIN + i - 1]
            if (abs(diff) > 10) return true
            else if (abs(diff - prevDiff) > 12) return true
            prevDiff = diff
        }
        return false
    }

    private fun computeSvector(gain: FloatArray, cdn: FloatArray) {
        var p = 0
        when (bitrate) {
            RATE_FULL -> for (i in 0 until 16) {
                val g = (gain[i] * T.RATE_FULL_CODEBOOK_RATIO).toFloat()
                var c = -frame[T.CINDEX + i]
                for (j in 0 until 10) cdn[p++] = g * T.RATE_FULL_CODEBOOK[c++ and 127]
            }
            RATE_HALF -> for (i in 0 until 4) {
                val g = (gain[i] * T.RATE_HALF_CODEBOOK_RATIO).toFloat()
                var c = -frame[T.CINDEX + i]
                for (j in 0 until 40) cdn[p++] = g * T.RATE_HALF_CODEBOOK[c++ and 127]
            }
            RATE_QUARTER -> {
                val l = T.LSPV
                var seed = ((0x0003 and frame[l + 4]) shl 14) or ((0x003F and frame[l + 3]) shl 8) or
                    ((0x0060 and frame[l + 2]) shl 1) or ((0x0007 and frame[l + 1]) shl 3) or ((0x0038 and frame[l]) shr 3)
                seed = seed and 0xFFFF
                var rnd = 20
                for (i in 0 until 8) {
                    val g = (gain[i] * (T.SQRT1887 / 32768.0)).toFloat()
                    for (k in 0 until 20) {
                        seed = (521 * seed + 259) and 0xFFFF
                        rndFirFilterMem[rnd] = seed.toShort().toFloat()
                        var fir = 0.0f
                        for (j in 0 until 10) fir = (fir + T.RND_FIR_COEFS[j] * (rndFirFilterMem[rnd - j] + rndFirFilterMem[rnd - 20 + j])).toFloat()
                        fir = (fir + T.RND_FIR_COEFS[10] * rndFirFilterMem[rnd - 10]).toFloat()
                        cdn[p++] = g * fir
                        rnd++
                    }
                }
                System.arraycopy(rndFirFilterMem, 160, rndFirFilterMem, 0, 20)
            }
            RATE_OCTAVE -> {
                var seed = first16bits
                for (i in 0 until 8) {
                    val g = (gain[i] * (T.SQRT1887 / 32768.0)).toFloat()
                    for (j in 0 until 20) {
                        seed = (521 * seed + 259) and 0xFFFF
                        cdn[p++] = g * seed.toShort()
                    }
                }
            }
            I_F_Q -> {
                var seed = -44
                for (i in 0 until 4) {
                    val g = (gain[i] * T.RATE_FULL_CODEBOOK_RATIO).toFloat()
                    for (j in 0 until 40) cdn[p++] = g * T.RATE_FULL_CODEBOOK[seed++ and 127]
                }
            }
            else -> cdn.fill(0f, 0, 160)
        }
    }

    private fun dot(a: FloatArray, ao: Int, b: FloatArray, bo: Int, n: Int): Float {
        var s = 0.0f
        for (i in 0 until n) s += a[ao + i] * b[bo + i]
        return s
    }

    private fun applyGainCtrl(out: FloatArray, ref: FloatArray, refOff: Int, inp: FloatArray, inOff: Int) {
        var i = 0
        while (i < 160) {
            val res = dot(ref, refOff + i, ref, refOff + i, 40)
            var sf = dot(inp, inOff + i, inp, inOff + i, 40)
            if (sf != 0f) sf = sqrt(res.toDouble() / sf).toFloat()
            for (k in 0 until 40) out[i + k] = inp[inOff + i + k] * sf
            i += 40
        }
    }

    /** FFmpeg's do_pitchfilter: output lands in memory[143..303). */
    private fun doPitchFilter(memory: FloatArray, vIn: FloatArray, vInOff: Int, gain: FloatArray, lag: IntArray, pfrac: Int) {
        var vo = 143
        var vi = vInOff
        val tmp = FloatArray(160)
        System.arraycopy(vIn, vInOff, tmp, 0, 160) // vIn may be another filter's memory; take it before writing
        vi = 0
        for (i in 0 until 4) {
            if (gain[i] != 0f) {
                var vl = 143 + 40 * i - lag[i]
                for (k in 0 until 40) {
                    var o: Float
                    if (frame[pfrac + i] != 0) {
                        o = 0f
                        for (j in 0 until 4) o += T.HAMMSINC[j] * (memory[vl + j - 4] + memory[vl + 3 - j])
                    } else {
                        o = memory[vl]
                    }
                    memory[vo] = tmp[vi] + gain[i] * o
                    vi++; vl++; vo++
                }
            } else {
                System.arraycopy(tmp, vi, memory, vo, 40)
                vi += 40; vo += 40
            }
        }
        System.arraycopy(memory, 160, memory, 0, 143)
    }

    private fun applyPitchFilters(cdn: FloatArray) {
        if (bitrate >= RATE_HALF || bitrate == SILENCE || (bitrate == I_F_Q && prevBitrate >= RATE_HALF)) {
            if (bitrate >= RATE_HALF) {
                for (i in 0 until 4) {
                    pitchGain[i] = if (frame[T.PLAG + i] != 0) ((frame[T.PGAIN + i] + 1) * 0.25).toFloat() else 0.0f
                    pitchLag[i] = frame[T.PLAG + i] + 16
                }
            } else {
                val maxPitchGain = if (bitrate == I_F_Q) {
                    if (erasureCount < 3) (0.9 - 0.3 * (erasureCount - 1)).toFloat() else 0.0f
                } else 1.0f
                for (i in 0 until 4) pitchGain[i] = min(pitchGain[i], maxPitchGain)
                for (i in 0 until 4) frame[T.PFRAC + i] = 0
            }
            doPitchFilter(pitchSynthesisFilterMem, cdn, 0, pitchGain, pitchLag, T.PFRAC)
            for (i in 0 until 4) pitchGain[i] = 0.5f * min(pitchGain[i], 1.0f)
            doPitchFilter(pitchPreFilterMem, pitchSynthesisFilterMem, 143, pitchGain, pitchLag, T.PFRAC)
            applyGainCtrl(cdn, pitchSynthesisFilterMem, 143, pitchPreFilterMem, 143)
        } else {
            System.arraycopy(cdn, 17, pitchSynthesisFilterMem, 0, 143)
            System.arraycopy(cdn, 17, pitchPreFilterMem, 0, 143)
            pitchGain.fill(0f)
            pitchLag.fill(0)
        }
    }

    private fun lspf2lpc(lspf: FloatArray, lpc: FloatArray) {
        val lsp = DoubleArray(10) { cos(PI * lspf[it]) }
        lspd2lpc(lsp, lpc, 5)
        var bw = T.BANDWIDTH_EXPANSION_COEFF
        for (i in 0 until 10) {
            lpc[i] = (lpc[i] * bw).toFloat()
            bw *= T.BANDWIDTH_EXPANSION_COEFF
        }
    }

    private fun interpolateLpc(curr: FloatArray, lpc: FloatArray, sub: Int) {
        val weight = when {
            bitrate >= RATE_QUARTER -> 0.25f * (sub + 1)
            bitrate == RATE_OCTAVE && sub == 0 -> 0.625f
            else -> 1.0f
        }
        if (weight != 1.0f) {
            val interp = FloatArray(10) { weight * curr[it] + (1.0f - weight) * prevLspf[it] }
            lspf2lpc(interp, lpc)
        } else if (bitrate >= RATE_QUARTER || (bitrate == I_F_Q && sub == 0)) {
            lspf2lpc(curr, lpc)
        } else if (bitrate == SILENCE && sub == 0) {
            lspf2lpc(prevLspf, lpc)
        }
    }

    private fun postfilter(samples: FloatArray, lpc: FloatArray) {
        val lpcS = FloatArray(10) { lpc[it] * POW_0_625[it] }
        val lpcP = FloatArray(10) { lpc[it] * POW_0_775[it] }
        val poleOut = FloatArray(170)
        val zeroOut = FloatArray(160)
        // ff_celp_lp_zero_synthesis_filterf(zero_out, lpc_s, formant_mem + 10, 160, 10)
        for (n in 0 until 160) {
            var o = formantMem[10 + n]
            for (i in 1..10) o += lpcS[i - 1] * formantMem[10 + n - i]
            zeroOut[n] = o
        }
        System.arraycopy(postfilterSynthMem, 0, poleOut, 0, 10)
        lpSynthesis(poleOut, 10, lpcP, zeroOut, 0, 160, 10)
        System.arraycopy(poleOut, 160, postfilterSynthMem, 0, 10)

        // ff_tilt_compensation(&tilt_mem, 0.3, pole_out + 10, 160)
        val newTilt = poleOut[10 + 159]
        for (i in 159 downTo 1) poleOut[10 + i] -= 0.3f * poleOut[10 + i - 1]
        poleOut[10] -= 0.3f * postfilterTiltMem
        postfilterTiltMem = newTilt

        // ff_adaptive_gain_control(samples, pole_out + 10, energy(formant_mem + 10), 160, 0.9375, &agc_mem)
        val speech = dot(formantMem, 10, formantMem, 10, 160)
        val post = dot(poleOut, 10, poleOut, 10, 160)
        var gsf = 1.0f
        if (post != 0f) gsf = sqrt(speech.toDouble() / post).toFloat()
        gsf = (gsf * (1.0 - 0.9375)).toFloat()
        var mem = postfilterAgcMem
        for (i in 0 until 160) {
            mem = 0.9375f * mem + gsf
            samples[i] = poleOut[10 + i] * mem
        }
        postfilterAgcMem = mem
    }

    /** ff_celp_lp_synthesis_filterf: out[o+n] = in[n] - sum(c[i-1] * out[o+n-i]). */
    private fun lpSynthesis(out: FloatArray, o: Int, c: FloatArray, inp: FloatArray, io: Int, len: Int, order: Int) {
        for (n in 0 until len) {
            var v = inp[io + n]
            for (i in 1..order) v -= c[i - 1] * out[o + n - i]
            out[o + n] = v
        }
    }

    /** ff_acelp_lspd2lpc + lsp2polyf (lsp.c). */
    private fun lspd2lpc(lsp: DoubleArray, lpc: FloatArray, halfOrder: Int) {
        val pa = DoubleArray(halfOrder + 1)
        val qa = DoubleArray(halfOrder + 1)
        lsp2polyf(lsp, 0, pa, halfOrder)
        lsp2polyf(lsp, 1, qa, halfOrder)
        var h = halfOrder
        val lpc2 = (halfOrder shl 1) - 1
        while (h-- > 0) {
            val paf = pa[h + 1] + pa[h]
            val qaf = qa[h + 1] - qa[h]
            lpc[h] = (0.5 * (paf + qaf)).toFloat()
            lpc[lpc2 - h] = (0.5 * (paf - qaf)).toFloat()
        }
    }

    private fun lsp2polyf(lsp: DoubleArray, base: Int, f: DoubleArray, halfOrder: Int) {
        f[0] = 1.0
        f[1] = -2 * lsp[base]
        for (i in 2..halfOrder) {
            val v = -2 * lsp[base + 2 * i - 2]
            f[i] = v * f[i - 1] + 2 * f[i - 2]
            for (j in i - 1 downTo 2) f[j] += f[j - 1] * v + f[j - 2]
            f[1] += v
        }
    }
}
