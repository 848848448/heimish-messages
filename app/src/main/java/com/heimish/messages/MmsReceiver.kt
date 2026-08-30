package com.heimish.messages

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pdu = intent.getByteArrayExtra("data")
            ?: intent.getByteArrayExtra("pdu")
            ?: return

        val contentLocation = extractContentLocation(pdu)

        val pendingResult = goAsync()
        Thread {
            try {
                if (contentLocation != null) {
                    downloadMms(context, contentLocation)
                    // Wait for download to complete — poll for new MMS entry
                    val latestBefore = getLatestMmsId(context)
                    var attempts = 0
                    while (attempts < 20) {
                        Thread.sleep(2000)
                        attempts++
                        val latestNow = getLatestMmsId(context)
                        if (latestNow > latestBefore) break
                    }
                } else {
                    Thread.sleep(5000)
                }
                showMmsNotification(context)
            } catch (e: Exception) {
                Log.e("MmsReceiver", "MMS receive error: ${e.message}")
                try { showMmsNotification(context) } catch (_: Exception) {}
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun downloadMms(context: Context, contentLocation: String) {
        val downloadFile = File(context.cacheDir, "mms_dl_${System.currentTimeMillis()}.dat")
        downloadFile.createNewFile()
        val downloadUri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", downloadFile
        )
        context.grantUriPermission(
            "com.android.mms.service", downloadUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )

        val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
        else SmsManager.getDefault()

        val downloadedIntent = PendingIntent.getBroadcast(
            context, System.currentTimeMillis().toInt(),
            Intent("com.heimish.messages.MMS_DOWNLOADED"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val configOverrides = Bundle()
        configOverrides.putBoolean(SmsManager.MMS_CONFIG_GROUP_MMS_ENABLED, true)

        sms.downloadMultimediaMessage(context, contentLocation, downloadUri, configOverrides, downloadedIntent)

        Thread { Thread.sleep(120_000); downloadFile.delete() }.start()
    }

    private fun getLatestMmsId(ctx: Context): Long {
        return try {
            ctx.contentResolver.query(
                Uri.parse("content://mms"),
                arrayOf("_id"),
                null, null, "_id DESC"
            )?.use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            } ?: 0L
        } catch (_: Exception) { 0L }
    }

    private fun extractContentLocation(pdu: ByteArray): String? {
        // WAP-230: X-Mms-Content-Location header = 0x83
        var i = 0
        while (i < pdu.size - 1) {
            if (pdu[i].toInt() and 0xFF == 0x83) {
                val start = i + 1
                var end = start
                while (end < pdu.size && pdu[end] != 0.toByte()) end++
                if (end > start) {
                    val url = String(pdu, start, end - start, Charsets.US_ASCII).trim()
                    if (url.startsWith("http")) return url
                }
            }
            i++
        }

        val str = String(pdu, Charsets.ISO_8859_1)
        val httpIdx = str.indexOf("http")
        if (httpIdx >= 0) {
            var end = httpIdx
            while (end < str.length && str[end].code >= 0x20) end++
            return str.substring(httpIdx, end)
        }

        return null
    }

    private fun showMmsNotification(context: Context) {
        val cursor = context.contentResolver.query(
            Uri.parse("content://mms"),
            arrayOf("_id", "thread_id", "date", "read"),
            null, null, "date DESC"
        ) ?: return
        cursor.use {
            if (!it.moveToFirst()) return
            val mmsId = it.getLong(0)
            val threadId = it.getLong(1)
            val read = it.getInt(3)
            if (read == 1) return
            val addr = getAddress(context, mmsId)
            if (addr.isBlank()) return
            val name = SmsRepository.getContactName(context, addr) ?: addr
            val body = getMmsText(context, mmsId)
            val prefs = context.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("notif_allow", true) && !prefs.getBoolean("muted_$threadId", false)) {
                Notifications.showMessage(
                    context, name,
                    body.ifBlank { "📷 MMS" },
                    addr, threadId
                )
            }
        }
    }

    private fun getMmsText(ctx: Context, mmsId: Long): String {
        try {
            ctx.contentResolver.query(
                Uri.parse("content://mms/$mmsId/part"),
                arrayOf("ct", "text"), null, null, null
            )?.use {
                val iCt = it.getColumnIndex("ct")
                val iText = it.getColumnIndex("text")
                while (it.moveToNext()) {
                    if (it.getString(iCt) == "text/plain") {
                        return it.getString(iText) ?: ""
                    }
                }
            }
        } catch (_: Exception) {}
        return ""
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
            // Fallback: try FROM address type 151
            ctx.contentResolver.query(
                Uri.parse("content://mms/$mmsId/addr"),
                arrayOf("address"),
                "type = 151", null, null
            )?.use {
                if (it.moveToFirst()) return it.getString(0) ?: ""
            }
        } catch (_: Exception) {}
        return ""
    }
}
