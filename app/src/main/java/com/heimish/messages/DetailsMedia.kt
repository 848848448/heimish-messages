package com.heimish.messages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ━━━━━━━━━━━━ "Videos, links, & more": the media gallery of one conversation ━━━━━━━━━━━━

/** One attachment of an MMS in this chat (photo, video, audio or any other file). */
data class MediaItem(val uri: Uri, val mime: String, val date: Long, val incoming: Boolean, val name: String, val sender: String?)
data class LinkItem(val url: String, val date: Long, val incoming: Boolean, val sender: String?)

object MediaRepo {
    private val LINK = Regex("(?i)\\b((https?://|www\\.)[^\\s<>\"]+)")

    /** Every attachment part of the MMS messages in [msgs], read straight from the MMS part table (newest first). */
    fun attachments(ctx: Context, msgs: List<Message>): List<MediaItem> {
        val mms = msgs.filter { it.isMms }.associateBy { it.rowId }
        val out = ArrayList<MediaItem>()
        runCatching {
            mms.keys.chunked(400).forEach { chunk ->
                val sel = "mid IN (" + chunk.joinToString(",") + ")"
                ctx.contentResolver.query(Uri.parse("content://mms/part"), arrayOf("_id", "mid", "ct", "name", "cl", "fn"), sel, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val ct = (c.getString(2) ?: "").lowercase()
                        if (ct.isBlank() || ct == "text/plain" || ct == "application/smil") continue
                        val m = mms[c.getLong(1)] ?: continue
                        val name = listOf(c.getString(3), c.getString(5), c.getString(4)).firstOrNull { !it.isNullOrBlank() } ?: defaultName(ct)
                        out.add(MediaItem(Uri.parse("content://mms/part/" + c.getLong(0)), ct, m.date, m.incoming, name, m.address))
                    }
                }
            }
        }
        // Fallback: whatever the thread loader already resolved (keeps photos visible if the part table can't be read)
        if (out.isEmpty()) msgs.filter { it.imageUri != null }.forEach { m ->
            val mime = m.mediaMime ?: "image/jpeg"
            out.add(MediaItem(m.imageUri!!, mime, m.date, m.incoming, defaultName(mime), m.address))
        }
        return out.distinctBy { it.uri }.sortedByDescending { it.date }
    }

    fun links(msgs: List<Message>): List<LinkItem> = msgs.flatMap { m ->
        LINK.findAll(m.body).map { LinkItem(it.value.trimEnd('.', ',', ')', '!', '?', ';', ':', '"', '\''), m.date, m.incoming, m.address) }.toList()
    }.distinctBy { it.url.lowercase() }.sortedByDescending { it.date }

    fun domain(url: String): String = runCatching { Uri.parse(if (url.startsWith("http", true)) url else "https://$url").host?.removePrefix("www.") }.getOrNull() ?: url

    private fun defaultName(mime: String) = when {
        mime.startsWith("image/") -> "Photo"
        mime.startsWith("video/") -> "Video"
        mime.startsWith("audio/") -> "Audio"
        mime.contains("vcard") -> "Contact card"
        mime.contains("pdf") -> "PDF document"
        else -> "File"
    }

    private val thumbs = android.util.LruCache<String, Bitmap>(60)
    /** A frame from the video for the grid (null when the codec can't read it). */
    fun videoThumb(ctx: Context, uri: Uri): Bitmap? {
        thumbs.get(uri.toString())?.let { return it }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val f = r.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.frameAtTime
            val scaled = f?.let { val s = 360f / maxOf(it.width, it.height).coerceAtLeast(1); if (s < 1f) Bitmap.createScaledBitmap(it, (it.width * s).toInt().coerceAtLeast(1), (it.height * s).toInt().coerceAtLeast(1), true) else it }
            scaled?.also { thumbs.put(uri.toString(), it) }
        } catch (_: Throwable) { null } finally { runCatching { r.release() } }
    }

    fun durationMs(ctx: Context, uri: Uri): Long = runCatching {
        val r = MediaMetadataRetriever()
        try { r.setDataSource(ctx, uri); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L } finally { runCatching { r.release() } }
    }.getOrDefault(0L)

    /** Opens an attachment in another app: the MMS part is copied to the cache and shared through our FileProvider. */
    fun open(ctx: Context, item: MediaItem) {
        runCatching {
            val dir = java.io.File(ctx.cacheDir, "media_open").apply { mkdirs() }
            dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
            val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(item.mime) ?: "bin"
            val base = item.name.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(60).ifBlank { "file" }
            val f = java.io.File(dir, (item.uri.lastPathSegment ?: "x") + "_" + (if (base.contains('.')) base else "$base.$ext"))
            if (!f.exists() || f.length() == 0L) ctx.contentResolver.openInputStream(item.uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            val u = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(u, item.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(Intent.createChooser(view, item.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Toast.makeText(ctx, "No app here can open this file", Toast.LENGTH_SHORT).show() }
    }

    fun share(ctx: Context, item: MediaItem) {
        runCatching {
            val dir = java.io.File(ctx.cacheDir, "media_open").apply { mkdirs() }
            val f = java.io.File(dir, (item.uri.lastPathSegment ?: "x") + "_share")
            ctx.contentResolver.openInputStream(item.uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            val u = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(item.mime).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

internal fun monthLabel(t: Long): String = java.text.SimpleDateFormat("LLLL yyyy", java.util.Locale.getDefault()).format(java.util.Date(t))
internal fun shortDate(t: Long): String = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date(t))

/** Full-screen gallery with Photos / Videos / Links / Files tabs (swipe or tap a tab). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MediaDialog(msgs: List<Message>, startTab: Int = 0, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf<List<MediaItem>?>(null) }
    LaunchedEffect(msgs) { items = withContext(Dispatchers.IO) { MediaRepo.attachments(ctx, msgs) } }
    val links = remember(msgs) { MediaRepo.links(msgs) }
    val all = items ?: emptyList()
    val photos = remember(all) { all.filter { it.mime.startsWith("image/") } }
    val videos = remember(all) { all.filter { it.mime.startsWith("video/") } }
    val files = remember(all) { all.filter { !it.mime.startsWith("image/") && !it.mime.startsWith("video/") } }
    val tabs = buildList {
        add(Triple("Photos", Ms.Image, photos.size)); add(Triple("Videos", Ms.Videocam, videos.size)); add(Triple("Links", Ms.Link, links.size))
        if (files.isNotEmpty()) add(Triple("Files", Ms.Description, files.size))
    }
    val pager = rememberPagerState(initialPage = startTab.coerceIn(0, 2)) { tabs.size }
    val scope = rememberCoroutineScope()
    var viewing by remember { mutableStateOf<MediaItem?>(null) }
    var linkMenu by remember { mutableStateOf<LinkItem?>(null) }
    // Each tab keeps its own scroll spot while you swipe between tabs or open a photo
    val photoGrid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val videoGrid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val linkListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val fileListState = androidx.compose.foundation.lazy.rememberLazyListState()

    androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Chrome) {
            Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                TopAppBar(title = { Text("Videos, links, & more", fontWeight = FontWeight.Medium, fontSize = 22.sp, color = TextPrimary) },
                    navigationIcon = { IconButton(onClose) { Icon(Ms.ArrowBack, "Back", tint = TextPrimary) } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Chrome))
                PrimaryTabRow(selectedTabIndex = pager.currentPage.coerceAtMost(tabs.size - 1), containerColor = Chrome, contentColor = Brand) {
                    tabs.forEachIndexed { i, (label, _, n) ->
                        val sel = pager.currentPage == i
                        Tab(sel, { scope.launch { pager.animateScrollToPage(i) } }, text = {
                            Text(if (items == null || n == 0) label else "$label · $n", maxLines = 1, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, color = if (sel) Brand else TextSecond, fontSize = 14.sp)
                        })
                    }
                }
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(ListBg)) {
                    if (items == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Brand)
                    else HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                        when (tabs[page].first) {
                            "Photos" -> if (photos.isEmpty()) EmptyMedia(Ms.Image, "No photos in this conversation yet") else MediaGrid(photos, photoGrid) { viewing = it }
                            "Videos" -> if (videos.isEmpty()) EmptyMedia(Ms.Videocam, "No videos in this conversation yet") else MediaGrid(videos, videoGrid) { MediaRepo.open(ctx, it) }
                            "Links" -> if (links.isEmpty()) EmptyMedia(Ms.Link, "No links in this conversation yet") else LinkList(links, linkListState, onLong = { linkMenu = it })
                            else -> FileList(files, fileListState)
                        }
                    }
                }
            }
        }
    }
    viewing?.let { v ->
        // Swipe through all the chat's photos from here too
        val vItems = remember(photos) { photos.map { p -> ViewerItem(p.uri, if (p.incoming) (p.sender?.let { s -> SmsRepository.peekContactName(s)?.first ?: s } ?: "") else "You", p.date, incoming = p.incoming, address = p.sender) } }
        PhotoViewer(vItems, photos.indexOf(v).coerceAtLeast(0), onClose = { viewing = null })
    }
    linkMenu?.let { l ->
        AlertDialog(onDismissRequest = { linkMenu = null }, containerColor = Surf,
            title = { Text(MediaRepo.domain(l.url), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = { Text(l.url, color = TextSecond, fontSize = 14.sp, style = TextStyle(textDirection = TextDirection.Ltr)) },
            confirmButton = { TextButton({ openLink(ctx, l.url); linkMenu = null }) { Text("Open", color = Brand) } },
            dismissButton = {
                Row {
                    TextButton({ copyText(ctx, l.url, "Link copied"); linkMenu = null }) { Text("Copy", color = Brand) }
                    TextButton({ runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, l.url), "Share link")) }; linkMenu = null }) { Text("Share", color = Brand) }
                }
            })
    }
}

internal fun openLink(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (url.startsWith("http", true)) url else "https://$url")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(ctx, "No browser found", Toast.LENGTH_SHORT).show() }
}

internal fun copyText(ctx: Context, text: String, toast: String) {
    runCatching { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("text", text)) }
    if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, toast, Toast.LENGTH_SHORT).show()
}

@Composable
private fun EmptyMedia(icon: ImageVector, text: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(88.dp).clip(CircleShape).background(Tonal), contentAlignment = Alignment.Center) { Icon(icon, null, tint = OnTonal, modifier = Modifier.size(40.dp)) }
        Spacer(Modifier.height(16.dp))
        Text(text, color = TextSecond, fontSize = 15.sp)
    }
}

/** Photos / videos in a grid, grouped by month like Google Messages. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGrid(list: List<MediaItem>, state: androidx.compose.foundation.lazy.grid.LazyGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(), onTap: (MediaItem) -> Unit) {
    val ctx = LocalContext.current
    val groups = remember(list) { list.groupBy { monthLabel(it.date) } }
    var menu by remember { mutableStateOf<MediaItem?>(null) }
    LazyVerticalGrid(GridCells.Adaptive(108.dp), Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        groups.forEach { (month, ms) ->
            item(key = "h_$month", span = { GridItemSpan(maxLineSpan) }) {
                Text(month, color = TextSecond, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp))
            }
            items(ms.size, key = { ms[it].uri.toString() }) { i ->
                val it2 = ms[i]
                Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(FieldBg).combinedClickable(onLongClick = { menu = it2 }) { onTap(it2) }) {
                    if (it2.mime.startsWith("video/")) {
                        val thumb by produceState<Bitmap?>(null, it2.uri) { value = withContext(Dispatchers.IO) { MediaRepo.videoThumb(ctx, it2.uri) } }
                        val dur by produceState(0L, it2.uri) { value = withContext(Dispatchers.IO) { MediaRepo.durationMs(ctx, it2.uri) } }
                        thumb?.let { b -> androidx.compose.foundation.Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        Box(Modifier.align(Alignment.Center).size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = .45f)), contentAlignment = Alignment.Center) {
                            Icon(Ms.PlayArrowFill, "Play", tint = Color.White, modifier = Modifier.size(26.dp))
                        }
                        if (dur > 0) Text("%d:%02d".format(dur / 60000, (dur / 1000) % 60), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = .5f)).padding(horizontal = 5.dp, vertical = 1.dp))
                    } else {
                        coil.compose.AsyncImage(it2.uri, it2.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
            }
        }
    }
    menu?.let { m -> MediaActions(m) { menu = null } }
}

@Composable
private fun MediaActions(m: MediaItem, onDone: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(onDismissRequest = onDone, containerColor = Surf,
        title = { Text(m.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = { Text((if (m.incoming) "Received " else "Sent ") + shortDate(m.date), color = TextSecond) },
        confirmButton = { TextButton({ MediaRepo.open(ctx, m); onDone() }) { Text("Open", color = Brand) } },
        dismissButton = { TextButton({ MediaRepo.share(ctx, m); onDone() }) { Text("Share", color = Brand) } })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinkList(links: List<LinkItem>, state: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(), onLong: (LinkItem) -> Unit) {
    val ctx = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(vertical = 8.dp)) {
        items(links.size, key = { links[it].url }) { i ->
            val l = links[i]
            Row(Modifier.fillMaxWidth().combinedClickable(onLongClick = { onLong(l) }) { openLink(ctx, l.url) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(Tonal), contentAlignment = Alignment.Center) {
                    Text(MediaRepo.domain(l.url).take(1).uppercase(), color = OnTonal, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(MediaRepo.domain(l.url), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, style = listTextStyle())
                    Text(l.url, color = Brand, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(textDirection = TextDirection.Ltr))
                    Text((if (l.incoming) "Received " else "Sent ") + shortDate(l.date), color = TextSecond, fontSize = 12.sp)
                }
                Icon(Ms.OpenInNew, null, tint = TextSecond, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileList(files: List<MediaItem>, state: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState()) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf<MediaItem?>(null) }
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(vertical = 8.dp)) {
        items(files.size, key = { files[it].uri.toString() }) { i ->
            val f = files[i]
            Row(Modifier.fillMaxWidth().combinedClickable(onLongClick = { menu = f }) { MediaRepo.open(ctx, f) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(Tonal), contentAlignment = Alignment.Center) {
                    Icon(when { f.mime.startsWith("audio/") -> Ms.AudioFile; f.mime.contains("vcard") -> Ms.Contacts; else -> Ms.Description }, null, tint = OnTonal)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.name, color = TextPrimary, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, style = listTextStyle())
                    Text((if (f.incoming) "Received " else "Sent ") + shortDate(f.date), color = TextSecond, fontSize = 13.sp)
                }
            }
        }
    }
    menu?.let { m -> MediaActions(m) { menu = null } }
}
