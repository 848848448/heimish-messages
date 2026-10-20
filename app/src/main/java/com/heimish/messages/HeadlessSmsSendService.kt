package com.heimish.messages

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Handles the "respond via message" quick action from the phone/dialer screen.
 * Sends through the normal path, so long texts split correctly, the chosen SIM is used,
 * and the reply shows up in the conversation history.
 */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val to = intent?.data?.let { IntentRouter.recipientsFrom(it) }
        if (!text.isNullOrBlank() && !to.isNullOrBlank()) {
            val app = applicationContext
            Thread {
                runCatching { SmsRepository.sendSms(app, to, text) }
                stopSelf(startId)
            }.start()
        } else stopSelf(startId)
        return START_NOT_STICKY
    }
}
