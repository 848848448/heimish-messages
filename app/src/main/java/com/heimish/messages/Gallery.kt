package com.heimish.messages

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One photo or video on the phone, for the in-chat gallery. */
data class PhoneMedia(val uri: Uri, val isVideo: Boolean, val mime: String, val durationMs: Long)

/** Photos + videos permission, the way Android 13/14 split it. */
internal object MediaAccess {
    fun perms(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    private fun has(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    /** Every photo and video. */
    fun full(ctx: Context): Boolean = if (Build.VERSION.SDK_INT >= 33) has(ctx, Manifest.permission.READ_MEDIA_IMAGES) || has(ctx, Manifest.permission.READ_MEDIA_VIDEO) else has(ctx, Manifest.permission.READ_EXTERNAL_STORAGE)
    /** Android 14 "Select photos and videos": only the ones the person picked. */
    fun partial(ctx: Context): Boolean = Build.VERSION.SDK_INT >= 34 && !full(ctx) && has(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    /** Newest photos and videos first (only the allowed ones on partial access). */
    fun load(ctx: Context, limit: Int = 400): List<PhoneMedia> = runCatching {
        val out = mutableListOf<PhoneMedia>()
        val files = MediaStore.Files.getContentUri("external")
        val img = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE; val vid = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        val sel = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN ($img,$vid)"
        val withDur = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.Files.FileColumns.MIME_TYPE, "duration")
        val noDur = withDur.copyOf(3)
        val c = runCatching { ctx.contentResolver.query(files, withDur, sel, null, "${MediaStore.Files.FileColumns.DATE_ADDED} DESC") }.getOrNull()
            ?: ctx.contentResolver.query(files, noDur, sel, null, "${MediaStore.Files.FileColumns.DATE_ADDED} DESC")
        c?.use {
            while (it.moveToNext() && out.size < limit) {
                val id = it.getLong(0); val isVid = it.getInt(1) == vid
                val base = if (isVid) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val mime = it.getString(2) ?: if (isVid) "video/mp4" else "image/jpeg"
                val dur = if (it.columnCount > 3) runCatching { it.getLong(3) }.getOrDefault(0L) else 0L
                out += PhoneMedia(ContentUris.withAppendedId(base, id), isVid, mime, dur)
            }
        }
        out
    }.getOrDefault(emptyList())
}

fun fmtDuration(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

/** A square thumbnail for a photo or a video (videos get a real frame). */
@Composable
fun MediaThumb(uri: Uri, isVideo: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    if (!isVideo) { AsyncImage(uri, null, modifier, contentScale = ContentScale.Crop); return }
    val bmp by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(uri, android.util.Size(320, 320), null) else null }.getOrNull()
                ?: MediaRepo.videoThumb(ctx, uri)
        }
    }
    Box(modifier.background(Color(0xFF202124))) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/** true when [uri] is a video (by its MIME type). */
fun isVideoUri(ctx: Context, uri: Uri): Boolean = (runCatching { ctx.contentResolver.getType(uri) }.getOrNull() ?: "").startsWith("video/")

/**
 * Google Messages in-chat gallery: a sheet where the keyboard would be, with a drag handle,
 * a Camera tile first, then the phone's newest photos and videos. Tap to pick (numbered checks, up to 10),
 * long-press to look at one full screen. Drag the handle up for a taller sheet, down to close.
 * "Gallery" opens Android's full photo picker. Picked items go straight into the compose bar,
 * so the message you type there is the caption; Send sends them right away.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryPanel(
    selected: List<Uri>,
    onSelectionChange: (List<Uri>) -> Unit,
    onCamera: () -> Unit,
    onFullPicker: () -> Unit,
    onCaption: () -> Unit,
    onSend: () -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE) }
    var full by remember { mutableStateOf(MediaAccess.full(ctx)) }
    var partial by remember { mutableStateOf(MediaAccess.partial(ctx)) }
    var blocked by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableIntStateOf(0) }
    fun recheck() { full = MediaAccess.full(ctx); partial = MediaAccess.partial(ctx); if (full || partial) blocked = false }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        recheck(); reloadTick++
        val act = ctx as? Activity
        // Asked and refused for good: Android won't show the dialog again, so send them to the app's settings
        if (!full && !partial) blocked = act != null && MediaAccess.perms().none { p -> act.shouldShowRequestPermissionRationale(p) }
    }
    fun ask() {
        if (blocked) runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))) }
        else runCatching { permLauncher.launch(MediaAccess.perms()) }
    }
    // Ask by itself the very first time the gallery opens; after that the button inside the sheet asks
    LaunchedEffect(Unit) { if (!full && !partial && !prefs.getBoolean("gallery_perm_asked", false)) { prefs.edit().putBoolean("gallery_perm_asked", true).apply(); ask() } }
    // Coming back from Settings or the Android 14 picker: look again
    @Suppress("DEPRECATION") val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { recheck(); reloadTick++ } }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val canRead = full || partial
    var media by remember { mutableStateOf(emptyList<PhoneMedia>()) }
    LaunchedEffect(canRead, reloadTick) { media = if (canRead) withContext(Dispatchers.IO) { MediaAccess.load(ctx) } else emptyList() }

    val screenH = LocalConfiguration.current.screenHeightDp.dp
    var expanded by remember { mutableStateOf(false) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val baseH: Dp = PanelHeight + 56.dp
    val target = if (expanded) (screenH * 0.86f).coerceAtLeast(baseH) else baseH
    val height by animateDpAsState(target, label = "galleryH")
    var peek by remember { mutableStateOf<PhoneMedia?>(null) }
    val cols = if (LocalConfiguration.current.screenWidthDp >= 600) 5 else 3

    Column(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(SheetBg)) {
        // Handle + header: drag up = taller, drag down = smaller / close
        Column(Modifier.fillMaxWidth().pointerInput(expanded) {
            detectVerticalDragGestures(onDragEnd = {
                when {
                    dragY < -60f -> expanded = true
                    dragY > 60f && expanded -> expanded = false
                    dragY > 60f -> onClose()
                }
                dragY = 0f
            }, onDragCancel = { dragY = 0f }) { ch, d -> ch.consume(); dragY += d }
        }) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(TextHint.copy(alpha = .5f)))
            }
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ expanded = !expanded }) { Icon(if (expanded) Ms.ExpandMore else Ms.ExpandLess, if (expanded) "Smaller" else "Bigger", tint = TextPrimary) }
                Text(if (selected.isEmpty()) "Photos & videos" else "${selected.size} selected", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
                if (partial) {
                    Text("Manage", color = Brand, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable { ask() }.padding(horizontal = 12.dp, vertical = 8.dp))
                }
                Row(Modifier.clip(RoundedCornerShape(50)).background(TileBg).clickable(onClick = onFullPicker).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ms.PhotoLibrary, null, tint = TextPrimary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text("Gallery", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        if (partial) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(Tonal).clickable { ask() }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Ms.Info, null, tint = OnTonal, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp))
                Text("You let the app see only some photos.", color = OnTonal, fontSize = 13.sp, modifier = Modifier.weight(1f))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyVerticalGrid(GridCells.Fixed(cols), Modifier.fillMaxSize().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp),
                contentPadding = PaddingValues(bottom = if (selected.isNotEmpty()) 72.dp else 8.dp)) {
                item(key = "camera") {
                    Column(Modifier.aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(Tonal).clickable(onClick = onCamera),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Ms.PhotoCamera, null, tint = OnTonal, modifier = Modifier.size(32.dp)); Spacer(Modifier.height(6.dp))
                        Text("Camera", color = OnTonal, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
                if (canRead) items(media, key = { it.uri.toString() }) { m ->
                    val n = selected.indexOf(m.uri)
                    val pad by animateDpAsState(if (n >= 0) 8.dp else 0.dp, label = "pickPad")
                    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(if (n >= 0) 20.dp else 4.dp)).background(if (n >= 0) Tonal else Color.Transparent)
                        .combinedClickable(onLongClick = {
                            if (m.isVideo) runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(m.uri, m.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                            else peek = m
                        }) {
                            onSelectionChange(if (n >= 0) selected - m.uri else if (selected.size < 10) selected + m.uri else { android.widget.Toast.makeText(ctx, "Up to 10 at a time", android.widget.Toast.LENGTH_SHORT).show(); selected })
                        }) {
                        MediaThumb(m.uri, m.isVideo, Modifier.fillMaxSize().padding(pad).clip(RoundedCornerShape(if (n >= 0) 14.dp else 0.dp)))
                        if (m.isVideo) Row(Modifier.align(Alignment.BottomStart).padding(pad + 6.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(.55f)).padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Ms.PlayArrowFill, null, tint = Color.White, modifier = Modifier.size(14.dp))
                            if (m.durationMs > 0) Text(fmtDuration(m.durationMs), color = Color.White, fontSize = 11.sp)
                        }
                        Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape).background(if (n >= 0) Brand else Color.Black.copy(.25f))
                            .then(if (n >= 0) Modifier else Modifier.border(1.5.dp, Color.White, CircleShape)), contentAlignment = Alignment.Center) {
                            if (n >= 0) Text("${n + 1}", color = OnBrand, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            // No permission yet: say why, inside the sheet, with one clear button
            if (!canRead) Column(Modifier.align(Alignment.Center).padding(horizontal = 24.dp).padding(top = 24.dp).clip(RoundedCornerShape(24.dp)).background(TileBg).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Ms.PhotoLibrary, null, tint = Brand, modifier = Modifier.size(32.dp)); Spacer(Modifier.height(8.dp))
                Text("See your photos and videos here", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text(if (blocked) "Photo access is off. Turn on \"Photos and videos\" in the app's settings." else "Photos and videos aren't shared with the app yet.",
                    color = TextSecond, fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button({ ask() }, shape = RoundedCornerShape(50), colors = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = OnBrand)) { Text(if (blocked) "Open settings" else "Allow access") }
            }
            // Something picked: caption + send, Google Messages style
            if (selected.isNotEmpty()) Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(SheetBg).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(FieldBg).clickable(onClick = onCaption).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Add a message", color = TextHint, fontSize = 15.sp, maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(48.dp).clip(CircleShape).background(Brand).clickable(onClick = onSend), contentAlignment = Alignment.Center) {
                    Icon(Ms.SendFill, "Send ${selected.size}", tint = OnBrand, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
    peek?.let { p -> PhotoViewer(p.uri, onClose = { peek = null }, showForward = false) }
}
