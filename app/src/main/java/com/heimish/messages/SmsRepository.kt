package com.heimish.messages

import android.content.ContentValues
import android.content.Context
import android.net.Uri
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

    /** Read the list of conversations from the system SMS store, newest first. */
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
            val iAddr = c.getColumnIndex(Telephony.Sms.ADDRESS)
            val iBody = c.getColumnIndex(Telephony.Sms.BODY)
            val iDate = c.getColumnIndex(Telephony.Sms.DATE)
            val iRead = c.getColumnIndex(Telephony.Sms.READ)
            while (c.moveToNext()) {
                val thread = c.getLong(iThread)
                if (!seen.add(thread)) continue   // keep only the newest row per thread
                val addr = c.getString(iAddr) ?: ""
                out.add(
                    Conversation(
                        threadId = thread,
                        address = addr,
                        displayName = contactName(ctx, addr) ?: addr,
                        snippet = c.getString(iBody) ?: "",
                        date = c.getLong(iDate),
                        unread = c.getInt(iRead) == 0
                    )
                )
            }
        }
        return out
    }

    /** Read all messages in one thread, oldest first. */
    fun loadMessages(ctx: Context, threadId: Long): List<Message> {
        val out = ArrayList<Message>()
        val uri = Telephony.Sms.CONTENT_URI
        val cols = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE
        )
        val sel = Telephony.Sms.THREAD_ID + " = ?"
        ctx.contentResolver.query(uri, cols, sel, arrayOf(threadId.toString()), Telephony.Sms.DATE + " ASC")?.use { c ->
            val iId = c.getColumnIndex(Telephony.Sms._ID)
            val iBody = c.getColumnIndex(Telephony.Sms.BODY)
            val iDate = c.getColumnIndex(Telephony.Sms.DATE)
            val iType = c.getColumnIndex(Telephony.Sms.TYPE)
            while (c.moveToNext()) {
                val type = c.getInt(iType)
                out.add(
                    Message(
                        id = c.getLong(iId),
                        body = c.getString(iBody) ?: "",
                        date = c.getLong(iDate),
                        incoming = type == Telephony.Sms.MESSAGE_TYPE_INBOX
                    )
                )
            }
        }
        return out
    }

    /** Send an SMS and store it in the system "sent" box so it shows in the thread. */
    fun sendSms(ctx: Context, address: String, body: String) {
        val sms = ctx.getSystemService(SmsManager::class.java)
        val parts = sms.divideMessage(body)
        if (parts.size > 1) sms.sendMultipartTextMessage(address, null, parts, null, null)
        else sms.sendTextMessage(address, null, body, null, null)

        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
        }
        runCatching { ctx.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values) }
    }

    /** Mark a whole thread as read. */
    fun markThreadRead(ctx: Context, threadId: Long) {
        val values = ContentValues().apply { put(Telephony.Sms.READ, 1) }
        runCatching {
            ctx.contentResolver.update(
                Telephony.Sms.CONTENT_URI, values,
                Telephony.Sms.THREAD_ID + " = ?", arrayOf(threadId.toString())
            )
        }
    }

    /** Resolve a phone number to a saved contact name, if any. */
    private fun contactName(ctx: Context, number: String): String? {
        if (number.isBlank()) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }
}
