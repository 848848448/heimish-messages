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
    val unread: Boolean,
    val unreadCount: Int = 0,
    val outgoing: Boolean = false
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
    val status: Int = -1,
    val type: Int = 0,
    val subId: Int = -1,
    val dateSent: Long = 0L
) {
    /** Only a message the radio really refused counts as "Not sent". A bad delivery report is not a failed send. */
    val failed: Boolean get() = type == Telephony.Sms.MESSAGE_TYPE_FAILED
    val notDelivered: Boolean get() = !failed && status == Telephony.Sms.STATUS_FAILED
    val sending: Boolean get() = type == Telephony.Sms.MESSAGE_TYPE_OUTBOX || type == Telephony.Sms.MESSAGE_TYPE_QUEUED
    val delivered: Boolean get() = status == Telephony.Sms.STATUS_COMPLETE
    /** Real provider row id (MMS ids are offset by 1,000,000 in [id] so they never collide with SMS keys). */
    val rowId: Long get() = if (isMms) id - 1_000_000L else id
}

data class MsgHit(val id: Long, val threadId: Long, val address: String, val name: String, val body: String, val date: Long)

object SmsRepository {

    fun searchMessages(ctx: Context, q: String, limit: Int = 40): List<MsgHit> {
        val out = ArrayList<MsgHit>()
        runCatching {
            ctx.contentResolver.query(Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                Telephony.Sms.BODY + " LIKE ?", arrayOf("%" + q + "%"), Telephony.Sms.DATE + " DESC LIMIT " + limit)?.use { c ->
                while (c.moveToNext()) {
                    val a = c.getString(2) ?: ""
                    out.add(MsgHit(c.getLong(0), c.getLong(1), a, cachedContactName(ctx, a) ?: a, c.getString(3) ?: "", c.getLong(4)))
                }
            }
        }
        return out
    }


    private val contactCache = HashMap<String, String?>()
    private var contactCacheTime = 0L

    // Called from several background threads at once (list, search, notifications): keep it synchronized,
    // a plain HashMap here could crash with ConcurrentModificationException.
    private fun cachedContactName(ctx: Context, number: String): String? {
        if (number.isBlank()) return null
        synchronized(contactCache) {
            if (System.currentTimeMillis() - contactCacheTime > 120_000) {
                contactCache.clear()
                contactCacheTime = System.currentTimeMillis()
            }
            if (contactCache.containsKey(number)) return contactCache[number]
        }
        val n = contactName(ctx, number)
        synchronized(contactCache) { contactCache[number] = n }
        return n
    }

    // ── Conversations ─────────────────────────────────────────────────────────

    /**
     * The chat list, read from the threads table: one row per chat with its latest snippet and date,
     * instead of walking every SMS ever received (that took seconds on big phones, and a chat whose
     * newest message was a picture showed an older text and sat too low in the list).
     */
    fun loadConversations(ctx: Context): List<Conversation> =
        runCatching { loadConversationsFast(ctx) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: loadConversationsFull(ctx)

    private fun loadConversationsFast(ctx: Context): List<Conversation>? {
        val canon = HashMap<Long, String>()
        ctx.contentResolver.query(Uri.parse("content://mms-sms/canonical-addresses"), arrayOf("_id", "address"), null, null, null)?.use { c ->
            while (c.moveToNext()) canon[c.getLong(0)] = c.getString(1) ?: ""
        } ?: return null
        // Unread counts per thread
        val unreadCounts = HashMap<Long, Int>()
        runCatching {
            ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.THREAD_ID), Telephony.Sms.READ + " = 0", null, null)?.use { c ->
                while (c.moveToNext()) { val t = c.getLong(0); unreadCounts[t] = (unreadCounts[t] ?: 0) + 1 }
            }
        }
        runCatching {
            ctx.contentResolver.query(Uri.parse("content://mms"), arrayOf("thread_id"), "read = 0", null, null)?.use { c ->
                while (c.moveToNext()) { val t = c.getLong(0); unreadCounts[t] = (unreadCounts[t] ?: 0) + 1 }
            }
        }
        // Was the latest text in each chat sent by me? Only the newest few thousand texts are needed for that.
        val lastType = HashMap<Long, Int>()
        runCatching {
            ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.TYPE, Telephony.Sms.DATE), null, null,
                Telephony.Sms.DATE + " DESC LIMIT 3000")?.use { c ->
                while (c.moveToNext()) { val t = c.getLong(0); if (!lastType.containsKey(t)) lastType[t] = c.getInt(1) }
            }
        }
        val out = ArrayList<Conversation>()
        ctx.contentResolver.query(Uri.parse("content://mms-sms/conversations?simple=true"),
            arrayOf("_id", "date", "snippet", "recipient_ids", "message_count", "has_attachment"), null, null, "date DESC")?.use { c ->
            val iId = c.getColumnIndex("_id"); val iDate = c.getColumnIndex("date"); val iSn = c.getColumnIndex("snippet")
            val iRec = c.getColumnIndex("recipient_ids"); val iCount = c.getColumnIndex("message_count"); val iAtt = c.getColumnIndex("has_attachment")
            if (iId < 0 || iDate < 0 || iRec < 0) return null
            while (c.moveToNext()) {
                val thread = c.getLong(iId)
                if (iCount >= 0 && c.getInt(iCount) == 0) continue // empty draft threads
                val members = (c.getString(iRec) ?: "").split(" ").mapNotNull { it.trim().toLongOrNull()?.let(canon::get) }.filter { it.isNotBlank() }
                if (members.isEmpty()) continue
                if (members.size > 1) recipientCache[thread] = members
                val addr = members.joinToString(";")
                val name = if (members.size > 1) members.joinToString(", ") { cachedContactName(ctx, it) ?: it } else (cachedContactName(ctx, addr) ?: addr)
                var snippet = if (iSn >= 0) c.getString(iSn) ?: "" else ""
                if (snippet.isBlank() && iAtt >= 0 && c.getInt(iAtt) == 1) snippet = "📷 Photo"
                if (snippet.isBlank()) snippet = mmsSnippetForThread(ctx, thread)
                val uc = unreadCounts[thread] ?: 0
                val t = lastType[thread]
                out.add(Conversation(threadId = thread, address = addr, displayName = name, snippet = snippet, date = c.getLong(iDate),
                    unread = uc > 0, unreadCount = uc, outgoing = t != null && t != Telephony.Sms.MESSAGE_TYPE_INBOX))
            }
        } ?: return null
        return out.sortedByDescending { it.date }
    }

    private fun mmsSnippetForThread(ctx: Context, threadId: Long): String = runCatching {
        ctx.contentResolver.query(Uri.parse("content://mms"), arrayOf("_id"), "thread_id = ?", arrayOf(threadId.toString()), "date DESC LIMIT 1")
            ?.use { c -> if (c.moveToFirst()) mmsSnippet(ctx, c.getLong(0)) else "" } ?: ""
    }.getOrDefault("")

    private fun loadConversationsFull(ctx: Context): List<Conversation> {
        val out = ArrayList<Conversation>()
        val seen = HashSet<Long>()

        // Unread counts per thread (any unread message, not just the latest)
        val unreadCounts = HashMap<Long, Int>()
        runCatching {
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.THREAD_ID),
                Telephony.Sms.READ + " = 0", null, null
            )?.use { c ->
                val iT = c.getColumnIndex(Telephony.Sms.THREAD_ID)
                while (c.moveToNext()) { val t = c.getLong(iT); unreadCounts[t] = (unreadCounts[t] ?: 0) + 1 }
            }
        }
        runCatching {
            ctx.contentResolver.query(
                Uri.parse("content://mms"),
                arrayOf("thread_id"), "read = 0", null, null
            )?.use { c ->
                val iT = c.getColumnIndex("thread_id")
                while (c.moveToNext()) { val t = c.getLong(iT); unreadCounts[t] = (unreadCounts[t] ?: 0) + 1 }
            }
        }

        // SMS
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ, Telephony.Sms.TYPE),
            null, null, Telephony.Sms.DATE + " DESC"
        )?.use { c ->
            val iThread = c.getColumnIndex(Telephony.Sms.THREAD_ID)
            val iAddr   = c.getColumnIndex(Telephony.Sms.ADDRESS)
            val iBody   = c.getColumnIndex(Telephony.Sms.BODY)
            val iDate   = c.getColumnIndex(Telephony.Sms.DATE)
            val iType   = c.getColumnIndex(Telephony.Sms.TYPE)
            while (c.moveToNext()) {
                val thread = c.getLong(iThread)
                if (!seen.add(thread)) continue
                val addr = c.getString(iAddr) ?: ""
                val uc = unreadCounts[thread] ?: 0
                out.add(Conversation(
                    threadId    = thread,
                    address     = addr,
                    displayName = cachedContactName(ctx, addr) ?: addr,
                    snippet     = c.getString(iBody) ?: "",
                    date        = c.getLong(iDate),
                    unread      = uc > 0,
                    unreadCount = uc,
                    outgoing    = iType >= 0 && c.getInt(iType) != Telephony.Sms.MESSAGE_TYPE_INBOX
                ))
            }
        }

        // MMS threads not already in SMS list
        ctx.contentResolver.query(
            Uri.parse("content://mms"),
            arrayOf("_id", "thread_id", "date", "msg_box"),
            null, null, "date DESC"
        )?.use { c ->
            val iId     = c.getColumnIndex("_id")
            val iThread = c.getColumnIndex("thread_id")
            val iDate   = c.getColumnIndex("date")
            val iBox    = c.getColumnIndex("msg_box")
            while (c.moveToNext()) {
                val thread = c.getLong(iThread)
                if (!seen.add(thread)) continue
                val mmsId = c.getLong(iId)
                val addr = mmsAddress(ctx, thread)
                val snippet = mmsSnippet(ctx, mmsId)
                val uc = unreadCounts[thread] ?: 0
                out.add(Conversation(
                    threadId    = thread,
                    address     = addr,
                    displayName = cachedContactName(ctx, addr) ?: addr,
                    snippet     = snippet,
                    date        = c.getLong(iDate) * 1000L,
                    unread      = uc > 0,
                    unreadCount = uc,
                    outgoing    = iBox >= 0 && c.getInt(iBox) == 2
                ))
            }
        }

        // Group threads: the address must hold every member, otherwise replies went to one person.
        val fixed = out.map { c ->
            val members = threadRecipients(ctx, c.threadId)
            if (members.size > 1) {
                val addr = members.joinToString(";")
                c.copy(address = addr, displayName = members.joinToString(", ") { cachedContactName(ctx, it) ?: it })
            } else c
        }
        return fixed.sortedByDescending { it.date }
    }

    // ── Messages ──────────────────────────────────────────────────────────────

    fun loadMessages(ctx: Context, threadId: Long, limit: Int = 800): List<Message> {
        val out = ArrayList<Message>()

        // SMS
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.ADDRESS, Telephony.Sms.STATUS, Telephony.Sms.SUBSCRIPTION_ID, Telephony.Sms.DATE_SENT),
            Telephony.Sms.THREAD_ID + " = ?", arrayOf(threadId.toString()),
            Telephony.Sms.DATE + " DESC LIMIT " + limit
        )?.use { c ->
            val iId   = c.getColumnIndex(Telephony.Sms._ID)
            val iBody = c.getColumnIndex(Telephony.Sms.BODY)
            val iDate = c.getColumnIndex(Telephony.Sms.DATE)
            val iType = c.getColumnIndex(Telephony.Sms.TYPE)
            val iAddr = c.getColumnIndex(Telephony.Sms.ADDRESS)
            val iStat = c.getColumnIndex(Telephony.Sms.STATUS)
            val iSub  = c.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)
            val iDs   = c.getColumnIndex(Telephony.Sms.DATE_SENT)
            while (c.moveToNext()) {
                out.add(Message(
                    id       = c.getLong(iId),
                    body     = c.getString(iBody) ?: "",
                    date     = c.getLong(iDate),
                    incoming = c.getInt(iType) == Telephony.Sms.MESSAGE_TYPE_INBOX,
                    address  = if (iAddr >= 0) c.getString(iAddr) else null,
                    status   = if (iStat >= 0) c.getInt(iStat) else -1,
                    type     = c.getInt(iType),
                    subId    = if (iSub >= 0) c.getInt(iSub) else -1,
                    dateSent = if (iDs >= 0) c.getLong(iDs) else 0L
                ))
            }
        }

        // MMS
        ctx.contentResolver.query(
            Uri.parse("content://mms"),
            arrayOf("_id", "date", "msg_box"),
            "thread_id = ?", arrayOf(threadId.toString()),
            "date DESC LIMIT " + (limit * 3 / 8)
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
                    mediaMime = parts.mediaMime,
                    type     = c.getInt(iBox)
                ))
            }
        }

        out.sortBy { it.date }
        return out
    }

    // ── Send SMS ──────────────────────────────────────────────────────────────

    fun splitRecipients(address: String): List<String> =
        address.split(";", ",").map { it.trim() }.filter { it.isNotBlank() }.distinct()

    /**
     * Sends a text as SMS (one message per recipient). Each message is written to the
     * provider as OUTBOX first and moved to SENT / FAILED by [SmsStatusReceiver] when the
     * radio reports back, so failed messages show as failed and can be resent.
     */
    fun sendSms(ctx: Context, address: String, body: String, subId: Int = -1): Boolean {
        val recipients = splitRecipients(address)
        if (recipients.isEmpty() || body.isEmpty()) return false
        val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        val wantDelivery = prefs.getBoolean("sms_delivery", false)
        val threadId = getOrCreateThreadId(ctx, address)
        var anyOk = false
        for (recipient in recipients) {
            var rowUri: Uri? = null
            val ok = runCatching {
                val sms = smsManager(ctx, subId)
                val values = ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, recipient)
                    put(Telephony.Sms.BODY, body)
                    put(Telephony.Sms.DATE, System.currentTimeMillis())
                    put(Telephony.Sms.READ, 1)
                    put(Telephony.Sms.SEEN, 1)
                    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
                    put(Telephony.Sms.STATUS, if (wantDelivery) Telephony.Sms.STATUS_PENDING else Telephony.Sms.STATUS_NONE)
                    if (threadId > 0) put(Telephony.Sms.THREAD_ID, threadId)
                    val sid = effectiveSubId(ctx, subId)
                    if (sid >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, sid)
                }
                rowUri = ctx.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
                val parts = sms.divideMessage(body)
                val sent = ArrayList(parts.indices.map { SmsStatusReceiver.sentIntent(ctx, rowUri, it, it == parts.lastIndex) })
                val delivered = if (wantDelivery) ArrayList(parts.indices.map { SmsStatusReceiver.deliveredIntent(ctx, rowUri, it) }) else null
                if (parts.size > 1) sms.sendMultipartTextMessage(recipient, null, parts, sent, delivered)
                else sms.sendTextMessage(recipient, null, body, sent[0], delivered?.get(0))
                true
            }.getOrElse { e ->
                Log.e("SmsRepo", "sendSms: ${e.message}")
                rowUri?.let { u -> runCatching { ctx.contentResolver.update(u, ContentValues().apply { put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED) }, null, null) } }
                false
            }
            anyOk = anyOk || ok
        }
        return anyOk
    }

    /** Re-sends a failed message in place (same row, back to OUTBOX). */
    fun resend(ctx: Context, m: Message, fallbackAddress: String): Boolean {
        if (m.isMms) return false
        val to = m.address?.takeIf { it.isNotBlank() } ?: fallbackAddress
        deleteMessage(ctx, m)
        return sendSms(ctx, to, m.body, m.subId)
    }

    // ── Send MMS ──────────────────────────────────────────────────────────────

    private fun cleanupOldPdus(ctx: Context) {
        runCatching {
            val cutoff = System.currentTimeMillis() - 60 * 60_000L
            ctx.cacheDir.listFiles { f -> f.name.startsWith("mms_send_") && f.lastModified() < cutoff }?.forEach { it.delete() }
        }
    }

    /** Writes an outgoing MMS to the provider as OUTBOX; [MmsSentReceiver] moves it to SENT / FAILED. */
    private fun insertOutgoingMms(ctx: Context, threadId: Long, recipients: List<String>, media: Pair<String, ByteArray>?, text: String): Uri? {
        val now = System.currentTimeMillis()
        val mmsUri = ctx.contentResolver.insert(Uri.parse("content://mms"), ContentValues().apply {
            put("thread_id", threadId)
            put("msg_box", 4) // outbox
            put("read", 1); put("seen", 1)
            put("date", now / 1000)
            put("m_type", 128) // m-send-req
            put("ct_t", "application/vnd.wap.multipart.related")
            put("sub_id", effectiveSubId(ctx, -1))
        }) ?: return null
        val mmsId = mmsUri.lastPathSegment?.toLongOrNull() ?: return mmsUri
        ctx.contentResolver.insert(Uri.parse("content://mms/$mmsId/addr"), ContentValues().apply {
            put("address", "insert-address-token"); put("msg_id", mmsId); put("type", 137); put("charset", 106)
        })
        for (rcp in recipients) {
            ctx.contentResolver.insert(Uri.parse("content://mms/$mmsId/addr"), ContentValues().apply {
                put("address", rcp); put("msg_id", mmsId); put("type", 151); put("charset", 106)
            })
        }
        if (media != null) {
            val (mimeType, bytes) = media
            val ext = when {
                mimeType.startsWith("image/") -> "img"
                mimeType.startsWith("video/") -> "vid"
                mimeType.startsWith("audio/") -> "aud"
                else -> "file"
            }
            val partUri = ctx.contentResolver.insert(Uri.parse("content://mms/$mmsId/part"), ContentValues().apply {
                put("mid", mmsId); put("ct", mimeType); put("name", "$ext.${mimeType.substringAfter("/").take(4)}"); put("cl", "$ext.${mimeType.substringAfter("/").take(4)}")
            })
            if (partUri != null) ctx.contentResolver.openOutputStream(partUri)?.use { it.write(bytes) }
        }
        if (text.isNotBlank()) {
            ctx.contentResolver.insert(Uri.parse("content://mms/$mmsId/part"), ContentValues().apply {
                put("mid", mmsId); put("ct", "text/plain"); put("chset", 106); put("cl", "text.txt"); put("text", text)
            })
        }
        return mmsUri
    }

    private fun dispatchMms(ctx: Context, pdu: ByteArray, rowUri: Uri?) {
        cleanupOldPdus(ctx)
        val sms = smsManager(ctx)
        val pduFile = java.io.File(ctx.cacheDir, "mms_send_${System.currentTimeMillis()}_${(0..9999).random()}.dat")
        pduFile.writeBytes(pdu)
        val pduUri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", pduFile)
        ctx.grantUriPermission("com.android.mms.service", pduUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val cfg = android.os.Bundle()
        cfg.putBoolean(SmsManager.MMS_CONFIG_GROUP_MMS_ENABLED, true)
        val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        cfg.putBoolean("enabledMmsDeliveryReports", prefs.getBoolean("mms_delivery", false))
        val sendIntent = MmsSentReceiver.intent(ctx, rowUri, pduFile.absolutePath)
        try {
            sms.sendMultimediaMessage(ctx, pduUri, null, cfg, sendIntent)
        } catch (e: Exception) {
            rowUri?.let { u -> runCatching { ctx.contentResolver.update(u, ContentValues().apply { put("msg_box", 5) }, null, null) } }
            pduFile.delete()
            throw e
        }
    }

    fun sendMms(ctx: Context, address: String, mediaUri: Uri, caption: String = "", overrideMime: String? = null): Boolean {
        return runCatching {
            val rawBytes = ctx.contentResolver.openInputStream(mediaUri)?.use { it.readBytes() } ?: return false
            var mimeType = overrideMime ?: ctx.contentResolver.getType(mediaUri)
                ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(mediaUri.toString().substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
            val threadId = getOrCreateThreadId(ctx, address)
            val bytes = if (mimeType.startsWith("image/") && !(mimeType == "image/gif" && rawBytes.size < 600_000)) {
                mimeType = "image/jpeg"
                compressImage(rawBytes, 280_000)
            } else rawBytes
            val recipients = splitRecipients(address)
            val row = insertOutgoingMms(ctx, threadId, recipients, mimeType to bytes, caption)
            dispatchMms(ctx, buildSendReqPdu(address, bytes, mimeType, caption), row)
            true
        }.getOrElse { e -> Log.e("SmsRepo", "sendMms: ${e.message}"); false }
    }

    fun sendGroupText(ctx: Context, address: String, body: String): Boolean {
        return runCatching {
            val threadId = getOrCreateThreadId(ctx, address)
            val row = insertOutgoingMms(ctx, threadId, splitRecipients(address), null, body)
            dispatchMms(ctx, buildSendReqPdu(address, ByteArray(0), "text/plain", body, true), row)
            true
        }.getOrElse { e -> Log.e("SmsRepo", "sendGroupText: ${e.message}"); false }
    }

    private fun buildSendReqPdu(address: String, mediaBytes: ByteArray, mimeType: String, caption: String, textOnly: Boolean = false): ByteArray {
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
        val recipients = splitRecipients(address).map { it.replace(Regex("[^0-9+]"), "") }.filter { it.isNotBlank() }
        for (recipient in recipients) {
            out.write(0x97)
            out.write("$recipient/TYPE=PLMN".toByteArray()); out.write(0x00)
        }

        // No Subject header: the caption travels as its own UTF-8 text part. (Putting it in the
        // subject too made many phones show the caption twice.)

        // Content-Type: application/vnd.wap.multipart.mixed (0xA3 short-integer)
        out.write(0x84); out.write(0xA3.toByte().toInt())

        // Part count
        val partCount = if (textOnly) 1 else 1 + (if (caption.isNotBlank()) 1 else 0)
        writeUintVar(out, partCount)

        // Media part
        if (!textOnly) {
        val mediaPartHeaders = java.io.ByteArrayOutputStream()
        mediaPartHeaders.write(mimeType.toByteArray()); mediaPartHeaders.write(0x00)
        // Content-Location, so receivers (iPhones especially) get a file name
        mediaPartHeaders.write(0x8E); mediaPartHeaders.write(("media." + mimeType.substringAfter("/").substringBefore(";").take(4)).toByteArray()); mediaPartHeaders.write(0x00)
        writeUintVar(out, mediaPartHeaders.size())
        writeUintVar(out, mediaBytes.size)
        out.write(mediaPartHeaders.toByteArray())
        out.write(mediaBytes)
        }

        // Text part (optional)
        if (caption.isNotBlank()) {
            val textBytes = caption.toByteArray(Charsets.UTF_8)
            val textPartHeaders = java.io.ByteArrayOutputStream()
            // Content-Type: text/plain; charset=utf-8  (general form: length 3, 0x83 text/plain,
            // 0x81 charset, 0xEA utf-8). Without the charset, Yiddish/Hebrew arrived garbled.
            textPartHeaders.write(0x03); textPartHeaders.write(0x83); textPartHeaders.write(0x81); textPartHeaders.write(0xEA)
            textPartHeaders.write(0x8E); textPartHeaders.write("text.txt".toByteArray()); textPartHeaders.write(0x00)
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
        while (opts.outWidth / sampleSize > 1600 || opts.outHeight / sampleSize > 1600) sampleSize *= 2
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sampleSize }) ?: return raw
        // Respect EXIF orientation — without this, portrait camera photos were sent sideways.
        val rotation = runCatching {
            when (android.media.ExifInterface(java.io.ByteArrayInputStream(raw)).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        var bmp = if (rotation != 0f) {
            val m = android.graphics.Matrix().apply { postRotate(rotation) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true).also { if (it !== decoded) decoded.recycle() }
        } else decoded
        var result = raw
        for (attempt in 0 until 4) {
            var quality = 90
            while (quality >= 50) {
                val baos = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                result = baos.toByteArray()
                if (result.size <= maxSize) { bmp.recycle(); return result }
                quality -= 10
            }
            // Still too big at decent quality: shrink the picture instead of making it mushy.
            val smaller = Bitmap.createScaledBitmap(bmp, (bmp.width * 0.75f).toInt().coerceAtLeast(1), (bmp.height * 0.75f).toInt().coerceAtLeast(1), true)
            if (smaller !== bmp) bmp.recycle()
            bmp = smaller
        }
        bmp.recycle()
        return result
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

    fun markThreadUnread(ctx: Context, threadId: Long) {
        runCatching {
            var id = -1L
            ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID), Telephony.Sms.THREAD_ID + " = ? AND " + Telephony.Sms.TYPE + " = 1", arrayOf(threadId.toString()), Telephony.Sms.DATE + " DESC LIMIT 1")?.use { if (it.moveToFirst()) id = it.getLong(0) }
            if (id >= 0) ctx.contentResolver.update(Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, id.toString()), ContentValues().apply { put(Telephony.Sms.READ, 0); put(Telephony.Sms.SEEN, 0) }, null, null)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** The SIM to use: an explicit [subId], else the one picked in Settings, else -1 (system default). */
    fun effectiveSubId(ctx: Context, subId: Int): Int {
        val s = if (subId >= 0) subId else ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE).getInt("sim_sub", -1)
        if (s < 0) return -1
        // A SIM that was removed or switched off can't send: use the phone's default instead of failing every message
        val active = runCatching {
            if (Build.VERSION.SDK_INT >= 22) (ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? android.telephony.SubscriptionManager)?.getActiveSubscriptionInfo(s) != null else true
        }.getOrDefault(true)
        return if (active) s else -1
    }

    @Suppress("DEPRECATION")
    internal fun smsManager(ctx: Context, subId: Int = -1): SmsManager {
        val sub = effectiveSubId(ctx, subId)
        if (sub >= 0) runCatching {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                ctx.getSystemService(SmsManager::class.java).createForSubscriptionId(sub)
            else SmsManager.getSmsManagerForSubscriptionId(sub)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ctx.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
        else
            SmsManager.getDefault()
    }

    /** Thread for one or more recipients ("a;b;c" is a group thread, not a thread with a weird address). */
    fun getOrCreateThreadId(ctx: Context, address: String): Long {
        val recipients = splitRecipients(address)
        if (recipients.isEmpty()) return 0L
        return runCatching { Telephony.Threads.getOrCreateThreadId(ctx, recipients.toSet()) }.getOrDefault(0L)
    }

    private val recipientCache = java.util.concurrent.ConcurrentHashMap<Long, List<String>>()

    /** Every participant of a thread (all members of a group), from the threads table. */
    fun threadRecipients(ctx: Context, threadId: Long): List<String> {
        if (threadId <= 0) return emptyList()
        recipientCache[threadId]?.let { return it }
        val ids = runCatching {
            ctx.contentResolver.query(Uri.parse("content://mms-sms/conversations?simple=true"), arrayOf("recipient_ids"),
                "_id = ?", arrayOf(threadId.toString()), null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()?.split(" ")?.mapNotNull { it.trim().toLongOrNull() } ?: return emptyList()
        val out = ids.mapNotNull { id ->
            runCatching {
                ctx.contentResolver.query(Uri.parse("content://mms-sms/canonical-address/$id"), arrayOf("address"), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        if (out.isNotEmpty()) recipientCache[threadId] = out
        return out
    }

    /** The address to send to for a thread: every member joined with ";" for groups. */
    fun threadAddress(ctx: Context, threadId: Long, fallback: String): String {
        val r = threadRecipients(ctx, threadId)
        return if (r.isEmpty()) fallback else r.joinToString(";")
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
            // Never invent a thread id (the old hashCode fallback opened chats that did not exist).
            return getOrCreateThreadId(ctx, address)
        }

        fun getContactName(ctx: Context, address: String): String? {
            return cachedContactName(ctx, address)
        }


        private val photoCache = HashMap<String, Uri?>()

        fun contactPhotoUri(ctx: Context, number: String): Uri? {
            if (number.isBlank() || number.contains(";")) return null
            photoCache[number]?.let { return it }
            if (photoCache.containsKey(number)) return null
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
            )
            val result = runCatching {
                ctx.contentResolver.query(
                    uri, arrayOf(ContactsContract.PhoneLookup.PHOTO_URI), null, null, null
                )?.use { c -> if (c.moveToFirst()) c.getString(0)?.let(Uri::parse) else null }
            }.getOrNull()
            photoCache[number] = result
            return result
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

        fun deleteMessage(ctx: Context, m: Message) {
        try {
            if (m.isMms) ctx.contentResolver.delete(Uri.parse("content://mms/${m.rowId}"), null, null)
            else ctx.contentResolver.delete(Uri.parse("content://sms/${m.rowId}"), null, null)
        } catch (_: Exception) {}
    }

    fun deleteThread(ctx: Context, threadId: Long) {
            try {
                ctx.contentResolver.delete(Telephony.Sms.CONTENT_URI, "thread_id = ?", arrayOf(threadId.toString()))
                ctx.contentResolver.delete(Uri.parse("content://mms"), "thread_id = ?", arrayOf(threadId.toString()))
            } catch (_: Exception) {}
        }

        fun deleteAllConversations(ctx: Context) {
            try { ctx.contentResolver.delete(Telephony.Sms.CONTENT_URI, null, null) } catch (_: Exception) {}
            try { ctx.contentResolver.delete(Uri.parse("content://mms"), null, null) } catch (_: Exception) {}
        }

}
