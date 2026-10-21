package com.heimish.messages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * After a reboot: re-schedules the nightly contacts sync, the update check, scheduled messages and reminders
 * (Android clears all alarms on reboot). The automatic backup job is persisted by JobScheduler itself.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Device booted — re-scheduling alarms")
            ContactsSyncService.schedule(context)
            UpdateCheckReceiver.schedule(context)
            ScheduledSendReceiver.rearmAll(context)
            RemindReceiver.rearmAll(context)
            runCatching { AutoBackup.schedule(context) }
            Thread { runCatching { MmsIncoming.retryPending(context.applicationContext, force = true) } }.start()
        }
    }
}
