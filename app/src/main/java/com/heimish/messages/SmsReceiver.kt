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

        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body.toString())
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
        }
        val uri = runCatching { context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values) }.getOrNull()

        // Get thread ID for notification reply
        val threadId = SmsRepository.getThreadIdForAddress(context, address)
        val sender = SmsRepository.getContactName(context, address) ?: address

        Notifications.showMessage(context, sender, body.toString(), address, threadId)
    }
}
