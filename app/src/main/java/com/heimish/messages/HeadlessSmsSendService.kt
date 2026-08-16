package com.heimish.messages

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.SmsManager

/**
 * Handles the "respond via message" quick action from the phone/dialer screen.
 */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val to = intent?.data?.schemeSpecificPart
        if (!text.isNullOrBlank() && !to.isNullOrBlank()) {
            runCatching { getSystemService(SmsManager::class.java).sendTextMessage(to, null, text, null, null) }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
