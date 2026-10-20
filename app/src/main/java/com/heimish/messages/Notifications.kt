package com.heimish.messages

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifications {
    private const val CHANNEL_ID = "heimish_sms"
    private const val CHANNEL_QUIET = "heimish_sms_quiet"
    private const val REPLY_KEY = "key_reply"

    private fun channelFor(ctx: Context): String {
        val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        val sound = prefs.getBoolean("notif_sound", true)
        val vibrate = prefs.getBoolean("notif_vibrate", true)
        return if (sound || vibrate) CHANNEL_ID else CHANNEL_QUIET
    }

    private fun ensureChannel(ctx: Context, id: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val noisy = id == CHANNEL_ID
            val chan = NotificationChannel(
                id, if (noisy) "Messages" else "Messages (silent)",
                if (noisy) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "New message notifications"
                enableVibration(noisy)
                if (!noisy) setSound(null, null)
            }
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(chan)
        }
    }

    fun init(ctx: Context) {
        ensureChannel(ctx, CHANNEL_ID)
        ensureChannel(ctx, CHANNEL_QUIET)
    }

    fun showMessage(ctx: Context, sender: String, body: String, address: String, threadId: Long) {
        val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notif_allow", true)) return
        if (prefs.getBoolean("muted_$threadId", false)) return

        val showSender = prefs.getBoolean("notif_sender", true)
        val showPreview = prefs.getBoolean("notif_preview", true)
        val title = if (showSender) sender else "New message"
        val text = if (showPreview) body else ""

        val channelId = channelFor(ctx)
        ensureChannel(ctx, channelId)

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

        val builder = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(title)
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (channelId == CHANNEL_ID) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .addAction(replyAction)
            .addAction(readAction)
        if (text.isNotEmpty()) {
            builder.setContentText(text)
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }

        try { NotificationManagerCompat.from(ctx).notify(nid, builder.build()) } catch (_: SecurityException) {}
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

        Thread {
            SmsRepository.sendSms(ctx, address, reply)
        }.start()

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
