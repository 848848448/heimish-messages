package com.heimish.messages

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.LruCache
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ━━━━━━━━━━━━━━━━ VOICE NOTES (WhatsApp style) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━

/** Formats milliseconds as m:ss. */
internal fun fmtVoice(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return String.format("%d:%02d", s / 60, s % 60) }

/** What a received part really is (audio, video or image), from its content type with a file-name fallback; null for
 *  text, layout and anything else. Types are compared case-insensitively and without parameters ("Video/3GPP; name=x"). */
internal fun mediaMimeFor(ct: String, name: String?): String? {
    val c = ct.lowercase().substringBefore(';').trim()
    if (c == "text/plain" || c == "application/smil" || c.startsWith("text/")) return null
    audioMimeFor(c, name)?.let { return it }
    if (c.startsWith("image/") || c.startsWith("video/")) return c
    if (c == "application/octet-stream" || c.isBlank() || c == "application/vnd.oma.drm.message") {
        val n = (name ?: "").lowercase()
        return when {
            n.endsWith(".mp4") || n.endsWith(".m4v") -> "video/mp4"
            n.endsWith(".mov") -> "video/quicktime"
            n.endsWith(".jpg") || n.endsWith(".jpeg") -> "image/jpeg"
            n.endsWith(".png") -> "image/png"
            n.endsWith(".gif") -> "image/gif"
            else -> null
        }
    }
    return null
}

/** MMS audio that other apps send under odd content types (application/ogg, octet-stream with a .amr name...). */
internal fun audioMimeFor(ct: String, name: String?): String? {
    val c = ct.lowercase().substringBefore(';').trim()
    if (c.startsWith("audio/")) return c
    if (c == "application/ogg") return "audio/ogg"
    val n = (name ?: "").lowercase()
    if (c == "application/octet-stream" || c.isBlank()) {
        return when {
            n.endsWith(".amr") -> "audio/amr"
            n.endsWith(".m4a") || n.endsWith(".aac") -> "audio/mp4"
            n.endsWith(".ogg") || n.endsWith(".opus") || n.endsWith(".oga") -> "audio/ogg"
            n.endsWith(".3ga") || n.endsWith(".3gpp") || n.endsWith(".3gp") -> "audio/3gpp"
            n.endsWith(".mp3") -> "audio/mpeg"
            n.endsWith(".wav") -> "audio/wav"
            else -> null
        }
    }
    // 3gp audio sent as video/3gpp by some phones; only treat it as audio when the file name says so
    if (c == "video/3gpp" && (n.endsWith(".3ga") || n.endsWith(".amr"))) return "audio/3gpp"
    return null
}

/** True when an MMS carries an audio part (for notification text). */
internal fun mmsHasAudio(ctx: Context, mmsId: Long): Boolean = runCatching {
    ctx.contentResolver.query(Uri.parse("content://mms/$mmsId/part"), arrayOf("ct", "cl", "name"), null, null, null)?.use { c ->
        var found = false
        while (!found && c.moveToNext()) found = audioMimeFor(c.getString(0) ?: "", c.getString(1) ?: c.getString(2)) != null
        found
    } ?: false
}.getOrDefault(false)

/** Which voice notes the user already listened to (the blue dot goes away). */
object VoicePlayedStore {
    private const val P = "voice_played"
    fun isPlayed(ctx: Context, key: String) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getBoolean(key, false)
    fun markPlayed(ctx: Context, key: String) { ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putBoolean(key, true).apply() }
}

/** Real waveform (and duration) of an audio file, decoded once and cached. */
object VoiceWave {
    data class Info(val bars: FloatArray, val durationMs: Long)
    private val cache = LruCache<String, Info>(80)
    const val BARS = 40

    fun cached(uri: Uri): Info? = cache.get(uri.toString())

    fun pseudo(seed: Long): FloatArray { // fallback when a codec can't be decoded
        var x = seed xor 0x5DEECE66DL
        return FloatArray(BARS) { x = (x * 6364136223846793005L + 1442695040888963407L); (0.25f + ((x ushr 33) % 1000) / 1000f * 0.75f) }
    }

    fun load(ctx: Context, uri: Uri, mime: String? = null): Info {
        cache.get(uri.toString())?.let { return it }
        var duration = 0L
        // read from a private copy: the part provider's descriptor isn't always usable by the media framework (0:00, silence)
        val f = runCatching { MmsMedia.localFile(ctx, uri, mime) }.getOrNull()
        val bars = runCatching { decode(ctx, uri, f) { d -> duration = d } }.getOrNull()
        if (duration <= 0L) duration = runCatching { MmsMedia.probe(ctx, uri, mime).durationMs }.getOrDefault(0L)
        if (duration <= 0L) duration = runCatching {
            val r = android.media.MediaMetadataRetriever()
            try { r.setDataSource(ctx, uri); r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L } finally { runCatching { r.release() } }
        }.getOrDefault(0L)
        if (duration <= 0L) MmsLog.log(ctx, "ERROR voice note unreadable mime=$mime uri=$uri file=${f?.name} ${f?.length() ?: 0} bytes head=${MmsMedia.headHex(f)}")
        val info = Info(bars ?: pseudo(uri.toString().hashCode().toLong()), duration)
        if (duration > 0L) cache.put(uri.toString(), info)
        return info
    }

    private fun decode(ctx: Context, uri: Uri, f: File?, onDuration: (Long) -> Unit): FloatArray? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (f != null) ex.setDataSource(f.absolutePath) else ex.setDataSource(ctx, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            val fmt = ex.getTrackFormat(track)
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durUs > 0) onDuration(durUs / 1000)
            if (durUs <= 0) return null
            ex.selectTrack(track)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            val sums = DoubleArray(BARS); val counts = IntArray(BARS)
            val info = MediaCodec.BufferInfo()
            var inDone = false; var outDone = false
            val deadline = System.currentTimeMillis() + 4000
            while (!outDone && System.currentTimeMillis() < deadline) {
                if (!inDone) {
                    val i = c.dequeueInputBuffer(5000)
                    if (i >= 0) {
                        val buf = c.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { c.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 5000)
                if (o >= 0) {
                    val out = c.getOutputBuffer(o)
                    if (out != null && info.size > 0) {
                        val bin = ((info.presentationTimeUs.toDouble() / durUs) * BARS).toInt().coerceIn(0, BARS - 1)
                        out.position(info.offset); out.limit(info.offset + info.size)
                        val sb = out.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        var s = 0.0; var k = 0
                        while (sb.hasRemaining()) { val v = sb.get().toDouble(); s += v * v; k++; if (k > 1 && sb.hasRemaining()) { sb.get() } } // every other sample is plenty
                        if (k > 0) { sums[bin] += s / k; counts[bin]++ }
                    }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            val rms = FloatArray(BARS) { if (counts[it] > 0) sqrt(sums[it] / counts[it]).toFloat() else 0f }
            val peak = rms.maxOrNull()?.takeIf { it > 0f } ?: return null
            return FloatArray(BARS) { (0.12f + 0.88f * (rms[it] / peak)).coerceIn(0.12f, 1f) }
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            runCatching { ex.release() }
        }
    }
}

/** One player for the whole app, so only one voice note plays at a time. */
object VoicePlayer {
    var currentKey by mutableStateOf<String?>(null); private set
    var playing by mutableStateOf(false); private set
    var preparing by mutableStateOf(false); private set
    var speed by mutableFloatStateOf(1f); private set
    private var mp: MediaPlayer? = null
    private var focusAm: AudioManager? = null

    fun position(): Long = runCatching { mp?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
    fun duration(): Long = runCatching { mp?.duration?.toLong() ?: 0L }.getOrDefault(0L)

    fun loadSpeed(ctx: Context) { speed = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE).getFloat("voice_speed", 1f) }

    fun cycleSpeed(ctx: Context) {
        speed = when (speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
        ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE).edit().putFloat("voice_speed", speed).apply()
        if (playing) applySpeed()
    }

    private fun applySpeed() { runCatching { mp?.let { it.playbackParams = it.playbackParams.setSpeed(speed) } } }

    /** Play (or resume) [uri]; starts at [startFraction] of the note when given. */
    fun play(ctx: Context, key: String, uri: Uri, startFraction: Float? = null, mime: String? = null) {
        if (currentKey == key && mp != null && !preparing) {
            startFraction?.let { f -> seekTo(f) }
            runCatching { mp?.start(); applySpeed(); playing = true }
            requestFocus(ctx)
            return
        }
        stop()
        currentKey = key; preparing = true
        val app = ctx.applicationContext
        val token = ++playToken
        Thread {
            val f = runCatching { MmsMedia.localFile(app, uri, mime) }.getOrNull()
            main.post { if (token == playToken && currentKey == key) start(ctx, key, uri, f, startFraction, mime, token, useFile = f != null) }
        }.start()
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var playToken = 0

    private fun start(ctx: Context, key: String, uri: Uri, f: File?, startFraction: Float?, mime: String?, token: Int, useFile: Boolean) {
        val p = MediaPlayer()
        mp = p
        fun fallbackOrFail(what: Int, extra: Int) {
            MmsMedia.logPlayError(ctx, "voice", uri, mime, what, extra, f)
            runCatching { p.release() }
            if (mp !== p) return
            mp = null
            if (useFile) start(ctx, key, uri, f, startFraction, mime, token, useFile = false) // second try: straight from the message store
            else { stop(); Toast.makeText(ctx, "Can't play this audio", Toast.LENGTH_SHORT).show() }
        }
        runCatching {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            if (useFile && f != null) p.setDataSource(f.absolutePath) else p.setDataSource(ctx.applicationContext, uri)
            p.setOnPreparedListener {
                if (mp !== p) return@setOnPreparedListener
                preparing = false
                startFraction?.let { fr -> runCatching { p.seekTo((p.duration * fr).toInt()) } }
                runCatching { p.start(); applySpeed() }
                playing = true
                requestFocus(ctx)
                VoicePlayedStore.markPlayed(ctx, key)
            }
            p.setOnCompletionListener { if (mp === p) { playing = false; runCatching { p.seekTo(0) }; currentKey = null; releaseFocus(); runCatching { p.release() }; mp = null } }
            p.setOnErrorListener { _, what, extra -> if (mp === p) fallbackOrFail(what, extra); true }
            p.prepareAsync()
        }.onFailure { e -> MmsLog.err(ctx, "voice play setup", e); fallbackOrFail(-1, -1) }
    }

    fun pause() { runCatching { mp?.pause() }; playing = false; releaseFocus() }

    fun seekTo(f: Float) { mp?.let { p -> runCatching { p.seekTo((p.duration * f.coerceIn(0f, 1f)).toInt()) } } }

    fun stop() {
        playToken++
        val p = mp
        mp = null; playing = false; preparing = false; currentKey = null
        runCatching { p?.stop() }; runCatching { p?.release() }
        releaseFocus()
    }

    @Suppress("DEPRECATION")
    private fun requestFocus(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        focusAm = am
        runCatching { am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) }
    }
    @Suppress("DEPRECATION")
    private fun releaseFocus() { runCatching { focusAm?.abandonAudioFocus(focusListener) } }
    private val focusListener = AudioManager.OnAudioFocusChangeListener { ch -> if (ch == AudioManager.AUDIOFOCUS_LOSS || ch == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) { if (playing) pause() } }
}

// ── Audio bubble ────────────────────────────────────────────────────────────
@Composable
fun VoiceNoteBubble(m: Message, uri: Uri, isIn: Boolean, textColor: Color, subColor: Color, onLongClick: () -> Unit, mime: String? = m.mediaMime) {
    val ctx = LocalContext.current
    val key = msgKey(m)
    val info by produceState(VoiceWave.cached(uri), uri) { if (value == null) value = withContext(Dispatchers.Default) { VoiceWave.load(ctx, uri, mime) } }
    var played by remember(key) { mutableStateOf(!isIn || VoicePlayedStore.isPlayed(ctx, key)) }
    val isCurrent = VoicePlayer.currentKey == key
    val isPlaying = isCurrent && VoicePlayer.playing
    var progress by remember(key) { mutableFloatStateOf(0f) }
    var posMs by remember(key) { mutableLongStateOf(0L) }
    LaunchedEffect(isCurrent, isPlaying) {
        if (isPlaying) played = true
        while (isActive && isCurrent && VoicePlayer.playing) {
            val d = VoicePlayer.duration().takeIf { it > 0 } ?: info?.durationMs ?: 0L
            posMs = VoicePlayer.position()
            progress = if (d > 0) (posMs.toFloat() / d).coerceIn(0f, 1f) else 0f
            delay(40)
        }
        if (!isCurrent) { progress = 0f; posMs = 0L } // finished or another note took over
    }
    val totalMs = info?.durationMs ?: 0L
    val placeholderBars = remember(key) { VoiceWave.pseudo(key.hashCode().toLong()).map { it * 0.4f }.toFloatArray() }
    val accent = if (isIn) Brand else textColor
    val unplayedDot = Color(0xFF1E88E5)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    fun toggle() { if (isPlaying) VoicePlayer.pause() else VoicePlayer.play(ctx, key, uri, mime = mime) }
    fun seek(f: Float) { progress = f; if (isCurrent) VoicePlayer.seekTo(f) else VoicePlayer.play(ctx, key, uri, f, mime) }

    Row(Modifier.width(260.dp).padding(start = 8.dp, end = 10.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        // play / pause
        val onAccent = if (accent.luminance() > 0.5f) Color(0xFF1B1B1F) else Color.White
        Box(Modifier.size(40.dp).clip(CircleShape).background(accent).clickable { toggle() }, contentAlignment = Alignment.Center) {
            if (isCurrent && VoicePlayer.preparing) CircularProgressIndicator(Modifier.size(20.dp), color = onAccent, strokeWidth = 2.dp)
            else androidx.compose.animation.Crossfade(isPlaying, animationSpec = tween(150), label = "playPause") { pl ->
                Icon(if (pl) Ms.PauseFill else Ms.PlayArrowFill, if (pl) "Pause" else "Play", tint = onAccent, modifier = Modifier.size(26.dp))
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            val bars = info?.bars ?: placeholderBars
            val playedColor = accent
            val restColor = subColor.copy(alpha = 0.55f)
            val thumbColor = if (!played && isIn) unplayedDot else accent
            Canvas(
                Modifier.fillMaxWidth().height(34.dp)
                    .pointerInput(key) {
                        detectTapGestures(onTap = { o -> val f = (o.x / size.width).let { if (rtl) 1f - it else it }; seek(f.coerceIn(0f, 1f)) }, onLongPress = { onLongClick() })
                    }
                    .pointerInput(key) {
                        detectHorizontalDragGestures(
                            onDragStart = { o -> val f = (o.x / size.width).let { if (rtl) 1f - it else it }; progress = f.coerceIn(0f, 1f) },
                            onDragEnd = { seek(progress) },
                            onHorizontalDrag = { ch, dx -> ch.consume(); progress = (progress + (if (rtl) -dx else dx) / size.width).coerceIn(0f, 1f) }
                        )
                    }
            ) {
                val n = bars.size
                val gap = 2.dp.toPx()
                val bw = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
                val mid = size.height / 2f
                for (i in 0 until n) {
                    val h = max(3.dp.toPx(), bars[i] * (size.height - 8.dp.toPx()))
                    val xL = i * (bw + gap)
                    val x = if (rtl) size.width - xL - bw else xL
                    val done = (i + 0.5f) / n <= progress
                    drawRoundRect(if (done) playedColor else restColor, Offset(x, mid - h / 2f), Size(bw, h), CornerRadius(bw / 2f, bw / 2f))
                }
                val tx = (progress * size.width).let { if (rtl) size.width - it else it }.coerceIn(5.dp.toPx(), size.width - 5.dp.toPx())
                drawCircle(thumbColor, 6.dp.toPx(), Offset(tx, mid))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (isCurrent || posMs > 0) fmtVoice(posMs) else if (totalMs > 0) fmtVoice(totalMs) else " ", color = subColor, fontSize = 11.sp)
                if (!played && isIn) { Spacer(Modifier.width(5.dp)); Box(Modifier.size(7.dp).clip(CircleShape).background(unplayedDot)) }
            }
        }
        // speed, like Google Messages: always there, one tap cycles 1× / 1.5× / 2×
        Box(Modifier.padding(start = 2.dp).clip(RoundedCornerShape(50)).background(accent.copy(alpha = 0.14f)).clickable { VoicePlayer.cycleSpeed(ctx) }.padding(horizontal = 9.dp, vertical = 5.dp)) {
            Text(when (VoicePlayer.speed) { 1.5f -> "1.5×"; 2f -> "2×"; else -> "1×" }, color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ── Recording ───────────────────────────────────────────────────────────────
enum class RecMode { IDLE, HOLDING, LOCKED }

/** Hold-to-record state for one chat: AAC in m4a, mono, ~20 kbps so notes fit in an MMS. */
class VoiceRecState(private val ctx: Context) {
    var mode by mutableStateOf(RecMode.IDLE)
    var elapsedMs by mutableLongStateOf(0L)
    var dragX by mutableFloatStateOf(0f)   // toward the cancel side, in px (always <= 0)
    var dragY by mutableFloatStateOf(0f)   // upward, in px (always <= 0)
    val levels: SnapshotStateList<Float> = mutableStateListOf()
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    var onLimit: (() -> Unit)? = null

    fun hasPermission() = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start(): Boolean {
        if (mode != RecMode.IDLE) return true
        VoicePlayer.stop()
        val f = File(ctx.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        return runCatching {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(20000)
            r.setMaxFileSize(900_000L) // ~6 min; stays under common MMS limits
            r.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) onLimit?.invoke() }
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r; file = f; startedAt = System.currentTimeMillis()
            elapsedMs = 0L; levels.clear(); dragX = 0f; dragY = 0f
            mode = RecMode.HOLDING
            vibrate()
            true
        }.getOrElse {
            runCatching { r.release() }; f.delete()
            Toast.makeText(ctx, "Can't use the microphone right now", Toast.LENGTH_SHORT).show()
            false
        }
    }

    /** Called every ~80 ms while recording: timer and live waveform. */
    fun tick() {
        val r = recorder ?: return
        elapsedMs = System.currentTimeMillis() - startedAt
        val amp = runCatching { r.maxAmplitude }.getOrDefault(0)
        val v = (sqrt(amp / 32767f)).coerceIn(0.06f, 1f)
        levels.add(v)
        if (levels.size > 400) levels.removeAt(0)
    }

    /** Stops; returns the file when it's worth sending (at least ~0.7 s). */
    fun finish(keep: Boolean): File? {
        val r = recorder; val f = file
        recorder = null; file = null
        val len = System.currentTimeMillis() - startedAt
        mode = RecMode.IDLE; dragX = 0f; dragY = 0f
        val stopped = runCatching { r?.stop() }.isSuccess
        runCatching { r?.release() }
        if (keep && stopped && f != null && f.exists() && f.length() > 0 && len >= 700) return f
        f?.delete()
        return null
    }

    private fun vibrate() {
        runCatching {
            val v = if (Build.VERSION.SDK_INT >= 31) (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
                    else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") v.vibrate(35)
        }
    }
}

/** What shows in place of the text field while recording. */
@Composable
fun RecordingBar(rec: VoiceRecState, onTrash: () -> Unit, modifier: Modifier = Modifier) {
    val blink by rememberInfiniteTransition(label = "rec").animateFloat(1f, 0.15f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "dot")
    Row(modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp)).background(SearchBg).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (rec.mode == RecMode.LOCKED) {
            IconButton(onClick = onTrash, Modifier.size(44.dp)) { Icon(Ms.Delete, "Delete recording", tint = Red, modifier = Modifier.size(24.dp)) }
        } else Spacer(Modifier.width(10.dp))
        Icon(Ms.MicFill, null, tint = Color(0xFFE53935), modifier = Modifier.size(22.dp).alpha(blink))
        Spacer(Modifier.width(6.dp))
        Text(fmtVoice(rec.elapsedMs), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(10.dp))
        if (rec.mode == RecMode.LOCKED) {
            LiveWave(rec.levels, Modifier.weight(1f).height(30.dp).padding(end = 10.dp))
        } else {
            val d = LocalDensity.current
            val pull = with(d) { (-rec.dragX).toDp() }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Row(Modifier.offset(x = -pull).alpha((1f - pull.value / 140f).coerceIn(0.15f, 1f)), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ms.ChevronLeft, null, tint = TextHint, modifier = Modifier.size(20.dp))
                    Text("Slide to cancel", color = TextHint, fontSize = 14.sp)
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
}

@Composable
fun LiveWave(levels: List<Float>, modifier: Modifier) {
    val color = Brand
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier) {
        val bw = 3.dp.toPx(); val gap = 2.dp.toPx()
        val n = ((size.width + gap) / (bw + gap)).toInt().coerceAtLeast(1)
        val shown = levels.takeLast(n)
        val mid = size.height / 2f
        // newest bar sits next to the timer's far side, older ones scroll away (mirrors in RTL)
        shown.forEachIndexed { i, v ->
            val h = max(2.dp.toPx(), v * size.height)
            val xL = (n - shown.size + i) * (bw + gap)
            val x = if (rtl) size.width - xL - bw else xL
            drawRoundRect(color, Offset(x, mid - h / 2f), Size(bw, h), CornerRadius(bw / 2f, bw / 2f))
        }
    }
}

/**
 * The round button at the end of the compose bar: Send when there's text, otherwise the mic.
 * Hold the mic to record, slide toward the start to cancel, slide up to lock; release sends.
 */
@Composable
fun MicSendButton(
    hasContent: Boolean,
    rec: VoiceRecState,
    onSendText: () -> Unit,
    onNeedPermission: () -> Unit,
    onVoiceDone: (File) -> Unit,
    onLongSend: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val longSend by rememberUpdatedState(onLongSend)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val cancelPx = with(density) { 110.dp.toPx() }
    val lockPx = with(density) { 90.dp.toPx() }
    val hasC by rememberUpdatedState(hasContent)
    val sendText by rememberUpdatedState(onSendText)
    val needPerm by rememberUpdatedState(onNeedPermission)
    val done by rememberUpdatedState(onVoiceDone)
    LaunchedEffect(rec.mode != RecMode.IDLE) { while (rec.mode != RecMode.IDLE) { rec.tick(); delay(80) } }
    val holding = rec.mode == RecMode.HOLDING
    val showSend = hasContent || rec.mode == RecMode.LOCKED
    Box(contentAlignment = Alignment.BottomCenter) {
        if (holding) {
            // lock hint above the mic, rises with the finger
            val up = with(density) { (-rec.dragY).toDp() }
            Column(Modifier.offset(y = -(64.dp) - up / 3).clip(RoundedCornerShape(20.dp)).background(SearchBg).padding(horizontal = 8.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (up > 60.dp) Ms.LockFill else Ms.LockOpen, "Slide up to lock", tint = TextSecond, modifier = Modifier.size(20.dp))
                Icon(Ms.KeyboardArrowUp, null, tint = TextHint, modifier = Modifier.size(18.dp))
            }
        }
        Box(
            Modifier
                .size(52.dp)
                .pointerInput(rtl) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (hasC) { // text waiting: a normal tap sends it; press and hold = schedule send (like Google Messages)
                            val ls = longSend
                            if (ls == null) {
                                val up = waitForUpOrCancellation(down.id)
                                if (up) sendText()
                                return@awaitEachGesture
                            }
                            var lifted: Boolean? = null
                            val inTime = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { lifted = waitForUpOrCancellation(down.id); true }
                            if (inTime == true) { if (lifted == true) sendText(); return@awaitEachGesture }
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            ls()
                            waitForUpOrCancellation(down.id)  // swallow the release
                            return@awaitEachGesture
                        }
                        if (rec.mode == RecMode.LOCKED) { // locked: this button is Send
                            val up = waitForUpOrCancellation(down.id)
                            if (up) rec.finish(true)?.let { done(it) }
                            return@awaitEachGesture
                        }
                        if (!rec.hasPermission()) { needPerm(); return@awaitEachGesture }
                        val t0 = System.currentTimeMillis()
                        if (!rec.start()) return@awaitEachGesture
                        down.consume()
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: ev.changes.first()
                            if (!ch.pressed) {
                                ch.consume()
                                if (rec.mode == RecMode.HOLDING) {
                                    val f = rec.finish(true)
                                    if (f != null) done(f)
                                }
                                break
                            }
                            ch.consume()
                            val dx = (ch.position.x - down.position.x).let { if (rtl) -it else it } // toward start = negative
                            val dy = ch.position.y - down.position.y
                            rec.dragX = min(0f, dx); rec.dragY = min(0f, dy)
                            if (dx < -cancelPx) {
                                rec.finish(false)
                                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                break
                            }
                            if (dy < -lockPx && abs(dx) < cancelPx / 2) {
                                rec.mode = RecMode.LOCKED; rec.dragX = 0f; rec.dragY = 0f
                                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                break
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // only the drawing follows the finger; the touch area stays put so the drag math stays simple
            val btnScale by androidx.compose.animation.core.animateFloatAsState(if (holding) 1.4f else 1f,
                androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 500f), label = "micScale")
            val btnBg by androidx.compose.animation.animateColorAsState(if (showSend || holding) Brand else SearchBg, tween(180), label = "micBg")
            val btnFg by androidx.compose.animation.animateColorAsState(if (showSend || holding) OnBrand else TextPrimary, tween(180), label = "micFg")
            Box(
                Modifier
                    .offset { if (holding) IntOffset((rec.dragX * 0.5f).roundToInt(), (rec.dragY * 0.5f).roundToInt()) else IntOffset.Zero } // offset{} mirrors itself in RTL
                    .scale(btnScale)
                    .fillMaxSize().clip(CircleShape)
                    .background(btnBg),
                contentAlignment = Alignment.Center
            ) {
                // Mic ⇄ Send swap with a quick scale + fade, like WhatsApp / Google Messages
                androidx.compose.animation.AnimatedContent(showSend, transitionSpec = {
                    (androidx.compose.animation.scaleIn(tween(160), initialScale = 0.6f) + androidx.compose.animation.fadeIn(tween(160))) togetherWith
                        (androidx.compose.animation.scaleOut(tween(120), targetScale = 0.6f) + androidx.compose.animation.fadeOut(tween(120)))
                }, label = "micIcon") { send ->
                    Icon(if (send) Ms.SendFill else Ms.Mic,
                        if (send) "Send" else "Voice note (hold to record)",
                        tint = btnFg,
                        modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

/** Waits for [id] to lift; true if it lifted (a tap), false if the gesture was cancelled. */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.waitForUpOrCancellation(id: androidx.compose.ui.input.pointer.PointerId): Boolean {
    while (true) {
        val ev = awaitPointerEvent()
        val ch = ev.changes.firstOrNull { it.id == id } ?: return false
        if (!ch.pressed) { ch.consume(); return true }
        if (ch.isConsumed) return false
    }
}
