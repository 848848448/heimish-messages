package com.heimish.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class MmsPduTest {

    // ── tiny WSP encoder for building test PDUs ──
    private class W {
        val o = ByteArrayOutputStream()
        fun b(vararg x: Int) = apply { x.forEach { o.write(it) } }
        fun text(s: String) = apply { o.write(s.toByteArray(Charsets.UTF_8)); o.write(0) }
        fun uintvar(v: Int) = apply {
            val bytes = ArrayList<Int>(); var x = v
            bytes.add(x and 0x7F); x = x ushr 7
            while (x > 0) { bytes.add(0x80 or (x and 0x7F)); x = x ushr 7 }
            bytes.reversed().forEach { o.write(it) }
        }
        fun valueLen(n: Int) = apply { if (n <= 30) o.write(n) else { o.write(31); uintvar(n) } }
        fun raw(a: ByteArray) = apply { o.write(a) }
        fun bytes(): ByteArray = o.toByteArray()
    }

    private fun encodedUtf8(s: String): ByteArray {
        val body = W().b(0xEA).text(s).bytes() // charset utf-8 (106 = 0x6A | 0x80)
        return W().valueLen(body.size).raw(body).bytes()
    }

    private fun fromHeader(addr: String): ByteArray {
        val v = W().b(0x80).text(addr).bytes()
        return W().b(0x89).valueLen(v.size).raw(v).bytes()
    }

    @Test fun parsesNotificationInd() {
        val pdu = W()
            .b(0x8C, 0x82)                       // message type: notification-ind
            .b(0x98).text("T123abc")             // transaction id
            .b(0x8D, 0x92)                       // version 1.2
            .raw(fromHeader("+17185551234/TYPE=PLMN"))
            .b(0x8A, 0x80)                       // class personal
            .b(0x8E, 0x02, 0x40, 0x00)           // size 16384
            .b(0x88, 0x05, 0x81, 0x03, 0x01, 0x51, 0x80) // expiry relative 86400 s
            .b(0x83).text("http://mmsc.example.com/mms/abc?x=1")
            .bytes()
        val m = MmsPdu.parse(pdu)
        assertEquals(MmsPdu.TYPE_NOTIFICATION_IND, m.messageType)
        assertEquals("T123abc", m.transactionId)
        assertEquals("+17185551234", m.from)
        assertEquals("http://mmsc.example.com/mms/abc?x=1", m.contentLocation)
        assertEquals(16384L, m.messageSize)
        assertEquals(86400L, m.expiry)
        assertEquals(false, m.expiryAbsolute)
        assertEquals("personal", m.messageClass)
    }

    /** A carrier-style M-Notification.ind as raw bytes: charset-encoded subject, absolute expiry, delivery-report and an
     *  application header before the download address (the order real MMSCs use). */
    @Test fun parsesRawCarrierNotification() {
        val hex = "8c829830613162326333643465008d928918802b31323132353535303139392f545950453d504c4d4e009607ea50686f746f008a808e030186a0880680046a0f1b808681582d436172726965722d496e666f006162630083687474703a2f2f6d6d732e6578616d706c652e6e65742f6d6d732f776170656e633f6c6f636174696f6e3d31323132353535303139395f616263267269643d30323700"
        val pdu = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val m = MmsPdu.parse(pdu)
        assertEquals(MmsPdu.TYPE_NOTIFICATION_IND, m.messageType)
        assertEquals("0a1b2c3d4e", m.transactionId)
        assertEquals("+12125550199", m.from)
        assertEquals("Photo", m.subject)
        assertEquals(100000L, m.messageSize)
        assertEquals(true, m.expiryAbsolute)
        assertEquals(0x6A0F1B80L, m.expiry)
        assertEquals("http://mms.example.net/mms/wapenc?location=12125550199_abc&rid=027", m.contentLocation)
    }

    @Test fun parsesGroupRetrieveConfWithParts() {
        val yiddish = "גוט מארגן 👋"
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4, 0xFF.toByte(), 0xD9.toByte())
        val smil = "<smil><body><par><img src=\"photo.jpg\"/></par></body></smil>"

        fun part(ctHeader: ByteArray, extraHeaders: ByteArray, data: ByteArray): ByteArray {
            val headers = W().raw(ctHeader).raw(extraHeaders).bytes()
            return W().uintvar(headers.size).uintvar(data.size).raw(headers).raw(data).bytes()
        }
        // application/smil as text media type (general form with no params)
        val smilCt = W().b(0x7F).text("application/smil").bytes().let { W().valueLen(it.size).raw(it).bytes() }
        // text/plain (0x83) ; charset=utf-8
        val textCt = W().b(0x83, 0x81, 0xEA).bytes().let { W().valueLen(it.size).raw(it).bytes() }
        // image/jpeg (0x9E) ; name="photo.jpg"
        val imgCt = W().b(0x9E, 0x85).text("photo.jpg").bytes().let { W().valueLen(it.size).raw(it).bytes() }

        val body = W().uintvar(3)
            .raw(part(smilCt, W().b(0xC0).b(0x22).text("<smil>").bytes(), smil.toByteArray()))
            .raw(part(textCt, W().b(0x8E).text("text_0.txt").bytes(), yiddish.toByteArray(Charsets.UTF_8)))
            .raw(part(imgCt, W().b(0x8E).text("photo.jpg").b(0xC0).b(0x22).text("<img1>").bytes(), jpeg))
            .bytes()
        // multipart/related (0xB3) ; type=application/smil ; start=<smil>
        val ctVal = W().b(0xB3).b(0x89).text("application/smil").b(0x8A).text("<smil>").bytes()

        val pdu = W()
            .b(0x8C, 0x84)
            .b(0x98).text("tid-9")
            .b(0x8D, 0x92)
            .b(0x8B).text("msg-id-42@mmsc")
            .b(0x85, 0x04, 0x65, 0x4A, 0x1B, 0x2C)   // date
            .raw(fromHeader("+17185551234/TYPE=PLMN"))
            .b(0x97).text("+13475550001/TYPE=PLMN")  // To: me
            .b(0x97).text("+19175550002/TYPE=PLMN")  // To: another member
            .b(0x82).text("+16465550003/TYPE=PLMN")  // Cc
            .b(0x96).raw(encodedUtf8("שבת"))        // subject
            .b(0x84).valueLen(ctVal.size).raw(ctVal)
            .raw(body)
            .bytes()

        val m = MmsPdu.parse(pdu)
        assertEquals(MmsPdu.TYPE_RETRIEVE_CONF, m.messageType)
        assertEquals("tid-9", m.transactionId)
        assertEquals("msg-id-42@mmsc", m.messageId)
        assertEquals(0x654A1B2CL, m.date)
        assertEquals("+17185551234", m.from)
        assertEquals(listOf("+13475550001", "+19175550002"), m.to)
        assertEquals(listOf("+16465550003"), m.cc)
        assertEquals("שבת", m.subject)
        assertEquals("application/vnd.wap.multipart.related", m.contentType)
        assertEquals(3, m.parts.size)

        val s = m.parts[0]
        assertEquals("application/smil", s.contentType)
        assertTrue(s.isSmil)
        assertEquals("smil", s.contentId)

        val t = m.parts[1]
        assertEquals("text/plain", t.contentType)
        assertEquals(106, t.charset)
        assertEquals(yiddish, t.text())
        assertEquals("text_0.txt", t.contentLocation)

        val i = m.parts[2]
        assertEquals("image/jpeg", i.contentType)
        assertEquals("photo.jpg", i.name)
        assertEquals("photo.jpg", i.contentLocation)
        assertEquals("img1", i.contentId)
        assertTrue(jpeg.contentEquals(i.data))
    }

    @Test fun parsesTextOnlyAndApplicationHeaders() {
        // single-part body (text/plain constrained), plus an unknown application header that must be skipped
        val pdu = W()
            .b(0x8C, 0x84).b(0x98).text("t").b(0x8D, 0x90)
            .text("X-Carrier-Thing").text("whatever")
            .raw(fromHeader("5551234567"))
            .b(0x84, 0x83)
            .raw("hello".toByteArray())
            .bytes()
        val m = MmsPdu.parse(pdu)
        assertEquals("5551234567", m.from)
        assertEquals(1, m.parts.size)
        assertEquals("hello", m.parts[0].text())
    }

    @Test fun insertAddressTokenIsNoSender() {
        val v = W().b(0x81).bytes()
        val pdu = W().b(0x8C, 0x84).b(0x89).valueLen(v.size).raw(v).b(0x84, 0x83).raw("x".toByteArray()).bytes()
        assertNull(MmsPdu.parse(pdu).from)
        assertNull(MmsPdu.cleanAddress("insert-address-token"))
        assertEquals("+12125550000", MmsPdu.cleanAddress(" +12125550000/TYPE=PLMN "))
    }

    @Test fun notifyRespRoundTrip() {
        val m = MmsPdu.parse(MmsPdu.buildNotifyRespInd("abc-1"))
        assertEquals(MmsPdu.TYPE_NOTIFYRESP_IND, m.messageType)
        assertEquals("abc-1", m.transactionId)
    }

    @Test(expected = MmsPdu.Malformed::class)
    fun garbageIsRejected() { MmsPdu.parse(byteArrayOf(0x8C.toByte())) }

    @Test fun decodesUcs2() {
        assertEquals("אב", MmsPdu.decodeText("אב".toByteArray(Charsets.UTF_16BE), 1000))
    }
}

class MmsThreadAddressesTest {
    private val me = listOf("+1 (347) 555-0001")

    @Test fun groupIsSenderPlusOthersMinusMe() {
        val r = MmsThreadAddresses.forIncoming("+17185551234", listOf("+13475550001", "+19175550002"), listOf("+16465550003"), me)
        assertEquals(listOf("+17185551234", "+19175550002", "+16465550003"), r)
    }

    @Test fun oneToOnePictureMessage() {
        assertEquals(listOf("+17185551234"), MmsThreadAddresses.forIncoming("+17185551234", listOf("13475550001"), emptyList(), me))
    }

    @Test fun oneToOneWhenOwnNumberUnknown() {
        assertEquals(listOf("+17185551234"), MmsThreadAddresses.forIncoming("+17185551234", listOf("+13475550001"), emptyList(), emptyList()))
    }

    @Test fun groupWhenOwnNumberUnknownKeepsEveryone() {
        val r = MmsThreadAddresses.forIncoming("+17185551234", listOf("+13475550001", "+19175550002"), emptyList(), emptyList())
        assertEquals(listOf("+17185551234", "+13475550001", "+19175550002"), r)
    }

    @Test fun duplicatesInDifferentFormatsCollapse() {
        val r = MmsThreadAddresses.forIncoming("+17185551234", listOf("7185551234", "+13475550001", "(917) 555-0002"), listOf("+19175550002"), me)
        assertEquals(listOf("+17185551234", "(917) 555-0002"), r)
    }

    @Test fun groupMessagingOffThreadsBySenderOnly() {
        val r = MmsThreadAddresses.forIncoming("+17185551234", listOf("+13475550001", "+19175550002"), emptyList(), me, groupEnabled = false)
        assertEquals(listOf("+17185551234"), r)
    }

    @Test fun shortCodesAndEmails() {
        assertEquals(listOf("22000"), MmsThreadAddresses.forIncoming("22000", listOf("+13475550001"), emptyList(), me))
        assertEquals(listOf("someone@example.com"), MmsThreadAddresses.forIncoming("someone@example.com", listOf("+13475550001"), emptyList(), me))
    }
}
