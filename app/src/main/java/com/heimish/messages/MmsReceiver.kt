package com.heimish.messages

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Incoming MMS, step 1: the WAP push (M-Notification.ind) says an MMS is waiting at the carrier.
 * We ask the system to download it; the result arrives in [MmsDownloadedReceiver].
 */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pdu = intent.getByteArrayExtra("data") ?: intent.getByteArrayExtra("pdu")
        val subId = intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", intent.getIntExtra("subscription", -1))
        MmsLog.log(app, "WAP_PUSH received: action=${intent.action} mime=${intent.type} subId=$subId pdu=${pdu?.size ?: "NONE"} extras=${MmsLog.extras(intent)}")
        if (pdu == null) { MmsLog.log(app, "ERROR WAP push without data — dropped"); return }
        MmsLog.log(app, "push pdu hex: ${MmsLog.hex(pdu)}")
        val pending = goAsync()
        Thread {
            try { MmsIncoming.onWapPush(app, pdu, subId) }
            catch (e: Throwable) { Log.e("MmsReceiver", "push: ${e.message}", e); MmsLog.err(app, "push handling crashed", e) }
            finally { pending.finish() }
        }.start()
    }
}

/**
 * Incoming MMS, step 2: the download finished (or failed). As the default SMS app WE must parse the
 * M-Retrieve.conf and write it to the message store — Android does not do it for the default app.
 * Also handles the retry alarm for downloads that failed.
 */
class MmsDownloadedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = resultCode
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                when (intent.action) {
                    MmsIncoming.ACTION_RETRY -> { MmsLog.log(app, "retry alarm fired"); MmsIncoming.retryPending(app, force = true) }
                    MmsIncoming.ACTION_TIMEOUT -> MmsIncoming.onTimeout(app, intent)
                    else -> MmsIncoming.onDownloaded(app, intent, code)
                }
            } catch (e: Throwable) { Log.e("MmsDownloaded", "${e.message}", e); MmsLog.err(app, "download receiver crashed (${intent.action})", e) }
            finally { pending.finish() }
        }.start()
    }
}

object MmsIncoming {
    private const val TAG = "MmsIncoming"
    const val ACTION_DOWNLOADED = "com.heimish.messages.MMS_DOWNLOADED"
    const val ACTION_RETRY = "com.heimish.messages.MMS_RETRY"
    const val ACTION_TIMEOUT = "com.heimish.messages.MMS_DL_TIMEOUT"
    /** No answer from the phone's MMS service this long after a download request = treat it as failed and retry. */
    private const val ANSWER_TIMEOUT_MS = 4 * 60_000L
    private const val QUEUE_PREFS = "heimish_mms_queue"
    private const val MAX_ATTEMPTS = 8
    private const val KEEP_MS = 7 * 24 * 3600_000L
    private val seq = AtomicInteger((System.currentTimeMillis() / 1000 % 100_000).toInt())
    private val lock = Any()

    // ── Step 1: WAP push ─────────────────────────────────────────────────────────

    fun onWapPush(ctx: Context, pdu: ByteArray, subId: Int) {
        val n = runCatching { MmsPdu.parse(pdu) }.onFailure { Log.w(TAG, "notification parse: ${it.message}"); MmsLog.err(ctx, "notification parse failed", it) }.getOrNull()
        if (n != null) MmsLog.log(ctx, "parsed push: m_type=${n.messageType} (0x${Integer.toHexString(n.messageType)}) tid=${n.transactionId} from=${n.from} " +
            "loc=${n.contentLocation} size=${n.messageSize} expiry=${n.expiry}${if (n.expiryAbsolute) " abs" else " rel"} class=${n.messageClass} v=0x${Integer.toHexString(n.version)}")
        if (n != null && n.messageType != MmsPdu.TYPE_NOTIFICATION_IND) {
            Log.d(TAG, "WAP push type ${n.messageType} (delivery/read report) — nothing to download")
            MmsLog.log(ctx, "not a notification (delivery/read report) — nothing to download")
            return
        }
        val loc = n?.contentLocation?.trim()?.takeIf { it.startsWith("http", true) } ?: legacyContentLocation(pdu)?.also { MmsLog.log(ctx, "address from raw bytes: $it") } ?: run {
            Log.w(TAG, "MMS notification without a download address"); MmsLog.log(ctx, "ERROR notification without a download address — dropped"); return
        }
        val expiresAt = when {
            n == null || n.expiry <= 0 -> 0L
            n.expiryAbsolute -> n.expiry * 1000
            else -> System.currentTimeMillis() + n.expiry * 1000
        }
        val entry: JSONObject
        synchronized(lock) {
            val q = load(ctx)
            if (q.done.contains(loc)) { Log.d(TAG, "duplicate push for an MMS already saved"); MmsLog.log(ctx, "duplicate push for an MMS already saved — ignored"); return }
            val existing = q.items[loc]
            if (existing != null && System.currentTimeMillis() - existing.optLong("last") < 3 * 60_000L) {
                Log.d(TAG, "duplicate push, download already running"); MmsLog.log(ctx, "duplicate push, download already running — ignored"); return
            }
            entry = existing ?: JSONObject().apply {
                put("loc", loc); put("tid", n?.transactionId ?: ""); put("from", n?.from ?: "")
                put("sub", subId); put("first", System.currentTimeMillis()); put("exp", expiresAt)
                put("size", n?.messageSize ?: 0L); put("attempts", 0); put("row", -1L)
            }
            q.items[loc] = entry
            save(ctx, q)
        }
        // Show it in the chat right away as "downloading…" (like AOSP Messaging stores the notification first), so a
        // message is never invisible while the download runs or fails; replaced by the real message once downloaded.
        if (entry.optLong("row", -1L) <= 0) {
            val row = runCatching { savePlaceholder(ctx, entry) }.onFailure { MmsLog.err(ctx, "placeholder insert failed", it) }.getOrNull()
            MmsLog.log(ctx, "placeholder row: ${row ?: "none"}")
            if (row != null) synchronized(lock) { val q = load(ctx); q.items[loc]?.put("row", row); entry.put("row", row); save(ctx, q) }
        }
        startDownload(ctx, entry)
    }

    /** Watchdog: the phone's MMS service never answered the download request. */
    fun onTimeout(ctx: Context, intent: Intent) {
        val loc = intent.getStringExtra("loc") ?: return
        val id = intent.getIntExtra("dl", -1)
        val stillWaiting = synchronized(lock) {
            val q = load(ctx); val e = q.items[loc]
            if (e != null && e.optInt("pend", 0) == id) { e.put("pend", 0); save(ctx, q); true } else false
        }
        if (!stillWaiting) return
        MmsLog.log(ctx, "ERROR no answer from the phone's MMS service ${ANSWER_TIMEOUT_MS / 60_000} min after download #$id — counting as failed")
        onFailed(ctx, loc)
    }

    fun queueSummary(ctx: Context): String = synchronized(lock) {
        val q = load(ctx)
        "queue: ${q.items.size} waiting, ${q.done.size} done" + q.items.values.joinToString("") { e ->
            "\n  ${e.optString("loc")} from=${e.optString("from")} sub=${e.optInt("sub")} attempts=${e.optInt("attempts")} row=${e.optLong("row")}"
        }
    }

    private fun startDownload(ctx: Context, e: JSONObject) {
        val loc = e.getString("loc")
        val subId = e.optInt("sub", -1)
        synchronized(lock) {
            val q = load(ctx)
            val cur = q.items[loc] ?: e
            cur.put("attempts", cur.optInt("attempts") + 1); cur.put("last", System.currentTimeMillis())
            q.items[loc] = cur; save(ctx, q)
        }
        val id = seq.incrementAndGet()
        synchronized(lock) { val q = load(ctx); q.items[loc]?.put("pend", id); save(ctx, q) }
        val file = File(ctx.cacheDir, "mms_dl_${System.currentTimeMillis()}_$id.dat")
        try {
            file.delete(); file.createNewFile()
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val grants = ArrayList<String>()
            for (pkg in mmsServicePackages(ctx)) {
                runCatching { ctx.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION); grants.add(pkg) }
                    .onFailure { grants.add("$pkg:FAILED(${it.message})") }
            }
            // Explicit receiver + unique request code + unique data, so parallel downloads never overwrite each other.
            val done = Intent(ctx, MmsDownloadedReceiver::class.java)
                .setAction(ACTION_DOWNLOADED)
                .setData(Uri.parse("heimish-mms://download/$id"))
                .putExtra("file", file.absolutePath).putExtra("loc", loc).putExtra("sub", subId)
                .putExtra("dl", id)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, 40_000 + id % 50_000, done, flags)
            val attempt = e.optInt("attempts") + 1
            // Fallback for phones where the SIM named in the push can't fetch MMS (dual SIM / eSIM, data on the other
            // SIM): every 3rd try goes through the default data/SMS SIM when that is a different one, with no config overrides.
            val altSub = runCatching {
                listOf(SubscriptionManager.getDefaultDataSubscriptionId(), SubscriptionManager.getDefaultSmsSubscriptionId())
                    .firstOrNull { it >= 0 && it != SmsRepository.effectiveSubId(ctx, subId) } ?: -1
            }.getOrDefault(-1)
            val fallback = attempt >= 3 && attempt % 3 == 0
            val cfg: Bundle? = if (fallback) null else Bundle().apply {
                val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
                putBoolean(SmsManager.MMS_CONFIG_GROUP_MMS_ENABLED, prefs.getBoolean("group_mms", true))
            }
            val useAlt = fallback && altSub >= 0
            Log.d(TAG, "downloading MMS (sub ${if (useAlt) altSub else subId})")
            val mgr = SmsRepository.smsManager(ctx, if (useAlt) altSub else subId)
            val mgrSub = SmsRepository.effectiveSubId(ctx, if (useAlt) altSub else subId) // -1 = the phone's default SIM
            MmsLog.log(ctx, "download #$id requested: attempt=$attempt${if (fallback) " (fallback: no overrides${if (useAlt) ", other SIM $altSub" else ""})" else ""} url=$loc pushSub=$subId managerSub=$mgrSub uri=$uri grants=$grants pi=mutable:${Build.VERSION.SDK_INT >= 31}")
            armTimeout(ctx, loc, id)
            mgr.downloadMultimediaMessage(ctx, loc, uri, cfg, pi)
            MmsLog.log(ctx, "download #$id handed to the system (waiting for its answer)")
        } catch (t: Throwable) {
            Log.e(TAG, "download request failed: ${t.message}", t)
            MmsLog.err(ctx, "download #$id request failed", t)
            file.delete()
            synchronized(lock) { val q = load(ctx); q.items[loc]?.put("pend", 0); save(ctx, q) }
            onFailed(ctx, loc)
        }
    }

    /** The phone process and the MMS service package(s) that write the downloaded file into our content Uri. */
    private fun mmsServicePackages(ctx: Context): List<String> {
        val out = linkedSetOf("com.android.phone", "com.android.mms.service")
        // Some OEMs ship the MMS service under another package name: find whoever implements it.
        runCatching {
            ctx.packageManager.queryIntentServices(Intent("android.telephony.MmsService"), 0)
                .forEach { out.add(it.serviceInfo.packageName) }
        }
        return out.toList()
    }

    private fun armTimeout(ctx: Context, loc: String, id: Int) {
        runCatching {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(ctx, 30_000 + id % 9_000,
                Intent(ctx, MmsDownloadedReceiver::class.java).setAction(ACTION_TIMEOUT)
                    .setData(Uri.parse("heimish-mms://timeout/$id")).putExtra("loc", loc).putExtra("dl", id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + ANSWER_TIMEOUT_MS, pi)
        }.onFailure { MmsLog.err(ctx, "watchdog alarm", it) }
    }

    // ── Step 2: downloaded ───────────────────────────────────────────────────────

    fun onDownloaded(ctx: Context, intent: Intent, resultCode: Int) {
        val path = intent.getStringExtra("file")
        val loc = intent.getStringExtra("loc") ?: return
        val subId = intent.getIntExtra("sub", -1)
        val file = path?.let { File(it) }
        val dl = intent.getIntExtra("dl", -1)
        try {
            synchronized(lock) { val q = load(ctx); q.items[loc]?.let { if (it.optInt("pend", 0) == dl) { it.put("pend", 0); save(ctx, q) } } }
            val fileLen = file?.let { if (it.exists()) it.length() else -1L } ?: -2L
            MmsLog.log(ctx, "download #$dl answered: result=${MmsLog.resultName(resultCode)} http=${intent.getIntExtra("android.telephony.extra.MMS_HTTP_STATUS", 0)} " +
                "file=${if (fileLen == -1L) "missing" else "$fileLen bytes"} extras=${MmsLog.extras(intent)}")
            var bytes = file?.takeIf { it.exists() && it.length() > 0 }?.readBytes()
            if (bytes == null || bytes.isEmpty()) bytes = intent.getByteArrayExtra("android.telephony.extra.MMS_DATA")
            if (resultCode != Activity.RESULT_OK || bytes == null || bytes.isEmpty()) {
                Log.w(TAG, "download failed: result $resultCode, ${bytes?.size ?: 0} bytes, http ${intent.getIntExtra("android.telephony.extra.MMS_HTTP_STATUS", 0)}")
                MmsLog.log(ctx, "ERROR download #$dl failed (${MmsLog.resultName(resultCode)}, ${bytes?.size ?: 0} bytes)")
                onFailed(ctx, loc); return
            }
            val msg = runCatching { MmsPdu.parse(bytes) }.onFailure { Log.e(TAG, "retrieve parse: ${it.message}", it); MmsLog.err(ctx, "retrieve parse failed", it) }.getOrNull()
            MmsLog.log(ctx, if (msg == null) "retrieved head hex: ${MmsLog.hex(bytes, 200)}" else
                "parsed retrieve: m_type=0x${Integer.toHexString(msg.messageType)} status=${msg.retrieveStatus} from=${msg.from} to=${msg.to} cc=${msg.cc} " +
                    "ct=${msg.contentType} mid=${msg.messageId} parts=${msg.parts.joinToString { it.contentType + ":" + it.data.size }}")
            if (msg == null || msg.messageType != MmsPdu.TYPE_RETRIEVE_CONF || (msg.retrieveStatus != 0 && msg.retrieveStatus != 0x80)) {
                Log.w(TAG, "not a usable M-Retrieve.conf: type ${msg?.messageType} status ${msg?.retrieveStatus}")
                if (msg != null) MmsLog.log(ctx, "ERROR not a usable M-Retrieve.conf; head hex: ${MmsLog.hex(bytes, 200)}")
                onFailed(ctx, loc); return
            }
            val entry = synchronized(lock) { load(ctx).items[loc] }
            val saved = runCatching { persist(ctx, msg, subId, loc) }.onFailure { MmsLog.err(ctx, "saving crashed", it) }.getOrNull()
                ?: run { MmsLog.log(ctx, "ERROR could not save the message"); onFailed(ctx, loc); return }
            MmsLog.log(ctx, "saved: mms id=${saved.first} new=${saved.second}")
            // Saved: forget the queue entry, remove its "not downloaded" placeholder, remember it against duplicate pushes
            synchronized(lock) {
                val q = load(ctx); q.items.remove(loc); q.done.add(loc)
                while (q.done.size > 60) q.done.removeAt(0)
                save(ctx, q)
            }
            entry?.optLong("row", -1L)?.takeIf { it > 0 }?.let { row ->
                runCatching { ctx.contentResolver.delete(Uri.parse("content://mms/$row"), null, null) }
            }
            if (saved.second) {
                runCatching { notifyIncoming(ctx, saved.first) }.onFailure { Log.e(TAG, "notify: ${it.message}", it); MmsLog.err(ctx, "notification crashed", it) }
                msg.transactionId?.takeIf { it.isNotBlank() }?.let { tid ->
                    runCatching { sendNotifyResp(ctx, tid, subId) }.onSuccess { MmsLog.log(ctx, "retrieved-ack sent to carrier (tid $tid)") }
                        .onFailure { MmsLog.err(ctx, "retrieved-ack", it) }
                }
            }
        } catch (t: Throwable) {
            MmsLog.err(ctx, "download #$dl handling crashed", t); throw t
        } finally {
            file?.delete()
        }
    }

    private fun onFailed(ctx: Context, loc: String) {
        val e = synchronized(lock) { load(ctx).items[loc] } ?: run { MmsLog.log(ctx, "failure for an MMS no longer queued"); return }
        val attempts = e.optInt("attempts")
        val expired = e.optLong("exp") in 1 until System.currentTimeMillis()
        MmsLog.log(ctx, "attempt $attempts failed; expired=$expired; " + if (!expired && attempts < MAX_ATTEMPTS) "retrying later" else "giving up (open the app to try again)")
        // Make sure the message shows in the sender's chat as "downloading…" (normally done at push time) and keep
        // retrying in the background.
        if (e.optLong("row", -1L) <= 0) {
            val row = runCatching { savePlaceholder(ctx, e) }.onFailure { Log.e(TAG, "placeholder: ${it.message}"); MmsLog.err(ctx, "placeholder", it) }.getOrNull()
            if (row != null) synchronized(lock) {
                val q = load(ctx); q.items[loc]?.put("row", row); save(ctx, q)
            }
        }
        if (!expired && attempts < MAX_ATTEMPTS) scheduleRetry(ctx, attempts)
    }

    private fun scheduleRetry(ctx: Context, attempts: Int) {
        val delayMin = when (attempts) { 1 -> 1L; 2 -> 3L; 3 -> 10L; 4 -> 30L; else -> 120L }
        runCatching {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(ctx, 39_999, Intent(ctx, MmsDownloadedReceiver::class.java).setAction(ACTION_RETRY),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMin * 60_000L, pi)
        }
    }

    /**
     * Re-tries every MMS that is not downloaded yet: queued ones, and "notification only" rows (m_type 130) already in
     * the message store (e.g. left by another app). Called on app open, after an update, after boot and by the retry alarm.
     */
    fun retryPending(ctx: Context, force: Boolean = false) {
        val prefs = ctx.getSharedPreferences(QUEUE_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("last_sweep", 0L) < 2 * 60_000L) return
        prefs.edit().putLong("last_sweep", now).apply()
        runCatching { adoptStuckRows(ctx) }.onFailure { Log.w(TAG, "scan: ${it.message}"); MmsLog.err(ctx, "scan for stuck rows", it) }
        val todo = ArrayList<JSONObject>()
        synchronized(lock) {
            val q = load(ctx)
            val it = q.items.entries.iterator()
            while (it.hasNext()) {
                val e = it.next().value
                if (now - e.optLong("first", now) > KEEP_MS) { it.remove(); continue }
                val exp = e.optLong("exp")
                if (exp in 1 until now) continue
                if (e.optInt("attempts") >= MAX_ATTEMPTS) continue
                if (now - e.optLong("last") < 60_000L) continue // a download is probably running
                todo.add(e)
            }
            save(ctx, q)
        }
        if (todo.isNotEmpty() || force) MmsLog.log(ctx, "retry sweep (force=$force): ${todo.size} to download")
        for (e in todo) { startDownload(ctx, e); Thread.sleep(300) }
    }

    private fun adoptStuckRows(ctx: Context) {
        val since = (System.currentTimeMillis() - KEEP_MS) / 1000
        ctx.contentResolver.query(Uri.parse("content://mms/inbox"), arrayOf("_id", "ct_l", "tr_id", "sub_id", "exp", "date"),
            "m_type = 130 AND date > ?", arrayOf(since.toString()), "date DESC LIMIT 30")?.use { c ->
            while (c.moveToNext()) {
                val row = c.getLong(0)
                val loc = c.getString(1)?.trim()?.takeIf { it.startsWith("http", true) } ?: continue
                synchronized(lock) {
                    val q = load(ctx)
                    if (q.items.containsKey(loc)) return@synchronized
                    if (q.done.contains(loc)) { runCatching { ctx.contentResolver.delete(Uri.parse("content://mms/$row"), null, null) }; return@synchronized }
                    val from = runCatching {
                        ctx.contentResolver.query(Uri.parse("content://mms/$row/addr"), arrayOf("address"), "type = 137", null, null)
                            ?.use { a -> if (a.moveToFirst()) a.getString(0) else null }
                    }.getOrNull()
                    val exp = c.getLong(4)
                    q.items[loc] = JSONObject().apply {
                        put("loc", loc); put("tid", c.getString(2) ?: ""); put("from", MmsPdu.cleanAddress(from) ?: "")
                        put("sub", if (c.isNull(3)) -1 else c.getInt(3)); put("first", c.getLong(5) * 1000)
                        put("exp", if (exp > 100_000_000L) exp * 1000 else 0L); put("attempts", 0); put("row", row)
                    }
                    save(ctx, q)
                }
            }
        }
    }

    // ── Writing to the message store ─────────────────────────────────────────────

    /** Saves a downloaded MMS. Returns (mms id, newly inserted) or null when it could not be written. */
    fun persist(ctx: Context, m: MmsPdu.Message, subId: Int, loc: String?): Pair<Long, Boolean>? {
        val cr = ctx.contentResolver
        // The same message delivered twice (carriers do re-send notifications): keep one copy
        m.messageId?.takeIf { it.isNotBlank() }?.let { mid ->
            runCatching {
                cr.query(Uri.parse("content://mms/inbox"), arrayOf("_id"), "m_id = ?", arrayOf(mid), null)
                    ?.use { c -> if (c.moveToFirst()) return c.getLong(0) to false }
            }
        }
        val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        val members = MmsThreadAddresses.forIncoming(m.from, m.to, m.cc, selfNumbers(ctx, subId), prefs.getBoolean("group_mms", true))
            .ifEmpty { listOf(m.from ?: "Unknown") }
        val threadId = runCatching { Telephony.Threads.getOrCreateThreadId(ctx, members.toSet()) }
            .onFailure { Log.e(TAG, "thread id: ${it.message}"); MmsLog.err(ctx, "thread id for $members", it) }.getOrNull() ?: return null
        MmsLog.log(ctx, "thread $threadId for members $members")
        val now = System.currentTimeMillis() / 1000
        val v = ContentValues().apply {
            put("thread_id", threadId)
            put("date", now)
            if (m.date > 0) put("date_sent", m.date)
            put("msg_box", 1)
            put("read", 0); put("seen", 0)
            put("m_type", MmsPdu.TYPE_RETRIEVE_CONF)
            put("ct_t", m.contentType ?: "application/vnd.wap.multipart.related")
            m.messageId?.let { put("m_id", it) }
            m.transactionId?.let { put("tr_id", it) }
            loc?.let { put("ct_l", it) }
            m.subject?.let { put("sub", it); put("sub_cs", 106) }
            put("v", m.version)
            m.messageClass?.let { put("m_cls", it) }
            if (m.messageSize > 0) put("m_size", m.messageSize)
            val sid = SmsRepository.effectiveSubId(ctx, subId)
            if (sid >= 0) put("sub_id", sid)
        }
        val uri = cr.insert(Uri.parse("content://mms/inbox"), v)
        MmsLog.log(ctx, "inbox insert -> $uri")
        if (uri == null) return null
        val id = uri.lastPathSegment?.toLongOrNull() ?: return null
        try {
            fun addr(a: String, type: Int) = cr.insert(Uri.parse("content://mms/$id/addr"), ContentValues().apply {
                put("address", a); put("type", type); put("charset", 106); put("msg_id", id)
            }).also { if (it == null) MmsLog.log(ctx, "ERROR addr insert failed ($type $a)") }
            addr(m.from ?: "insert-address-token", 137)
            m.to.forEach { addr(it, 151) }
            m.cc.forEach { addr(it, 130) }
            m.bcc.forEach { addr(it, 129) }
            var n = 0
            for (p in m.parts.sortedBy { if (it.isSmil) 1 else 0 }) {
                n++
                val cv = ContentValues().apply {
                    put("mid", id)
                    put("seq", if (p.isSmil) -1 else 0)
                    put("ct", p.contentType)
                    (p.name ?: p.fileName ?: p.contentLocation)?.let { put("name", it) }
                    p.fileName?.let { put("fn", it) }
                    p.contentId?.let { put("cid", "<$it>") }
                    put("cl", p.contentLocation ?: p.name ?: p.fileName ?: "part$n")
                    if (p.isText) { put("chset", 106); put("text", p.text()) }
                    else if (p.charset > 0) put("chset", p.charset)
                }
                val partUri = runCatching { cr.insert(Uri.parse("content://mms/$id/part"), cv) }
                    .onFailure { Log.e(TAG, "part ${p.contentType}: ${it.message}"); MmsLog.err(ctx, "part insert ${p.contentType}", it) }.getOrNull()
                MmsLog.log(ctx, "part ${p.contentType} (${p.data.size} bytes) -> ${partUri ?: "NOT inserted"}")
                if (partUri == null) continue
                if (!p.isText) runCatching { cr.openOutputStream(partUri)?.use { it.write(p.data) } ?: MmsLog.log(ctx, "ERROR no output stream for $partUri") }
                    .onFailure { Log.e(TAG, "part data ${p.contentType}: ${it.message}"); MmsLog.err(ctx, "part data ${p.contentType}", it) }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "addr/parts: ${t.message}", t) // keep the message itself — better than losing it
            MmsLog.err(ctx, "addr/parts", t)
        }
        SmsRepository.forgetMms(id)
        runCatching { cr.notifyChange(Uri.parse("content://mms-sms/"), null) }
        Log.d(TAG, "saved MMS $id in thread $threadId (${members.size} member(s), ${m.parts.size} part(s))")
        return id to true
    }

    /** A visible "not downloaded yet" row (m_type 130) in the sender's chat while we keep retrying. */
    private fun savePlaceholder(ctx: Context, e: JSONObject): Long? {
        val from = e.optString("from").takeIf { it.isNotBlank() } ?: return null
        val threadId = Telephony.Threads.getOrCreateThreadId(ctx, from)
        val v = ContentValues().apply {
            put("thread_id", threadId); put("date", System.currentTimeMillis() / 1000)
            put("msg_box", 1); put("read", 1); put("seen", 1)
            put("m_type", MmsPdu.TYPE_NOTIFICATION_IND)
            put("ct_l", e.getString("loc"))
            e.optString("tid").takeIf { it.isNotBlank() }?.let { put("tr_id", it) }
            val exp = e.optLong("exp"); if (exp > 0) put("exp", exp / 1000)
            val size = e.optLong("size"); if (size > 0) put("m_size", size)
            put("v", 0x12)
            val sid = SmsRepository.effectiveSubId(ctx, e.optInt("sub", -1)); if (sid >= 0) put("sub_id", sid)
        }
        val uri = ctx.contentResolver.insert(Uri.parse("content://mms/inbox"), v) ?: return null
        val id = uri.lastPathSegment?.toLongOrNull() ?: return null
        ctx.contentResolver.insert(Uri.parse("content://mms/$id/addr"), ContentValues().apply {
            put("address", from); put("type", 137); put("charset", 106); put("msg_id", id)
        })
        return id
    }

    /** Our own number(s), so they are left out of group chats. Best effort: carriers often do not store it on the SIM. */
    @Suppress("DEPRECATION", "MissingPermission")
    fun selfNumbers(ctx: Context, subId: Int): Set<String> {
        val out = LinkedHashSet<String>()
        runCatching {
            val sm = ctx.getSystemService(SubscriptionManager::class.java)
            sm?.activeSubscriptionInfoList?.forEach { info ->
                if (Build.VERSION.SDK_INT >= 33) runCatching { sm.getPhoneNumber(info.subscriptionId) }.getOrNull()?.let { out.add(it) }
                runCatching { info.number }.getOrNull()?.let { out.add(it) }
            }
        }
        runCatching {
            val tm = ctx.getSystemService(TelephonyManager::class.java)
            val t = if (subId >= 0) tm?.createForSubscriptionId(subId) else tm
            t?.line1Number?.let { out.add(it) }
            if (t !== tm) tm?.line1Number?.let { out.add(it) }
        }
        return out.filter { it.count { ch -> ch.isDigit() } >= 7 }.toSet()
    }

    // ── Notification ─────────────────────────────────────────────────────────────

    fun notifyIncoming(ctx: Context, mmsId: Long) {
        val cr = ctx.contentResolver
        var threadId = -1L
        cr.query(Uri.parse("content://mms/$mmsId"), arrayOf("thread_id", "read"), null, null, null)?.use { c ->
            if (!c.moveToFirst() || c.getInt(1) == 1) { MmsLog.log(ctx, "notify: row $mmsId missing or already read — no notification"); return }
            threadId = c.getLong(0)
        }
        val addr = cr.query(Uri.parse("content://mms/$mmsId/addr"), arrayOf("address"), "type = 137", null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }?.takeIf { it.isNotBlank() && it != "insert-address-token" }
            ?: run { MmsLog.log(ctx, "notify: no sender address on $mmsId — no notification"); return }
        var text = ""; var media: String? = null
        cr.query(Uri.parse("content://mms/$mmsId/part"), arrayOf("ct", "text"), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val ct = c.getString(0) ?: continue
                if (ct == "text/plain" && text.isBlank()) text = c.getString(1) ?: ""
                else if (media == null && (ct.startsWith("image/") || ct.startsWith("video/") || ct.startsWith("audio/"))) media = ct
                else if (media == null && ct != "application/smil" && !ct.startsWith("text/")) media = ct
            }
        }
        val markRead = { runCatching { cr.update(Uri.parse("content://mms/$mmsId"), ContentValues().apply { put("read", 1); put("seen", 1) }, null, null) } }
        if (BlockedStore.isBlocked(ctx, addr)) { MmsLog.log(ctx, "notify: sender $addr is blocked — silent"); markRead(); return }
        if (SpamFilter.isSpam(ctx, addr, text) || (text.isNotBlank() && SmsRepository.getContactName(ctx, addr) == null && KeywordStore.matches(ctx, text))) {
            MmsLog.log(ctx, "notify: spam filter matched $addr — moved to spam"); markRead(); SpamStore.add(ctx, threadId); return
        }
        if (threadId > 0) runCatching { TrashStore.remove(ctx, threadId) } // a new message brings a trashed chat back
        runCatching { MessageWidget.refresh(ctx) }
        val name = SmsRepository.getContactName(ctx, addr) ?: addr
        val body = text.ifBlank {
            when {
                media == null -> "📩 MMS"
                media!!.startsWith("image/") -> "📷 Photo"
                media!!.startsWith("video/") -> "🎥 Video"
                media!!.startsWith("audio/") -> "🎤 Voice message"
                else -> "📎 Attachment"
            }
        }
        Notifications.showMessage(ctx, name, body, addr, threadId)
        MmsLog.log(ctx, "notification shown for thread $threadId ($body)")
    }

    /** Admin "Retry now": every queued MMS gets a fresh set of attempts, downloaded now. */
    fun retryNow(ctx: Context) {
        synchronized(lock) {
            val q = load(ctx)
            q.items.values.forEach { it.put("attempts", 0); it.put("last", 0L); it.put("pend", 0) }
            save(ctx, q)
        }
        MmsLog.log(ctx, "manual retry")
        retryPending(ctx, force = true)
    }

    /** Tells the carrier the message was retrieved (what Google Messages does), so it is not pushed again. */
    private fun sendNotifyResp(ctx: Context, transactionId: String, subId: Int) {
        val f = File(ctx.cacheDir, "mms_send_ack_${System.currentTimeMillis()}_${seq.incrementAndGet()}.dat")
        f.writeBytes(MmsPdu.buildNotifyRespInd(transactionId))
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        for (pkg in listOf("com.android.phone", "com.android.mms.service")) runCatching { ctx.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        SmsRepository.smsManager(ctx, subId).sendMultimediaMessage(ctx, uri, null, null, null)
    }

    // ── Queue (SharedPreferences JSON) ───────────────────────────────────────────

    private class Queue(val items: LinkedHashMap<String, JSONObject>, val done: MutableList<String>)

    private fun load(ctx: Context): Queue {
        val p = ctx.getSharedPreferences(QUEUE_PREFS, Context.MODE_PRIVATE)
        val items = LinkedHashMap<String, JSONObject>()
        runCatching {
            val a = JSONArray(p.getString("items", "[]"))
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); items[o.getString("loc")] = o }
        }
        val done = ArrayList<String>()
        runCatching { val a = JSONArray(p.getString("done", "[]")); for (i in 0 until a.length()) done.add(a.getString(i)) }
        return Queue(items, done)
    }

    private fun save(ctx: Context, q: Queue) {
        val a = JSONArray(); q.items.values.forEach { a.put(it) }
        val d = JSONArray(); q.done.forEach { d.put(it) }
        ctx.getSharedPreferences(QUEUE_PREFS, Context.MODE_PRIVATE).edit().putString("items", a.toString()).putString("done", d.toString()).commit()
    }

    /** Fallback for a notification our parser could not read: find the http(s) download address in the raw bytes. */
    private fun legacyContentLocation(pdu: ByteArray): String? {
        val str = String(pdu, Charsets.ISO_8859_1)
        val i = str.indexOf("http")
        if (i < 0) return null
        var end = i
        while (end < str.length && str[end].code in 0x21..0x7E) end++
        return str.substring(i, end).takeIf { it.length > 10 }
    }
}
