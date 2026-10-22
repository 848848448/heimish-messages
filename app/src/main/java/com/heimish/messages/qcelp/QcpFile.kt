/*
 * QCELP (PureVoice, TIA/EIA/IS-733) container (.qcp, RIFF/QLCM) reader — Kotlin port of FFmpeg's libavformat/qcp.c, plus
 * a PCM WAV writer.
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

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

/** A parsed Qualcomm PureVoice (.qcp) file: RIFF 'QLCM' with 'fmt ', optional 'vrat', and 'data' chunks. */
class QcpFile private constructor(
    val codec: Codec,
    val guidHex: String,
    val sampleRate: Int,
    val packetSize: Int,
    val variableRate: Boolean,
    /** Payload size (without the rate byte) per mode 0..4; -1 when the rate map has no entry. */
    val ratesPerMode: IntArray,
    /** Each packet as (mode, offset, length) into [bytes]; length excludes the rate byte. */
    val packets: List<IntArray>,
    val bytes: ByteArray,
) {
    enum class Codec { QCELP, EVRC, SMV, FOURGV, UNKNOWN }

    class QcpException(msg: String) : Exception(msg)

    /** Count of packets per mode (0 = blank … 4 = full). */
    fun modeCounts(): IntArray = IntArray(5).also { c -> packets.forEach { p -> if (p[0] in 0..4) c[p[0]]++ } }

    companion object {
        const val MAX_MODE = 4
        private val GUID_QCELP_13K_PART = bytes(0x6d, 0x7f, 0x5e, 0x15, 0xb1, 0xd0, 0x11, 0xba, 0x91, 0x00, 0x80, 0x5f, 0xb4, 0xb9, 0x7e)
        private val GUID_EVRC = bytes(0x8d, 0xd4, 0x89, 0xe6, 0x76, 0x90, 0xb5, 0x46, 0x91, 0xef, 0x73, 0x6a, 0x51, 0x00, 0xce, 0xb4)
        private val GUID_4GV = bytes(0xca, 0x29, 0xfd, 0x3c, 0x53, 0xf6, 0xf5, 0x4e, 0x90, 0xe9, 0xf4, 0x23, 0x6d, 0x59, 0x9b, 0x61)
        private val GUID_SMV = bytes(0x75, 0x2b, 0x7c, 0x8d, 0x97, 0xa7, 0x49, 0xed, 0x98, 0x5e, 0xd5, 0x3c, 0x8c, 0xc7, 0x5f, 0x84)
        /** QCELP-13K GUID as written in the file (first byte 0x41 or 0x42). */
        val GUID_QCELP_13K: ByteArray = byteArrayOf(0x41) + GUID_QCELP_13K_PART

        private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

        /** True when [head] starts like a .qcp file ("RIFF" ???? "QLCMfmt "). */
        fun looksLikeQcp(head: ByteArray): Boolean {
            if (head.size < 12) return false
            fun at(o: Int, s: String) = head.size >= o + s.length && s.indices.all { head[o + it] == s[it].code.toByte() }
            return at(0, "RIFF") && at(8, "QLCM")
        }

        /** True for the content types carriers use for .qcp voice notes. */
        fun isQcpMime(mime: String?): Boolean {
            val m = mime?.lowercase()?.substringBefore(';')?.trim() ?: return false
            return m == "audio/vnd.qcelp" || m == "audio/qcelp" || m == "audio/x-qcelp" || m == "audio/qcp" || m == "audio/x-qcp"
        }

        fun parse(b: ByteArray): QcpFile {
            if (!looksLikeQcp(b)) throw QcpException("not a QCP file (no RIFF/QLCM header)")
            fun rl16(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
            fun rl32(o: Int) = rl16(o) or (rl16(o + 2) shl 16)
            fun tag(o: Int) = String(b, o, 4, Charsets.ISO_8859_1)
            var pos = 12
            var codec: Codec? = null
            var guidHex = ""
            var sampleRate = 8000
            var packetSize = 0
            var variable = false
            val rates = IntArray(MAX_MODE + 1) { -1 }
            var dataOff = -1
            var dataLen = 0
            while (pos + 8 <= b.size) {
                val t = tag(pos)
                val size = rl32(pos + 4).toLong() and 0xFFFFFFFFL
                val body = pos + 8
                val avail = (b.size - body).toLong()
                val len = minOf(size, avail).toInt()
                when (t) {
                    "fmt " -> {
                        if (len < 2 + 16) throw QcpException("fmt chunk too short ($len)")
                        val g = b.copyOfRange(body + 2, body + 18)
                        guidHex = g.joinToString("") { "%02x".format(it) }
                        codec = when {
                            (g[0] == 0x41.toByte() || g[0] == 0x42.toByte()) && g.copyOfRange(1, 16).contentEquals(GUID_QCELP_13K_PART) -> Codec.QCELP
                            g.contentEquals(GUID_EVRC) -> Codec.EVRC
                            g.contentEquals(GUID_SMV) -> Codec.SMV
                            g.contentEquals(GUID_4GV) -> Codec.FOURGV
                            else -> Codec.UNKNOWN
                        }
                        // major, minor, guid(16), version(2), name(80), avg bps(2), packet size(2), block size(2),
                        // sample rate(2), sample size(2), rate count(4), rate map 8 x (size, mode), reserved(20)
                        val f = body + 2 + 16 + 2 + 80
                        if (len >= f - body + 14) {
                            packetSize = rl16(f + 2)
                            sampleRate = rl16(f + 6).takeIf { it > 0 } ?: 8000
                            val n = minOf(rl32(f + 10).toLong() and 0xFFFFFFFFL, 8L).toInt()
                            for (i in 0 until n) {
                                val o = f + 14 + 2 * i
                                if (o + 1 >= body + len) break
                                val sz = b[o].toInt() and 0xFF
                                val mode = b[o + 1].toInt() and 0xFF
                                if (mode <= MAX_MODE) rates[mode] = sz
                            }
                        }
                    }
                    "vrat" -> if (len >= 4 && rl32(body) != 0) { variable = true }
                    "data" -> { dataOff = body; dataLen = len }
                }
                if (t == "data") break
                pos = body + len + (len and 1)
            }
            if (codec == null) throw QcpException("no fmt chunk")
            if (dataOff < 0) throw QcpException("no data chunk")
            if (variable) packetSize = 0
            if (rates.all { it < 0 }) { // no rate map: the standard QCELP-13K sizes
                for (m in 0..MAX_MODE) rates[m] = QcelpDecoder.PAYLOAD_BYTES[m]
            }
            // qcp_read_packet
            val packets = ArrayList<IntArray>()
            var p = dataOff
            var left = dataLen
            while (left > 0 && p < b.size) {
                val mode = b[p].toInt() and 0xFF
                var size: Int
                if (packetSize != 0) size = packetSize - 1
                else if (mode > MAX_MODE || rates[mode] < 0) { p++; left--; continue }
                else size = rates[mode]
                if (left <= size) size = left - 1
                size = minOf(size, b.size - (p + 1)).coerceAtLeast(0)
                packets.add(intArrayOf(mode, p + 1, size))
                p += size + 1
                left -= size + 1
            }
            return QcpFile(codec, guidHex, sampleRate, packetSize, variable || packetSize == 0, rates, packets, b)
        }

        /** Writes a 16-bit mono PCM WAV. */
        fun writeWav(out: OutputStream, pcm: ShortArray, sampleRate: Int) {
            val dataBytes = pcm.size * 2
            val h = ByteArrayOutputStream(44)
            fun s(x: String) = h.write(x.toByteArray(Charsets.ISO_8859_1))
            fun i32(v: Int) { h.write(v and 0xFF); h.write((v shr 8) and 0xFF); h.write((v shr 16) and 0xFF); h.write((v shr 24) and 0xFF) }
            fun i16(v: Int) { h.write(v and 0xFF); h.write((v shr 8) and 0xFF) }
            s("RIFF"); i32(36 + dataBytes); s("WAVE")
            s("fmt "); i32(16); i16(1); i16(1); i32(sampleRate); i32(sampleRate * 2); i16(2); i16(16)
            s("data"); i32(dataBytes)
            out.write(h.toByteArray())
            val buf = ByteArray(dataBytes)
            for (i in pcm.indices) { val v = pcm[i].toInt(); buf[2 * i] = v.toByte(); buf[2 * i + 1] = (v shr 8).toByte() }
            out.write(buf)
        }
    }

    /** Decodes every packet to 16-bit PCM (160 samples per packet). Throws for non-QCELP codecs. */
    fun decodePcm(): ShortArray {
        if (codec != Codec.QCELP) throw QcpException("${codec.name} voice note, unsupported (guid $guidHex)")
        val dec = QcelpDecoder()
        val f = FloatArray(QcelpDecoder.SAMPLES_PER_FRAME)
        val out = ShortArray(packets.size * QcelpDecoder.SAMPLES_PER_FRAME)
        var o = 0
        for (p in packets) {
            dec.decodeFrame(bytes, p[1], p[2], f, 0)
            for (x in f) {
                val v = Math.round(x * 32768.0).toInt().coerceIn(-32768, 32767)
                out[o++] = v.toShort()
            }
        }
        lastRateCounts = dec.rateCounts.copyOf()
        return out
    }

    /** Frames per decoded rate after [decodePcm]: [0] erasures, [1] blank, [2] eighth, [3] quarter, [4] half, [5] full. */
    var lastRateCounts: IntArray = IntArray(6)
        private set

    /** Decodes [this] into a WAV file at [dest] (written via a temp file). Returns the sample count. */
    fun decodeToWav(dest: File): Int {
        val pcm = decodePcm()
        val tmp = File(dest.parentFile, dest.name + ".part")
        tmp.outputStream().buffered().use { writeWav(it, pcm, sampleRate) }
        if (!tmp.renameTo(dest)) { tmp.delete(); throw QcpException("could not write ${dest.name}") }
        return pcm.size
    }
}
