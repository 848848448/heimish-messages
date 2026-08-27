package com.heimish.messages

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifications {
    private const val CHANNEL_ID = "heimish_sms"
    private const val REPLY_KEY = "key_reply"

    fun init(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(CHANNEL_ID, "Messages",
                NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New message notifications"
                enableVibration(true)
            }
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(chan)
        }
    }

    fun showMessage(ctx: Context, sender: String, body: String, address: String, threadId: Long) {
        init(ctx)

        val nid = (threadId % Int.MAX_VALUE).toInt()

        // Tap → open app
        val openIntent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(ctx, nid, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // Direct reply action
        val remoteInput = RemoteInput.Builder(REPLY_KEY)
            .setLabel("Reply")
            .build()

        val replyIntent = Intent(ctx, DirectReplyReceiver::class.java).apply {
            putExtra("address", address)
            putExtra("thread_id", threadId)
            putExtra("nid", nid)
        }
        val replyPi = PendingIntent.getBroadcast(ctx, nid + 10000, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send, "Reply", replyPi
        ).addRemoteInput(remoteInput).build()

        // Mark as read action
        val readIntent = Intent(ctx, MarkReadReceiver::class.java).apply {
            putExtra("thread_id", threadId)
            putExtra("nid", nid)
        }
        val readPi = PendingIntent.getBroadcast(ctx, nid + 20000, readIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val readAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel, "Mark as read", readPi
        ).build()

        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(sender)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(replyAction)
            .addAction(readAction)
            .build()

        try { NotificationManagerCompat.from(ctx).notify(nid, notif) } catch (_: SecurityException) {}
    }

    fun showTestNotification(ctx: Context) {
        showMessage(ctx, "Heimish Messages", "This is a test notification \ud83d\udcf1", "test", 0)
    }
}

/** Handles direct reply from the notification shade */
class DirectReplyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val results = RemoteInput.getResultsFromIntent(intent) ?: return
        val reply = results.getCharSequence("key_reply")?.toString() ?: return
        val address = intent.getStringExtra("address") ?: return
        val nid = intent.getIntExtra("nid", 0)

        // Send the SMS
        Thread {
            SmsRepository.sendSms(ctx, address, reply)
        }.start()

        // Update notification to show "Sent"
        val notif = NotificationCompat.Builder(ctx, "heimish_sms")
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentText("Reply sent")
            .build()
        try { NotificationManagerCompat.from(ctx).notify(nid, notif) } catch (_: SecurityException) {}
    }
}

/** Handles "Mark as read" from the notification */
class MarkReadReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val threadId = intent.getLongExtra("thread_id", -1)
        val nid = intent.getIntExtra("nid", 0)
        if (threadId >= 0) SmsRepository.markThreadRead(ctx, threadId)
        NotificationManagerCompat.from(ctx).cancel(nid)
    }
}
