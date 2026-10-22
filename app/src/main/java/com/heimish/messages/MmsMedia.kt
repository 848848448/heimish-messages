package com.heimish.messages

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import com.heimish.messages.qcelp.QcelpTranscoder
import java.io.File

/**
 * Received (and sent) MMS audio/video, read reliably: every part is first copied to a private cache file with the
 * right extension (the part provider's file descriptor isn't always usable by the media framework), the real format
 * is sniffed from the bytes (carriers send octet-stream, odd or wrong content types), and duration / video track /
 * thumbnail come from that file.
 */
object MmsMedia {
    data class Probe(val hasVideo: Boolean, val hasAudio: Boolean, val durationMs: Long, val width: Int, val height: Int)

    private val probes = LruCache<String, Probe>(200)
    private val thumbs = LruCache<String, Bitmap>(24)
    private val lock = Any()

    fun sniffMime(head: ByteArray): String? = MediaTypes.sniffMime(head)
    fun extFor(mime: String): String = MediaTypes.extFor(mime)
    fun fixPartType(ct: String, name: String?, data: ByteArray): String = MediaTypes.fixPartType(ct, name, data)

    /** A playable local file for [uri] (a content://mms/part/... or file:// URI); null when it can't be read.
     *  The copy is checked against the part's current size every time: a copy made while the part was still being
     *  written (the chat refreshes as soon as the part row exists), or left from a deleted message whose part number was
     *  reused, is made again instead of being played truncated or wrong.
     *  A Verizon .qcp (QCELP) voice note comes back as its decoded WAV. Blocking; call off the main thread. */
    fun localFile(ctx: Context, uri: Uri, mime: String?): File? = localCopy(ctx, uri, mime)?.let { QcelpTranscoder.playable(ctx, it, mime) }

    private fun localCopy(ctx: Context, uri: Uri, mime: String?): File? {
        if (uri.scheme == "file") return uri.path?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }
        val dir = File(ctx.cacheDir, "media_open").apply { mkdirs() }
        val base = "mms_" + uri.toString().hashCode().toUInt().toString(16)
        val srcSize = runCatching { ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: -1L
        synchronized(lock) {
            val cached = dir.listFiles { f -> f.name.startsWith("$base.") && !f.name.endsWith(".tmp") && f.length() > 0 }?.firstOrNull()
            if (cached != null) {
                if (srcSize <= 0L || cached.length() == srcSize || cached.length() == srcSize + 6 || cached.length() == srcSize + 9) { cached.setLastModified(System.currentTimeMillis()); return cached }
                MmsLog.log(ctx, "media copy ${cached.name} is ${cached.length()} bytes but the part is $srcSize now: copying again")
                cached.delete(); probes.remove(uri.toString()); thumbs.remove(uri.toString())
            }
            val tmp = File(dir, "$base.tmp")
            val n = runCatching { ctx.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } } }
                .onFailure { MmsLog.err(ctx, "media read $uri", it) }.getOrNull()
            val info = partInfo(ctx, uri)
            if (n == null || n <= 0L) { tmp.delete(); MmsLog.log(ctx, "ERROR media $uri ($mime) is empty or unreadable: copied ${n ?: "nothing"}, part size $srcSize, $info"); return null }
            var head = ByteArray(16).also { b -> runCatching { tmp.inputStream().use { it.read(b) } } }
            MediaTypes.missingAmrHeader(mime, head.copyOf(minOf(16, n.toInt())))?.let { hdr ->
                runCatching {
                    val fixed = File(dir, "$base.fix")
                    fixed.outputStream().use { o -> o.write(hdr.toByteArray(Charsets.ISO_8859_1)); tmp.inputStream().use { it.copyTo(o) } }
                    tmp.delete(); fixed.renameTo(tmp)
                    head = ByteArray(16).also { b -> tmp.inputStream().use { it.read(b) } }
                    MmsLog.log(ctx, "media $uri: AMR without its header, added ${hdr.trim()}")
                }
            }
            val sniffed = sniffMime(head)
            val real = sniffed ?: mime ?: ""
            val out = File(dir, "$base.${extFor(real)}")
            if (!tmp.renameTo(out)) { tmp.delete(); MmsLog.log(ctx, "ERROR media copy rename failed for $uri"); return null }
            MmsLog.log(ctx, "media copy $uri $info mime=$mime: $n bytes (part size $srcSize) head=${MmsLog.hex(head.copyOf(minOf(16, n.toInt())), 16)} sniffed=${sniffed ?: "unknown"} -> ${out.name}")
            return out
        }
    }

    /** The part's stored content type and name, for the log. */
    private fun partInfo(ctx: Context, uri: Uri): String = runCatching {
        ctx.contentResolver.query(uri, arrayOf("ct", "name", "cl"), null, null, null)?.use { c ->
            if (c.moveToFirst()) "ct=${c.getString(0)} name=${c.getString(1) ?: c.getString(2)}" else "part row missing"
        } ?: "no part row"
    }.getOrElse { "part info: ${it.message}" }

    /** The format of a local file from its first bytes. */
    fun sniffFile(f: File): String? = runCatching { val b = ByteArray(16); val n = f.inputStream().use { it.read(b) }; sniffMime(b.copyOf(maxOf(n, 0))) }.getOrNull()

    fun headHex(f: File?): String = runCatching { val b = ByteArray(16); val n = f!!.inputStream().use { it.read(b) }; MmsLog.hex(b.copyOf(maxOf(n, 0)), 16) }.getOrDefault("-")

    fun cachedProbe(uri: Uri): Probe? = probes.get(uri.toString())

    /** Duration, tracks and size of the media at [uri]. Never throws. */
    fun probe(ctx: Context, uri: Uri, mime: String?): Probe {
        probes.get(uri.toString())?.let { return it }
        val f = localFile(ctx, uri, mime)
        var p = Probe(false, false, 0L, 0, 0)
        if (f != null) {
            val r = MediaMetadataRetriever()
            runCatching {
                r.setDataSource(f.absolutePath)
                fun k(key: Int) = r.extractMetadata(key)
                p = Probe(
                    hasVideo = k(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes",
                    hasAudio = k(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
                    durationMs = k(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                    width = k(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                    height = k(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                )
                if (p.hasVideo) {
                    val rot = k(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (rot == 90 || rot == 270) p = p.copy(width = p.height, height = p.width)
                    runCatching { r.getFrameAtTime(minOf(1_000_000L, p.durationMs * 500L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }
                        .getOrNull()?.let { thumbs.put(uri.toString(), scaled(it)) }
                }
            }.onFailure { MmsLog.log(ctx, "ERROR media probe $mime ${f.name} ${f.length()} bytes head=${headHex(f)}: ${it.message}") }
            runCatching { r.release() }
        }
        probes.put(uri.toString(), p)
        return p
    }

    private fun scaled(b: Bitmap): Bitmap {
        val max = 720
        if (b.width <= max && b.height <= max) return b
        val s = max.toFloat() / maxOf(b.width, b.height)
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
    }

    /** First frame of a video (after [probe]). */
    fun thumbnail(ctx: Context, uri: Uri, mime: String?): Bitmap? {
        thumbs.get(uri.toString())?.let { return it }
        probes.remove(uri.toString())
        probe(ctx, uri, mime)
        return thumbs.get(uri.toString())
    }

    /** Logs a playback failure with what's needed to fix it next time. */
    fun logPlayError(ctx: Context, kind: String, uri: Uri, mime: String?, what: Int, extra: Int, f: File?) {
        MmsLog.log(ctx, "ERROR $kind play failed what=$what extra=$extra mime=$mime uri=$uri file=${f?.name} ${f?.length() ?: 0} bytes head=${headHex(f)}")
    }
}

/** Pure helpers (no Android classes, unit-tested). */
object MediaTypes {
    /** The format from the first bytes, or null when not recognised. Pure (unit-tested). */
    fun sniffMime(head: ByteArray): String? {
        fun at(off: Int, s: String) = head.size >= off + s.length && s.indices.all { head[off + it] == s[it].code.toByte() }
        fun u(i: Int) = if (i < head.size) head[i].toInt() and 0xFF else -1
        return when {
            at(0, "#!AMR-WB\n") -> "audio/amr-wb"
            at(0, "#!AMR\n") -> "audio/amr"
            at(4, "ftyp") -> {
                val brand = if (head.size >= 12) String(head, 8, 4, Charsets.ISO_8859_1).lowercase() else ""
                when {
                    brand.startsWith("m4a") || brand.startsWith("m4b") -> "audio/mp4"
                    brand.startsWith("3g") -> "video/3gpp"   // may be audio only; the probe decides
                    brand.startsWith("qt") -> "video/quicktime"
                    else -> "video/mp4"                      // may be audio only; the probe decides
                }
            }
            at(0, "OggS") -> "audio/ogg"
            at(0, "caff") -> "audio/x-caf"
            at(0, "RIFF") && at(8, "WAVE") -> "audio/wav"
            at(0, "ID3") || (u(0) == 0xFF && u(1) >= 0xE0 && u(1) != 0xFF && (u(1) and 0x06) != 0 && (u(1) and 0x18) != 0x08) -> "audio/mpeg"
            u(0) == 0xFF && (u(1) and 0xF6) == 0xF0 -> "audio/aac"   // ADTS
            u(0) == 0xFF && u(1) == 0xD8 -> "image/jpeg"
            at(0, "\u0089PNG") -> "image/png"
            at(0, "GIF8") -> "image/gif"
            at(0, "RIFF") && at(8, "WEBP") -> "image/webp"
            else -> null
        }
    }

    /** The content type to store for a received part: carriers send voice notes and videos as octet-stream or other odd
     *  types; the bytes say what they really are. Text and layout parts are left alone. Pure (unit-tested). */
    fun fixPartType(ct: String, name: String?, data: ByteArray): String {
        val c = ct.lowercase().substringBefore(';').trim()
        if (c.startsWith("text/") || c == "application/smil") return ct
        if (mediaMimeFor(ct, name) != null && c != "application/octet-stream") return ct
        return sniffMime(data.copyOf(minOf(16, data.size))) ?: mediaMimeFor(ct, name) ?: ct
    }

    /** The storage header to put in front of AMR audio sent as bare frames (no "#!AMR" line; some phones and carriers
     *  do this and no player opens it without the header), or null when the data already has one or isn't AMR. Pure. */
    fun missingAmrHeader(mime: String?, head: ByteArray): String? {
        val c = (mime ?: "").lowercase().substringBefore(';').trim()
        if (head.isEmpty() || sniffMime(head) != null) return null
        val b = head[0].toInt() and 0xFF
        if (b and 0x83 != 0) return null           // frame header: padding bit, 4-bit frame type, quality bit, 2 padding bits
        val ft = (b shr 3) and 0x0F
        return when (c) {
            "audio/amr", "audio/x-amr" -> if (ft <= 8 || ft == 15) "#!AMR\n" else null
            "audio/amr-wb", "audio/x-amr-wb" -> if (ft <= 9 || ft == 15) "#!AMR-WB\n" else null
            else -> null
        }
    }

    /** Extension for a cache copy, so every player recognises the container. */
    fun extFor(mime: String): String = when (mime.lowercase().substringBefore(";").trim()) {
        "audio/amr" -> "amr"; "audio/amr-wb" -> "awb"; "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac-mp4" -> "m4a"
        "audio/aac" -> "aac"; "audio/3gpp", "video/3gpp", "audio/3gp", "video/3gp" -> "3gp"; "audio/3gpp2", "video/3gpp2" -> "3g2"
        "audio/ogg", "audio/opus", "application/ogg" -> "ogg"; "audio/mpeg", "audio/mp3" -> "mp3"; "audio/wav", "audio/x-wav" -> "wav"
        "video/mp4" -> "mp4"; "video/quicktime" -> "mov"; "audio/x-caf" -> "caf"; "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/gif" -> "gif"
        else -> "bin"
    }

}
