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
}
