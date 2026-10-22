package com.heimish.messages.qcelp

import android.content.Context
import android.os.SystemClock
import com.heimish.messages.MmsLog
import java.io.File

/**
 * Verizon (and other CDMA carriers) send voice notes as Qualcomm PureVoice .qcp files (QCELP-13K in RIFF/QLCM),
 * which Android's media framework can't play. [playable] turns such a file into a cached 8 kHz 16-bit WAV once, so
 * duration, waveform and playback all work; anything that isn't QCP is returned untouched.
 */
object QcelpTranscoder {
    private val lock = Any()

    private fun head(f: File): ByteArray = runCatching {
        val b = ByteArray(16); val n = f.inputStream().use { it.read(b) }; b.copyOf(maxOf(n, 0))
    }.getOrDefault(ByteArray(0))

    /** The file to play for [src]: a decoded WAV when [src] is a .qcp voice note, otherwise [src] itself. Blocking; call off the main thread. */
    fun playable(ctx: Context, src: File, mime: String?): File {
        val h = head(src)
        if (!QcpFile.looksLikeQcp(h)) {
            if (QcpFile.isQcpMime(mime)) MmsLog.log(ctx, "qcelp: ${src.name} is typed $mime but has no QCP header (head=${MmsLog.hex(h, 16)}), left as is")
            return src
        }
        val dir = File(ctx.cacheDir, "media_qcelp").apply { mkdirs() }
        val key = src.nameWithoutExtension + "_" + src.length()
        val out = File(dir, "$key.wav")
        val failed = File(dir, "$key.failed")
        synchronized(lock) {
            if (out.length() > 44) return out
            if (failed.exists()) return src
            val t0 = SystemClock.elapsedRealtime()
            try {
                val q = QcpFile.parse(src.readBytes())
                if (q.codec != QcpFile.Codec.QCELP) {
                    MmsLog.log(ctx, "ERROR ${q.codec.name.replace("FOURGV", "4GV")} voice note — unsupported (${src.name} ${src.length()} bytes, codec guid ${q.guidHex})")
                    runCatching { failed.createNewFile() }
                    return src
                }
                val m = q.modeCounts()
                MmsLog.log(ctx, "qcelp: ${src.name} ${src.length()} bytes, ${q.packets.size} frames (full=${m[4]} half=${m[3]} quarter=${m[2]} eighth=${m[1]} blank=${m[0]})" +
                    " ${if (q.variableRate) "variable" else "fixed ${q.packetSize}"} rate, ${q.sampleRate} Hz")
                val samples = q.decodeToWav(out)
                val r = q.lastRateCounts
                MmsLog.log(ctx, "qcelp: wrote wav ${out.length()} bytes, duration ${samples * 1000L / q.sampleRate} ms, erasures=${r[0]}, in ${SystemClock.elapsedRealtime() - t0} ms")
                return out
            } catch (e: Throwable) {
                MmsLog.err(ctx, "qcelp decode ${src.name} ${src.length()} bytes head=${MmsLog.hex(h, 16)}", e)
                runCatching { out.delete(); failed.createNewFile() }
                return src
            }
        }
    }
}
