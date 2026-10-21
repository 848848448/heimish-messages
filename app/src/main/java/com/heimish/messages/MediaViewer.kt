package com.heimish.messages

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Copies a photo into the app cache so it can be shared or forwarded even if the message goes away. */
private fun cacheCopy(ctx: Context, uri: Uri): java.io.File? = runCatching {
    val f = java.io.File(ctx.cacheDir, "photo_" + System.currentTimeMillis() + ".jpg")
    ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
    f
}.getOrNull()

internal fun savePhoto(ctx: Context, uri: Uri) {
    runCatching {
        val v = ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "Message_" + System.currentTimeMillis() + ".jpg")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (android.os.Build.VERSION.SDK_INT >= 29) put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Message")
        }
        val out = ctx.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)!!
        ctx.contentResolver.openInputStream(uri)!!.use { i -> ctx.contentResolver.openOutputStream(out)!!.use { o -> i.copyTo(o) } }
        Toast.makeText(ctx, "Saved to Pictures", Toast.LENGTH_SHORT).show()
    }.onFailure { Toast.makeText(ctx, "Could not save", Toast.LENGTH_SHORT).show() }
}

internal fun sharePhoto(ctx: Context, uri: Uri) {
    runCatching {
        val f = cacheCopy(ctx, uri)!!
        val u = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"))
    }.onFailure { Toast.makeText(ctx, "Could not share", Toast.LENGTH_SHORT).show() }
}

/** Forward inside the app: the photo waits while you pick who gets it (the same path as sharing into the app). */
internal fun forwardPhoto(ctx: Context, uri: Uri): Boolean {
    val f = cacheCopy(ctx, uri) ?: run { Toast.makeText(ctx, "Could not forward", Toast.LENGTH_SHORT).show(); return false }
    IntentRouter.pending = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, Uri.fromFile(f))
    return true
}

/**
 * Full-screen photo, Google Messages style, with obvious ways out:
 * a back arrow at the top (always on a dark strip so it shows on any photo), the phone's Back button,
 * swipe the photo down (it shrinks and fades), and a hint the first few times.
 * Tap shows / hides the bars; double-tap or pinch zooms. Top bar: Forward, Share, Save (and Delete in ⋮).
 */
@Composable
fun PhotoViewer(uri: Uri, onClose: () -> Unit, title: String = "", date: Long = 0L, reactKey: String? = null, onReacted: () -> Unit = {},
                onDelete: (() -> Unit)? = null, showForward: Boolean = true) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val white = Color.White
    var scale by remember { mutableFloatStateOf(1f) }
    var off by remember { mutableStateOf(Offset.Zero) }
    val drag = remember { Animatable(0f) }
    var chrome by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var reaction by remember { mutableStateOf(reactKey?.let { ReactionStore.get(ctx, it) }) }
    val prefs = remember { ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE) }
    // "How do I get out?" — say it plainly the first few times the viewer opens
    var hint by remember { mutableStateOf(prefs.getInt("viewer_hint_count", 0) < 4) }
    LaunchedEffect(Unit) { if (hint) { prefs.edit().putInt("viewer_hint_count", prefs.getInt("viewer_hint_count", 0) + 1).apply(); delay(4500); hint = false } }
    val density = LocalDensity.current
    val dismissPx = with(density) { 140.dp.toPx() }
    val slop = LocalViewConfiguration.current.touchSlop
    val progress = (abs(drag.value) / (dismissPx * 2.2f)).coerceIn(0f, 1f)

    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { if (menu) menu = false else onClose() }
        // Some phones report no status-bar inset inside a dialog; never let the top bar slide under the clock
        val sb = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val topPad = if (sb > 0.dp) sb else 24.dp
        Box(Modifier.fillMaxSize().background(Color(0xFF111214).copy(alpha = 1f - progress))) {
            if (android.os.Build.VERSION.SDK_INT >= 31) AsyncImage(uri, null, Modifier.fillMaxSize().blur(60.dp).graphicsLayer(alpha = .45f * (1f - progress)), contentScale = ContentScale.Crop)
            AsyncImage(uri, "Photo", Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { chrome = !chrome; hint = false },
                        onDoubleTap = { p -> if (scale > 1f) { scale = 1f; off = Offset.Zero } else { scale = 2.5f; off = (Offset(size.width / 2f, size.height / 2f) - p) * 1.5f } },
                    )
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var dragging = false; var total = Offset.Zero
                        do {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.count { it.pressed }
                            if (pressed >= 2 || scale > 1f) {
                                // pinch / pan a zoomed photo
                                val z = ev.calculateZoom(); val p = ev.calculatePan()
                                if (z != 1f || p != Offset.Zero) {
                                    scale = (scale * z).coerceIn(1f, 6f)
                                    off = if (scale <= 1f) Offset.Zero else off + p
                                    ev.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                                }
                            } else if (pressed == 1) {
                                // one finger on an unzoomed photo: swipe down (or up) to close
                                val c = ev.changes.first { it.pressed }
                                total += c.positionChange()
                                if (!dragging && abs(total.y) > slop && abs(total.y) > abs(total.x)) dragging = true
                                if (dragging) { scope.launch { drag.snapTo(drag.value + c.positionChange().y) }; c.consume() }
                            }
                        } while (ev.changes.any { it.pressed })
                        if (dragging) {
                            if (abs(drag.value) > dismissPx) onClose()
                            else scope.launch { drag.animateTo(0f, tween(180)) }
                        }
                    }
                }
                .graphicsLayer {
                    val s = scale * (1f - progress * .35f)
                    scaleX = s; scaleY = s; translationX = off.x; translationY = off.y + drag.value
                },
                contentScale = ContentScale.Fit)

            // Top bar: dark gradient so the back arrow always shows, even on a white photo
            AnimatedVisibility(chrome && progress < .05f, Modifier.align(Alignment.TopCenter), enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
                Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(.72f), Color.Black.copy(.35f), Color.Transparent)))
                    .padding(top = topPad).padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose, Modifier.size(48.dp)) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(.35f)), contentAlignment = Alignment.Center) {
                            Icon(Ms.ArrowBack, "Close photo", tint = white, modifier = Modifier.size(24.dp))
                        }
                    }
                    Column(Modifier.weight(1f).padding(start = 6.dp)) {
                        if (title.isNotEmpty()) Text(title, color = white, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = LocalTextStyle.current.copy(textDirection = TextDirection.Content))
                        if (date > 0) Text(java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(date)), color = white.copy(.75f), fontSize = 12.sp, maxLines = 1)
                    }
                    if (showForward) IconButton({ if (forwardPhoto(ctx, uri)) onClose() }) { Icon(Ms.Forward, "Forward", tint = white) }
                    IconButton({ sharePhoto(ctx, uri) }) { Icon(Ms.Share, "Share", tint = white) }
                    IconButton({ savePhoto(ctx, uri) }) { Icon(Ms.Download, "Save", tint = white) }
                    if (onDelete != null) Box {
                        IconButton({ menu = true }) { Icon(Ms.MoreVert, "More", tint = white) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Delete") }, { menu = false; onDelete(); onClose() }, leadingIcon = { Icon(Ms.Delete, null) })
                        }
                    }
                }
            }
            if (chrome && progress < .05f && reactKey != null) Row(Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.55f))))
                .navigationBarsPadding().padding(horizontal = 12.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                QuickReactions.forEach { e ->
                    Box(Modifier.size(46.dp).clip(CircleShape).background(if (reaction == e) white.copy(.25f) else Color.Transparent)
                        .clickable { val nv = if (reaction == e) null else e; ReactionStore.set(ctx, reactKey, nv); reaction = nv; onReacted() }, contentAlignment = Alignment.Center) { Text(e, fontSize = 26.sp) }
                }
            }
            // Bars hidden: a small back arrow stays in the corner so there's always a way out
            if (!chrome && progress < .05f) Box(Modifier.align(Alignment.TopStart).padding(top = topPad).padding(8.dp).size(40.dp).clip(CircleShape)
                .background(Color.Black.copy(.45f)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Ms.ArrowBack, "Close photo", tint = white, modifier = Modifier.size(22.dp))
            }
            AnimatedVisibility(hint, Modifier.align(Alignment.Center).padding(top = 260.dp), enter = fadeIn(), exit = fadeOut()) {
                Row(Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(.7f)).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ms.ArrowBack, null, tint = white, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text("Tap the arrow or swipe down to close", color = white, fontSize = 14.sp)
                }
            }
        }
    }
}

fun Modifier.androidxPointer(onGesture: (Float, Offset) -> Unit): Modifier =
    this.pointerInput(Unit) { detectTransformGestures { _, pan, zoom, _ -> onGesture(zoom, pan) } }
