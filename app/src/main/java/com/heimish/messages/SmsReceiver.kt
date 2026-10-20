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

        val blocked = BlockedStore.isBlocked(context, address)

        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body.toString())
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.READ, if (blocked) 1 else 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
        }
        runCatching { context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values) }

        if (blocked) return

        val threadId = SmsRepository.getThreadIdForAddress(context, address)
        val sender = SmsRepository.getContactName(context, address) ?: address

        Notifications.showMessage(context, sender, body.toString(), address, threadId)
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
