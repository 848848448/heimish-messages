package com.heimish.messages

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.Telephony
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Backup & restore of every text and picture message (SMS + MMS with photos, videos, voice notes) to one .zip file:
 *   messages.json  – SMS rows, MMS rows with their addresses and parts
 *   parts/…        – the MMS attachments
 *   settings.json  – app settings
 * Restore skips messages that are already on the phone. Old ".json" backups (texts only) still restore.
 */
object Backup {
    /** Live status for the Backup screen; the work runs on its own thread so leaving the screen doesn't stop it. */
    object State {
        var running by mutableStateOf(false)
        var status by mutableStateOf("")
    }

    data class Stats(val sms: Int, val mms: Int, val files: Int)

    private val SMS_COLS = listOf("address", "body", "date", "date_sent", "type", "read", "seen", "status", "sub_id", "locked", "error_code", "service_center", "protocol", "subject", "reply_path_present")
    private val MMS_COLS = listOf("date", "date_sent", "msg_box", "read", "seen", "sub", "sub_cs", "ct_t", "ct_l", "m_type", "m_cls", "m_id", "tr_id", "v", "pri", "rr", "rpt_a", "d_rpt",
        "read_status", "st", "resp_st", "retr_st", "retr_txt", "retr_txt_cs", "m_size", "exp", "d_tm", "sub_id", "locked", "text_only", "creator")
    private val PART_COLS = listOf("seq", "ct", "name", "chset", "cd", "fn", "cid", "cl", "ctt_s", "ctt_t", "text")
    private val ID_PREFS = listOf("starred_messages", "pinned_msgs", "reactions_", "scheduled_", "sched_last_id", "reminders_v1", "seen_build", "upd_", "ar_last_", "draft_", "auto_backup_")

    private fun JsonWriter.col(c: Cursor, name: String) {
        val i = c.getColumnIndex(name); if (i < 0 || c.isNull(i)) return
        when (c.getType(i)) {
            Cursor.FIELD_TYPE_INTEGER -> name(name).value(c.getLong(i))
            Cursor.FIELD_TYPE_FLOAT -> name(name).value(c.getDouble(i))
            Cursor.FIELD_TYPE_STRING -> name(name).value(c.getString(i))
            else -> {}
        }
    }

    fun fileName(): String = "Messages-backup-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date()) + ".zip"

    /** Writes the whole backup to [uri]. */
    fun export(ctx: Context, uri: Uri, progress: (String) -> Unit = {}): Stats =
        (ctx.contentResolver.openOutputStream(uri, "w") ?: throw IllegalStateException("can't write")).use { exportTo(ctx, it, progress) }

    fun exportTo(ctx: Context, os: java.io.OutputStream, progress: (String) -> Unit = {}): Stats {
        val cr = ctx.contentResolver
        val zip = ZipOutputStream(os.buffered(64 * 1024))
        var sms = 0; var mms = 0
        val files = ArrayList<Pair<Long, String>>()  // part id -> entry name
        zip.putNextEntry(ZipEntry("messages.json"))
        val w = JsonWriter(OutputStreamWriter(zip, Charsets.UTF_8))
        w.beginObject()
        w.name("app").value("heimish-messages"); w.name("version").value(3); w.name("created").value(System.currentTimeMillis())
        w.name("build").value(BuildConfig.BUILD_NUMBER.toLong())
        // ── SMS
        progress("Backing up texts…")
        w.name("sms").beginArray()
        cr.query(Telephony.Sms.CONTENT_URI, null, null, null, "date ASC")?.use { c ->
            while (c.moveToNext()) {
                w.beginObject(); SMS_COLS.forEach { w.col(c, it) }; w.endObject(); sms++
                if (sms % 500 == 0) progress("Backing up texts… $sms")
            }
        }
        w.endArray()
        // ── MMS
        progress("Backing up picture messages…")
        val mmsRows = ArrayList<Long>()
        cr.query(Uri.parse("content://mms"), arrayOf("_id"), null, null, "date ASC")?.use { c -> while (c.moveToNext()) mmsRows.add(c.getLong(0)) }
        w.name("mms").beginArray()
        for (chunk in mmsRows.chunked(300)) {
            val inList = chunk.joinToString(",")
            val parts = HashMap<Long, MutableList<ContentValues>>()
            val partIds = HashMap<Long, MutableList<Long>>()
            cr.query(Uri.parse("content://mms/part"), null, "mid IN ($inList)", null, "seq ASC")?.use { c ->
                val iMid = c.getColumnIndex("mid"); val iId = c.getColumnIndex("_id")
                while (c.moveToNext()) {
                    val cv = ContentValues()
                    PART_COLS.forEach { n -> val i = c.getColumnIndex(n); if (i >= 0 && !c.isNull(i)) { if (c.getType(i) == Cursor.FIELD_TYPE_INTEGER) cv.put(n, c.getLong(i)) else cv.put(n, c.getString(i)) } }
                    parts.getOrPut(c.getLong(iMid)) { mutableListOf() }.add(cv)
                    partIds.getOrPut(c.getLong(iMid)) { mutableListOf() }.add(c.getLong(iId))
                }
            }
            val addrs = HashMap<Long, MutableList<ContentValues>>()
            val batched = runCatching {
                cr.query(Uri.parse("content://mms/addr"), arrayOf("msg_id", "address", "type", "charset"), "msg_id IN ($inList)", null, null)?.use { c ->
                    while (c.moveToNext()) addrs.getOrPut(c.getLong(0)) { mutableListOf() }.add(ContentValues().apply { put("address", c.getString(1)); put("type", c.getLong(2)); put("charset", c.getLong(3)) })
                } != null
            }.getOrDefault(false)
            cr.query(Uri.parse("content://mms"), null, "_id IN ($inList)", null, "date ASC")?.use { c ->
                val iId = c.getColumnIndex("_id")
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    w.beginObject(); MMS_COLS.forEach { w.col(c, it) }
                    val al = addrs[id] ?: if (!batched) mutableListOf<ContentValues>().also { l ->
                        runCatching { cr.query(Uri.parse("content://mms/$id/addr"), arrayOf("address", "type", "charset"), null, null, null)?.use { a ->
                            while (a.moveToNext()) l.add(ContentValues().apply { put("address", a.getString(0)); put("type", a.getLong(1)); put("charset", a.getLong(2)) }) } }
                    } else null
                    w.name("addr").beginArray()
                    al?.forEach { a -> w.beginObject(); w.name("address").value(a.getAsString("address") ?: ""); w.name("type").value(a.getAsLong("type") ?: 151L); w.name("charset").value(a.getAsLong("charset") ?: 106L); w.endObject() }
                    w.endArray()
                    w.name("parts").beginArray()
                    val pl = parts[id].orEmpty(); val pids = partIds[id].orEmpty()
                    pl.forEachIndexed { k, cv ->
                        w.beginObject()
                        for (n in PART_COLS) { val v = cv.get(n) ?: continue; if (v is Long) w.name(n).value(v) else w.name(n).value(v.toString()) }
                        val ct = cv.getAsString("ct") ?: ""
                        if (cv.getAsString("text") == null && ct != "application/smil" && !ct.startsWith("text/")) {
                            val name = "parts/" + id + "_" + pids[k] + extFor(ct, cv.getAsString("cl") ?: cv.getAsString("name"))
                            w.name("file").value(name); files.add(pids[k] to name)
                        }
                        w.endObject()
                    }
                    w.endArray()
                    w.endObject(); mms++
                    if (mms % 200 == 0) progress("Backing up picture messages… $mms")
                }
            }
        }
        w.endArray()
        w.endObject(); w.flush()
        zip.closeEntry()
        // ── attachments
        var nFiles = 0
        files.forEachIndexed { i, (pid, name) ->
            runCatching {
                cr.openInputStream(Uri.parse("content://mms/part/$pid"))?.use { inp ->
                    zip.putNextEntry(ZipEntry(name)); inp.copyTo(zip, 64 * 1024); zip.closeEntry(); nFiles++
                }
            }
            if (i % 25 == 0) progress("Saving photos and videos… ${i + 1} / ${files.size}")
        }
        // ── settings (minus things tied to this phone's message ids)
        zip.putNextEntry(ZipEntry("settings.json"))
        val st = settingsToJson(ctx); ID_PREFS.forEach { pre -> st.keys().asSequence().toList().filter { it.startsWith(pre) }.forEach { st.remove(it) } }
        zip.write(st.toString().toByteArray()); zip.closeEntry()
        zip.finish(); zip.flush()
        return Stats(sms, mms, nFiles)
    }

    private fun extFor(ct: String, name: String?): String {
        name?.substringAfterLast('.', "")?.takeIf { it.length in 1..5 && it.all { ch -> ch.isLetterOrDigit() } }?.let { return ".$it" }
        return when {
            ct.contains("jpeg") || ct.contains("jpg") -> ".jpg"; ct.contains("png") -> ".png"; ct.contains("gif") -> ".gif"; ct.contains("webp") -> ".webp"
            ct.contains("heic") -> ".heic"; ct.contains("mp4") -> ".mp4"; ct.contains("3gp") -> ".3gp"; ct.contains("amr") -> ".amr"
            ct.contains("vcard") -> ".vcf"; ct.contains("mpeg") -> ".mp3"; ct.contains("ogg") -> ".ogg"; else -> ".bin"
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ RESTORE ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** Restores from a backup file. Returns how many messages were added (existing ones are skipped). */
    fun restore(ctx: Context, uri: Uri, progress: (String) -> Unit = {}): Stats {
        progress("Reading backup…")
        val tmp = File(ctx.cacheDir, "restore.tmp")
        ctx.contentResolver.openInputStream(uri)?.use { inp -> tmp.outputStream().use { inp.copyTo(it, 64 * 1024) } } ?: throw IllegalStateException("can't read")
        try {
            val head = tmp.inputStream().use { val b = ByteArray(2); it.read(b); b }
            return if (head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) ZipFile(tmp).use { restoreZip(ctx, it, progress) }
            else restoreOldJson(ctx, tmp)
        } finally { tmp.delete() }
    }

    private fun digits(a: String?) = (a ?: "").filter { it.isDigit() }.takeLast(9)

    private fun restoreZip(ctx: Context, zf: ZipFile, progress: (String) -> Unit): Stats {
        val cr = ctx.contentResolver
        zf.getEntry("settings.json")?.let { e -> runCatching { settingsFromJson(ctx, JSONObject(zf.getInputStream(e).bufferedReader().readText())) } }
        // What's already on the phone (one read each, instead of one query per message)
        val haveSms = HashSet<String>()
        cr.query(Telephony.Sms.CONTENT_URI, arrayOf("address", "date", "type"), null, null, null)?.use { c -> while (c.moveToNext()) haveSms.add(digits(c.getString(0)) + "|" + c.getLong(1) + "|" + c.getInt(2)) }
        val haveMms = HashSet<String>()
        cr.query(Uri.parse("content://mms"), arrayOf("date", "msg_box"), null, null, null)?.use { c -> while (c.moveToNext()) haveMms.add(c.getLong(0).toString() + "|" + c.getInt(1)) }
        val entry = zf.getEntry("messages.json") ?: throw IllegalStateException("not a backup")
        var nSms = 0; var nMms = 0; var nFiles = 0
        val threadCache = HashMap<String, Long>()
        JsonReader(zf.getInputStream(entry).bufferedReader()).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "sms" -> {
                        r.beginArray(); var seen = 0
                        while (r.hasNext()) {
                            val cv = readValues(r); seen++
                            val k = digits(cv.getAsString("address")) + "|" + (cv.getAsLong("date") ?: 0L) + "|" + (cv.getAsInteger("type") ?: 1)
                            if (k !in haveSms && cv.getAsString("address") != null) {
                                // keep only columns this phone knows; a missing column makes the insert fail
                                if (runCatching { cr.insert(Telephony.Sms.CONTENT_URI, cv) }.getOrNull() == null) {
                                    val basic = ContentValues(); listOf("address", "body", "date", "date_sent", "type", "read", "seen", "status").forEach { n -> cv.get(n)?.let { v -> if (v is Long) basic.put(n, v) else basic.put(n, v.toString()) } }
                                    runCatching { cr.insert(Telephony.Sms.CONTENT_URI, basic) }.getOrNull()?.let { nSms++ }
                                } else nSms++
                                haveSms.add(k)
                            }
                            if (seen % 300 == 0) progress("Restoring texts… $seen")
                        }
                        r.endArray()
                    }
                    "mms" -> {
                        r.beginArray(); var seen = 0
                        while (r.hasNext()) {
                            seen++
                            val (row, addrs, parts) = readMms(r)
                            val k = (row.getAsLong("date") ?: 0L).toString() + "|" + (row.getAsInteger("msg_box") ?: 1)
                            if (k in haveMms) continue
                            val n = insertMms(ctx, zf, row, addrs, parts, threadCache)
                            if (n >= 0) { nMms++; nFiles += n; haveMms.add(k) }
                            if (seen % 50 == 0) progress("Restoring picture messages… $seen")
                        }
                        r.endArray()
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
        }
        return Stats(nSms, nMms, nFiles)
    }

    private fun readValues(r: JsonReader): ContentValues {
        val cv = ContentValues()
        r.beginObject()
        while (r.hasNext()) {
            val n = r.nextName()
            when (r.peek()) {
                JsonToken.NUMBER -> { val s = r.nextString(); s.toLongOrNull()?.let { cv.put(n, it) } ?: cv.put(n, s) }
                JsonToken.STRING -> cv.put(n, r.nextString())
                JsonToken.BOOLEAN -> cv.put(n, if (r.nextBoolean()) 1 else 0)
                JsonToken.NULL -> r.nextNull()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return cv
    }

    private fun readMms(r: JsonReader): Triple<ContentValues, List<ContentValues>, List<ContentValues>> {
        val row = ContentValues(); val addrs = ArrayList<ContentValues>(); val parts = ArrayList<ContentValues>()
        r.beginObject()
        while (r.hasNext()) {
            val n = r.nextName()
            when {
                n == "addr" -> { r.beginArray(); while (r.hasNext()) addrs.add(readValues(r)); r.endArray() }
                n == "parts" -> { r.beginArray(); while (r.hasNext()) parts.add(readValues(r)); r.endArray() }
                r.peek() == JsonToken.NUMBER -> { val s = r.nextString(); s.toLongOrNull()?.let { row.put(n, it) } ?: row.put(n, s) }
                r.peek() == JsonToken.STRING -> row.put(n, r.nextString())
                else -> r.skipValue()
            }
        }
        r.endObject()
        return Triple(row, addrs, parts)
    }

    /** Inserts one MMS with its addresses and parts. Returns the number of attachment files written, or -1 if it failed. */
    private fun insertMms(ctx: Context, zf: ZipFile, row: ContentValues, addrs: List<ContentValues>, parts: List<ContentValues>, threadCache: HashMap<String, Long>): Int {
        val cr = ctx.contentResolver
        val box = row.getAsInteger("msg_box") ?: 1
        // Thread = everyone in the message except me (FROM for incoming, TO/CC/BCC for outgoing)
        val people = addrs.filter { val t = it.getAsInteger("type") ?: 0; if (box == 1) t == 137 || t == 151 || t == 130 else t == 151 || t == 130 || t == 129 }
            .mapNotNull { it.getAsString("address") }.filter { it.isNotBlank() && it != "insert-address-token" }.distinct()
        if (people.isEmpty()) return -1
        val tkey = people.sorted().joinToString(";")
        val threadId = threadCache.getOrPut(tkey) { runCatching { Telephony.Threads.getOrCreateThreadId(ctx, people.toSet()) }.getOrDefault(0L) }
        if (threadId <= 0) return -1
        val cv = ContentValues(row); cv.remove("file"); cv.put("thread_id", threadId)
        val msgUri = runCatching { cr.insert(Uri.parse("content://mms"), cv) }.getOrNull()
            ?: runCatching { val b = ContentValues(); listOf("date", "date_sent", "msg_box", "read", "seen", "sub", "sub_cs", "ct_t", "m_type", "v", "m_id", "tr_id").forEach { n -> row.get(n)?.let { v -> if (v is Long) b.put(n, v) else b.put(n, v.toString()) } }
                b.put("thread_id", threadId); cr.insert(Uri.parse("content://mms"), b) }.getOrNull()
            ?: return -1
        val mid = msgUri.lastPathSegment?.toLongOrNull() ?: return -1
        addrs.forEach { a -> runCatching { cr.insert(Uri.parse("content://mms/$mid/addr"), ContentValues(a).apply { put("msg_id", mid) }) } }
        var files = 0
        parts.forEach { p ->
            val file = p.getAsString("file")
            val pv = ContentValues(p); pv.remove("file"); pv.put("mid", mid)
            val partUri = runCatching { cr.insert(Uri.parse("content://mms/$mid/part"), pv) }.getOrNull() ?: return@forEach
            if (file != null) {
                val e = zf.getEntry(file) ?: return@forEach
                runCatching { cr.openOutputStream(partUri)?.use { o -> zf.getInputStream(e).use { it.copyTo(o, 64 * 1024) } }; files++ }
            }
        }
        return files
    }

    /** Backups made before build 245 (texts only, one .json file). */
    private fun restoreOldJson(ctx: Context, f: File): Stats {
        val root = JSONObject(f.readText())
        runCatching { root.optJSONObject("settings")?.let { settingsFromJson(ctx, it) } }
        val arr = root.getJSONArray("sms")
        val have = HashSet<String>()
        ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf("address", "date", "type"), null, null, null)?.use { c -> while (c.moveToNext()) have.add(digits(c.getString(0)) + "|" + c.getLong(1) + "|" + c.getInt(2)) }
        var n = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val k = digits(o.optString("a")) + "|" + o.optLong("d") + "|" + o.optInt("t", 1)
            if (k in have) continue
            val cv = ContentValues().apply { put("address", o.optString("a")); put("body", o.optString("b")); put("date", o.optLong("d")); put("type", o.optInt("t", 1)); put("read", o.optInt("r", 1)); put("seen", 1) }
            if (runCatching { ctx.contentResolver.insert(Telephony.Sms.CONTENT_URI, cv) }.getOrNull() != null) { n++; have.add(k) }
        }
        return Stats(n, 0, 0)
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ RUNNERS (survive leaving the screen) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    fun runExport(ctx: Context, uri: Uri) = runBg { p ->
        val s = export(ctx.applicationContext, uri, p)
        "Saved ${s.sms} texts and ${s.mms} picture messages (${s.files} photos/videos)"
    }

    fun runRestore(ctx: Context, uri: Uri) = runBg { p ->
        val s = restore(ctx.applicationContext, uri, p)
        ConvCache.list = null
        "Restored ${s.sms} texts and ${s.mms} picture messages. Messages already on the phone were skipped."
    }

    private fun runBg(work: ((String) -> Unit) -> String) {
        if (State.running) return
        State.running = true; State.status = "Starting…"
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val msg = runCatching { work { s -> main.post { State.status = s } } }.getOrElse { e -> "Didn't finish: " + (e.message ?: e.javaClass.simpleName) }
            main.post { State.status = msg; State.running = false }
        }.start()
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ AUTOMATIC BACKUP ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

/** Daily/weekly backup into a folder you picked (Settings > Backup & restore). Keeps the newest few and deletes older ones. */
object AutoBackup {
    private const val JOB_ID = 4245
    private fun p(ctx: Context) = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
    fun folder(ctx: Context): Uri? = p(ctx).getString("auto_backup_tree", null)?.let { Uri.parse(it) }
    fun enabled(ctx: Context) = p(ctx).getBoolean("auto_backup_on", false) && folder(ctx) != null
    fun everyDays(ctx: Context) = p(ctx).getInt("auto_backup_days", 1)
    fun keep(ctx: Context) = p(ctx).getInt("auto_backup_keep", 5)
    fun lastAt(ctx: Context) = p(ctx).getLong("auto_backup_last", 0L)
    fun lastResult(ctx: Context) = p(ctx).getString("auto_backup_result", "") ?: ""

    fun setFolder(ctx: Context, tree: Uri) {
        runCatching { ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        p(ctx).edit().putString("auto_backup_tree", tree.toString()).apply()
        schedule(ctx)
    }

    fun folderName(ctx: Context): String = folder(ctx)?.let { t -> runCatching { DocumentsContract.getTreeDocumentId(t).substringAfter(':').ifBlank { "Phone storage" } }.getOrDefault("Chosen folder") } ?: "Not chosen"

    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (!enabled(ctx)) { js.cancel(JOB_ID); return }
        val period = everyDays(ctx) * 24L * 3600_000L
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, AutoBackupJob::class.java))
            .setPeriodic(period)
            .setPersisted(true)  // survives reboot (RECEIVE_BOOT_COMPLETED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
        val cur = js.getPendingJob(JOB_ID)
        if (cur == null || cur.intervalMillis != period) runCatching { js.schedule(job) }
    }

    /** Writes one backup into the folder now and removes old ones. Returns a status line. */
    fun runNow(ctx: Context): String {
        val tree = folder(ctx) ?: return "Pick a folder first"
        val cr = ctx.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(cr, parent, "application/zip", Backup.fileName()) ?: return "Couldn't write to the folder. Pick it again."
        val res = runCatching { Backup.export(ctx, doc) }
        val msg = res.fold({ s -> "${s.sms + s.mms} messages, ${s.files} photos/videos" }, { e -> runCatching { DocumentsContract.deleteDocument(cr, doc) }; "Failed: " + (e.message ?: e.javaClass.simpleName) })
        if (res.isSuccess) prune(ctx, tree)
        p(ctx).edit().putLong("auto_backup_last", System.currentTimeMillis()).putString("auto_backup_result", msg).apply()
        return msg
    }

    private fun prune(ctx: Context, tree: Uri) = runCatching {
        val cr = ctx.contentResolver
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val found = ArrayList<Pair<String, String>>()
        cr.query(kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) { val n = c.getString(1) ?: continue; if (n.startsWith("Messages-backup-") && n.endsWith(".zip")) found.add(c.getString(0) to n) }
        }
        found.sortedByDescending { it.second }.drop(keep(ctx)).forEach { (id, _) -> runCatching { DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, id)) } }
    }
}

class AutoBackupJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            runCatching { AutoBackup.runNow(applicationContext) }
            jobFinished(params, false)
        }.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean = true
}
