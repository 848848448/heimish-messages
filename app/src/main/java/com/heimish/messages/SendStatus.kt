package com.heimish.messages

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsMessage
import java.util.concurrent.atomic.AtomicInteger

/**
 * Receives the radio's "sent" and "delivered" reports for outgoing SMS and updates the
 * provider row: OUTBOX -> SENT or FAILED, and STATUS -> COMPLETE / FAILED for delivery.
 * Before this existed every message was written as "sent" up front, so failures were invisible.
 */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val uri = intent.data ?: return
        val pending = goAsync()
        val code = resultCode
        Thread {
            try {
                when (intent.action) {
                    ACTION_SENT -> {
                        // RESULT_OK (-1) is success; some phones report RESULT_ERROR_NONE (0), which also means no error
                        if (code == Activity.RESULT_OK || code == 0) {
                            if (intent.getBooleanExtra(EXTRA_LAST, true)) {
                                ctx.contentResolver.update(uri, ContentValues().apply { put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT) },
                                    Telephony.Sms.TYPE + " = " + Telephony.Sms.MESSAGE_TYPE_OUTBOX, null)
                            }
                        } else {
                            ctx.contentResolver.update(uri, ContentValues().apply {
                                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED)
                                put(Telephony.Sms.ERROR_CODE, code)
                            }, null, null)
                            notifyFailed(ctx, uri)
                        }
                    }
                    ACTION_DELIVERED -> {
                        val pdu = intent.getByteArrayExtra("pdu")
                        val format = intent.getStringExtra("format")
                        val st = runCatching { if (pdu != null) SmsMessage.createFromPdu(pdu, format)?.status else null }.getOrNull()
                        val status = when {
                            st == null -> Telephony.Sms.STATUS_COMPLETE
                            // CDMA (3gpp2) packs the status as ((errorClass << 8) | messageStatus) << 16, so a good
                            // report is a big number; reading it like GSM marked every delivered message as failed.
                            format == "3gpp2" -> when ((st shr 24) and 0x03) { 0 -> Telephony.Sms.STATUS_COMPLETE; 2 -> Telephony.Sms.STATUS_PENDING; 3 -> Telephony.Sms.STATUS_FAILED; else -> Telephony.Sms.STATUS_COMPLETE }
                            // GSM TP-Status: 0..31 done, 32..63 still trying, 64+ failed
                            st >= 64 -> Telephony.Sms.STATUS_FAILED
                            st >= 32 -> Telephony.Sms.STATUS_PENDING
                            else -> Telephony.Sms.STATUS_COMPLETE
                        }
                        ctx.contentResolver.update(uri, ContentValues().apply { put(Telephony.Sms.STATUS, status) }, null, null)
                    }
                }
                MessageWidget.refresh(ctx)
            } catch (_: Exception) {
            } finally { pending.finish() }
        }.start()
    }

    private fun notifyFailed(ctx: Context, uri: Uri) {
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.THREAD_ID), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val addr = c.getString(0) ?: ""
                    val name = SmsRepository.getContactName(ctx, addr) ?: addr
                    Notifications.showFailed(ctx, name, addr, c.getLong(1))
                }
            }
        }
    }

    companion object {
        const val ACTION_SENT = "com.heimish.messages.SMS_SENT"
        const val ACTION_DELIVERED = "com.heimish.messages.SMS_DELIVERED"
        private const val EXTRA_LAST = "last"
        private val req = AtomicInteger((System.currentTimeMillis() % 100_000).toInt() * 10)

        fun sentIntent(ctx: Context, row: Uri?, part: Int, last: Boolean): PendingIntent {
            val i = Intent(ACTION_SENT, row ?: Uri.parse("content://sms/0"), ctx, SmsStatusReceiver::class.java).putExtra(EXTRA_LAST, last).putExtra("part", part)
            return PendingIntent.getBroadcast(ctx, req.incrementAndGet(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        }

        fun deliveredIntent(ctx: Context, row: Uri?, part: Int): PendingIntent {
            val i = Intent(ACTION_DELIVERED, row ?: Uri.parse("content://sms/0"), ctx, SmsStatusReceiver::class.java).putExtra("part", part)
            return PendingIntent.getBroadcast(ctx, req.incrementAndGet(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        }
    }
}

/** Result of an outgoing MMS: moves the row from outbox (4) to sent (2) or failed (5) and removes the PDU file. */
class MmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val code = resultCode
        val pending = goAsync()
        Thread {
            try {
                intent.getStringExtra(EXTRA_FILE)?.let { runCatching { java.io.File(it).delete() } }
                val uri = intent.data
                if (uri != null && uri.toString() != "content://mms/0") {
                    ctx.contentResolver.update(uri, ContentValues().apply { put("msg_box", if (code == Activity.RESULT_OK) 2 else 5) }, null, null)
                }
                MessageWidget.refresh(ctx)
            } catch (_: Exception) {
            } finally { pending.finish() }
        }.start()
    }

    companion object {
        const val ACTION = "com.heimish.messages.MMS_SENT"
        private const val EXTRA_FILE = "pdu_file"
        private val req = AtomicInteger((System.currentTimeMillis() % 100_000).toInt() * 10 + 5)
        fun intent(ctx: Context, row: Uri?, file: String): PendingIntent {
            val i = Intent(ACTION, row ?: Uri.parse("content://mms/0"), ctx, MmsSentReceiver::class.java).putExtra(EXTRA_FILE, file)
            return PendingIntent.getBroadcast(ctx, req.incrementAndGet(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        }
    }
}
