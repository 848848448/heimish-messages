package com.heimish.messages

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.LayoutInflater
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/** True when a viewer item is a video (a voice note labelled video/3gpp is left out by the caller using the probe). */
internal val ViewerItem.isVideo: Boolean get() = mime?.startsWith("video/") == true

/**
 * One video page of the full-screen media viewer. The real aspect ratio is kept (fit inside the screen, black around
 * it; the clip's rotation and pixel aspect are applied by the player view), the player is created only when the page is
 * shown and pauses as soon as it's swiped away. [controls] (toggled by a tap on the page) shows the play/pause button and
 * the seek bar with the times. [layer] carries the swipe-down transform.
 */
@Composable
fun VideoPage(item: ViewerItem, isCur: Boolean, controls: Boolean, bottomInset: Dp, layer: Modifier) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val thumb by produceState<Bitmap?>(null, item.uri) { value = withContext(Dispatchers.IO) { runCatching { MmsMedia.thumbnail(app, item.uri, item.mime) }.getOrNull() } }
    var file by remember(item.uri) { mutableStateOf<File?>(null) }
    var resolved by remember(item.uri) { mutableStateOf(false) }
    LaunchedEffect(item.uri) { file = withContext(Dispatchers.IO) { runCatching { MmsMedia.localFile(app, item.uri, item.mime) }.getOrNull() }; resolved = true }
    val holder = remember(item.uri) { arrayOfNulls<ExoPlayer>(1) }
    var player by remember(item.uri) { mutableStateOf<ExoPlayer?>(null) }
    var playing by remember(item.uri) { mutableStateOf(false) }
    var ended by remember(item.uri) { mutableStateOf(false) }
    var ready by remember(item.uri) { mutableStateOf(false) }
    var firstFrame by remember(item.uri) { mutableStateOf(false) }
    var failed by remember(item.uri) { mutableStateOf(false) }
    var pos by remember(item.uri) { mutableLongStateOf(0L) }
    var dur by remember(item.uri) { mutableLongStateOf(0L) }
    var scrub by remember(item.uri) { mutableStateOf<Float?>(null) }

    fun build(src: Uri, triedContent: Boolean) {
        holder[0]?.release()
        val p = ExoPlayer.Builder(app).build()
        holder[0] = p; player = p
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying; if (isPlaying) VoicePlayer.stop() }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) { ready = true; dur = p.duration.coerceAtLeast(0L) }
                ended = state == Player.STATE_ENDED
            }
            override fun onRenderedFirstFrame() { firstFrame = true }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                MmsLog.log(app, "video size ${videoSize.width}x${videoSize.height} rot=${videoSize.unappliedRotationDegrees} par=${videoSize.pixelWidthHeightRatio} (${item.mime})")
            }
            override fun onPlayerError(error: PlaybackException) {
                MmsLog.log(app, "ERROR video play failed ${error.errorCodeName} (${error.errorCode}) ${error.message} mime=${item.mime} src=$src head=${MmsMedia.headHex(file)}")
                if (!triedContent && src.scheme == "file") { build(item.uri, true); return } // second try: straight from the message store
                failed = true
                Toast.makeText(ctx, "Can't play this video", Toast.LENGTH_SHORT).show()
            }
        })
        p.setMediaItem(MediaItem.fromUri(src))
        p.prepare()
        p.playWhenReady = true
        MmsLog.log(app, "video open ${item.mime} src=${if (src.scheme == "file") "file ${file?.name} ${file?.length()} bytes" else src.toString()}")
    }

    LaunchedEffect(isCur, resolved) {
        if (!isCur) { holder[0]?.pause(); return@LaunchedEffect }
        if (!resolved) return@LaunchedEffect
        if (holder[0] == null && !failed) build(file?.let { Uri.fromFile(it) } ?: item.uri, file == null)
    }
    DisposableEffect(item.uri) { onDispose { holder[0]?.release(); holder[0] = null } }
    LaunchedEffect(player, isCur) {
        while (isActive && isCur) {
            holder[0]?.let { p -> if (scrub == null) pos = p.currentPosition.coerceAtLeast(0L); if (p.duration > 0) dur = p.duration }
            delay(200)
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(layer.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            if (player != null) AndroidView(
                factory = { c -> (LayoutInflater.from(c).inflate(R.layout.video_surface, null, false) as PlayerView).apply { isClickable = false; isFocusable = false } },
                update = { v -> if (v.player !== player) v.player = player },
                onRelease = { v -> v.player = null },
                modifier = Modifier.fillMaxSize()
            )
            // the first frame until the video draws its own (and on pages next to the shown one)
            if (!firstFrame) thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
        if (isCur && player != null && !ready && !failed) CircularProgressIndicator(Modifier.size(44.dp), color = Color.White, strokeWidth = 3.dp)
        val showCenter = controls || !playing
        if (showCenter && (ready || player == null)) Box(
            Modifier.size(68.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable {
                val p = holder[0]
                if (p == null) { failed = false; build(file?.let { Uri.fromFile(it) } ?: item.uri, file == null); return@clickable }
                if (p.isPlaying) p.pause() else { if (ended || p.playbackState == Player.STATE_ENDED) p.seekTo(0); p.play() }
            },
            contentAlignment = Alignment.Center
        ) { Icon(if (playing) Ms.PauseFill else Ms.PlayArrowFill, if (playing) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(42.dp)) }
        if (controls && dur > 0) Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = bottomInset).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val shown = scrub?.let { (it * dur).toLong() } ?: pos
            Text(fmtVoice(shown), color = Color.White, fontSize = 13.sp)
            Slider(
                value = scrub ?: (if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f),
                onValueChange = { scrub = it },
                onValueChangeFinished = { scrub?.let { f -> holder[0]?.seekTo((f * dur).toLong()); pos = (f * dur).toLong() }; scrub = null },
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f))
            )
            Text(fmtVoice(dur), color = Color.White, fontSize = 13.sp)
        }
    }
}

/** Saves a received photo or video to the phone's gallery (Pictures/Message or Movies/Message). */
internal fun saveMedia(ctx: Context, item: ViewerItem) {
    if (!item.isVideo) { savePhoto(ctx, item.uri); return }
    runCatching {
        val f = MmsMedia.localFile(ctx, item.uri, item.mime)
        val ext = f?.extension?.takeIf { it.isNotBlank() && it != "bin" } ?: MmsMedia.extFor(item.mime ?: "video/mp4")
        val mime = when (ext) { "3gp" -> "video/3gpp"; "mov" -> "video/quicktime"; "3g2" -> "video/3gpp2"; else -> "video/mp4" }
        val v = ContentValues().apply {
            put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, "Message_" + System.currentTimeMillis() + "." + ext)
            put(android.provider.MediaStore.Video.Media.MIME_TYPE, mime)
            if (android.os.Build.VERSION.SDK_INT >= 29) put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/Message")
        }
        val out = ctx.contentResolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)!!
        (f?.inputStream() ?: ctx.contentResolver.openInputStream(item.uri)!!).use { i -> ctx.contentResolver.openOutputStream(out)!!.use { o -> i.copyTo(o) } }
        Toast.makeText(ctx, "Saved to Movies", Toast.LENGTH_SHORT).show()
    }.onFailure { Toast.makeText(ctx, "Could not save", Toast.LENGTH_SHORT).show() }
}

/** Shares a photo or video with another app. */
internal fun shareMedia(ctx: Context, item: ViewerItem) {
    if (!item.isVideo) { sharePhoto(ctx, item.uri); return }
    runCatching {
        val src = MmsMedia.localFile(ctx, item.uri, item.mime)!!
        val f = File(ctx.cacheDir, "share_" + System.currentTimeMillis() + "." + src.extension.ifBlank { "mp4" })
        src.copyTo(f, overwrite = true)
        val u = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(item.mime ?: "video/*").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"))
    }.onFailure { Toast.makeText(ctx, "Could not share", Toast.LENGTH_SHORT).show() }
}
