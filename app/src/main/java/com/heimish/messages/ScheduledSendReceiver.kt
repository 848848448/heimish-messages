package com.heimish.messages

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** Exact wake-up alarms (scheduled send, reminders). Falls back to an inexact one if exact alarms aren't allowed. */
object ExactAlarm {
    fun set(ctx: Context, at: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val exactOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (exactOk) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
    fun canExact(ctx: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    /** Android 12+ "Alarms & reminders" page for this app (only needed when exact alarms are off). */
    fun settingsIntent(ctx: Context): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName)) else null
}

class ScheduledSendReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra("sched_id", 0L)
        val pending = goAsync()
        Thread {
            try { fire(ctx, id) } catch (e: Exception) { Log.e("ScheduledSend", "Failed to send scheduled message", e) } finally { pending.finish() }
        }.start()
    }

    companion object {
        /** Sends scheduled message [id] now. The store is the truth: a message cancelled or edited after the alarm was set is honoured. */
        fun fire(ctx: Context, id: Long): Boolean {
            val m = ScheduledStore.get(ctx, id) ?: return false
            ScheduledStore.remove(ctx, id)  // first, so a second alarm (reboot re-arm) can never send it twice
            val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
            val ok = runCatching {
                if (m.mediaPath != null) {
                    val f = java.io.File(m.mediaPath)
                    val sent = f.exists() && SmsRepository.sendMms(ctx, m.address, Uri.fromFile(f), m.body, m.mime?.takeIf { !it.startsWith("image/") })
                    if (sent) runCatching { f.delete() }
                    sent
                } else if (m.address.contains(";") && prefs.getBoolean("group_mms_send", true)) {
                    SmsRepository.sendGroupText(ctx, m.address, m.body) || SmsRepository.sendSms(ctx, m.address, m.body)
                } else SmsRepository.sendSms(ctx, m.address, m.body)
            }.getOrDefault(false)
            if (!ok) {
                val name = SmsRepository.splitRecipients(m.address).joinToString(", ") { SmsRepository.getContactName(ctx, it) ?: it }
                runCatching { Notifications.showFailed(ctx, name, m.address, SmsRepository.getThreadIdForAddress(ctx, m.address)) }
            }
            runCatching { MessageWidget.refresh(ctx) }
            Log.d("ScheduledSend", "Scheduled message $id sent=$ok")
            return ok
        }

        private fun pi(ctx: Context, id: Long): PendingIntent {
            val intent = Intent(ctx, ScheduledSendReceiver::class.java).putExtra("sched_id", id)
            return PendingIntent.getBroadcast(ctx, id.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun arm(ctx: Context, m: ScheduledMessage) =
            ExactAlarm.set(ctx, maxOf(m.sendAt, System.currentTimeMillis() + 5_000), pi(ctx, m.id))

        /** Schedules a text (or a photo/video with [mediaUri]; it is copied so it's still there at send time). Returns the id. */
        fun schedule(ctx: Context, address: String, body: String, sendAtMillis: Long, mediaUri: Uri? = null, mime: String? = null): Long {
            val id = ScheduledStore.newId(ctx)
            var path: String? = null
            if (mediaUri != null) {
                val dir = java.io.File(ctx.filesDir, "scheduled").apply { mkdirs() }
                val f = java.io.File(dir, "s$id")
                runCatching { ctx.contentResolver.openInputStream(mediaUri)?.use { inp -> f.outputStream().use { inp.copyTo(it) } } }
                if (f.exists() && f.length() > 0) path = f.absolutePath
            }
            val m = ScheduledMessage(id, sendAtMillis, address, body, path, mime ?: mediaUri?.let { runCatching { ctx.contentResolver.getType(it) }.getOrNull() })
            ScheduledStore.put(ctx, m)
            arm(ctx, m)
            return id
        }

        /** Changes the text and/or the time of a scheduled message (same id). */
        fun update(ctx: Context, id: Long, body: String? = null, sendAt: Long? = null): Boolean {
            val m = ScheduledStore.get(ctx, id) ?: return false
            val n = m.copy(body = body ?: m.body, sendAt = sendAt ?: m.sendAt)
            ScheduledStore.put(ctx, n)
            arm(ctx, n)
            return true
        }

        fun cancel(ctx: Context, id: Long) {
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi(ctx, id))
            ScheduledStore.get(ctx, id)?.mediaPath?.let { runCatching { java.io.File(it).delete() } }
            ScheduledStore.remove(ctx, id)
        }

        /** Sets every alarm again: after a reboot, an app update or a force stop (Android drops alarms then). Safe to call often. */
        fun rearmAll(ctx: Context) { runCatching { ScheduledStore.getAll(ctx).forEach { arm(ctx, it) } } }
    }
}

object ScheduledStore {
    private const val OLD_KEY = "scheduled_messages"
    private const val KEY = "scheduled_v2"
    private val lock = Any()
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)

    private fun read(ctx: Context): MutableList<ScheduledMessage> {
        val p = prefs(ctx)
        val out = ArrayList<ScheduledMessage>()
        runCatching {
            val a = JSONArray(p.getString(KEY, "[]"))
            for (i in 0 until a.length()) { val o = a.getJSONObject(i)
                out.add(ScheduledMessage(o.getLong("id"), o.getLong("at"), o.getString("a"), o.optString("b"),
                    o.optString("f").takeIf { it.isNotEmpty() }, o.optString("mt").takeIf { it.isNotEmpty() })) }
        }
        // Messages scheduled by older versions ("id|sendAt|address|body")
        val old = p.getStringSet(OLD_KEY, null)
        if (old != null) {
            old.forEach { e -> val parts = e.split("|", limit = 4)
                if (parts.size == 4) { val id = parts[0].toLongOrNull(); val at = parts[1].toLongOrNull()
                    if (id != null && at != null && out.none { it.id == id }) out.add(ScheduledMessage(id, at, parts[2], parts[3])) } }
            write(ctx, out); p.edit().remove(OLD_KEY).apply()
        }
        return out
    }
    private fun write(ctx: Context, l: List<ScheduledMessage>) {
        val a = JSONArray()
        l.forEach { m -> a.put(JSONObject().put("id", m.id).put("at", m.sendAt).put("a", m.address).put("b", m.body).apply { m.mediaPath?.let { put("f", it) }; m.mime?.let { put("mt", it) } }) }
        prefs(ctx).edit().putString(KEY, a.toString()).commit()
    }

    /** A unique id, even for several messages scheduled in the same millisecond (the boot re-arm used to merge them). */
    fun newId(ctx: Context): Long = synchronized(lock) {
        val p = prefs(ctx); val id = maxOf(System.currentTimeMillis(), p.getLong("sched_last_id", 0L) + 1)
        p.edit().putLong("sched_last_id", id).commit(); id
    }
    fun put(ctx: Context, m: ScheduledMessage) = synchronized(lock) { val l = read(ctx); l.removeAll { it.id == m.id }; l.add(m); write(ctx, l) }
    fun add(ctx: Context, id: Long, address: String, body: String, sendAt: Long) = put(ctx, ScheduledMessage(id, sendAt, address, body))
    fun remove(ctx: Context, id: Long) = synchronized(lock) { val l = read(ctx); if (l.removeAll { it.id == id }) write(ctx, l) }
    fun get(ctx: Context, id: Long): ScheduledMessage? = synchronized(lock) { read(ctx).firstOrNull { it.id == id } }
    fun getAll(ctx: Context): List<ScheduledMessage> = synchronized(lock) { read(ctx).sortedBy { it.sendAt } }
}

data class ScheduledMessage(val id: Long, val sendAt: Long, val address: String, val body: String, val mediaPath: String? = null, val mime: String? = null)
