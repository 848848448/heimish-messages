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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Syncs phone contacts to the Cloudflare Worker (R2) every night at 4 AM (and on app start when stale),
 * plus each contact's photo as a small JPEG — only the photos that changed since the last upload.
 * Worker URL and token come from BuildConfig (build-time injected, never committed).
 */
class ContactsSyncService : BroadcastReceiver() {

    companion object {
        private const val TAG = "ContactsSync"
        private const val REQUEST_CODE = 4400
        private const val STALE_MS = 20L * 60 * 60 * 1000 // 20 hours
        private const val PHOTO_SYNC_VERSION = 1 // bump to force one full photo pass after an update
        private const val PHOTO_MAX_PX = 256
        private const val PHOTO_TARGET_BYTES = 30 * 1024
        private const val PHOTO_HARD_MAX = 95 * 1024 // the worker rejects > 100 KB
        private const val PHOTO_THREADS = 3
        private val running = AtomicBoolean(false)

        /** The id this phone syncs under (same as the contacts upload). */
        fun deviceId(): String = Build.MODEL.replace(" ", "-") + "-" + Build.ID

        /** Last 10 digits of a number — the worker's photo key. */
        fun photoKey(number: String): String {
            val d = number.filter { it.isDigit() }
            return if (d.length > 10) d.takeLast(10) else d
        }

        private fun configured(): Boolean =
            BuildConfig.WORKER_URL.isNotBlank() && BuildConfig.ADMIN_TOKEN.isNotBlank()

        /** Schedule nightly sync at 4:00 AM */
        fun schedule(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ContactsSyncService::class.java)
            val pending = PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 4)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                cal.timeInMillis,
                AlarmManager.INTERVAL_DAY,
                pending
            )
            Log.d(TAG, "Scheduled nightly sync at 4:00 AM")
        }

        /** Sync only if the last successful sync is stale (used on app start). */
        fun syncNowIfStale(context: Context) {
            val prefs = context.getSharedPreferences("heimish", Context.MODE_PRIVATE)
            val last = prefs.getLong("last_contacts_sync", 0L)
            val photosDone = prefs.getInt("photo_sync_v", 0) >= PHOTO_SYNC_VERSION
            if (System.currentTimeMillis() - last > STALE_MS || !photosDone) syncNow(context)
        }

        /** Run sync immediately */
        fun syncNow(context: Context, done: (() -> Unit)? = null) {
            if (!configured()) {
                Log.d(TAG, "Worker not configured — skipping contacts sync")
                done?.invoke(); return
            }
            if (!running.compareAndSet(false, true)) { Log.d(TAG, "Sync already running"); done?.invoke(); return }
            val app = context.applicationContext
            Thread {
                try {
                    val photos = HashMap<String, Pair<String?, String?>>() // key -> (thumbnail uri, full photo uri)
                    val contacts = readContacts(app, photos)
                    val ok = uploadContacts(app, contacts)
                    Log.d(TAG, "Synced ${contacts.length()} contacts")
                    if (ok) syncPhotos(app, photos)
                } catch (e: Throwable) {
                    Log.e(TAG, "Sync failed: ${e.message}")
                } finally {
                    running.set(false)
                    done?.invoke()
                }
            }.start()
        }

        /**
         * Upload each contact photo that is new or changed (hash of the thumbnail bytes + photo uri kept locally),
         * delete photos that were removed, a few at a time. A failed upload is simply retried on the next sync.
         */
        private fun syncPhotos(context: Context, photos: Map<String, Pair<String?, String?>>) {
            val store = context.getSharedPreferences("contact_photo_hashes", Context.MODE_PRIVATE)
            val known = HashMap(store.all.mapValues { it.value as? String ?: "" })
            val dev = android.net.Uri.encode(deviceId())
            val pool = Executors.newFixedThreadPool(PHOTO_THREADS)
            val stop = AtomicBoolean(false)
            val failures = AtomicInteger(0)
            val sent = AtomicInteger(0)
            var changed = 0
            for ((key, src) in photos) {
                val hash = runCatching { sourceHash(context, src) }.getOrNull() ?: continue
                if (known[key] == hash) continue
                changed++
                pool.execute {
                    if (stop.get()) return@execute
                    try {
                        val jpeg = encodePhoto(context, src) ?: return@execute
                        val code = request("PUT", "/photo/$dev/$key", jpeg, "image/jpeg")
                        when {
                            code == 200 -> { store.edit().putString(key, hash).apply(); sent.incrementAndGet(); failures.set(0) }
                            code == 401 || code == 403 || code == 503 -> stop.set(true) // auth/config problem: try again next sync
                            code == 429 -> { Thread.sleep(5000); failures.incrementAndGet() }
                            else -> if (failures.incrementAndGet() >= 10) stop.set(true)
                        }
                    } catch (e: Throwable) {
                        if (failures.incrementAndGet() >= 10) stop.set(true)
                        Log.w(TAG, "Photo $key failed: ${e.message}")
                    }
                }
            }
            // Photos that were removed from the phone
            val gone = known.keys.filter { it !in photos }
            for (key in gone) pool.execute {
                if (stop.get()) return@execute
                runCatching {
                    val code = request("DELETE", "/photo/$dev/$key", null, null)
                    if (code == 200 || code == 404) store.edit().remove(key).apply()
                }
            }
            pool.shutdown()
            pool.awaitTermination(15, TimeUnit.MINUTES)
            if (!stop.get()) context.getSharedPreferences("heimish", Context.MODE_PRIVATE).edit().putInt("photo_sync_v", PHOTO_SYNC_VERSION).apply()
            Log.d(TAG, "Photos: ${photos.size} with photo, $changed changed, ${sent.get()} uploaded, ${gone.size} removed")
        }

        private fun readBytes(context: Context, uri: String?, limit: Int): ByteArray? {
            if (uri.isNullOrBlank()) return null
            return context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { ins ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16 * 1024); var total = 0
                while (true) { val n = ins.read(buf); if (n < 0) break; total += n; if (total > limit) return null; out.write(buf, 0, n) }
                out.toByteArray()
            }
        }

        /** Cheap change detector: the small thumbnail's bytes plus the full photo's uri. */
        private fun sourceHash(context: Context, src: Pair<String?, String?>): String? {
            val thumb = runCatching { readBytes(context, src.first, 512 * 1024) }.getOrNull()
            if (thumb == null && src.second == null) return null
            val md = java.security.MessageDigest.getInstance("SHA-1")
            thumb?.let { md.update(it) }
            md.update((src.second ?: "").toByteArray())
            md.update("v$PHOTO_MAX_PX".toByteArray())
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** The contact photo (full photo preferred, else the thumbnail) as a JPEG of at most [PHOTO_MAX_PX] px, ~30 KB. */
        private fun encodePhoto(context: Context, src: Pair<String?, String?>): ByteArray? {
            val bytes = runCatching { readBytes(context, src.second, 8 * 1024 * 1024) }.getOrNull()
                ?: runCatching { readBytes(context, src.first, 8 * 1024 * 1024) }.getOrNull() ?: return null
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PHOTO_MAX_PX) sample *= 2
            val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val scale = PHOTO_MAX_PX.toFloat() / maxOf(decoded.width, decoded.height)
            val bmp = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true) else decoded
            // JPEG has no alpha: paint transparent photos onto white
            val opaque = if (bmp.hasAlpha()) android.graphics.Bitmap.createBitmap(bmp.width, bmp.height, android.graphics.Bitmap.Config.RGB_565).also {
                android.graphics.Canvas(it).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
            } else bmp
            var q = 85; var out: ByteArray
            do {
                val bos = java.io.ByteArrayOutputStream()
                opaque.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, bos)
                out = bos.toByteArray(); q -= 10
            } while (out.size > PHOTO_TARGET_BYTES && q >= 45)
            return if (out.size <= PHOTO_HARD_MAX) out else null
        }

        /** One authenticated call to the worker; returns the HTTP status. */
        private fun request(method: String, path: String, body: ByteArray?, type: String?): Int {
            val conn = URL("${BuildConfig.WORKER_URL}$path").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.ADMIN_TOKEN}")
                conn.connectTimeout = 15000; conn.readTimeout = 20000
                if (body != null) {
                    conn.doOutput = true
                    type?.let { conn.setRequestProperty("Content-Type", it) }
                    conn.setFixedLengthStreamingMode(body.size)
                    conn.outputStream.use { it.write(body) }
                }
                val code = conn.responseCode
                runCatching { (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes() } }
                return code
            } finally { conn.disconnect() }
        }

        /** Read all contacts from the phone */
        private fun readContacts(context: Context, photos: MutableMap<String, Pair<String?, String?>>): JSONArray {
            val contacts = JSONArray()
            val seen = mutableSetOf<String>()

            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_URI
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )

            cursor?.use {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val thumbIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI)
                val photoIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)

                while (it.moveToNext()) {
                    val name = it.getString(nameIdx) ?: continue
                    val number = it.getString(numIdx) ?: continue
                    val clean = number.replace(Regex("[^+0-9]"), "")

                    if (clean.isEmpty() || seen.contains(clean)) continue
                    seen.add(clean)

                    val thumb = if (thumbIdx >= 0) it.getString(thumbIdx) else null
                    val full = if (photoIdx >= 0) it.getString(photoIdx) else null
                    val key = photoKey(clean)
                    val hasPhoto = (thumb != null || full != null) && key.length >= 7
                    if (hasPhoto && key !in photos) photos[key] = thumb to full

                    contacts.put(JSONObject().apply {
                        put("name", name)
                        put("addr", clean)
                        if (hasPhoto) put("photo", true)
                    })
                }
            }

            return contacts
        }

        /** Upload contacts to the Cloudflare Worker */
        private fun uploadContacts(context: Context, contacts: JSONArray): Boolean {
            val deviceId = deviceId()

            val body = JSONObject().apply {
                put("device", deviceId)
                put("contacts", contacts)
            }

            val url = URL("${BuildConfig.WORKER_URL}/contacts/sync")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.ADMIN_TOKEN}")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            val code = conn.responseCode
            val ok = code == 200
            if (ok) {
                Log.d(TAG, "Upload success: ${contacts.length()} contacts from $deviceId")
                context.getSharedPreferences("heimish", Context.MODE_PRIVATE)
                    .edit()
                    .putLong("last_contacts_sync", System.currentTimeMillis())
                    .apply()
            } else {
                val err = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
                Log.e(TAG, "Upload failed: HTTP $code ${err.take(200)}")
            }
            conn.disconnect()
            return ok
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(TAG, "Alarm fired — syncing contacts")
        // Keep the receiver alive while the sync runs (the photo pass may go on a little longer; it resumes next time)
        val pending = goAsync()
        val finished = AtomicBoolean(false)
        fun finish() { if (finished.compareAndSet(false, true)) runCatching { pending.finish() } }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ finish() }, 50_000)
        syncNow(context) { finish() }
    }
}
