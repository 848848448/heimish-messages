package com.heimish.messages

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Audio/video parts of a message. Phones label voice notes as video/3gpp and videos as octet-stream; the file itself
 *  decides: a video track gets the video tile, sound only gets the voice-note player. */
@Composable
fun AvMediaBubble(m: Message, uri: Uri, mime: String, isIn: Boolean, textColor: Color, subColor: Color, shape: Shape, onLongClick: () -> Unit) {
    val ctx = LocalContext.current
    val ambiguous = mime.startsWith("video/") || mime == "audio/3gpp" || mime == "audio/mp4" || mime == "audio/3gpp2"
    if (!ambiguous) { VoiceNoteBubble(m, uri, isIn, textColor, subColor, onLongClick, mime); return }
    val probe by produceState(MmsMedia.cachedProbe(uri), uri) { if (value == null) value = withContext(Dispatchers.IO) { MmsMedia.probe(ctx, uri, mime) } }
    val p = probe
    val isVideo = when {
        p == null -> mime.startsWith("video/")
        p.hasVideo -> true
        p.hasAudio -> false
        else -> mime.startsWith("video/")
    }
    if (isVideo) VideoTile(uri, mime, p, shape, onLongClick)
    else VoiceNoteBubble(m, uri, isIn, textColor, subColor, onLongClick, if (mime.startsWith("video/")) "audio/" + mime.substringAfter('/') else mime)
}

/** Google Messages style video: the first frame, a round play button, the length in a corner. Opens the in-app player. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoTile(uri: Uri, mime: String, probe: MmsMedia.Probe?, shape: Shape, onLongClick: () -> Unit) {
    val ctx = LocalContext.current
    val thumb by produceState<Bitmap?>(null, uri) { value = withContext(Dispatchers.IO) { runCatching { MmsMedia.thumbnail(ctx, uri, mime) }.getOrNull() } }
    var open by remember { mutableStateOf(false) }
    val ratio = probe?.takeIf { it.width > 0 && it.height > 0 }?.let { (it.width.toFloat() / it.height).coerceIn(0.6f, 1.78f) }
        ?: thumb?.let { (it.width.toFloat() / it.height.coerceAtLeast(1)).coerceIn(0.6f, 1.78f) } ?: 1.33f
    Box(
        Modifier.width(250.dp).aspectRatio(ratio).clip(shape).background(Color(0xFF1B1B1F))
            .combinedClickable(onClick = { VoicePlayer.stop(); open = true }, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Box(Modifier.size(54.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
            Icon(Ms.PlayArrowFill, "Play", tint = Color.White, modifier = Modifier.size(34.dp))
        }
        val d = probe?.durationMs ?: 0L
        if (d > 0) Row(
            Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Ms.Videocam, null, tint = Color.White, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(fmtVoice(d), color = Color.White, fontSize = 11.sp)
        }
    }
    if (open) VideoPlayerDialog(uri, mime) { open = false }
}

/** Full-screen in-app video player (the platform player handles mp4 and 3gpp, H.263/H.264, AMR/AAC sound). */
@Composable
fun VideoPlayerDialog(uri: Uri, mime: String?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var resolved by remember(uri) { mutableStateOf(false) }
    var file by remember(uri) { mutableStateOf<File?>(null) }
    LaunchedEffect(uri) { file = withContext(Dispatchers.IO) { runCatching { MmsMedia.localFile(ctx, uri, mime) }.getOrNull() }; resolved = true }
    var view by remember { mutableStateOf<VideoView?>(null) }
    var playing by remember { mutableStateOf(false) }
    var prepared by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(playing) { while (playing) { view?.let { v -> runCatching { pos = v.currentPosition.toLong() } }; delay(200) } }
    LaunchedEffect(playing, controls) { if (playing && controls) { delay(2500); controls = false } }
    DisposableEffect(Unit) { onDispose { runCatching { view?.stopPlayback() } } }
    androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { controls = !controls }) {
            if (resolved) AndroidView(
                factory = { c ->
                    VideoView(c).apply {
                        var fromFile = file != null
                        setOnPreparedListener { mp -> prepared = true; dur = mp.duration.toLong().coerceAtLeast(0L); start(); playing = true }
                        setOnCompletionListener { playing = false; controls = true; pos = dur }
                        setOnErrorListener { _, what, extra ->
                            MmsMedia.logPlayError(ctx, "video", uri, mime, what, extra, file)
                            if (fromFile) { fromFile = false; setVideoURI(uri) } // second try: straight from the message store
                            else { playing = false; Toast.makeText(ctx, "Can't play this video", Toast.LENGTH_SHORT).show() }
                            true
                        }
                        val f = file
                        if (f != null) setVideoPath(f.absolutePath) else setVideoURI(uri)
                        view = this
                    }
                },
                modifier = Modifier.align(Alignment.Center).fillMaxWidth()
            )
            if (!prepared) CircularProgressIndicator(Modifier.align(Alignment.Center).size(40.dp), color = Color.White, strokeWidth = 3.dp)
            if (controls || !playing) {
                Box(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp).size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).clickable { onClose() }, contentAlignment = Alignment.Center) {
                    Icon(Ms.Close, "Close", tint = Color.White, modifier = Modifier.size(24.dp))
                }
                if (prepared) Box(Modifier.align(Alignment.Center).size(68.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable {
                    val v = view ?: return@clickable
                    if (playing) { v.pause(); playing = false } else { if (dur > 0 && pos >= dur - 300) v.seekTo(0); v.start(); playing = true }
                }, contentAlignment = Alignment.Center) {
                    Icon(if (playing) Ms.PauseFill else Ms.PlayArrowFill, if (playing) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(40.dp))
                }
                if (prepared && dur > 0) Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(fmtVoice(dragging?.let { (it * dur).toLong() } ?: pos), color = Color.White, fontSize = 12.sp)
                    Slider(
                        value = dragging ?: (pos.toFloat() / dur).coerceIn(0f, 1f),
                        onValueChange = { dragging = it; controls = true },
                        onValueChangeFinished = { dragging?.let { f -> val t = (f * dur).toLong(); view?.seekTo(t.toInt()); pos = t }; dragging = null },
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f))
                    )
                    Text(fmtVoice(dur), color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}

/** "אברהם, מרדכי, Joe" — first names of a group's members (the number when there's no contact). */
fun groupFirstNames(ctx: android.content.Context, members: List<String>): String =
    members.joinToString(", ") { a ->
        val n = runCatching { SmsRepository.getContactName(ctx, a) }.getOrNull()?.trim().orEmpty()
        if (n.isEmpty()) a else n.split(' ', '\u00A0').firstOrNull { it.isNotBlank() } ?: n
    }

/** Google Messages style group picture: two overlapping member photos, or up to four in a 2×2 grid. */
@Composable
fun GroupAvatar(members: List<String>, size: androidx.compose.ui.unit.Dp) {
    val ctx = LocalContext.current
    val shown = members.take(4)
    fun nameOf(a: String) = runCatching { SmsRepository.getContactName(ctx, a) }.getOrNull() ?: a
    Box(Modifier.size(size)) {
        if (shown.size == 2) {
            val s = size * 0.68f
            Box(Modifier.align(Alignment.TopStart)) { Avatar(nameOf(shown[0]), shown[0], s, 11.sp, tapToView = false) }
            Box(Modifier.align(Alignment.BottomEnd).clip(CircleShape).background(Chrome).padding(1.5.dp)) { Avatar(nameOf(shown[1]), shown[1], s - 3.dp, 11.sp, tapToView = false) }
        } else {
            val s = size / 2 - 1.dp
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                for (row in 0 until 2) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (col in 0 until 2) {
                        val a = shown.getOrNull(row * 2 + col)
                        if (a != null) Avatar(nameOf(a), a, s, 9.sp, tapToView = false) else Spacer(Modifier.size(s))
                    }
                }
            }
        }
    }
}
