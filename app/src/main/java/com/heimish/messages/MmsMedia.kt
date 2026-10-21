package com.heimish.messages

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
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

    /** A playable local file for [uri] (a content://mms/part/... or file:// URI); null when it can't be read. */
    fun localFile(ctx: Context, uri: Uri, mime: String?): File? {
        if (uri.scheme == "file") return uri.path?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }
        val dir = File(ctx.cacheDir, "media_open").apply { mkdirs() }
        val base = "mms_" + uri.toString().hashCode().toUInt().toString(16)
        synchronized(lock) {
            dir.listFiles { f -> f.name.startsWith("$base.") && f.length() > 0 }?.firstOrNull()?.let { it.setLastModified(System.currentTimeMillis()); return it }
            val tmp = File(dir, "$base.tmp")
            val n = runCatching { ctx.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } } }
                .onFailure { MmsLog.err(ctx, "media read $uri", it) }.getOrNull()
            if (n == null || n <= 0L) { tmp.delete(); MmsLog.log(ctx, "ERROR media $uri ($mime) is empty or unreadable"); return null }
            val head = ByteArray(16).also { b -> runCatching { tmp.inputStream().use { it.read(b) } } }
            val real = sniffMime(head) ?: mime ?: ""
            val out = File(dir, "$base.${extFor(real)}")
            if (!tmp.renameTo(out)) { tmp.delete(); return null }
            return out
        }
    }

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

    /** Extension for a cache copy, so every player recognises the container. */
    fun extFor(mime: String): String = when (mime.lowercase().substringBefore(";").trim()) {
        "audio/amr" -> "amr"; "audio/amr-wb" -> "awb"; "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac-mp4" -> "m4a"
        "audio/aac" -> "aac"; "audio/3gpp", "video/3gpp", "audio/3gp", "video/3gp" -> "3gp"; "audio/3gpp2", "video/3gpp2" -> "3g2"
        "audio/ogg", "audio/opus", "application/ogg" -> "ogg"; "audio/mpeg", "audio/mp3" -> "mp3"; "audio/wav", "audio/x-wav" -> "wav"
        "video/mp4" -> "mp4"; "video/quicktime" -> "mov"; "audio/x-caf" -> "caf"; "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/gif" -> "gif"
        else -> "bin"
    }

}
