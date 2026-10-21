package com.heimish.messages

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

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

/** One photo the full-screen viewer can show. [key] identifies the message for reactions. */
@Immutable
data class ViewerItem(val uri: Uri, val title: String = "", val date: Long = 0L, val reactKey: String? = null, val incoming: Boolean = true, val address: String? = null)

/** Single-photo entry point (gallery peek, older callers): the same viewer with one page. */
@Composable
fun PhotoViewer(uri: Uri, onClose: () -> Unit, title: String = "", date: Long = 0L, reactKey: String? = null, onReacted: () -> Unit = {},
                onDelete: (() -> Unit)? = null, showForward: Boolean = true) {
    val items = remember(uri, title, date, reactKey) { listOf(ViewerItem(uri, title, date, reactKey)) }
    PhotoViewer(items, 0, onClose, onReacted = { onReacted() }, onDelete = onDelete?.let { d -> { _: ViewerItem -> d() } }, showForward = showForward)
}

/** Pinch / double-tap zoom of one page. Plain state so the gesture code reads the live value. */
@Stable
private class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    val zoomed: Boolean get() = scale > 1.01f

    fun clamp(t: Offset, s: Float, size: IntSize): Offset {
        val mx = (s - 1f) * size.width / 2f; val my = (s - 1f) * size.height / 2f
        return Offset(t.x.coerceIn(-mx, mx), t.y.coerceIn(-my, my))
    }
    /** Zoom by [z] around [centroid] (keeps the spot under the fingers still) and move by [pan]. */
    fun transform(z: Float, pan: Offset, centroid: Offset, size: IntSize) {
        val old = scale; val ns = (old * z).coerceIn(1f, 5f)
        val c = centroid - Offset(size.width / 2f, size.height / 2f)
        scale = ns
        offset = clamp(c - (c - offset) * (ns / old) + pan, ns, size)
    }
    suspend fun animateTo(s: Float, t: Offset) {
        val s0 = scale; val t0 = offset
        animate(0f, 1f, animationSpec = tween(260, easing = FastOutSlowInEasing)) { f, _ -> scale = s0 + (s - s0) * f; offset = t0 + (t - t0) * f }
    }
    suspend fun toggle(p: Offset, size: IntSize) {
        if (zoomed) animateTo(1f, Offset.Zero)
        else { val c = p - Offset(size.width / 2f, size.height / 2f); animateTo(2.5f, clamp(-c * 1.5f, 2.5f, size)) }
    }
    suspend fun settle() { if (scale < 1.02f && (scale != 1f || offset != Offset.Zero)) animateTo(1f, Offset.Zero) }
    fun reset() { scale = 1f; offset = Offset.Zero }
}

private const val MODE_NONE = 0
private const val MODE_DISMISS = 1
private const val MODE_PAGER = 2
private const val MODE_ZOOM = 3

/**
 * Full-screen photos, Google Photos / Google Messages style.
 * - Swipe left / right for the other photos in the chat (follows the reading direction, so it works in RTL).
 * - Drag the photo down (or up): it follows the finger, shrinks and the background fades; let go early and it springs back,
 *   flick or drag far enough and it closes.
 * - Pinch or double-tap to zoom; while zoomed one finger pans the photo and never flips the page.
 * - Ways out: the back arrow (always on a dark strip), the phone's Back button, swipe down, and a hint the first few times.
 * Gestures: one detector decides per touch — two fingers or a zoomed photo = zoom/pan, a mostly vertical drag past the
 * touch slop = dismiss (consumed so the pager never sees it), anything else is left untouched for the pager.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoViewer(items: List<ViewerItem>, startIndex: Int, onClose: () -> Unit, onReacted: (ViewerItem) -> Unit = {},
                onDelete: ((ViewerItem) -> Unit)? = null, showForward: Boolean = true) {
    if (items.isEmpty()) { LaunchedEffect(Unit) { onClose() }; return }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val white = Color.White
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, items.lastIndex)) { items.size }
    val cur = items[pager.currentPage.coerceIn(0, items.lastIndex)]
    val zooms = remember { HashMap<Int, ZoomState>() }
    fun zoomOf(i: Int) = zooms.getOrPut(i) { ZoomState() }
    var chrome by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var reaction by remember(cur.reactKey) { mutableStateOf(cur.reactKey?.let { ReactionStore.get(ctx, it) }) }
    val prefs = remember { ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE) }
    var hint by remember { mutableStateOf(prefs.getInt("viewer_hint_count", 0) < 4) }
    LaunchedEffect(Unit) { if (hint) { prefs.edit().putInt("viewer_hint_count", prefs.getInt("viewer_hint_count", 0) + 1).apply(); delay(4500); hint = false } }
    // A page that scrolls away comes back unzoomed
    LaunchedEffect(pager.currentPage) { zooms.forEach { (i, z) -> if (i != pager.currentPage) z.reset() } }

    // ── Swipe-to-dismiss state (read only inside graphicsLayer / draw, so dragging never recomposes the screen) ──
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var closing by remember { mutableStateOf(false) }
    var boxH by remember { mutableFloatStateOf(2000f) }
    var anim by remember { mutableStateOf<Job?>(null) }
    val density = LocalDensity.current
    val flingPx = with(density) { 800.dp.toPx() }
    val minFlingDrag = with(density) { 24.dp.toPx() }
    fun progress() = (abs(dragY) / (boxH * 0.45f)).coerceIn(0f, 1f)
    val dragging by remember { derivedStateOf { closing || abs(dragY) > 2f } }
    val curZoomed by remember { derivedStateOf { zoomOf(pager.currentPage).zoomed } }
    val bg = Color(0xFF0E0F11)

    fun startDismiss() { anim?.cancel(); hint = false }
    fun dragBy(d: Offset) { dragX += d.x; dragY += d.y }
    fun endDismiss(v: Velocity) {
        val dir = if (dragY != 0f) sign(dragY) else sign(v.y)
        val fling = abs(v.y) > flingPx && sign(v.y) == dir && abs(dragY) > minFlingDrag
        if (fling || abs(dragY) > boxH * 0.16f) {
            closing = true
            val remaining = (boxH - abs(dragY)).coerceAtLeast(1f)
            val ms = (remaining / abs(v.y).coerceAtLeast(1f) * 1000f).toInt().coerceIn(110, 230)
            anim = scope.launch {
                launch { animate(dragX, dragX + v.x * ms / 1000f, animationSpec = tween(ms, easing = FastOutLinearInEasing)) { x, _ -> dragX = x } }
                animate(dragY, dir * boxH, animationSpec = tween(ms, easing = FastOutLinearInEasing)) { y, _ -> dragY = y }
                onClose()
            }
        } else {
            val spec = spring<Float>(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
            anim = scope.launch {
                launch { animate(dragX, 0f, v.x, spec) { x, _ -> dragX = x } }
                animate(dragY, 0f, v.y, spec) { y, _ -> dragY = y }
            }
        }
    }

    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { if (menu) menu = false else if (curZoomed) scope.launch { zoomOf(pager.currentPage).animateTo(1f, Offset.Zero) } else onClose() }
        val sb = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val topPad = if (sb > 0.dp) sb else 24.dp
        Box(Modifier.fillMaxSize().onSizeChanged { boxH = it.height.toFloat().coerceAtLeast(1f) }
            .drawBehind { drawRect(bg.copy(alpha = (1f - progress() * 1.25f).coerceIn(0f, 1f))) }) {
            HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, pageSpacing = 16.dp,
                userScrollEnabled = !curZoomed && !dragging) { page ->
                val item = items[page]
                val zoom = remember(page) { zoomOf(page) }
                val isCur = page == pager.currentPage
                Box(Modifier.fillMaxSize()
                    .pointerInput(page) {
                        detectTapGestures(
                            onTap = { chrome = !chrome; hint = false },
                            onDoubleTap = { p -> scope.launch { zoom.toggle(p, size) } })
                    }
                    .pointerInput(page) {
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (closing) return@awaitEachGesture
                            var mode = if (zoom.zoomed) MODE_ZOOM else MODE_NONE
                            var id = down.id
                            var total = Offset.Zero
                            val vt = VelocityTracker()
                            vt.addPosition(down.uptimeMillis, down.position)
                            while (true) {
                                val ev = awaitPointerEvent()
                                val pressed = ev.changes.count { it.pressed }
                                if (pressed == 0) break
                                if (mode == MODE_PAGER) continue
                                if (mode == MODE_DISMISS) {
                                    val c = ev.changes.firstOrNull { it.id == id && it.pressed } ?: ev.changes.first { it.pressed }.also { id = it.id }
                                    vt.addPosition(c.uptimeMillis, c.position)
                                    dragBy(c.positionChange()); c.consume()
                                    continue
                                }
                                if (pressed >= 2) mode = MODE_ZOOM
                                if (mode == MODE_ZOOM) {
                                    val z = ev.calculateZoom(); val pan = ev.calculatePan()
                                    if (z != 1f || pan != Offset.Zero) zoom.transform(z, pan, ev.calculateCentroid(useCurrent = true), size)
                                    ev.changes.forEach { if (it.positionChanged()) it.consume() }
                                    continue
                                }
                                val c = ev.changes.firstOrNull { it.id == id } ?: continue
                                if (c.isConsumed) { mode = MODE_PAGER; continue }
                                vt.addPosition(c.uptimeMillis, c.position)
                                total += c.positionChange()
                                if (total.getDistance() > slop) {
                                    // Direction lock: mostly vertical = dismiss, otherwise leave it to the pager
                                    if (abs(total.y) > abs(total.x) * 1.2f) { mode = MODE_DISMISS; startDismiss(); c.consume() }
                                    else mode = MODE_PAGER
                                }
                            }
                            when (mode) {
                                MODE_DISMISS -> endDismiss(vt.calculateVelocity())
                                MODE_ZOOM -> scope.launch { zoom.settle() }
                            }
                        }
                    }) {
                    AsyncImage(item.uri, "Photo", Modifier.fillMaxSize().graphicsLayer {
                        val p = if (isCur) progress() else 0f
                        val s = zoom.scale * (1f - p * 0.32f)
                        scaleX = s; scaleY = s
                        translationX = zoom.offset.x + (if (isCur) dragX else 0f)
                        translationY = zoom.offset.y + (if (isCur) dragY else 0f)
                    }, contentScale = ContentScale.Fit)
                }
            }

            val showBars = chrome && !dragging
            // Top bar: dark gradient so the back arrow always shows, even on a white photo
            AnimatedVisibility(showBars, Modifier.align(Alignment.TopCenter), enter = fadeIn(tween(150)), exit = fadeOut(tween(120))) {
                Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(.72f), Color.Black.copy(.35f), Color.Transparent)))
                    .padding(top = topPad).padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose, Modifier.size(48.dp)) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(.35f)), contentAlignment = Alignment.Center) {
                            Icon(Ms.ArrowBack, "Close photo", tint = white, modifier = Modifier.size(24.dp))
                        }
                    }
                    Column(Modifier.weight(1f).padding(start = 6.dp)) {
                        if (cur.title.isNotEmpty()) Text(cur.title, color = white, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = LocalTextStyle.current.copy(textDirection = TextDirection.Content))
                        val sub = remember(cur.date, pager.currentPage, items.size) {
                            listOfNotNull(if (cur.date > 0) viewerDate(cur.date) else null, if (items.size > 1) "${pager.currentPage + 1} / ${items.size}" else null).joinToString("  ·  ")
                        }
                        if (sub.isNotEmpty()) Text(sub, color = white.copy(.75f), fontSize = 12.sp, maxLines = 1)
                    }
                    if (showForward) IconButton({ if (forwardPhoto(ctx, cur.uri)) onClose() }) { Icon(Ms.Forward, "Forward", tint = white) }
                    IconButton({ sharePhoto(ctx, cur.uri) }) { Icon(Ms.Share, "Share", tint = white) }
                    IconButton({ savePhoto(ctx, cur.uri) }) { Icon(Ms.Download, "Save", tint = white) }
                    if (onDelete != null) Box {
                        IconButton({ menu = true }) { Icon(Ms.MoreVert, "More", tint = white) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Delete") }, { menu = false; onDelete(cur); onClose() }, leadingIcon = { Icon(Ms.Delete, null) })
                        }
                    }
                }
            }
            val rk = cur.reactKey
            if (showBars && rk != null) Row(Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.55f))))
                .navigationBarsPadding().padding(horizontal = 12.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                QuickReactions.forEach { e ->
                    Box(Modifier.size(46.dp).clip(CircleShape).background(if (reaction == e) white.copy(.25f) else Color.Transparent)
                        .clickable { val nv = if (reaction == e) null else e; ReactionStore.set(ctx, rk, nv); reaction = nv; onReacted(cur) }, contentAlignment = Alignment.Center) { Text(e, fontSize = 26.sp) }
                }
            }
            // Bars hidden: a small back arrow stays in the corner so there's always a way out
            if (!chrome && !dragging) Box(Modifier.align(Alignment.TopStart).padding(top = topPad).padding(8.dp).size(40.dp).clip(CircleShape)
                .background(Color.Black.copy(.45f)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Ms.ArrowBack, "Close photo", tint = white, modifier = Modifier.size(22.dp))
            }
            AnimatedVisibility(hint && !dragging, Modifier.align(Alignment.Center).padding(top = 260.dp), enter = fadeIn(), exit = fadeOut()) {
                Row(Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(.7f)).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ms.ArrowBack, null, tint = white, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(if (items.size > 1) "Swipe sideways for more · swipe down to close" else "Tap the arrow or swipe down to close", color = white, fontSize = 14.sp)
                }
            }
        }
    }
}

private val viewerFmt = object : java.lang.ThreadLocal<java.text.SimpleDateFormat>() { override fun initialValue() = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()) }
internal fun viewerDate(t: Long): String = viewerFmt.get()!!.format(java.util.Date(t))

fun Modifier.androidxPointer(onGesture: (Float, Offset) -> Unit): Modifier =
    this.pointerInput(Unit) { detectTransformGestures { _, pan, zoom, _ -> onGesture(zoom, pan) } }
