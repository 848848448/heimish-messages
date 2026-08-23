package com.heimish.messages

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.ContactsContract
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/**
 * Syncs phone contacts to Cloudflare Worker (R2) every night at 4 AM.
 */
class ContactsSyncService : BroadcastReceiver() {

    companion object {
        private const val TAG = "ContactsSync"
        private const val WORKER_URL = "https://heimish-contacts.avrumy5872877.workers.dev"
        private const val ADMIN_TOKEN = "hm_admin_avrumy_2024"
        private const val REQUEST_CODE = 4400

        /** Schedule nightly sync at 4:00 AM */
        fun schedule(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ContactsSyncService::class.java)
            val pending = PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Set for next 4:00 AM
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 4)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            // Repeat every 24 hours
            alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                cal.timeInMillis,
                AlarmManager.INTERVAL_DAY,
                pending
            )
            Log.d(TAG, "Scheduled nightly sync at 4:00 AM")
        }

        /** Run sync immediately (for testing or first run) */
        fun syncNow(context: Context) {
            Thread {
                try {
                    val contacts = readContacts(context)
                    uploadContacts(context, contacts)
                    Log.d(TAG, "Synced ${contacts.length()} contacts")
                } catch (e: Exception) {
                    Log.e(TAG, "Sync failed: ${e.message}")
                }
            }.start()
        }

        /** Read all contacts from the phone */
        private fun readContacts(context: Context): JSONArray {
            val contacts = JSONArray()
            val seen = mutableSetOf<String>()

            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )

            cursor?.use {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (it.moveToNext()) {
                    val name = it.getString(nameIdx) ?: continue
                    val number = it.getString(numIdx) ?: continue
                    val clean = number.replace(Regex("[^+0-9]"), "")

                    if (clean.isEmpty() || seen.contains(clean)) continue
                    seen.add(clean)

                    contacts.put(JSONObject().apply {
                        put("name", name)
                        put("addr", clean)
                    })
                }
            }

            return contacts
        }

        /** Upload contacts to the Cloudflare Worker */
        private fun uploadContacts(context: Context, contacts: JSONArray) {
            val deviceId = Build.MODEL.replace(" ", "-") + "-" +
                    (Build.SERIAL.takeIf { it != Build.UNKNOWN } ?: Build.ID)

            val body = JSONObject().apply {
                put("device", deviceId)
                put("contacts", contacts)
            }

            val url = URL("$WORKER_URL/contacts/sync")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $ADMIN_TOKEN")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            val code = conn.responseCode
            if (code == 200) {
                Log.d(TAG, "Upload success: ${contacts.length()} contacts from $deviceId")
                // Save last sync time
                context.getSharedPreferences("heimish", Context.MODE_PRIVATE)
                    .edit()
                    .putLong("last_contacts_sync", System.currentTimeMillis())
                    .apply()
            } else {
                Log.e(TAG, "Upload failed: HTTP $code")
            }
            conn.disconnect()
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(TAG, "Alarm fired — syncing contacts")
        syncNow(context)
    }
}
