package com.heimish.messages.qcelp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

class QcelpTest {
    private fun le16(o: ByteArrayOutputStream, v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }
    private fun le32(o: ByteArrayOutputStream, v: Int) { le16(o, v and 0xFFFF); le16(o, (v ushr 16) and 0xFFFF) }

    /** A QCP file laid out like the ones Verizon sends (variable rate, standard rate map, vrat chunk). */
    private fun qcp(frames: List<ByteArray>, guid: ByteArray = QcpFile.GUID_QCELP_13K, withVrat: Boolean = true, packetSize: Int = 35): ByteArray {
        val fmt = ByteArrayOutputStream()
        fmt.write(1); fmt.write(0)                         // major, minor
        fmt.write(guid)
        le16(fmt, 2)                                       // codec version
        fmt.write("Qcelp 13K".toByteArray().copyOf(80))    // codec name
        le16(fmt, 14000); le16(fmt, packetSize); le16(fmt, 160); le16(fmt, 8000); le16(fmt, 16)
        val map = listOf(34 to 4, 16 to 3, 7 to 2, 3 to 1, 0 to 0)
        le32(fmt, map.size)
        for ((s, m) in map) { fmt.write(s); fmt.write(m) }
        fmt.write(ByteArray(16 - 2 * map.size)); fmt.write(ByteArray(20))
        val data = ByteArrayOutputStream().apply { frames.forEach { write(it) } }.toByteArray()
        val body = ByteArrayOutputStream()
        body.write("QLCM".toByteArray())
        body.write("fmt ".toByteArray()); le32(body, fmt.size()); body.write(fmt.toByteArray())
        if (withVrat) { body.write("vrat".toByteArray()); le32(body, 8); le32(body, 1); le32(body, frames.size) }
        body.write("data".toByteArray()); le32(body, data.size); body.write(data)
        if (data.size % 2 == 1) body.write(0)
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray()); le32(out, body.size()); out.write(body.toByteArray())
        return out.toByteArray()
    }

    private fun frame(rnd: Random, mode: Int): ByteArray = byteArrayOf(mode.toByte()) + ByteArray(QcelpDecoder.PAYLOAD_BYTES[mode]).also { rnd.nextBytes(it) }

    @Test fun recognisesQcpHeaderAndMimes() {
        // The exact first 16 bytes from the voice note in the phone's log.
        val head = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x7e, 0x0f, 0, 0, 0x51, 0x4c, 0x43, 0x4d, 0x66, 0x6d, 0x74, 0x20)
        assertTrue(QcpFile.looksLikeQcp(head))
        assertFalse(QcpFile.looksLikeQcp("RIFF\u0000\u0000\u0000\u0000WAVEfmt ".toByteArray(Charsets.ISO_8859_1)))
        assertTrue(QcpFile.isQcpMime("audio/vnd.qcelp"))
        assertTrue(QcpFile.isQcpMime("audio/QCELP; name=voice.qcp"))
        assertFalse(QcpFile.isQcpMime("audio/amr"))
    }

    @Test fun frameSizeTable() {
        assertArrayEquals(intArrayOf(0, 3, 7, 16, 34), QcelpDecoder.PAYLOAD_BYTES)
        assertEquals(QcelpDecoder.RATE_FULL, QcelpDecoder.rateForPacketSize(35))
        assertEquals(QcelpDecoder.RATE_HALF, QcelpDecoder.rateForPacketSize(17))
        assertEquals(QcelpDecoder.RATE_QUARTER, QcelpDecoder.rateForPacketSize(8))
        assertEquals(QcelpDecoder.RATE_OCTAVE, QcelpDecoder.rateForPacketSize(4))
        assertEquals(QcelpDecoder.SILENCE, QcelpDecoder.rateForPacketSize(1))
        assertEquals(QcelpDecoder.I_F_Q, QcelpDecoder.rateForPacketSize(20))
        // Unpacking bitmaps cover exactly the IS-733 bit counts per rate.
        fun bits(t: IntArray) = (2 until t.size step 3).sumOf { t[it] }
        assertEquals(266, bits(QcelpTables.BITMAP_FULL))
        assertEquals(124, bits(QcelpTables.BITMAP_HALF))
        assertEquals(54, bits(QcelpTables.BITMAP_QUARTER))
        assertEquals(20, bits(QcelpTables.BITMAP_OCTAVE))
    }

    @Test fun parsesVariableRateFile() {
        val rnd = Random(7)
        val modes = listOf(4, 4, 3, 2, 1, 0, 4, 1, 1, 3)
        val f = QcpFile.parse(qcp(modes.map { frame(rnd, it) }))
        assertEquals(QcpFile.Codec.QCELP, f.codec)
        assertEquals(8000, f.sampleRate)
        assertTrue(f.variableRate)
        assertArrayEquals(intArrayOf(0, 3, 7, 16, 34), f.ratesPerMode)
        assertEquals(modes, f.packets.map { it[0] })
        assertEquals(modes.map { QcelpDecoder.PAYLOAD_BYTES[it] }, f.packets.map { it[2] })
        assertArrayEquals(intArrayOf(1, 3, 1, 2, 3), f.modeCounts())
    }

    @Test fun parsesFixedRateFileWithoutVrat() {
        val rnd = Random(3)
        val f = QcpFile.parse(qcp(List(5) { frame(rnd, 4) }, withVrat = false))
        assertFalse(f.variableRate)
        assertEquals(5, f.packets.size)
        assertTrue(f.packets.all { it[2] == 34 })
    }

    @Test fun decodesEveryFrameTo160Samples() {
        val rnd = Random(42)
        val modes = List(250) { listOf(4, 4, 4, 3, 2, 1, 0)[rnd.nextInt(7)] } // 5 seconds, random bits incl. erasures
        val f = QcpFile.parse(qcp(modes.map { frame(rnd, it) }))
        val pcm = f.decodePcm()
        assertEquals(250 * 160, pcm.size)
        assertEquals(250, f.lastRateCounts.sum())
        val wav = File.createTempFile("qcelp", ".wav")
        try {
            assertEquals(250 * 160, f.decodeToWav(wav))
            assertEquals(44L + 250 * 160 * 2, wav.length())
            val h = wav.readBytes().copyOf(12)
            assertEquals("RIFF", String(h, 0, 4)); assertEquals("WAVE", String(h, 8, 4))
        } finally { wav.delete() }
    }

    @Test fun truncatedFileStillDecodesWhatIsThere() {
        val rnd = Random(9)
        val full = qcp(List(20) { frame(rnd, 4) })
        val f = QcpFile.parse(full.copyOf(full.size - 50))
        assertTrue(f.packets.size in 18..19)
        assertEquals(f.packets.size * 160, f.decodePcm().size)
    }

    @Test fun evrcIsReportedNotCrashed() {
        val evrc = byteArrayOf(0x8d.toByte(), 0xd4.toByte(), 0x89.toByte(), 0xe6.toByte(), 0x76, 0x90.toByte(), 0xb5.toByte(), 0x46,
            0x91.toByte(), 0xef.toByte(), 0x73, 0x6a, 0x51, 0x00, 0xce.toByte(), 0xb4.toByte())
        val f = QcpFile.parse(qcp(List(3) { byteArrayOf(4) + ByteArray(22) }, guid = evrc))
        assertEquals(QcpFile.Codec.EVRC, f.codec)
        try { f.decodePcm(); fail() } catch (e: QcpFile.QcpException) { assertTrue(e.message!!.startsWith("EVRC voice note")) }
    }

    @Test fun rejectsNonQcp() {
        try { QcpFile.parse(ByteArray(40)); fail() } catch (e: QcpFile.QcpException) { }
    }

    /** Developer check against FFmpeg: QCP_SAMPLE=/path/x.qcp QCP_OUT=/path/out.s16 writes raw PCM to compare. */
    @Test fun optionalRealSample() {
        val path = System.getenv("QCP_SAMPLE") ?: return
        val f = QcpFile.parse(File(path).readBytes())
        val pcm = f.decodePcm()
        System.getenv("QCP_OUT")?.let { out ->
            val b = ByteArray(pcm.size * 2)
            for (i in pcm.indices) { b[2 * i] = pcm[i].toByte(); b[2 * i + 1] = (pcm[i].toInt() shr 8).toByte() }
            File(out).writeBytes(b)
        }
    }
}
