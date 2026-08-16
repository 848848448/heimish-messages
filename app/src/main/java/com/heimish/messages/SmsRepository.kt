package com.heimish.messages

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager

/** A single conversation (thread) shown in the list. */
data class Conversation(
    val threadId: Long,
    val address: String,
    val displayName: String,
    val snippet: String,
    val date: Long,
    val unread: Boolean
)

/** A single message inside a thread. */
data class Message(
    val id: Long,
    val body: String,
    val date: Long,
    val incoming: Boolean
)

object SmsRepository {

    fun loadConversations(ctx: Context): List<Conversation> {
        val out = ArrayList<Conversation>()
        val uri = Telephony.Sms.CONTENT_URI
        val cols = arrayOf(
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.READ
        )
        val seen = HashSet<Long>()
        ctx.contentResolver.query(uri, cols, null, null, Telephony.Sms.DATE + " DESC")?.use { c ->
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
        return out
    }

    fun loadMessages(ctx: Context, threadId: Long): List<Message> {
        val out = ArrayList<Message>()
        val cols = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE
        )
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI, cols,
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
        return out
    }

    /**
     * Send SMS — works on Android 5–14.
     * On API 31+ getSystemService(SmsManager) needs a subscription ID on some
     * devices; using the compat helper avoids that crash.
     */
    fun sendSms(ctx: Context, address: String, body: String): Boolean {
        return runCatching {
            val sms = smsManager(ctx)
            val parts = sms.divideMessage(body)
            if (parts.size > 1)
                sms.sendMultipartTextMessage(address, null, parts, null, null)
            else
                sms.sendTextMessage(address, null, body, null, null)

            // Store in the sent box so the thread shows the message immediately
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
        }.getOrElse { e ->
            e.printStackTrace()
            false
        }
    }

    fun markThreadRead(ctx: Context, threadId: Long) {
        runCatching {
            val values = ContentValues().apply { put(Telephony.Sms.READ, 1) }
            ctx.contentResolver.update(
                Telephony.Sms.CONTENT_URI, values,
                Telephony.Sms.THREAD_ID + " = ?", arrayOf(threadId.toString())
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
}
