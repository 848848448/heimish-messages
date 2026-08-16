package com.heimish.messages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Required for the app to qualify as the default SMS app. Full MMS download is
 * handled by the system; we just need to be registered to receive the WAP push.
 */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Incoming MMS notification — the platform manages the actual download.
        // A future version can parse the PDU here to show a preview.
    }
}
