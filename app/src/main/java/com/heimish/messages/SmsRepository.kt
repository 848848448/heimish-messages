package com.heimish.messages

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log

data class Conversation(
    val threadId: Long,
    val address: String,
    val displayName: String,
    val snippet: String,
    val date: Long,
    val unread: Boolean
)

data class Message(
    val id: Long,
    val body: String,
    val date: Long,
    val incoming: Boolean,
    val isMms: Boolean = false,
    val imageUri: Uri? = null,
    val address: String? = null,
    val mediaMime: String? = null,
    val status: Int = -1
)

object SmsRepository {

    private val contactCache = HashMap<String, String?>()
    private var contactCacheTime = 0L

    private fun cachedContactName(ctx: Context, number: String): String? {
        if (number.isBlank()) return null
        if (System.currentTimeMillis() - contactCacheTime > 120_000) {
            contactCache.clear()
            contactCacheTime = System.currentTimeMillis()
        }
        return contactCache.getOrPut(number) { contactName(ctx, number) }
    }

    // ── Conversations ─────────────────────────────────────────────────────────

    fun loadConversations(ctx: Context): List<Conversation> {
        val out = ArrayList<Conversation>()
        val seen = HashSet<Long>()

        // SMS
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ),
            null, null, Telephony.Sms.DATE + " DESC"
        )?.use { c ->
            val iThread = c.getColumnIndex(Telephony.Sms.THREAD_ID)
            val iAddr   = c.getColumnIndex(Telephony.Sms.ADDRESS)
            val iBody   = c.getColumnIndex(Telephony.Sms.BODY)
            val iDate   = c.getColumnIndex(Telephony.Sms.DATE)
            val iRead   = c.getColumnIndex(Telephony.Sms.READ)
            while (c.moveToNext()) {
                val thread = c.getLong(iThread)
                if (!seen.add(thread)) continue
                val addr = c.getString(iAddr) ?: ""
                out.add(Conversation(
                    threadId    = thread,
                    address     = addr,
                    displayName = cachedContactName(ctx, addr) ?: addr,
                    snippet     = c.getString(iBody) ?: "",
                    date        = c.getLong(iDate),
                    unread      = c.getInt(iRead) == 0
                ))
            }
        }

        // MMS threads not already in SMS list
        ctx.contentResolver.query(
            Uri.parse("content://mms"),
            arrayOf("_id", "thread_id", "date", "read"),
            null, null, "date DESC"
        )?.use { c ->
            val iId     = c.getColumnIndex("_id")
            val iThread = c.getColumnIndex("thread_id")
            val iDate   = c.getColumnIndex("date")
            val iRead   = c.getColumnIndex("read")
            while (c.moveToNext()) {
                val thread = c.getLong(iThread)
                if (!seen.add(thread)) continue
                val mmsId = c.getLong(iId)
                val addr = mmsAddress(ctx, thread)
                val snippet = mmsSnippet(ctx, mmsId)
                out.add(Conversation(
                    threadId    = thread,
                    address     = addr,
                    displayName = cachedContactName(ctx, addr) ?: addr,
                    snippet     = snippet,
                    date        = c.getLong(iDate) * 1000L,
                    unread      = c.getInt(iRead) == 0
                ))
            }
        }

        out.sortByDescending { it.date }
        return out
    }

    // ── Messages ──────────────────────────────────────────────────────────────

    fun loadMessages(ctx: Context, threadId: Long): List<Message> {
        val out = ArrayList<Message>()

        // SMS
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.ADDRESS, Telephony.Sms.STATUS),
            Telephony.Sms.THREAD_ID + " = ?", arrayOf(threadId.toString()),
            Telephony.Sms.DATE + " ASC"
        )?.use { c ->
            val iId   = c.getColumnIndex(Telephony.Sms._ID)
            val iBody = c.getColumnIndex(Telephony.Sms.BODY)
            val iDate = c.getColumnIndex(Telephony.Sms.DATE)
            val iType = c.getColumnIndex(Telephony.Sms.TYPE)
            val iAddr = c.getColumnIndex(Telephony.Sms.ADDRESS)
            val iStat = c.getColumnIndex(Telephony.Sms.STATUS)
            while (c.moveToNext()) {
                out.add(Message(
                    id       = c.getLong(iId),
                    body     = c.getString(iBody) ?: "",
                    date     = c.getLong(iDate),
                    incoming = c.getInt(iType) == Telephony.Sms.MESSAGE_TYPE_INBOX,
                    address  = if (iAddr >= 0) c.getString(iAddr) else null,
                    status   = if (iStat >= 0) c.getInt(iStat) else -1
                ))
            }
        }

        // MMS
        ctx.contentResolver.query(
            Uri.parse("content://mms"),
            arrayOf("_id", "date", "msg_box"),
            "thread_id = ?", arrayOf(threadId.toString()),
            "date ASC"
        )?.use { c ->
            val iId   = c.getColumnIndex("_id")
            val iDate = c.getColumnIndex("date")
            val iBox  = c.getColumnIndex("msg_box")
            while (c.moveToNext()) {
                val mmsId    = c.getLong(iId)
                val date     = c.getLong(iDate) * 1000L
                val incoming = c.getInt(iBox) == 1
                val parts = mmsParts(ctx, mmsId)
                val mmsAddr = mmsSenderAddress(ctx, mmsId)
                out.add(Message(
                    id       = mmsId + 1_000_000L,
                    body     = parts.text,
                    date     = date,
                    incoming = incoming,
                    isMms    = true,
                    imageUri = parts.mediaUri,
                    address  = mmsAddr,
                    mediaMime = parts.mediaMime
                ))
            }
        }

        out.sortBy { it.date }
        return out
    }

    // ── Send SMS ──────────────────────────────────────────────────────────────

    fun sendSms(ctx: Context, address: String, body: String): Boolean {
        return runCatching {
            val sms = smsManager(ctx)
            val recipients = address.split(";").map { it.trim() }.filter { it.isNotBlank() }
            for (recipient in recipients) {
                val parts = sms.divideMessage(body)
                if (parts.size > 1)
                    sms.sendMultipartTextMessage(recipient, null, parts, null, null)
                else
                    sms.sendTextMessage(recipient, null, body, null, null)
            }

            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                put(Telephony.Sms.THREAD_ID, getOrCreateThreadId(ctx, address))
            }
            ctx.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
            true
        }.getOrElse { e -> Log.e("SmsRepo", "sendSms: ${e.message}"); false }
    }

    // ── Send MMS (image) ──────────────────────────────────────────────────────

    fun sendMms(ctx: Context, address: String, mediaUri: Uri, caption: String = "", overrideMime: String? = null): Boolean {
        return runCatching {
            val rawBytes = ctx.contentResolver.openInputStream(mediaUri)?.readBytes() ?: return false
            var mimeType = overrideMime ?: ctx.contentResolver.getType(mediaUri) ?: "application/octet-stream"
            val sms = smsManager(ctx)
            val threadId = getOrCreateThreadId(ctx, address)

            val bytes = if (mimeType.startsWith("image/")) {
                val compressed = compressImage(rawBytes, 280_000)
                mimeType = "image/jpeg"
                compressed
            } else rawBytes

            val pdu = buildSendReqPdu(address, bytes, mimeType, caption)

            val pduFile = java.io.File(ctx.cacheDir, "mms_send_${System.currentTimeMillis()}.dat")
            pduFile.writeBytes(pdu)
            val pduUri = androidx.core.content.FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", pduFile
            )
            ctx.grantUriPermission("com.android.mms.service", pduUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

            val configOverrides = android.os.Bundle()
            configOverrides.putBoolean(android.telephony.SmsManager.MMS_CONFIG_GROUP_MMS_ENABLED, true)

            val sendIntent = android.app.PendingIntent.getBroadcast(
                ctx, System.currentTimeMillis().toInt(),
                Intent("com.heimish.messages.MMS_SENT"),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            sms.sendMultimediaMessage(ctx, pduUri, null, configOverrides, sendIntent)

            val mmsValues = ContentValues().apply {
                put("thread_id", threadId)
                put("msg_box", 2)
                put("read", 1)
                put("seen", 1)
                put("date", System.currentTimeMillis() / 1000)
                put("ct_t", "application/vnd.wap.multipart.mixed")
            }
            val mmsInsertUri = ctx.contentResolver.insert(Uri.parse("content://mms"), mmsValues)
            val mmsId = mmsInsertUri?.lastPathSegment?.toLong()
            if (mmsId != null) {
                val addrValues = ContentValues().apply {
                    put("address", address)
                    put("msg_id", mmsId)
                    put("type", 151) // TO
                    put("charset", 106) // UTF-8
                }
                ctx.contentResolver.insert(Uri.parse("content://mms/$mmsId/addr"), addrValues)

                val ext = when {
                    mimeType.startsWith("image/") -> "img"
                    mimeType.startsWith("video/") -> "vid"
                    mimeType.startsWith("audio/") -> "aud"
                    else -> "file"
                }
                val mediaValues = ContentValues().apply {
                    put("mid", mmsId)
                    put("ct", mimeType)
                    put("name", "$ext.${mimeType.substringAfter("/").take(4)}")
                    put("chset", 106)
                }
                val partUri = ctx.contentResolver.insert(Uri.parse("content://mms/$mmsId/part"), mediaValues)
                if (partUri != null) {
                    ctx.contentResolver.openOutputStream(partUri)?.use { it.write(bytes) }
                }

                if (caption.isNotBlank()) {
                    val textValues = ContentValues().apply {
                        put("mid", mmsId)
                        put("ct", "text/plain")
                        put("chset", 106)
                        put("text", caption)
                    }
                    ctx.contentResolver.insert(Uri.parse("content://mms/$mmsId/part"), textValues)
                }
            }

            Thread { Thread.sleep(30_000); pduFile.delete() }.start()
            true
        }.getOrElse { e -> Log.e("SmsRepo", "sendMms: ${e.message}"); false }
    }

    private fun buildSendReqPdu(address: String, mediaBytes: ByteArray, mimeType: String, caption: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()

        // M-Send.req (0x80), Transaction-ID, MMS Version 1.3
        out.write(0x8C); out.write(0x80)
        out.write(0x98)
        val txnId = "T${System.currentTimeMillis()}"
        out.write(txnId.toByteArray()); out.write(0x00)
        out.write(0x8D); out.write(0x93)

        // From: insert-address-token
        out.write(0x89); out.write(0x01); out.write(0x81)

        // Date
        out.write(0x85)
        writeLongInteger(out, (System.currentTimeMillis() / 1000).toInt())

        // To (one header per recipient for group MMS)
        val recipients = address.split(";").map { it.trim().replace(Regex("[^0-9+]"), "") }.filter { it.isNotBlank() }
        for (recipient in recipients) {
            out.write(0x97)
            out.write("$recipient/TYPE=PLMN".toByteArray()); out.write(0x00)
        }

        // Subject (optional)
        if (caption.isNotBlank()) {
            out.write(0x96)
            val subjBytes = caption.take(40).toByteArray(Charsets.UTF_8)
            writeValueLength(out, subjBytes.size + 2)
            out.write(0xEA.toByte().toInt())
            out.write(subjBytes); out.write(0x00)
        }

        // Content-Type: application/vnd.wap.multipart.mixed (0xA3 short-integer)
        out.write(0x84); out.write(0xA3.toByte().toInt())

        // Part count
        val partCount = 1 + (if (caption.isNotBlank()) 1 else 0)
        writeUintVar(out, partCount)

        // Media part
        val mediaPartHeaders = java.io.ByteArrayOutputStream()
        mediaPartHeaders.write(mimeType.toByteArray()); mediaPartHeaders.write(0x00)
        writeUintVar(out, mediaPartHeaders.size())
        writeUintVar(out, mediaBytes.size)
        out.write(mediaPartHeaders.toByteArray())
        out.write(mediaBytes)

        // Text part (optional)
        if (caption.isNotBlank()) {
            val textBytes = caption.toByteArray(Charsets.UTF_8)
            val textPartHeaders = java.io.ByteArrayOutputStream()
            textPartHeaders.write("text/plain".toByteArray()); textPartHeaders.write(0x00)
            writeUintVar(out, textPartHeaders.size())
            writeUintVar(out, textBytes.size)
            out.write(textPartHeaders.toByteArray())
            out.write(textBytes)
        }

        return out.toByteArray()
    }

    private fun writeLongInteger(out: java.io.ByteArrayOutputStream, value: Int) {
        val bytes = mutableListOf<Byte>()
        var v = value
        while (v > 0) { bytes.add(0, (v and 0xFF).toByte()); v = v shr 8 }
        if (bytes.isEmpty()) bytes.add(0)
        out.write(bytes.size)
        bytes.forEach { out.write(it.toInt()) }
    }

    private fun writeValueLength(out: java.io.ByteArrayOutputStream, length: Int) {
        if (length < 31) {
            out.write(length)
        } else {
            out.write(31)
            writeUintVar(out, length)
        }
    }

    private fun writeUintVar(out: java.io.ByteArrayOutputStream, value: Int) {
        if (value < 0x80) {
            out.write(value)
        } else {
            val bytes = mutableListOf<Int>()
            var v = value
            bytes.add(v and 0x7F)
            v = v shr 7
            while (v > 0) {
                bytes.add((v and 0x7F) or 0x80)
                v = v shr 7
            }
            bytes.reversed().forEach { out.write(it) }
        }
    }

    private fun compressImage(raw: ByteArray, maxSize: Int): ByteArray {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
        var sampleSize = 1
        while (opts.outWidth / sampleSize > 1024 || opts.outHeight / sampleSize > 1024) sampleSize *= 2
        val decOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, decOpts) ?: return raw
        var quality = 85
        while (quality >= 20) {
            val baos = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            val result = baos.toByteArray()
            if (result.size <= maxSize) { bmp.recycle(); return result }
            quality -= 10
        }
        val baos = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 20, baos)
        bmp.recycle()
        return baos.toByteArray()
    }

    // ── Mark read ─────────────────────────────────────────────────────────────

    fun markThreadRead(ctx: Context, threadId: Long) {
        runCatching {
            val values = ContentValues().apply { put(Telephony.Sms.READ, 1) }
            ctx.contentResolver.update(
                Telephony.Sms.CONTENT_URI, values,
                Telephony.Sms.THREAD_ID + " = ?", arrayOf(threadId.toString())
            )
            ctx.contentResolver.update(
                Uri.parse("content://mms"), values,
                "thread_id = ?", arrayOf(threadId.toString())
            )
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun smsManager(ctx: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ctx.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
        else
            SmsManager.getDefault()

    private fun getOrCreateThreadId(ctx: Context, address: String): Long {
        return runCatching {
            val uri = Uri.parse("content://mms-sms/threadID")
                .buildUpon().appendQueryParameter("recipient", address).build()
            ctx.contentResolver.query(uri, arrayOf("_id"), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun contactName(ctx: Context, number: String): String? {
        if (number.isBlank()) return null
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
        )
        return runCatching {
            ctx.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }

    private fun mmsAddress(ctx: Context, threadId: Long): String {
        return runCatching {
            ctx.contentResolver.query(
                Uri.parse("content://mms-sms/conversations/$threadId"),
                arrayOf("address"), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) ?: "" else "" } ?: ""
        }.getOrDefault("")
    }

    private fun mmsSnippet(ctx: Context, mmsId: Long): String {
        var text = ""
        var hasMedia = false
        runCatching {
            ctx.contentResolver.query(
                Uri.parse("content://mms/$mmsId/part"),
                arrayOf("ct", "text"), null, null, null
            )?.use { c ->
                val iCt   = c.getColumnIndex("ct")
                val iText = c.getColumnIndex("text")
                while (c.moveToNext()) {
                    val ct = c.getString(iCt) ?: ""
                    when {
                        ct == "text/plain" -> text = c.getString(iText) ?: ""
                        ct.startsWith("image/") || ct.startsWith("video/") || ct.startsWith("audio/") -> hasMedia = true
                    }
                }
            }
        }
        return when {
            text.isNotBlank() && hasMedia -> "📎 $text"
            text.isNotBlank() -> text
            hasMedia -> "📷 MMS"
            else -> "MMS"
        }
    }

    data class MmsParts(val text: String, val mediaUri: Uri?, val mediaMime: String?)

    private fun mmsParts(ctx: Context, mmsId: Long): MmsParts {
        var text = ""
        var mediaUri: Uri? = null
        var mediaMime: String? = null
        runCatching {
            ctx.contentResolver.query(
                Uri.parse("content://mms/$mmsId/part"),
                arrayOf("_id", "ct", "text"), null, null, null
            )?.use { c ->
                val iId   = c.getColumnIndex("_id")
                val iCt   = c.getColumnIndex("ct")
                val iText = c.getColumnIndex("text")
                while (c.moveToNext()) {
                    val partId = c.getLong(iId)
                    val ct     = c.getString(iCt) ?: ""
                    when {
                        ct == "text/plain" -> text = c.getString(iText) ?: ""
                        ct.startsWith("image/") || ct.startsWith("video/") || ct.startsWith("audio/") -> {
                            mediaUri = Uri.parse("content://mms/part/$partId")
                            mediaMime = ct
                        }
                    }
                }
            }
        }
        return MmsParts(text, mediaUri, mediaMime)
    }

    private fun mmsSenderAddress(ctx: Context, mmsId: Long): String? {
        return runCatching {
            ctx.contentResolver.query(
                Uri.parse("content://mms/$mmsId/addr"),
                arrayOf("address", "type"), "type=137", null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(c.getColumnIndex("address")) else null
            }
        }.getOrNull()
    }

        fun getThreadIdForAddress(ctx: Context, address: String): Long {
            try {
                val uri = android.provider.Telephony.Sms.CONTENT_URI
                val cursor = ctx.contentResolver.query(uri,
                    arrayOf(android.provider.Telephony.Sms.THREAD_ID),
                    "${android.provider.Telephony.Sms.ADDRESS} = ?",
                    arrayOf(address), "${android.provider.Telephony.Sms.DATE} DESC")
                cursor?.use {
                    if (it.moveToFirst()) return it.getLong(0)
                }
            } catch (_: Exception) {}
            return address.hashCode().toLong()
        }

        fun getContactName(ctx: Context, address: String): String? {
            return cachedContactName(ctx, address)
        }


        fun loadContactsList(ctx: Context): List<Pair<String, String>> {
            val contacts = mutableListOf<Pair<String, String>>()
            val seen = mutableSetOf<String>()
            try {
                val cursor = ctx.contentResolver.query(
                    android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                    ), null, null,
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
                )
                cursor?.use {
                    val ni = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val pi = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (it.moveToNext()) {
                        val name = it.getString(ni) ?: continue
                        val num = (it.getString(pi) ?: continue).replace(Regex("[^+0-9]"), "")
                        if (num.isBlank() || seen.contains(num)) continue
                        seen.add(num)
                        contacts.add(name to num)
                    }
                }
            } catch (_: Exception) {}
            return contacts
        }

        fun deleteMessage(ctx: Context, msgId: Long) {
            try {
                if (msgId >= 1_000_000L) {
                    val realMmsId = msgId - 1_000_000L
                    ctx.contentResolver.delete(Uri.parse("content://mms"), "_id = ?", arrayOf(realMmsId.toString()))
                } else {
                    ctx.contentResolver.delete(Telephony.Sms.CONTENT_URI, "_id = ?", arrayOf(msgId.toString()))
                }
            } catch (_: Exception) {}
        }

        fun deleteThread(ctx: Context, threadId: Long) {
            try {
                ctx.contentResolver.delete(Telephony.Sms.CONTENT_URI, "thread_id = ?", arrayOf(threadId.toString()))
                ctx.contentResolver.delete(Uri.parse("content://mms"), "thread_id = ?", arrayOf(threadId.toString()))
            } catch (_: Exception) {}
        }

}
