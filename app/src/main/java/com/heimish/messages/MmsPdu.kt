package com.heimish.messages

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * A small, dependency-free decoder for the MMS PDUs a default SMS app has to handle itself
 * (OMA-WAP-MMS-ENC 1.3 headers + WAP-230 WSP multipart body):
 *  - M-Notification.ind (130), arriving by WAP push: who sent it and where to download it.
 *  - M-Retrieve.conf (132), the downloaded message: addresses, subject and every part.
 * Android does NOT save a downloaded MMS for the default SMS app — the app must parse and store it.
 * Pure Kotlin on purpose so the JVM unit tests can exercise it.
 */
object MmsPdu {
    const val TYPE_SEND_REQ = 0x80
    const val TYPE_NOTIFICATION_IND = 0x82
    const val TYPE_NOTIFYRESP_IND = 0x83
    const val TYPE_RETRIEVE_CONF = 0x84
    const val TYPE_ACKNOWLEDGE_IND = 0x85

    class Part(
        val contentType: String,
        val charset: Int,
        val name: String?,
        val fileName: String?,
        val contentLocation: String?,
        val contentId: String?,
        val data: ByteArray,
    ) {
        val isText: Boolean get() = contentType.startsWith("text/plain") || contentType == "application/smil" || contentType == "text/html"
        val isSmil: Boolean get() = contentType == "application/smil"
        fun text(): String = decodeText(data, charset)
    }

    class Message(
        val messageType: Int,
        val transactionId: String?,
        val messageId: String?,
        val version: Int,
        val from: String?,
        val to: List<String>,
        val cc: List<String>,
        val bcc: List<String>,
        val subject: String?,
        val contentLocation: String?,
        /** Seconds since epoch, 0 when absent. */
        val date: Long,
        /** Seconds; absolute (epoch) when [expiryAbsolute], else relative. 0 when absent. */
        val expiry: Long,
        val expiryAbsolute: Boolean,
        val messageSize: Long,
        val messageClass: String?,
        val retrieveStatus: Int,
        val contentType: String?,
        val parts: List<Part>,
    )

    class Malformed(msg: String) : Exception(msg)

    fun parse(pdu: ByteArray): Message = Reader(pdu).readMessage()

    /** Strips "/TYPE=PLMN" etc. and spaces; drops the "insert-address-token" placeholder. */
    fun cleanAddress(raw: String?): String? {
        if (raw == null) return null
        var a = raw.trim()
        val slash = a.indexOf("/TYPE=", ignoreCase = true)
        if (slash >= 0) a = a.substring(0, slash)
        a = a.trim()
        if (a.isEmpty() || a.equals("insert-address-token", ignoreCase = true)) return null
        return a
    }

    fun decodeText(data: ByteArray, charset: Int): String {
        val cs: Charset = when (charset) {
            3 -> Charsets.US_ASCII
            4 -> Charsets.ISO_8859_1
            1000 -> Charsets.UTF_16BE   // iso-10646-ucs-2
            1013 -> Charsets.UTF_16BE
            1014 -> Charsets.UTF_16LE
            1015 -> Charsets.UTF_16
            0, 106 -> Charsets.UTF_8
            else -> runCatching { mibToCharset(charset) }.getOrNull() ?: Charsets.UTF_8
        }
        var s = String(data, cs)
        if (s.isNotEmpty() && s[0] == '\uFEFF') s = s.substring(1)
        return s.trimEnd('\u0000')
    }

    private fun mibToCharset(mib: Int): Charset? = when (mib) {
        5 -> Charset.forName("ISO-8859-2"); 6 -> Charset.forName("ISO-8859-3"); 7 -> Charset.forName("ISO-8859-4")
        8 -> Charset.forName("ISO-8859-5"); 9 -> Charset.forName("ISO-8859-6"); 10 -> Charset.forName("ISO-8859-7")
        11 -> Charset.forName("ISO-8859-8"); 12 -> Charset.forName("ISO-8859-9"); 17 -> Charset.forName("Shift_JIS")
        2026 -> Charset.forName("Big5"); 2252 -> Charset.forName("windows-1252"); 2255 -> Charset.forName("windows-1255")
        113 -> Charset.forName("GBK"); 2025 -> Charset.forName("GB2312")
        else -> null
    }

    /** M-NotifyResp.ind telling the MMSC the message was retrieved (sent after a successful download). */
    fun buildNotifyRespInd(transactionId: String, retrieved: Boolean = true): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(0x8C); o.write(TYPE_NOTIFYRESP_IND)
        o.write(0x98); o.write(transactionId.toByteArray(Charsets.US_ASCII)); o.write(0)
        o.write(0x8D); o.write(0x92) // MMS 1.2
        o.write(0x95); o.write(if (retrieved) 0x81 else 0x83) // retrieved / deferred
        o.write(0x91); o.write(0x81) // report-allowed: no
        return o.toByteArray()
    }

    // WSP well-known content types (WAP-230 table 40, as AOSP PduContentTypes)
    private val CONTENT_TYPES = arrayOf(
        "*/*", "text/*", "text/html", "text/plain", "text/x-hdml", "text/x-ttml", "text/x-vCalendar", "text/x-vCard",
        "text/vnd.wap.wml", "text/vnd.wap.wmlscript", "text/vnd.wap.wta-event", "multipart/*", "multipart/mixed",
        "multipart/form-data", "multipart/byterantes", "multipart/alternative", "application/*", "application/java-vm",
        "application/x-www-form-urlencoded", "application/x-hdmlc", "application/vnd.wap.wmlc",
        "application/vnd.wap.wmlscriptc", "application/vnd.wap.wta-eventc", "application/vnd.wap.uaprof",
        "application/vnd.wap.wtls-ca-certificate", "application/vnd.wap.wtls-user-certificate", "application/x-x509-ca-cert",
        "application/x-x509-user-cert", "image/*", "image/gif", "image/jpeg", "image/tiff", "image/png", "image/vnd.wap.wbmp",
        "application/vnd.wap.multipart.*", "application/vnd.wap.multipart.mixed", "application/vnd.wap.multipart.form-data",
        "application/vnd.wap.multipart.byteranges", "application/vnd.wap.multipart.alternative", "application/xml", "text/xml",
        "application/vnd.wap.wbxml", "application/x-x968-cross-cert", "application/x-x968-ca-cert",
        "application/x-x968-user-cert", "text/vnd.wap.si", "application/vnd.wap.sic", "text/vnd.wap.sl",
        "application/vnd.wap.slc", "text/vnd.wap.co", "application/vnd.wap.coc", "application/vnd.wap.multipart.related",
        "application/vnd.wap.sia", "text/vnd.wap.connectivity-xml", "application/vnd.wap.connectivity-wbxml",
        "application/pkcs7-mime", "application/vnd.wap.hashed-certificate", "application/vnd.wap.signed-certificate",
        "application/vnd.wap.cert-response", "application/xhtml+xml", "application/wml+xml", "text/css",
        "application/vnd.wap.mms-message", "application/vnd.wap.rollover-certificate", "application/vnd.wap.locc+wbxml",
        "application/vnd.wap.loc+xml", "application/vnd.syncml.dm+wbxml", "application/vnd.syncml.dm+xml",
        "application/vnd.syncml.notification", "application/vnd.wap.xhtml+xml", "application/vnd.wv.csp.cir",
        "application/vnd.oma.dd+xml", "application/vnd.oma.drm.message", "application/vnd.oma.drm.content",
        "application/vnd.oma.drm.rights+xml", "application/vnd.oma.drm.rights+wbxml",
    )

    private class ContentType(val type: String, val charset: Int, val name: String?, val fileName: String?)

    private class Reader(val b: ByteArray) {
        var p = 0

        fun eof() = p >= b.size
        fun u8(): Int { if (p >= b.size) throw Malformed("truncated at $p"); return b[p++].toInt() and 0xFF }
        fun peek(): Int { if (p >= b.size) throw Malformed("truncated at $p"); return b[p].toInt() and 0xFF }

        fun uintvar(): Long {
            var v = 0L
            for (i in 0 until 5) {
                val x = u8()
                v = (v shl 7) or (x and 0x7F).toLong()
                if (x and 0x80 == 0) return v
            }
            throw Malformed("bad uintvar")
        }

        /** Value-length: short-length (0..30) or 31 + uintvar. */
        fun valueLength(): Int {
            val x = u8()
            return when {
                x <= 30 -> x
                x == 31 -> uintvar().toInt()
                else -> throw Malformed("bad value-length $x at ${p - 1}")
            }
        }

        fun textBytes(): ByteArray {
            if (!eof() && peek() == 0x7F) p++ // Quote
            val start = p
            while (p < b.size && b[p].toInt() != 0) p++
            val out = b.copyOfRange(start, p)
            if (p < b.size) p++ // NUL
            return out
        }

        fun text(): String {
            var s = String(textBytes(), Charsets.ISO_8859_1)
            if (s.startsWith("\"")) s = s.substring(1)
            return s
        }

        fun utf8Text(): String {
            var s = String(textBytes(), Charsets.UTF_8)
            if (s.startsWith("\"")) s = s.substring(1)
            return s
        }

        fun longInteger(): Long {
            val n = u8()
            if (n > 30) throw Malformed("bad long-integer length $n")
            var v = 0L
            repeat(n) { v = (v shl 8) or u8().toLong() }
            return v
        }

        fun integerValue(): Long {
            val x = peek()
            return if (x >= 0x80) { p++; (x and 0x7F).toLong() } else longInteger()
        }

        /** Encoded-string-value: text, or value-length + charset + text. */
        fun encodedString(): String {
            val x = peek()
            if (x < 0x20) {
                val len = valueLength()
                val end = p + len
                val cs = integerValue().toInt()
                val raw = textBytes()
                p = maxOf(p, minOf(end, b.size))
                return decodeText(raw, cs)
            }
            return decodeText(textBytes(), 106)
        }

        /** Skips any WSP field value (short, length-prefixed or text). */
        fun skipValue() {
            val x = peek()
            when {
                x <= 30 -> { p++; p += x }
                x == 31 -> { p++; val n = uintvar().toInt(); p += n }
                x < 0x80 -> textBytes()
                else -> p++
            }
            if (p > b.size) p = b.size
        }

        fun contentType(): ContentType {
            val x = peek()
            if (x >= 0x80) { p++; return ContentType(wellKnown(x and 0x7F), 0, null, null) }
            if (x >= 0x20) return ContentType(text().lowercase(), 0, null, null)
            val len = valueLength()
            val end = minOf(p + len, b.size)
            val mt = peek()
            val type = when {
                mt >= 0x80 -> { p++; wellKnown(mt and 0x7F) }
                mt >= 0x20 -> text().lowercase()
                else -> { val n = longInteger(); wellKnown(n.toInt()) } // integer media type
            }
            var charset = 0; var name: String? = null; var fileName: String? = null
            while (p < end) {
                val k = peek()
                if (k >= 0x80) {
                    p++
                    when (k and 0x7F) {
                        0x01 -> charset = if (peek() == 0x80) { p++; 0 } else if (peek() >= 0x20 && peek() < 0x80) { text(); 106 } else integerValue().toInt()
                        0x05, 0x17 -> name = paramText()
                        0x06, 0x18 -> fileName = paramText()
                        0x00 -> uintvar() // Q
                        else -> skipValue()
                    }
                } else if (k >= 0x20) {
                    val pname = text().lowercase()
                    when (pname) {
                        "charset" -> { val v = peek(); charset = if (v >= 0x80 || v <= 30) integerValue().toInt() else charsetByName(text()) }
                        "name" -> name = paramText()
                        "filename" -> fileName = paramText()
                        else -> skipValue()
                    }
                } else skipValue()
            }
            p = end
            return ContentType(type, charset, name, fileName)
        }

        fun paramText(): String? {
            val x = peek()
            return if (x == 0) { p++; null } else if (x < 0x20 || x >= 0x80) { encodedString() } else utf8Text()
        }

        fun charsetByName(n: String): Int = when (n.lowercase()) {
            "utf-8" -> 106; "us-ascii" -> 3; "iso-8859-1" -> 4; "utf-16" -> 1015; "iso-10646-ucs-2" -> 1000
            else -> 106
        }

        fun wellKnown(i: Int): String = CONTENT_TYPES.getOrNull(i) ?: "application/octet-stream"

        fun readMessage(): Message {
            var type = -1; var tid: String? = null; var mid: String? = null; var ver = 0x92
            var from: String? = null; val to = ArrayList<String>(); val cc = ArrayList<String>(); val bcc = ArrayList<String>()
            var subject: String? = null; var loc: String? = null; var date = 0L; var exp = 0L; var expAbs = false
            var size = 0L; var mcls: String? = null; var rst = 0; var ct: ContentType? = null
            while (!eof() && ct == null) {
                val h = u8()
                if (h < 0x80) { p--; text(); if (!eof()) skipValue(); continue } // application header: name, value
                when (h and 0x7F) {
                    0x01 -> cleanAddress(encodedString())?.let { bcc.add(it) }
                    0x02 -> cleanAddress(encodedString())?.let { cc.add(it) }
                    0x03 -> loc = text()
                    0x04 -> ct = contentType()   // always the last header; the body follows
                    0x05 -> date = longInteger()
                    0x08 -> {
                        val len = valueLength(); val end = minOf(p + len, b.size)
                        val tok = u8(); expAbs = tok == 0x80
                        exp = runCatching { if (expAbs) longInteger() else integerValue() }.getOrDefault(0L)
                        p = end
                    }
                    0x09 -> {
                        val len = valueLength(); val end = minOf(p + len, b.size)
                        val tok = u8()
                        if (tok == 0x80 && p < end) from = cleanAddress(encodedString())
                        p = end
                    }
                    0x0A -> mcls = if (peek() >= 0x80) when (u8()) { 0x80 -> "personal"; 0x81 -> "advertisement"; 0x82 -> "informational"; 0x83 -> "auto"; else -> null } else text()
                    0x0B -> mid = text()
                    0x0C -> type = u8()
                    0x0D -> ver = if (peek() >= 0x80) u8() and 0x7F else { text(); 0x12 }
                    0x0E -> size = longInteger()
                    0x16 -> subject = encodedString()
                    0x17 -> cleanAddress(encodedString())?.let { to.add(it) }
                    0x18 -> tid = text()
                    0x19 -> rst = u8()
                    else -> skipValue()
                }
            }
            if (type < 0) throw Malformed("no message type")
            val parts = ArrayList<Part>()
            val ctype = ct?.type
            if (ct != null && !eof()) {
                if (ctype != null && (ctype.startsWith("application/vnd.wap.multipart") || ctype.startsWith("multipart/"))) {
                    readMultipart(parts, 0)
                } else {
                    parts.add(Part(ct.type, ct.charset, ct.name, ct.fileName, null, null, b.copyOfRange(p, b.size)))
                    p = b.size
                }
            }
            return Message(type, tid, mid, ver, from, to, cc, bcc, subject?.takeIf { it.isNotBlank() }, loc, date, exp, expAbs,
                size, mcls, rst, ctype, parts)
        }

        fun readMultipart(out: MutableList<Part>, depth: Int) {
            val n = uintvar().toInt()
            for (i in 0 until n) {
                if (eof()) break
                val hlen = uintvar().toInt()
                val dlen = uintvar().toInt()
                val hstart = p
                val hend = minOf(hstart + hlen, b.size)
                val ct = contentType()
                var cl: String? = null; var cid: String? = null; var fn = ct.fileName
                while (p < hend) {
                    val h = peek()
                    if (h >= 0x80) {
                        p++
                        when (h and 0x7F) {
                            0x0E -> cl = utf8Text()           // Content-Location
                            0x40 -> cid = text()              // Content-ID
                            0x2E, 0x45 -> {                   // Content-Disposition: look for a filename
                                val len = if (peek() <= 31) valueLength() else 0
                                val end = minOf(p + len, hend)
                                if (len > 0) {
                                    if (peek() >= 0x80) p++ else text()
                                    while (p < end) {
                                        val k = peek()
                                        if (k == 0x86 || k == 0x98) { p++; fn = paramText() ?: fn }
                                        else if (k >= 0x80) { p++; skipValue() }
                                        else { val nm = text().lowercase(); if (nm == "filename") fn = paramText() ?: fn else if (p < end) skipValue() }
                                    }
                                    p = end
                                } else if (peek() >= 0x80) p++ else text()
                            }
                            else -> skipValue()
                        }
                    } else if (h >= 0x20) {
                        val name = text().lowercase()
                        if (p >= hend) break
                        when (name) {
                            "content-location" -> cl = utf8Text()
                            "content-id" -> cid = text()
                            else -> skipValue()
                        }
                    } else skipValue()
                }
                p = hend
                val dend = minOf(p + dlen, b.size)
                val data = b.copyOfRange(p, dend)
                val nested = ct.type.startsWith("application/vnd.wap.multipart") || ct.type.startsWith("multipart/")
                if (nested && depth < 3) {
                    runCatching { Reader(data).readMultipart(out, depth + 1) }
                } else {
                    out.add(Part(ct.type, ct.charset, ct.name, fn, cl?.trim()?.removeSurrounding("<", ">"),
                        cid?.trim()?.removeSurrounding("<", ">"), data))
                }
                p = dend
            }
        }
    }
}

/** Who belongs in the conversation of an incoming MMS. */
object MmsThreadAddresses {
    /** Digits only, last 10 — so +1 718 555 1234, 17185551234 and (718) 555-1234 all match. */
    fun key(a: String): String {
        val d = a.filter { it.isDigit() }
        return if (d.length >= 7) d.takeLast(10) else a.trim().lowercase()
    }

    fun isSelf(a: String, self: Collection<String>): Boolean {
        val k = key(a)
        return self.any { s -> s.isNotBlank() && key(s) == k }
    }

    /**
     * The members of the chat an incoming MMS belongs to: sender + To + Cc, minus our own number(s) and duplicates.
     * When our own number is unknown and the message went to exactly one recipient, that recipient is us (a 1:1
     * picture message). With group messaging off, only the sender.
     */
    fun forIncoming(from: String?, to: List<String>, cc: List<String>, self: Collection<String>, groupEnabled: Boolean = true): List<String> {
        val sender = from?.takeIf { it.isNotBlank() }
        if (!groupEnabled) return listOfNotNull(sender)
        val recipients = (to + cc).filter { it.isNotBlank() }
        val others = ArrayList<String>()
        val seen = HashSet<String>()
        fun add(a: String) { if (seen.add(key(a))) others.add(a) }
        sender?.let { add(it) }
        val knownSelf = self.filter { it.isNotBlank() }
        val selfInList = knownSelf.isNotEmpty() && recipients.any { isSelf(it, knownSelf) }
        for (r in recipients) {
            if (knownSelf.isNotEmpty() && isSelf(r, knownSelf)) continue
            // Our number unknown (or the carrier wrote it differently) and only one recipient: that one is us.
            if (!selfInList && recipients.size == 1) continue
            add(r)
        }
        if (others.isEmpty()) {
            // e.g. a message from ourselves to ourselves, or no sender at all: fall back to whatever is there
            (listOfNotNull(sender) + recipients).firstOrNull()?.let { others.add(it) }
        }
        return others
    }
}
