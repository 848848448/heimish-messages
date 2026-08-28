package com.heimish.messages

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class ScheduledSendReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val address = intent.getStringExtra("address") ?: return
        val body = intent.getStringExtra("body") ?: return
        val id = intent.getLongExtra("sched_id", 0L)
        try {
            SmsRepository.sendSms(ctx, address, body)
            ScheduledStore.remove(ctx, id)
            Log.d("ScheduledSend", "Sent scheduled message to $address")
        } catch (e: Exception) {
            Log.e("ScheduledSend", "Failed to send scheduled message", e)
        }
    }

    companion object {
        fun schedule(ctx: Context, address: String, body: String, sendAtMillis: Long): Long {
            val id = System.currentTimeMillis()
            ScheduledStore.add(ctx, id, address, body, sendAtMillis)
            val intent = Intent(ctx, ScheduledSendReceiver::class.java).apply {
                putExtra("address", address)
                putExtra("body", body)
                putExtra("sched_id", id)
            }
            val pi = PendingIntent.getBroadcast(
                ctx, id.toInt(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, sendAtMillis, pi)
            } else {
                try {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, sendAtMillis, pi)
                } catch (_: SecurityException) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, sendAtMillis, pi)
                }
            }
            return id
        }

        fun cancel(ctx: Context, id: Long) {
            val intent = Intent(ctx, ScheduledSendReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                ctx, id.toInt(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pi)
            ScheduledStore.remove(ctx, id)
        }
    }
}

object ScheduledStore {
    private const val KEY = "scheduled_messages"

    fun add(ctx: Context, id: Long, address: String, body: String, sendAt: Long) {
        val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add("$id|$sendAt|$address|$body")
        prefs.edit().putStringSet(KEY, set).apply()
    }

    fun remove(ctx: Context, id: Long) {
        val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        set.removeAll { it.startsWith("$id|") }
        prefs.edit().putStringSet(KEY, set).apply()
    }

    fun getAll(ctx: Context): List<ScheduledMessage> {
        val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY, emptySet()) ?: emptySet()
        return set.mapNotNull { entry ->
            val parts = entry.split("|", limit = 4)
            if (parts.size == 4) ScheduledMessage(
                id = parts[0].toLongOrNull() ?: return@mapNotNull null,
                sendAt = parts[1].toLongOrNull() ?: return@mapNotNull null,
                address = parts[2],
                body = parts[3]
            ) else null
        }.sortedBy { it.sendAt }
    }
}

data class ScheduledMessage(val id: Long, val sendAt: Long, val address: String, val body: String)
