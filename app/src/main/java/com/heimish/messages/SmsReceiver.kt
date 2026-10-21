package com.heimish.messages

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (msgs.isEmpty()) return

        val address = msgs[0].originatingAddress ?: ""
        val body = StringBuilder()
        for (m: SmsMessage in msgs) body.append(m.messageBody ?: "")
        val sentAt = msgs[0].timestampMillis
        val subId = intent.getIntExtra("subscription", intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", -1))

        // Do the database + notification work off the main thread.
        val pending = goAsync()
        Thread {
            try { handle(context, address, body.toString(), sentAt, subId) } catch (_: Exception) {} finally { pending.finish() }
        }.start()
    }

    private fun handle(context: Context, address: String, body: String, sentAt: Long, subId: Int) {
        val blocked = BlockedStore.isBlocked(context, address)
        val spam = !blocked && (SpamFilter.isSpam(context, address, body) || (SmsRepository.getContactName(context, address) == null && KeywordStore.matches(context, body)))

        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            if (sentAt > 0) put(Telephony.Sms.DATE_SENT, sentAt)
            put(Telephony.Sms.READ, if (blocked || spam) 1 else 0)
            put(Telephony.Sms.SEEN, if (blocked || spam) 1 else 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            if (subId >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, subId)
        }
        val row = runCatching { context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values) }.getOrNull()

        if (blocked) return

        // The thread the provider actually filed it under (the old address lookup could miss and invent one).
        val threadId = row?.let { u ->
            runCatching { context.contentResolver.query(u, arrayOf(Telephony.Sms.THREAD_ID), null, null, null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else null } }.getOrNull()
        } ?: SmsRepository.getThreadIdForAddress(context, address)
        if (spam) { SpamStore.add(context, threadId); return }
        // A new message brings a trashed chat back right away (like Google Messages)
        if (threadId > 0) runCatching { TrashStore.remove(context, threadId) }
        MessageWidget.refresh(context)
        val sender = SmsRepository.getContactName(context, address) ?: address

        Notifications.showMessage(context, sender, body, address, threadId)
        if (Reactions.parse(body) == null) AutoReply.maybe(context, address)
    }
}

/** Shared blocked-numbers store (Settings > Protection & Safety). */
object BlockedStore {
    private const val KEY = "blocked_numbers"

    private fun norm(n: String) = n.replace(Regex("[^+0-9]"), "")

    fun isBlocked(ctx: Context, address: String): Boolean {
        val a = norm(address)
        if (a.isBlank()) return false
        return getAll(ctx).any { norm(it) == a }
    }

    fun block(ctx: Context, address: String) {
        val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(address)
        prefs.edit().putStringSet(KEY, set).apply()
    }

    fun unblock(ctx: Context, address: String) {
        val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        set.remove(address)
        prefs.edit().putStringSet(KEY, set).apply()
    }

    fun getAll(ctx: Context): Set<String> =
        ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet()) ?: emptySet()
}
