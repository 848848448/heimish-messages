package com.heimish.messages

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaTypesTest {
    private fun b(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun mp4(brand: String) = byteArrayOf(0, 0, 0, 0x18) + b("ftyp$brand") + ByteArray(8)

    @Test fun sniffsVoiceAndVideoFormats() {
        assertEquals("audio/amr", MediaTypes.sniffMime(b("#!AMR\n") + ByteArray(10)))
        assertEquals("audio/amr-wb", MediaTypes.sniffMime(b("#!AMR-WB\n") + ByteArray(10)))
        assertEquals("video/3gpp", MediaTypes.sniffMime(mp4("3gp4")))
        assertEquals("audio/mp4", MediaTypes.sniffMime(mp4("M4A ")))
        assertEquals("video/mp4", MediaTypes.sniffMime(mp4("isom")))
        assertEquals("audio/ogg", MediaTypes.sniffMime(b("OggS") + ByteArray(12)))
        assertEquals("audio/mpeg", MediaTypes.sniffMime(b("ID3") + ByteArray(13)))
        assertEquals("image/jpeg", MediaTypes.sniffMime(byteArrayOf(-1, -40, -1, -32) + ByteArray(12)))
        assertEquals(null, MediaTypes.sniffMime(b("hello world, text")))
    }

    @Test fun fixesOddCarrierTypes() {
        val amr = b("#!AMR\n") + ByteArray(30)
        assertEquals("audio/amr", MediaTypes.fixPartType("application/octet-stream", "part1", amr))
        assertEquals("audio/amr", MediaTypes.fixPartType("application/octet-stream", null, amr))
        assertEquals("video/3gpp", MediaTypes.fixPartType("application/octet-stream", "clip", mp4("3gp5")))
        assertEquals("audio/amr", MediaTypes.fixPartType("audio/amr", "a.amr", amr))   // already fine: kept
        assertEquals("text/plain", MediaTypes.fixPartType("text/plain", "t.txt", b("#!AMR\n")))
        assertEquals("application/octet-stream", MediaTypes.fixPartType("application/octet-stream", "x", b("plain bytes here")))
    }

    @Test fun mediaTypeFromNamesAndCase() {
        assertEquals("video/3gpp", mediaMimeFor("Video/3GPP; name=x.3gp", null))
        assertEquals("audio/amr", mediaMimeFor("Audio/AMR", null))
        assertEquals("audio/amr", mediaMimeFor("application/octet-stream", "Voice.AMR"))
        assertEquals("video/mp4", mediaMimeFor("application/octet-stream", "clip.mp4"))
        assertEquals(null, mediaMimeFor("text/plain", null))
        assertEquals(null, mediaMimeFor("application/smil", null))
    }

    @Test fun outgoingNames() {
        assertEquals("m4a", MediaTypes.extFor("audio/mp4"))
        assertEquals("amr", MediaTypes.extFor("audio/amr"))
        assertEquals("3gp", MediaTypes.extFor("video/3gpp"))
    }

    private fun hx(s: String): ByteArray = s.split(' ').filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    /** First 16 bytes of real files as phones send them. */
    @Test fun sniffsRealHeaders() {
        // iPhone / Samsung voice memo over MMS (AMR-NB storage format, then the first frame header 0x3C = 12.2 kbit/s)
        assertEquals("audio/amr", MediaTypes.sniffMime(hx("23 21 41 4D 52 0A 3C 91 17 16 BE 66 78 00 00 01")))
        // AMR-WB
        assertEquals("audio/amr-wb", MediaTypes.sniffMime(hx("23 21 41 4D 52 2D 57 42 0A 44 C0 F0 00 11 22 33")))
        // Android MediaRecorder 3GPP (AMR inside 3gp4)
        assertEquals("video/3gpp", MediaTypes.sniffMime(hx("00 00 00 18 66 74 79 70 33 67 70 34 00 00 00 00")))
        // Google Messages / Pixel recorder voice note (AAC in M4A)
        assertEquals("audio/mp4", MediaTypes.sniffMime(hx("00 00 00 1C 66 74 79 70 4D 34 41 20 00 00 00 00")))
        // phone camera video (isom / mp42)
        assertEquals("video/mp4", MediaTypes.sniffMime(hx("00 00 00 20 66 74 79 70 69 73 6F 6D 00 00 02 00")))
        assertEquals("video/mp4", MediaTypes.sniffMime(hx("00 00 00 18 66 74 79 70 6D 70 34 32 00 00 00 00")))
        // iPhone video
        assertEquals("video/quicktime", MediaTypes.sniffMime(hx("00 00 00 14 66 74 79 70 71 74 20 20 00 00 00 00")))
        // WhatsApp-style Ogg/Opus
        assertEquals("audio/ogg", MediaTypes.sniffMime(hx("4F 67 67 53 00 02 00 00 00 00 00 00 00 00 12 34")))
        // MP3 with an ID3 tag, and a bare MPEG-1 Layer 3 frame
        assertEquals("audio/mpeg", MediaTypes.sniffMime(hx("49 44 33 04 00 00 00 00 00 23 54 53 53 45 00 00")))
        assertEquals("audio/mpeg", MediaTypes.sniffMime(hx("FF FB 90 64 00 0F F0 00 00 69 00 00 00 08 00 00")))
        // AAC ADTS, MPEG-4 (FFF1) and MPEG-2 (FFF9)
        assertEquals("audio/aac", MediaTypes.sniffMime(hx("FF F1 50 80 2E 7F FC 21 1A 54 B6 31 00 00 00 00")))
        assertEquals("audio/aac", MediaTypes.sniffMime(hx("FF F9 50 80 2E 7F FC 21 1A 54 B6 31 00 00 00 00")))
        // a short read (tiny part) never crashes
        assertEquals(null, MediaTypes.sniffMime(hx("23 21 41")))
        assertEquals(null, MediaTypes.sniffMime(ByteArray(0)))
    }

    @Test fun bareAmrFramesGetTheirHeader() {
        assertEquals("#!AMR\n", MediaTypes.missingAmrHeader("audio/amr", hx("3C 91 17 16 BE 66 78 00 00 01 E7 AF 00 11 22 33")))
        assertEquals("#!AMR-WB\n", MediaTypes.missingAmrHeader("audio/amr-wb", hx("44 C0 F0 00 11 22 33 44")))
        assertEquals(null, MediaTypes.missingAmrHeader("audio/amr", hx("23 21 41 4D 52 0A 3C 91 17 16 BE 66")))   // has it
        assertEquals(null, MediaTypes.missingAmrHeader("audio/mp4", hx("3C 91 17 16")))                         // not AMR
        assertEquals(null, MediaTypes.missingAmrHeader("audio/amr", hx("FF 91 17 16")))                         // not a frame header
        assertEquals(null, MediaTypes.missingAmrHeader("audio/amr", ByteArray(0)))
    }
}
