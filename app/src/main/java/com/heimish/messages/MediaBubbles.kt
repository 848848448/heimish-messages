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
    if (isVideo) VideoTile(uri, mime, p, shape, onLongClick, m)
    else VoiceNoteBubble(m, uri, isIn, textColor, subColor, onLongClick, if (mime.startsWith("video/")) "audio/" + mime.substringAfter('/') else mime)
}

/** Google Messages style video: the first frame, a round play button, the length in a corner. Opens the in-app player. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoTile(uri: Uri, mime: String, probe: MmsMedia.Probe?, shape: Shape, onLongClick: () -> Unit, m: Message? = null) {
    val ctx = LocalContext.current
    val opener = LocalPhotoOpener.current
    val thumb by produceState<Bitmap?>(null, uri) { value = withContext(Dispatchers.IO) { runCatching { MmsMedia.thumbnail(ctx, uri, mime) }.getOrNull() } }
    var open by remember { mutableStateOf(false) }
    val ratio = probe?.takeIf { it.width > 0 && it.height > 0 }?.let { (it.width.toFloat() / it.height).coerceIn(0.6f, 1.78f) }
        ?: thumb?.let { (it.width.toFloat() / it.height.coerceAtLeast(1)).coerceIn(0.6f, 1.78f) } ?: 1.33f
    Box(
        Modifier.width(250.dp).aspectRatio(ratio).clip(shape).background(Color(0xFF1B1B1F))
            .combinedClickable(onClick = { VoicePlayer.stop(); if (opener != null && m != null) opener(m) else open = true }, onLongClick = onLongClick),
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

/** Full-screen in-app video player outside a chat: the same media viewer as photos, with one page. */
@Composable
fun VideoPlayerDialog(uri: Uri, mime: String?, onClose: () -> Unit) {
    val items = remember(uri, mime) { listOf(ViewerItem(uri, mime = mime?.takeIf { it.startsWith("video/") } ?: "video/mp4")) }
    PhotoViewer(items, 0, onClose, showForward = false)
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
