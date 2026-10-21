package com.heimish.messages

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 320dp like Google Messages, but never more than ~42% of the screen (small phones, split screen). */
internal val PanelHeight: androidx.compose.ui.unit.Dp
    @Composable get() = minOf(320.dp, (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.42f).dp).coerceAtLeast(200.dp)

/** Photo permission for the gallery and GIF panels. Android 14 "Select photos" (partial access) counts as allowed. */
private fun photoPerms(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
}
private fun hasPhotoAccess(ctx: Context) = photoPerms().any { androidx.core.content.ContextCompat.checkSelfPermission(ctx, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

/** Returns (granted, ask). Asks once automatically when the panel opens. */
@Composable
internal fun rememberPhotoAccess(): Pair<Boolean, () -> Unit> {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasPhotoAccess(ctx)) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { granted = hasPhotoAccess(ctx) }
    LaunchedEffect(Unit) { if (!granted) runCatching { launcher.launch(photoPerms()) } }
    return granted to { runCatching { launcher.launch(photoPerms()) }; Unit }
}

/** Google Messages "+" panel: big rounded tiles in a sheet where the keyboard would be. */
@Composable
fun PlusPanel(items: List<Triple<ImageVector, String, () -> Unit>>) {
    Column(Modifier.fillMaxWidth().height(PanelHeight).clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(SheetBg)
        .padding(horizontal = 14.dp, vertical = 18.dp)) {
        items.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { (icon, label, action) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.padding(horizontal = 5.dp).fillMaxWidth().height(56.dp).pressClick(RoundedCornerShape(24.dp), action).background(TileBg),
                            contentAlignment = Alignment.Center) { Icon(icon, label, tint = TextPrimary, modifier = Modifier.size(26.dp)) }
                        Spacer(Modifier.height(6.dp))
                        Text(label, fontSize = 13.sp, color = TextPrimary, maxLines = 1)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

internal object RecentEmoji {
    fun get(ctx: Context): List<String> = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE).getString("recent_emoji", "")!!.split("\u0001").filter { it.isNotEmpty() }
    fun add(ctx: Context, e: String) {
        val l = (listOf(e) + get(ctx).filter { it != e }).take(24)
        ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE).edit().putString("recent_emoji", l.joinToString("\u0001")).apply()
    }
}

private fun gifRequest(ctx: Context, data: Any) = ImageRequest.Builder(ctx).data(data)
    .decoderFactory(if (Build.VERSION.SDK_INT >= 28) ImageDecoderDecoder.Factory() else GifDecoder.Factory()).crossfade(true).build()

/** GIFs on the phone (Downloads, WhatsApp, keyboard saves…), newest first. */
private fun loadPhoneGifs(ctx: Context): List<Pair<Uri, String>> = runCatching {
    val out = mutableListOf<Pair<Uri, String>>()
    ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
        "${MediaStore.Images.Media.MIME_TYPE}=?", arrayOf("image/gif"), "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
        while (c.moveToNext() && out.size < 300) out += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) to (c.getString(1) ?: "")
    }
    out
}.getOrDefault(emptyList())

private val CatIcons: List<ImageVector> @Composable get() = listOf(Ms.Mood, Ms.EmojiPeople, Ms.EmojiNature, Ms.EmojiFoodBeverage,
    Ms.EmojiEvents, Ms.EmojiTransportation, Ms.EmojiObjects, Ms.EmojiSymbols, Ms.EmojiFlags)

/** Google Messages emoji / GIF / sticker panel: tabs on top, search pill, grid with category headers and a category bar. */
@Composable
fun ExpressionPanel(startTab: Int = 0, onEmoji: (String) -> Unit, onGif: (Uri) -> Unit, onSticker: (String) -> Unit) {
    val ctx = LocalContext.current
    var tab by remember(startTab) { mutableIntStateOf(startTab) }
    var q by remember { mutableStateOf("") }
    var recents by remember { mutableStateOf(RecentEmoji.get(ctx)) }
    Column(Modifier.fillMaxWidth().height(PanelHeight).clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(SheetBg)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.clip(RoundedCornerShape(50)).background(TileBg).padding(3.dp)) {
                listOf("Emoji", "GIFs", "Stickers").forEachIndexed { i, t ->
                    Box(Modifier.clip(RoundedCornerShape(50)).background(if (tab == i) Tonal else Color.Transparent).clickable { tab = i; q = "" }
                        .padding(horizontal = 16.dp, vertical = 7.dp)) {
                        Text(t, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (tab == i) OnTonal else TextSecond)
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Row(Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(50)).background(TileBg).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ms.Search, null, tint = TextSecond, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Box(Modifier.weight(1f)) {
                    if (q.isEmpty()) Text(listOf("Search emoji", "Search GIFs", "Search stickers")[tab], color = TextHint, fontSize = 14.sp)
                    BasicTextField(q, { q = it }, singleLine = true, textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp), cursorBrush = SolidColor(Brand), modifier = Modifier.fillMaxWidth())
                }
            }
        }
        when (tab) {
            0 -> {
                val grid = rememberLazyGridState()
                val scope = rememberCoroutineScope()
                val query = q.trim().lowercase()
                val sections = remember(query, recents) {
                    if (query.isNotEmpty()) listOf("Results" to EmojiCategories.flatMap { it.second }.distinct().filter { (EmojiNames[it] ?: "").contains(query) })
                    else (if (recents.isNotEmpty()) listOf("Recents" to recents) else emptyList()) + EmojiCategories
                }
                // index of each header inside the grid, for the category bar
                val headerIdx = remember(sections) { var i = 0; sections.map { s -> val h = i; i += 1 + s.second.size; h } }
                LazyVerticalGrid(GridCells.Adaptive(44.dp), Modifier.weight(1f).padding(horizontal = 8.dp), state = grid) {
                    sections.forEach { (title, list) ->
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextSecond, modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 6.dp))
                        }
                        list.forEach { e ->
                            item {
                                Box(Modifier.size(44.dp).clip(CircleShape).clickable { RecentEmoji.add(ctx, e); onEmoji(e) }, contentAlignment = Alignment.Center) { Text(e, fontSize = 26.sp) }
                            }
                        }
                    }
                }
                if (query.isEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    val off = if (recents.isNotEmpty()) 1 else 0
                    if (off == 1) Icon(Ms.Schedule, "Recents", tint = TextSecond, modifier = Modifier.size(34.dp).clip(CircleShape).clickable { scope.launch { grid.scrollToItem(0) } }.padding(6.dp))
                    CatIcons.forEachIndexed { i, ic ->
                        Icon(ic, null, tint = TextSecond, modifier = Modifier.size(34.dp).clip(CircleShape).clickable { scope.launch { headerIdx.getOrNull(i + off)?.let { grid.scrollToItem(it) } } }.padding(6.dp))
                    }
                }
            }
            1 -> {
                // GIFs come from the phone's photos, so ask for photo access first (it never asked before)
                val (photoOk, askPhotos) = rememberPhotoAccess()
                var gifs by remember { mutableStateOf<List<Pair<Uri, String>>?>(null) }
                LaunchedEffect(photoOk) { gifs = withContext(Dispatchers.IO) { loadPhoneGifs(ctx) } }
                val shown = gifs?.filter { q.isBlank() || it.second.contains(q.trim(), true) }
                if (shown.isNullOrEmpty()) Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Ms.Gif, null, tint = TextHint, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(if (gifs == null) "Loading…" else "No GIFs on this phone yet. GIFs you save, and GIFs from your keyboard, show up here.", color = TextHint, fontSize = 14.sp)
                        if (!photoOk) TextButton({ askPhotos() }) { Text("Allow photo access", color = Brand) }
                    }
                } else LazyVerticalGrid(GridCells.Fixed(2), Modifier.weight(1f).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    shown.forEach { (u, _) ->
                        item { AsyncImage(gifRequest(ctx, u), null, Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(16.dp)).background(TileBg).clickable { onGif(u) }, contentScale = ContentScale.Crop) }
                    }
                }
            }
            else -> {
                val query = q.trim().lowercase()
                val list = EmojiCategories.take(4).flatMap { it.second }.filter { query.isEmpty() || (EmojiNames[it] ?: "").contains(query) }
                LazyVerticalGrid(GridCells.Fixed(4), Modifier.weight(1f).padding(8.dp)) {
                    list.forEach { e -> item { Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(20.dp)).clickable { onSticker(e) }, contentAlignment = Alignment.Center) { Text(e, fontSize = 52.sp) } } }
                }
            }
        }
    }
}


/** Google Messages gallery: camera tile, Folders, recent photos; tap to pick (numbered), then Add. */
@Composable
fun GalleryPanel(onCamera: () -> Unit, onFolders: () -> Unit, onPicked: (List<Uri>) -> Unit) {
    val ctx = LocalContext.current
    val (granted, ask) = rememberPhotoAccess()
    var photos by remember { mutableStateOf(emptyList<Uri>()) }
    LaunchedEffect(granted) {
        if (granted) photos = withContext(Dispatchers.IO) {
            runCatching {
                val out = mutableListOf<Uri>()
                ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                    while (c.moveToNext() && out.size < 240) out += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                }
                out
            }.getOrDefault(emptyList())
        }
    }
    var picked by remember { mutableStateOf(listOf<Uri>()) }
    Column(Modifier.fillMaxWidth().height(PanelHeight + 40.dp).clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(SheetBg)) {
        Box(Modifier.weight(1f)) {
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    Column(Modifier.aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(TileBg).clickable(onClick = onCamera), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Ms.PhotoCamera, null, tint = TextPrimary, modifier = Modifier.size(30.dp)); Spacer(Modifier.height(6.dp)); Text("Camera", color = TextPrimary, fontSize = 13.sp)
                    }
                }
                item {
                    Column(Modifier.aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(TileBg).clickable(onClick = onFolders), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Ms.Folder, null, tint = TextPrimary, modifier = Modifier.size(30.dp)); Spacer(Modifier.height(6.dp)); Text("Folders", color = TextPrimary, fontSize = 13.sp)
                    }
                }
                photos.forEach { u ->
                    item(key = u.toString()) {
                        val n = picked.indexOf(u)
                        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(if (n >= 0) 24.dp else 6.dp)).clickable {
                            picked = if (n >= 0) picked - u else if (picked.size < 10) picked + u else picked
                        }) {
                            AsyncImage(u, null, Modifier.fillMaxSize().padding(if (n >= 0) 8.dp else 0.dp).clip(RoundedCornerShape(if (n >= 0) 18.dp else 0.dp)), contentScale = ContentScale.Crop)
                            Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape).background(if (n >= 0) Brand else Color.Black.copy(.25f))
                                .then(if (n >= 0) Modifier else Modifier.border(1.5.dp, Color.White, CircleShape)), contentAlignment = Alignment.Center) {
                                if (n >= 0) Text("${n + 1}", color = OnBrand, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            if (!granted) Text("Tap here to allow photo access, or tap Folders.", color = Brand, fontSize = 14.sp, modifier = Modifier.align(Alignment.BottomCenter).clip(RoundedCornerShape(50)).background(SheetBg).clickable { ask() }.padding(16.dp))
        }
        if (picked.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${picked.size} selected", color = TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Button({ onPicked(picked) }, shape = RoundedCornerShape(50), colors = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = OnBrand)) { Text("Add") }
        }
    }
}
