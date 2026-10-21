package com.heimish.messages

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** "Remind me" on a message (or a whole chat). The notification opens the chat right at that message. Survives reboots and updates. */
data class Reminder(val id: Long, val at: Long, val threadId: Long, val address: String, val name: String, val msgKey: String?, val snippet: String)

object ReminderStore {
    private const val KEY = "reminders_v1"
    private val lock = Any()
    private fun p(ctx: Context) = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
    private fun read(ctx: Context): MutableList<Reminder> {
        val out = ArrayList<Reminder>()
        runCatching { val a = JSONArray(p(ctx).getString(KEY, "[]"))
            for (i in 0 until a.length()) { val o = a.getJSONObject(i)
                out.add(Reminder(o.getLong("id"), o.getLong("at"), o.getLong("t"), o.getString("a"), o.optString("n"), o.optString("k").takeIf { it.isNotEmpty() }, o.optString("s"))) } }
        return out
    }
    private fun write(ctx: Context, l: List<Reminder>) {
        val a = JSONArray()
        l.forEach { r -> a.put(JSONObject().put("id", r.id).put("at", r.at).put("t", r.threadId).put("a", r.address).put("n", r.name).put("k", r.msgKey ?: "").put("s", r.snippet)) }
        p(ctx).edit().putString(KEY, a.toString()).commit()
    }
    fun all(ctx: Context): List<Reminder> = synchronized(lock) { read(ctx).sortedBy { it.at } }
    fun get(ctx: Context, id: Long): Reminder? = synchronized(lock) { read(ctx).firstOrNull { it.id == id } }
    fun forMessage(ctx: Context, key: String): Reminder? = synchronized(lock) { read(ctx).firstOrNull { it.msgKey == key } }
    fun forThread(ctx: Context, threadId: Long): List<Reminder> = synchronized(lock) { read(ctx).filter { it.threadId == threadId }.sortedBy { it.at } }
    fun put(ctx: Context, r: Reminder) = synchronized(lock) { val l = read(ctx); l.removeAll { it.id == r.id }; l.add(r); write(ctx, l) }
    fun remove(ctx: Context, id: Long) = synchronized(lock) { val l = read(ctx); if (l.removeAll { it.id == id }) write(ctx, l) }
}

class RemindReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.action) {
            ACTION_DONE -> { val id = i.getLongExtra("rid", 0L); NotificationManagerCompat.from(ctx).cancel(nid(id)); return }
            ACTION_SNOOZE -> {
                val id = i.getLongExtra("rid", 0L)
                NotificationManagerCompat.from(ctx).cancel(nid(id))
                val r = i.getStringExtra("r")?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() } ?: return
                set(ctx, r.copy(at = System.currentTimeMillis() + 3_600_000L))
                return
            }
        }
        val id = i.getLongExtra("rid", 0L)
        val r = ReminderStore.get(ctx, id)
        if (r == null) {
            // Reminder set by an older version (per chat, no store entry)
            val tid = i.getLongExtra("tid", 0L)
            Notifications.showSimple(ctx, "Reminder: " + (i.getStringExtra("name") ?: "chat"), "You asked to be reminded about this chat", 7000 + (tid % 1000).toInt())
            return
        }
        ReminderStore.remove(ctx, id)
        show(ctx, r)
    }

    companion object {
        const val ACTION_DONE = "com.heimish.messages.REMINDER_DONE"
        const val ACTION_SNOOZE = "com.heimish.messages.REMINDER_SNOOZE"
        private const val CHANNEL = "heimish_reminders"
        private fun nid(id: Long) = 50_000 + (id % 100_000).toInt()

        private fun toJson(r: Reminder) = JSONObject().put("id", r.id).put("at", r.at).put("t", r.threadId).put("a", r.address).put("n", r.name).put("k", r.msgKey ?: "").put("s", r.snippet)
        private fun fromJson(o: JSONObject) = Reminder(o.getLong("id"), o.getLong("at"), o.getLong("t"), o.getString("a"), o.optString("n"), o.optString("k").takeIf { it.isNotEmpty() }, o.optString("s"))

        private fun alarmPi(ctx: Context, id: Long): PendingIntent =
            PendingIntent.getBroadcast(ctx, (id % 1_000_000_000L).toInt(), Intent(ctx, RemindReceiver::class.java).putExtra("rid", id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        /** Reminder on one message ([m] null = the whole chat). */
        fun set(ctx: Context, c: Conversation, at: Long, m: Message? = null): Reminder {
            val snippet = m?.let { it.body.trim().take(200).ifBlank { if (it.imageUri != null) "\uD83D\uDCF7 Photo" else "Message" } } ?: ""
            ReminderStore.forThread(ctx, c.threadId).firstOrNull { it.msgKey == m?.let(::msgKey) }?.let { cancel(ctx, it.id) }
            val r = Reminder(System.currentTimeMillis(), at, c.threadId, c.address, c.displayName, m?.let(::msgKey), snippet)
            set(ctx, r)
            return r
        }
        private fun set(ctx: Context, r: Reminder) { ReminderStore.put(ctx, r); ExactAlarm.set(ctx, maxOf(r.at, System.currentTimeMillis() + 3_000), alarmPi(ctx, r.id)) }

        fun cancel(ctx: Context, id: Long) {
            (ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).cancel(alarmPi(ctx, id))
            ReminderStore.remove(ctx, id)
        }

        fun rearmAll(ctx: Context) { runCatching { ReminderStore.all(ctx).forEach { ExactAlarm.set(ctx, maxOf(it.at, System.currentTimeMillis() + 5_000), alarmPi(ctx, it.id)) } } }

        fun show(ctx: Context, r: Reminder) {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
                if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(
                    android.app.NotificationChannel(CHANNEL, "Reminders", android.app.NotificationManager.IMPORTANCE_HIGH).apply { description = "Reminders you set on messages" })
            }
            val open = Intent(ctx, MainActivity::class.java)
                .setAction("com.heimish.messages.OPEN_REMINDER_" + r.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("thread_id", r.threadId).putExtra("address", r.address).putExtra("name", r.name)
                .apply { r.msgKey?.let { putExtra("jump_key", it) } }
            val openPi = PendingIntent.getActivity(ctx, nid(r.id), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val snooze = PendingIntent.getBroadcast(ctx, nid(r.id) + 1, Intent(ctx, RemindReceiver::class.java).setAction(ACTION_SNOOZE).putExtra("rid", r.id).putExtra("r", toJson(r).toString()),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val done = PendingIntent.getBroadcast(ctx, nid(r.id) + 2, Intent(ctx, RemindReceiver::class.java).setAction(ACTION_DONE).putExtra("rid", r.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val text = r.snippet.ifBlank { "You asked to be reminded about this chat" }
            val b = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_stat_message)
                .setContentTitle("Reminder · " + r.name)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .addAction(0, "Snooze 1 hour", snooze)
                .addAction(0, "Done", done)
            runCatching { NotificationManagerCompat.from(ctx).notify(nid(r.id), b.build()) }
        }
    }
}

/** "Open this chat at that message" (reminder notification, Starred list). The chat scrolls there and flashes the bubble. */
object ChatJump {
    var threadId by mutableStateOf(-1L)
    var key by mutableStateOf<String?>(null)
    var seq by mutableStateOf(0)
    fun request(tid: Long, msgKey: String) { threadId = tid; key = msgKey; seq++ }
    /** The message to jump to in chat [tid], once. */
    fun take(tid: Long): String? = if (threadId == tid) key.also { key = null; threadId = -1L } else null
}
