package com.heimish.messages

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
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
    val imageUri: Uri? = null   // for MMS image parts
)

object SmsRepository {

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
                    displayName = contactName(ctx, addr) ?: addr,
                    snippet     = c.getString(iBody) ?: "",
                    date        = c.getLong(iDate),
                    unread      = c.getInt(iRead) == 0
                ))
            }
        }

        // MMS threads not already in SMS list
        ctx.contentResolver.query(
            Uri.parse("content://mms"),
            arrayOf("thread_id", "date", "read"),
            null, null, "date DESC"
        )?.use { c ->
            val iThread = c.getColumnIndex("thread_id")
            val iDate   = c.getColumnIndex("date")
            val iRead   = c.getColumnIndex("read")
            while (c.moveToNext()) {
                val thread = c.getLong(iThread)
                if (!seen.add(thread)) continue
                val addr = mmsAddress(ctx, thread)
                out.add(Conversation(
                    threadId    = thread,
                    address     = addr,
                    displayName = contactName(ctx, addr) ?: addr,
                    snippet     = "📷 MMS",
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
            arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
            Telephony.Sms.THREAD_ID + " = ?", arrayOf(threadId.toString()),
            Telephony.Sms.DATE + " ASC"
        )?.use { c ->
            val iId   = c.getColumnIndex(Telephony.Sms._ID)
            val iBody = c.getColumnIndex(Telephony.Sms.BODY)
            val iDate = c.getColumnIndex(Telephony.Sms.DATE)
            val iType = c.getColumnIndex(Telephony.Sms.TYPE)
            while (c.moveToNext()) {
                out.add(Message(
                    id       = c.getLong(iId),
                    body     = c.getString(iBody) ?: "",
                    date     = c.getLong(iDate),
                    incoming = c.getInt(iType) == Telephony.Sms.MESSAGE_TYPE_INBOX
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
                val (text, imgUri) = mmsParts(ctx, mmsId)
                out.add(Message(
                    id       = mmsId + 1_000_000L,
                    body     = text,
                    date     = date,
                    incoming = incoming,
                    isMms    = true,
                    imageUri = imgUri
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
            val parts = sms.divideMessage(body)
            if (parts.size > 1)
                sms.sendMultipartTextMessage(address, null, parts, null, null)
            else
                sms.sendTextMessage(address, null, body, null, null)

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

    fun sendMms(ctx: Context, address: String, imageUri: Uri, caption: String = ""): Boolean {
        return runCatching {
            // Read image bytes
            val bytes = ctx.contentResolver.openInputStream(imageUri)?.readBytes() ?: return false
            val mimeType = ctx.contentResolver.getType(imageUri) ?: "image/jpeg"

            @Suppress("DEPRECATION")
            val sms = smsManager(ctx)

            // Build MMS parts
            val parts = ArrayList<android.telephony.SmsManager>() // placeholder

            // Use system MMS API (API 21+)
            val sendReqUri = Uri.parse("content://mms/outbox")
            val threadId = getOrCreateThreadId(ctx, address)

            // Insert MMS into system
            val mmsValues = ContentValues().apply {
                put("thread_id", threadId)
                put("msg_box", 4) // outbox
                put("read", 1)
                put("seen", 1)
                put("date", System.currentTimeMillis() / 1000)
            }
            val mmsUri = ctx.contentResolver.insert(Uri.parse("content://mms"), mmsValues)
            val mmsId = mmsUri?.lastPathSegment?.toLong() ?: return false

            // Address part
            val addrValues = ContentValues().apply {
                put("address", address)
                put("msg_id", mmsId)
                put("type", 151) // PduHeaders.TO
                put("charset", 106)
            }
            ctx.contentResolver.insert(Uri.parse("content://mms/$mmsId/addr"), addrValues)

            // Image part
            val imgValues = ContentValues().apply {
                put("mid", mmsId)
                put("ct", mimeType)
                put("name", "image")
                put("chset", 106)
            }
            val partUri = ctx.contentResolver.insert(Uri.parse("content://mms/$mmsId/part"), imgValues)
            if (partUri != null) {
                ctx.contentResolver.openOutputStream(partUri)?.use { it.write(bytes) }
            }

            // Caption part (if any)
            if (caption.isNotBlank()) {
                val textValues = ContentValues().apply {
                    put("mid", mmsId)
                    put("ct", "text/plain")
                    put("chset", 106)
                    put("text", caption)
                }
                ctx.contentResolver.insert(Uri.parse("content://mms/$mmsId/part"), textValues)
            }

            // Trigger actual send
            sms.sendMultimediaMessage(
                ctx,
                mmsUri,
                null, null, null
            )
            true
        }.getOrElse { e -> Log.e("SmsRepo", "sendMms: ${e.message}"); false }
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

    private fun mmsParts(ctx: Context, mmsId: Long): Pair<String, Uri?> {
        var text = ""
        var imgUri: Uri? = null
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
                        ct.startsWith("image/") -> imgUri = Uri.parse("content://mms/part/$partId")
                    }
                }
            }
        }
        return text to imgUri
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
            try {
                val uri = android.net.Uri.withAppendedPath(
                    android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    android.net.Uri.encode(address))
                val cursor = ctx.contentResolver.query(uri,
                    arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME),
                    null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) return it.getString(0)
                }
            } catch (_: Exception) {}
            return null
        }

}
