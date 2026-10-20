package com.heimish.messages

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import java.io.File

/** Accent colors for one chat (Google Messages "Chat theme"). */
data class ChatPalette(val name: String, val lp: Long, val lc: Long, val loc: Long, val dp: Long, val dc: Long, val doc: Long) {
    val primary get() = Color(if (D) dp else lp)
    val container get() = Color(if (D) dc else lc)
    val onContainer get() = Color(if (D) doc else loc)
}

internal val ChatPalettes = listOf(
    ChatPalette("Blue", 0xFF0B57D0, 0xFFD3E3FD, 0xFF041E49, 0xFFA8C7FA, 0xFF0842A0, 0xFFD3E3FD),
    ChatPalette("Periwinkle", 0xFF4F5B92, 0xFFDDE1FF, 0xFF0A154B, 0xFFB8C3FF, 0xFF374379, 0xFFDDE1FF),
    ChatPalette("Lavender", 0xFF6750A4, 0xFFEADDFF, 0xFF21005D, 0xFFD0BCFF, 0xFF4F378B, 0xFFEADDFF),
    ChatPalette("Pink", 0xFF8E4585, 0xFFFFD7F0, 0xFF3A0035, 0xFFFFADEA, 0xFF722D6B, 0xFFFFD7F0),
    ChatPalette("Rose", 0xFF9C4146, 0xFFFFDAD9, 0xFF40000A, 0xFFFFB3B2, 0xFF7E2A30, 0xFFFFDAD9),
    ChatPalette("Peach", 0xFF8F4C2B, 0xFFFFDBCC, 0xFF351000, 0xFFFFB693, 0xFF723516, 0xFFFFDBCC),
    ChatPalette("Gold", 0xFF6D5E0F, 0xFFF8E287, 0xFF221B00, 0xFFDBC66E, 0xFF534600, 0xFFF8E287),
    ChatPalette("Sage", 0xFF4C662B, 0xFFCDEDA3, 0xFF102000, 0xFFB1D18A, 0xFF354E16, 0xFFCDEDA3),
    ChatPalette("Teal", 0xFF006A6A, 0xFF9CF1F0, 0xFF002020, 0xFF80D5D4, 0xFF004F4F, 0xFF9CF1F0),
    ChatPalette("Gray", 0xFF5D5F5F, 0xFFE2E2E2, 0xFF1A1C1C, 0xFFC6C6C7, 0xFF454747, 0xFFE2E2E2),
)

/** Built-in wallpapers: soft gradients (light, dark). */
internal val ChatGradients: List<Triple<String, List<Long>, List<Long>>> = listOf(
    Triple("Sunrise", listOf(0xFFFFE0B2, 0xFFFFCCBC, 0xFFF8BBD0), listOf(0xFF4E342E, 0xFF3E2723, 0xFF311B92)),
    Triple("Ocean", listOf(0xFFB3E5FC, 0xFFB2EBF2, 0xFFC5CAE9), listOf(0xFF01579B, 0xFF004D40, 0xFF1A237E)),
    Triple("Forest", listOf(0xFFDCEDC8, 0xFFC8E6C9, 0xFFB2DFDB), listOf(0xFF1B5E20, 0xFF004D40, 0xFF263238)),
    Triple("Lilac", listOf(0xFFE1BEE7, 0xFFD1C4E9, 0xFFC5CAE9), listOf(0xFF4A148C, 0xFF311B92, 0xFF1A237E)),
    Triple("Sand", listOf(0xFFFFF8E1, 0xFFFFECB3, 0xFFD7CCC8), listOf(0xFF3E2723, 0xFF4E342E, 0xFF212121)),
    Triple("Night", listOf(0xFFCFD8DC, 0xFFB0BEC5, 0xFF90A4AE), listOf(0xFF102027, 0xFF000A12, 0xFF1C313A)),
)

object ChatThemeStore {
    private fun p(ctx: Context) = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
    fun palette(ctx: Context, t: Long): ChatPalette? = p(ctx).getInt("ct_color_$t", -1).let { ChatPalettes.getOrNull(it) }
    /** "grad:N" or "photo" (file in app storage) or null */
    fun wallpaper(ctx: Context, t: Long): String? = p(ctx).getString("ct_wall_$t", null)
    fun wallFile(ctx: Context, t: Long) = File(ctx.filesDir, "chat_wall_$t.jpg")
    fun save(ctx: Context, t: Long, color: Int, wall: String?) = p(ctx).edit().putInt("ct_color_$t", color).putString("ct_wall_$t", wall).apply()
    fun reset(ctx: Context, t: Long) { p(ctx).edit().remove("ct_color_$t").remove("ct_wall_$t").apply(); wallFile(ctx, t).delete() }
}

@Composable
fun ChatWallpaper(threadId: Long, wall: String?, version: Int, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    when {
        wall == null -> {}
        wall.startsWith("grad:") -> {
            val g = ChatGradients.getOrNull(wall.removePrefix("grad:").toIntOrNull() ?: -1) ?: return
            Box(modifier.background(Brush.verticalGradient((if (D) g.third else g.second).map { Color(it) })))
        }
        wall == "photo" -> AsyncImage(coil.request.ImageRequest.Builder(ctx).data(ChatThemeStore.wallFile(ctx, threadId)).memoryCacheKey("wall_${threadId}_$version").build(),
            null, modifier, contentScale = ContentScale.Crop, alpha = if (D) .55f else .85f)
    }
}

/** Google Messages chat theme picker: preview on top, colors and wallpapers below, Apply at the top right. */
@Composable
fun ChatThemeScreen(c: Conversation, onClose: (applied: Boolean) -> Unit) {
    val ctx = LocalContext.current
    var color by remember { mutableIntStateOf(ChatPalettes.indexOf(ChatThemeStore.palette(ctx, c.threadId)).coerceAtLeast(0)) }
    var wall by remember { mutableStateOf(ChatThemeStore.wallpaper(ctx, c.threadId)) }
    var ver by remember { mutableIntStateOf(0) }
    // A picked photo is only kept if you press Apply; Back puts the old wallpaper photo back.
    val backup = remember { java.io.File(ctx.cacheDir, "wall_bak_${c.threadId}.jpg") }
    var touched by remember { mutableStateOf(false) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u: Uri? ->
        if (u != null) runCatching {
            val f = ChatThemeStore.wallFile(ctx, c.threadId)
            if (!touched) { touched = true; if (f.exists()) f.copyTo(backup, true) else backup.delete() }
            ctx.contentResolver.openInputStream(u)?.use { ins -> f.outputStream().use { ins.copyTo(it) } }
            wall = "photo"; ver++
        }
    }
    fun cancel() {
        if (touched) runCatching { val f = ChatThemeStore.wallFile(ctx, c.threadId); if (backup.exists()) backup.copyTo(f, true) else f.delete() }
        onClose(false)
    }
    val pal = ChatPalettes[color]
    Dialog(onDismissRequest = { cancel() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { cancel() }
        Column(Modifier.fillMaxSize().background(Chrome).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ cancel() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary) }
                Column(Modifier.weight(1f)) {
                    Text("Chat theme", color = TextPrimary, fontSize = 20.sp)
                    Text("Only you will see these changes", color = TextSecond, fontSize = 13.sp)
                }
                Button({ ChatThemeStore.save(ctx, c.threadId, color, wall); onClose(true) }, shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = pal.primary, contentColor = if (D) Color.Black else Color.White), modifier = Modifier.padding(end = 10.dp)) { Text("Apply") }
            }
            // Preview
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(28.dp)).background(Bg)) {
                ChatWallpaper(c.threadId, wall, ver, Modifier.matchParentSize())
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Bottom) {
                    PreviewBubble("Did you see the new colors?", false, pal)
                    Spacer(Modifier.height(6.dp))
                    PreviewBubble("Yes! This one looks great 😍", true, pal)
                    Spacer(Modifier.height(6.dp))
                    PreviewBubble("Let's keep it", true, pal)
                }
            }
            TextButton({ ChatThemeStore.reset(ctx, c.threadId); onClose(true) }, Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.Outlined.Refresh, null, tint = TextPrimary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Reset to default", color = TextPrimary)
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(Surf).padding(16.dp)) {
                Text("Colors", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChatPalettes.forEachIndexed { i, p ->
                        Box(Modifier.size(52.dp).clip(CircleShape).border(if (i == color) 3.dp else 0.dp, if (i == color) TextPrimary else Color.Transparent, CircleShape).padding(4.dp).clip(CircleShape).clickable { color = i }) {
                            Column(Modifier.fillMaxSize()) {
                                Box(Modifier.weight(1f).fillMaxWidth().background(Color(p.lc)))
                                Row(Modifier.weight(1f).fillMaxWidth()) {
                                    Box(Modifier.weight(1f).fillMaxHeight().background(Color(p.lp)))
                                    Box(Modifier.weight(1f).fillMaxHeight().background(Color(p.dc)))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Wallpapers", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    FilledTonalButton({ pickPhoto.launch("image/*") }, shape = RoundedCornerShape(50)) {
                        Icon(Icons.Outlined.AddPhotoAlternate, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Choose a photo")
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WallTile("None", wall == null, Modifier.background(Bg)) { wall = null }
                    ChatGradients.forEachIndexed { i, g ->
                        WallTile(g.first, wall == "grad:$i", Modifier.background(Brush.verticalGradient((if (D) g.third else g.second).map { Color(it) }))) { wall = "grad:$i" }
                    }
                }
            }
        }
    }
}

@Composable
private fun WallTile(label: String, on: Boolean, bg: Modifier, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(width = 84.dp, height = 120.dp).clip(RoundedCornerShape(18.dp)).then(bg).border(if (on) 3.dp else 1.dp, if (on) TextPrimary else DivLt, RoundedCornerShape(18.dp)).clickable(onClick = onClick))
        Spacer(Modifier.height(4.dp))
        Text(label, color = TextSecond, fontSize = 12.sp)
    }
}

@Composable
private fun PreviewBubble(text: String, mine: Boolean, p: ChatPalette) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(text, color = if (mine) p.onContainer else TextPrimary, fontSize = 15.sp,
            modifier = Modifier.clip(RoundedCornerShape(22.dp)).background(if (mine) p.container else BubbleIn).padding(horizontal = 14.dp, vertical = 10.dp))
    }
}
