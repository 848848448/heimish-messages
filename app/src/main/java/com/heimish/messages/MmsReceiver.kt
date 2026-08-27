package com.heimish.messages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper

class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val cursor = context.contentResolver.query(
                    Uri.parse("content://mms"),
                    arrayOf("_id", "thread_id", "date"),
                    null, null, "date DESC"
                )
                cursor?.use {
                    if (it.moveToFirst()) {
                        val mmsId = it.getLong(0)
                        val threadId = it.getLong(1)
                        val addr = getAddress(context, mmsId)
                        val name = SmsRepository.getContactName(context, addr) ?: addr
                        val prefs = context.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
                        if (prefs.getBoolean("notif_allow", true)) {
                            Notifications.showMessage(context, name, "📷 MMS", addr, threadId)
                        }
                    }
                }
            } catch (_: Exception) {}
        }, 3000)
    }

    private fun getAddress(ctx: Context, mmsId: Long): String {
        try {
            ctx.contentResolver.query(
                Uri.parse("content://mms/$mmsId/addr"),
                arrayOf("address"),
                "type = 137", null, null
            )?.use {
                if (it.moveToFirst()) return it.getString(0) ?: ""
            }
        } catch (_: Exception) {}
        return ""
    }
}
