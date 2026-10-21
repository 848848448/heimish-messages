package com.heimish.messages

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

object Notifications {
    private const val CHANNEL_ID = "heimish_sms"
    private const val CHANNEL_QUIET = "heimish_sms_quiet"
    private const val CHANNEL_FAILED = "heimish_failed"
    const val REPLY_KEY = "key_reply"

    private fun channelFor(ctx: Context): String {
        val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        val sound = prefs.getBoolean("notif_sound", true)
        val vibrate = prefs.getBoolean("notif_vibrate", true)
        return if (sound || vibrate) CHANNEL_ID else CHANNEL_QUIET
    }

    private fun ensureChannel(ctx: Context, id: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = when (id) {
                CHANNEL_FAILED -> NotificationChannel(id, "Messages not sent", NotificationManager.IMPORTANCE_HIGH).apply { description = "When a message could not be sent" }
                CHANNEL_ID -> NotificationChannel(id, "Messages", NotificationManager.IMPORTANCE_HIGH).apply { description = "New message notifications"; enableVibration(true) }
                else -> NotificationChannel(id, "Messages (silent)", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "New message notifications"; enableVibration(false); setSound(null, null) }
            }
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chan)
        }
    }

    fun init(ctx: Context) {
        ensureChannel(ctx, CHANNEL_ID)
        ensureChannel(ctx, CHANNEL_QUIET)
    }

    fun nidFor(threadId: Long) = (threadId % Int.MAX_VALUE).toInt()

    fun cancelThread(ctx: Context, threadId: Long) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(nidFor(threadId)) }
    }

    private data class Line(val address: String, val body: String, val date: Long)

    /** The unread incoming texts of a thread, oldest first, so the notification shows the whole burst. */
    private fun unreadLines(ctx: Context, threadId: Long): List<Line> = runCatching {
        val out = ArrayList<Line>()
        ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            Telephony.Sms.THREAD_ID + " = ? AND " + Telephony.Sms.READ + " = 0 AND " + Telephony.Sms.TYPE + " = 1",
            arrayOf(threadId.toString()), Telephony.Sms.DATE + " DESC LIMIT 8")?.use { c ->
            while (c.moveToNext()) out.add(Line(c.getString(0) ?: "", c.getString(1) ?: "", c.getLong(2)))
        }
        out.reversed()
    }.getOrDefault(emptyList())

    private fun contactBitmap(ctx: Context, address: String, name: String): Bitmap {
        runCatching {
            SmsRepository.contactPhotoUri(ctx, address)?.let { uri ->
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }?.let { return circle(it) }
            }
        }
        // Letter avatar in the same color the app uses
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = avatarSolid(address.ifBlank { name }).toArgb() }
        c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        p.color = android.graphics.Color.WHITE; p.textSize = 60f; p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true
        val letter = if (name.firstOrNull()?.isLetter() == true) initial(name) else "#"
        c.drawText(letter, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2, p)
        return bmp
    }

    private fun circle(src: Bitmap): Bitmap {
        val s = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawCircle(s / 2f, s / 2f, s / 2f, p)
        p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        c.drawBitmap(src, ((s - src.width) / 2).toFloat(), ((s - src.height) / 2).toFloat(), p)
        return out
    }

    private fun personFor(ctx: Context, address: String, name: String): Person {
        val b = Person.Builder().setName(name).setKey(address).setIcon(IconCompat.createWithBitmap(contactBitmap(ctx, address, name)))
        runCatching {
            val lookup = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
            ctx.contentResolver.query(lookup, arrayOf(ContactsContract.PhoneLookup.LOOKUP_KEY), null, null, null)?.use { c ->
                if (c.moveToFirst()) b.setUri(ContactsContract.Contacts.CONTENT_LOOKUP_URI.toString() + "/" + c.getString(0)).setImportant(true)
            }
        }
        return b.build()
    }

    fun openIntent(ctx: Context, threadId: Long, address: String, name: String): Intent =
        Intent(ctx, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("thread_id", threadId); putExtra("address", address); putExtra("name", name)
        }

    /** Long-lived conversation shortcut: puts the chat in Android's "Conversations" section and the share sheet. */
    private fun pushShortcut(ctx: Context, threadId: Long, address: String, title: String, icon: IconCompat, persons: List<Person>): String {
        val id = "thread_$threadId"
        runCatching {
            val sc = ShortcutInfoCompat.Builder(ctx, id)
                .setShortLabel(title.take(25)).setLongLabel(title)
                .setIcon(icon)
                .setLongLived(true)
                .setLocusId(LocusIdCompat(id))
                .setPersons(persons.toTypedArray())
                .setCategories(setOf("com.heimish.messages.category.SHARE_TARGET"))
                .setIntent(openIntent(ctx, threadId, address, title))
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(ctx, sc)
        }
        return id
    }

    fun showMessage(ctx: Context, sender: String, body: String, address: String, threadId: Long) {
        val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notif_allow", true)) return
        if (Mute.isMuted(ctx, threadId)) return
        if (ActiveThread.id == threadId && threadId > 0) return  // you are looking at this chat already

        val showSender = prefs.getBoolean("notif_sender", true)
        val showPreview = prefs.getBoolean("notif_preview", true)

        val channelId = if (Dnd.active(ctx)) CHANNEL_QUIET else (threadChannel(ctx, threadId) ?: channelFor(ctx))
        ensureChannel(ctx, channelFor(ctx)); ensureChannel(ctx, CHANNEL_QUIET)
        val nid = nidFor(threadId)

        val members = SmsRepository.threadRecipients(ctx, threadId)
        val isGroup = members.size > 1
        val sendTo = if (isGroup) members.joinToString(";") else address
        val groupTitle = if (isGroup) members.joinToString(", ") { SmsRepository.getContactName(ctx, it) ?: it } else sender

        val openPi = PendingIntent.getActivity(ctx, nid, openIntent(ctx, threadId, sendTo, if (isGroup) groupTitle else sender),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val replyIntent = Intent(ctx, DirectReplyReceiver::class.java).apply {
            putExtra("address", sendTo); putExtra("thread_id", threadId); putExtra("nid", nid)
        }
        val replyPi = PendingIntent.getBroadcast(ctx, nid + 10000, replyIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val replyAction = NotificationCompat.Action.Builder(android.R.drawable.ic_menu_send, "Reply", replyPi)
            .addRemoteInput(RemoteInput.Builder(REPLY_KEY).setLabel("Reply").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()

        val readIntent = Intent(ctx, MarkReadReceiver::class.java).apply { putExtra("thread_id", threadId); putExtra("nid", nid) }
        val readPi = PendingIntent.getBroadcast(ctx, nid + 20000, readIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val readAction = NotificationCompat.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Mark as read", readPi)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

        val builder = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setColor(0xFF0B57D0.toInt())
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (channelId != CHANNEL_QUIET) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .addAction(replyAction)
            .addAction(readAction)

        if (showPreview && showSender) {
            val me = Person.Builder().setName("You").setKey("me").build()
            val style = NotificationCompat.MessagingStyle(me)
            val lines = unreadLines(ctx, threadId).toMutableList()
            if (lines.none { it.body == body }) lines.add(Line(address, body, System.currentTimeMillis()))
            val persons = HashMap<String, Person>()
            for (l in lines.takeLast(8)) {
                val a = l.address.ifBlank { address }
                val p = persons.getOrPut(a) { personFor(ctx, a, SmsRepository.getContactName(ctx, a) ?: a) }
                style.addMessage(l.body, l.date, p)
            }
            if (isGroup) { style.setConversationTitle(groupTitle); style.isGroupConversation = true }
            builder.setStyle(style)
            val icon = IconCompat.createWithBitmap(contactBitmap(ctx, if (isGroup) sendTo else address, if (isGroup) groupTitle else sender))
            val shortcutId = pushShortcut(ctx, threadId, sendTo, if (isGroup) groupTitle else sender, icon, persons.values.toList())
            builder.setShortcutId(shortcutId).setLocusId(LocusIdCompat(shortcutId))
            // Chat bubble (Android 11+): shows when bubbles are allowed for this conversation
            if (Build.VERSION.SDK_INT >= 30 && prefs.getBoolean("bubbles", true) && ConvNotif.bubbleOn(ctx, threadId)) runCatching {
                val bi = Intent(ctx, BubbleActivity::class.java).setAction(Intent.ACTION_VIEW)
                    .setData(Uri.parse("heimish://thread/$threadId"))
                    .putExtra("thread_id", threadId).putExtra("address", sendTo).putExtra("name", if (isGroup) groupTitle else sender)
                val bpi = PendingIntent.getActivity(ctx, nid + 50000, bi, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                builder.setBubbleMetadata(NotificationCompat.BubbleMetadata.Builder(bpi, icon).setDesiredHeight(640).setSuppressNotification(false).setAutoExpandBubble(false).build())
            }
            builder.setLargeIcon(contactBitmap(ctx, if (isGroup) sendTo else address, if (isGroup) groupTitle else sender))
            if (lines.size > 1) builder.setNumber(lines.size)
        } else {
            builder.setContentTitle(if (showSender) sender else "New message")
            if (showPreview) builder.setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }

        if (Regex("code|otp|verif|passcode|\\bpin\\b|קאוד|קוד", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
            Regex("\\b(\\d{4,8})\\b").find(body)?.groupValues?.get(1)?.let { code ->
                val ci = Intent(ctx, CopyCodeReceiver::class.java).putExtra("code", code)
                builder.addAction(NotificationCompat.Action.Builder(android.R.drawable.ic_menu_edit, "Copy $code",
                    PendingIntent.getBroadcast(ctx, nid + 30000, ci, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
            }
        }

        try { NotificationManagerCompat.from(ctx).notify(nid, builder.build()) } catch (_: SecurityException) {}
    }

    fun showFailed(ctx: Context, name: String, address: String, threadId: Long) {
        ensureChannel(ctx, CHANNEL_FAILED)
        val nid = nidFor(threadId) + 40000
        val open = PendingIntent.getActivity(ctx, nid, openIntent(ctx, threadId, address, name), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(ctx, CHANNEL_FAILED).setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle("Message not sent").setContentText("To $name · tap to open and retry")
            .setCategory(NotificationCompat.CATEGORY_ERROR).setContentIntent(open).setAutoCancel(true)
        try { NotificationManagerCompat.from(ctx).notify(nid, b.build()) } catch (_: SecurityException) {}
    }

    fun showSimple(ctx: Context, title: String, text: String, nid: Int) {
        ensureChannel(ctx, CHANNEL_QUIET)
        val open = PendingIntent.getActivity(ctx, nid, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(ctx, CHANNEL_QUIET).setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true)
        try { NotificationManagerCompat.from(ctx).notify(nid, b.build()) } catch (_: SecurityException) {}
    }

    /** Per-conversation channel (sound, vibrate, pop on screen) once the user customised this chat in Details > Notifications. */
    fun threadChannel(ctx: Context, threadId: Long): String? = ConvNotif.channelFor(ctx, threadId)

    fun showTestNotification(ctx: Context) {
        showMessage(ctx, "Message", "This is a test notification \ud83d\udcf1", "test", 0)
    }
}

/** Handles direct reply from the notification shade: sends to the whole thread, marks it read, clears the notification. */
class DirectReplyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val results = RemoteInput.getResultsFromIntent(intent) ?: return
        val reply = results.getCharSequence(Notifications.REPLY_KEY)?.toString()?.trim().orEmpty()
        if (reply.isEmpty()) return
        val threadId = intent.getLongExtra("thread_id", -1)
        val fallback = intent.getStringExtra("address") ?: return
        val nid = intent.getIntExtra("nid", 0)
        val pending = goAsync()
        Thread {
            try {
                val address = SmsRepository.threadAddress(ctx, threadId, fallback)
                val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
                if (address.contains(";") && prefs.getBoolean("group_mms_send", true)) {
                    if (!SmsRepository.sendGroupText(ctx, address, reply)) SmsRepository.sendSms(ctx, address, reply)
                } else SmsRepository.sendSms(ctx, address, reply)
                if (threadId >= 0) SmsRepository.markThreadRead(ctx, threadId)
                NotificationManagerCompat.from(ctx).cancel(nid)
                MessageWidget.refresh(ctx)
            } finally { pending.finish() }
        }.start()
    }
}

/** Handles "Mark as read" from the notification */
class MarkReadReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val threadId = intent.getLongExtra("thread_id", -1)
        val nid = intent.getIntExtra("nid", 0)
        NotificationManagerCompat.from(ctx).cancel(nid)
        val pending = goAsync()
        Thread {
            try { if (threadId >= 0) SmsRepository.markThreadRead(ctx, threadId); MessageWidget.refresh(ctx) } finally { pending.finish() }
        }.start()
    }
}

class CopyCodeReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val code = i.getStringExtra("code") ?: return
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("code", code))
        android.widget.Toast.makeText(ctx, "Copied $code", android.widget.Toast.LENGTH_SHORT).show()
    }
}
