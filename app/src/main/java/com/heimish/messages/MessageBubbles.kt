package com.heimish.messages

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.location.LocationManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Telephony
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt

// ── Swipe to reply wrapper ───────────────────────────────────────────────────
@Composable
fun SwipeToReplyBubble(m: Message, reactTick: Int, onReply: () -> Unit, onLongClick: () -> Unit, showMeta: Boolean = true, joinedAbove: Boolean = false, joinedBelow: Boolean = false, onTap: (() -> Unit)? = null) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    val threshold = 100f
    Box(Modifier.fillMaxWidth().pointerInput(m.id) {
        detectHorizontalDragGestures(
            onDragEnd = { if (abs(offsetX) > threshold) onReply(); offsetX = 0f },
            onHorizontalDrag = { _, dx ->
                offsetX = if (m.incoming) (offsetX + dx).coerceIn(0f, 200f) else (offsetX + dx).coerceIn(-200f, 0f)
            }
        )
    }) {
        if (abs(offsetX) > 20f) {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = if (m.incoming) Alignment.CenterStart else Alignment.CenterEnd) {
                Icon(Ms.Reply, null, tint = Brand.copy(alpha = (abs(offsetX) / threshold).coerceAtMost(1f)), modifier = Modifier.size(24.dp))
            }
        }
        Box(Modifier.absoluteOffset { IntOffset(offsetX.roundToInt(), 0) }) { // follows the finger in Hebrew/RTL too
            MessageBubble(m, reactTick, onLongClick, showMeta, joinedAbove, joinedBelow, onTap)
        }
    }
}

// ── Attachment panel button ─────────────────────────────────────────────────
@Composable
fun AttachBtn(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(width = 60.dp, height = 48.dp).clip(RoundedCornerShape(16.dp)).background(AttachSurf), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = TextSecond, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(5.dp))
        Text(label, fontSize = 11.sp, color = TextSecond, maxLines = 1)
    }
}

@Composable
fun MenuBtn(icon: ImageVector, label: String, tint: Color = TextPrimary, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 13.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (tint == TextPrimary) TextSecond else tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 15.sp, color = if (tint == Red) Red else TextPrimary)
    }
}

// ━━━━━━━━━━━━━━━━ EMOJI PICKER ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@Composable
fun EmojiPicker(onPick: (String) -> Unit) {
    val emojis = listOf(
        "😀","😂","🥹","😍","🤔","😎","🙏","❤️","👍","👎",
        "🔥","🎉","😢","😡","🤣","😊","🥰","😘","🤗","😏",
        "😭","😤","🤯","🫡","✨","💪","🙄","😴","🤝","👋",
        "💯","🎁","📱","✅","❌","⭐","🌙","☀️","🍕","🍔",
        "☕","🍎","🌹","🕊️","📖","✍️","😇","🤩","😜","🥺",
        "🙃","😬","🤫","🫠","💔","💕","💖","👏","🤞","✌️",
        "🤟","🫵","👀","😴","🎂","🕯️","🍷","🥖","🧀","🍇"
    )
    Surface(color = SheetBg, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        LazyVerticalGrid(GridCells.Fixed(8), Modifier.fillMaxWidth().height(220.dp).padding(horizontal = 8.dp, vertical = 8.dp)) {
            gridItems(emojis) { e ->
                Box(Modifier.size(44.dp).clip(CircleShape).clickable { onPick(e) }, contentAlignment = Alignment.Center) { Text(e, fontSize = 24.sp) }
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━ MESSAGE BUBBLE ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
/** The chat screen provides this so a tapped photo opens the swipeable viewer over all the chat's photos. */
val LocalPhotoOpener = compositionLocalOf<((Message) -> Unit)?> { null }

internal val URL_REGEX = Regex("(https?://[\\w\\-._~:/?#\\[\\]@!\$&'()*+,;=%]+|[\\w.+-]+@[\\w-]+\\.[\\w.-]+[A-Za-z]|\\+?\\d[\\d ().-]{7,}\\d)", RegexOption.IGNORE_CASE)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(m: Message, reactTick: Int, onLongClick: () -> Unit, showMeta: Boolean = true, joinedAbove: Boolean = false, joinedBelow: Boolean = false, onTap: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE) }
    val isIn = m.incoming
    val bubbleColor = if (ThemeState.chat != null) BubbleOut else remember(reactTick) { Color(prefs.getInt("bubble_color", BubbleOut.toArgb())) }
    val outColor = if (isIn) BubbleIn else bubbleColor
    val customOut = prefs.contains("bubble_color") && ThemeState.chat == null
    val outText = if (isIn) TextPrimary else if (customOut) Color.White else BubbleOutText
    val outSub = if (isIn) TextHint else if (customOut) Color.White.copy(.72f) else BubbleOutSub
    val linkColor = if (isIn) Brand else if (customOut) Color(0xFFCDE1FF) else BubbleOutLink
    val previewBg = if (isIn) (if (D) Color(0xFF5F6368) else Color(0xFFD8DEE9)) else if (customOut) BrandDk else (if (D) Color(0xFF7CACF8) else BrandDk)
    val textSize = remember(reactTick) { when (prefs.getString("text_size", "default")) {
        "small" -> 14.sp; "large" -> 18.sp; "largest" -> 20.sp; else -> 16.sp
    } }
    val previewMode = remember(reactTick) { prefs.getString("preview_mode", "links") ?: "links" }
    var vTick by remember { mutableIntStateOf(0) }
    val reaction = remember(reactTick, vTick) { ReactionStore.get(ctx, msgKey(m)) }
    val starred = remember(reactTick) { StarStore.isStarred(ctx, msgKey(m)) }
    val failed = m.failed
    val sending = m.sending
    var warnUrl by remember { mutableStateOf<String?>(null) }
    warnUrl?.let { u ->
        AlertDialog(onDismissRequest = { warnUrl = null }, icon = { Icon(Ms.GppMaybe, null, tint = Red) },
            title = { Text("Open this link?") },
            text = { Text("This link came from a number that isn't in your contacts and looks suspicious:\n\n$u") },
            confirmButton = { TextButton({ warnUrl = null; runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } }) { Text("Open anyway") } },
            dismissButton = { TextButton({ warnUrl = null }) { Text("Cancel") } })
    }
    var viewing by remember { mutableStateOf(false) }
    val openPhoto = LocalPhotoOpener.current
    if (viewing && m.imageUri != null) PhotoViewer(m.imageUri, onClose = { viewing = false }, title = if (isIn) (m.address?.let { a -> SmsRepository.getContactName(ctx, a) ?: a } ?: "") else "You", date = m.date, reactKey = msgKey(m), onReacted = { vTick++ })
    val cr = prefs.getInt("bubble_corner", 24).dp
    // Google Messages style: bubbles in a run from the same person get tight corners where they touch
    val tight = if (cr < 4.dp) cr else 4.dp
    val shape = RoundedCornerShape(
        topStart = if (isIn && joinedAbove) tight else cr,
        topEnd = if (!isIn && joinedAbove) tight else cr,
        bottomEnd = if (!isIn && joinedBelow) tight else cr,
        bottomStart = if (isIn && joinedBelow) tight else cr
    )
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = { onTap?.invoke() }, onLongClick = onLongClick, indication = null, interactionSource = remember { MutableInteractionSource() }),
            horizontalArrangement = if (isIn) Arrangement.Start else Arrangement.End
        ) {
            if (!isIn) Spacer(Modifier.width(56.dp))
            Column(horizontalAlignment = if (isIn) Alignment.Start else Alignment.End) {
            Box(Modifier.widthIn(max = 300.dp).clip(shape).background(outColor)) {
                Column {
                    if (m.imageUri != null) {
                        val mime = m.mediaMime ?: "image/"
                        when {
                            mime.startsWith("video/") -> Box(Modifier.fillMaxWidth().height(180.dp).background(Color.Black), contentAlignment = Alignment.Center) {
                                Icon(Ms.PlayCircleFill, null, tint = Color.White, modifier = Modifier.size(48.dp))
                            }
                            mime.startsWith("audio/") -> VoiceNoteBubble(m, m.imageUri, isIn, outText, outSub, onLongClick)
                            else -> AsyncImage(m.imageUri, "image", Modifier.fillMaxWidth().heightIn(max = 240.dp).clickable { if (openPhoto != null) openPhoto(m) else viewing = true }.clip(
                                if (m.body.isBlank()) shape else RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                            ), contentScale = ContentScale.Crop)
                        }
                    }
                    if (m.body.isNotBlank()) {
                        val urls = remember(m.body) { URL_REGEX.findAll(m.body).toList() }
                        if (urls.isEmpty()) {
                            Text(m.body, color = outText, fontSize = textSize, lineHeight = (textSize.value + 6).sp,
                                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = if (m.imageUri != null) 6.dp else 9.dp, bottom = 9.dp))
                        } else {
                            val annotated = remember(m.body, outText, linkColor) { buildAnnotatedString {
                                var last = 0
                                urls.forEach { match ->
                                    withStyle(SpanStyle(color = outText)) { append(m.body.substring(last, match.range.first)) }
                                    pushStringAnnotation("URL", match.value)
                                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(match.value) }
                                    pop()
                                    last = match.range.last + 1
                                }
                                if (last < m.body.length) withStyle(SpanStyle(color = outText)) { append(m.body.substring(last)) }
                            } }
                            ClickableText(annotated, style = androidx.compose.ui.text.TextStyle(fontFamily = FontState.family, fontSize = textSize, lineHeight = (textSize.value + 6).sp),
                                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = if (m.imageUri != null) 6.dp else 9.dp, bottom = 9.dp),
                                onClick = { off -> annotated.getStringAnnotations("URL", off, off).firstOrNull()?.let { a -> val v = a.item
                                if (v.startsWith("http", true) && isIn && LinkSafety.shouldWarn(ctx, m, v)) { warnUrl = v; return@let }
                                runCatching { ctx.startActivity(if (v.startsWith("http", true)) Intent(Intent.ACTION_VIEW, Uri.parse(v)) else if (v.contains("@")) Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + v)) else Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + v.filter { c -> c.isDigit() || c == '+' }))) } } })
                            if (previewMode != "none" && urls.any { it.value.startsWith("http", true) }) {
                                val url = urls.first { it.value.startsWith("http", true) }.value
                                val domain = remember(url) { try { java.net.URL(url).host.removePrefix("www.") } catch (_: Exception) { url } }
                                Box(Modifier.padding(horizontal = 10.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .background(previewBg)
                                    .clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.padding(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Ms.Language, null, tint = if (isIn) TextSecond else outSub, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(domain, fontSize = 12.sp, color = if (isIn) TextSecond else outSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
                if (showMeta || failed || sending || starred) {
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (failed) {
                            Icon(Ms.Error, "Failed", tint = Color(0xFFD93025), modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Not sent · tap to retry", fontSize = 11.sp, color = Color(0xFFD93025), fontWeight = FontWeight.Medium)
                            Spacer(Modifier.width(6.dp))
                        }
                        if (starred) {
                            Icon(Ms.StarFill, null, tint = Amber, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(3.dp))
                        }
                        Text(remember(m.date) { msgTime(m.date) }, fontSize = 11.sp, color = TextHint)
                        if (!isIn && !failed) {
                            Spacer(Modifier.width(3.dp))
                            Icon(
                                when {
                                    sending -> Ms.Schedule
                                    m.delivered -> Ms.DoneAll
                                    else -> Ms.Check
                                },
                                when { sending -> "Sending…"; m.delivered -> "Delivered"; else -> "Sent" },
                                tint = TextHint, modifier = Modifier.size(13.dp))
                        }
                    }
                }
            }
            if (isIn) Spacer(Modifier.width(56.dp))
        }
        if (reaction != null) {
            Box(
                Modifier.align(if (isIn) Alignment.BottomStart else Alignment.BottomEnd)
                    .padding(start = if (isIn) 6.dp else 0.dp, end = if (isIn) 0.dp else 6.dp)
                    .offset(y = 6.dp)
                    .clip(CircleShape).background(Surf).border(1.dp, DivLt, CircleShape)
                    .padding(horizontal = 5.dp, vertical = 3.dp)
            ) { Text(reaction, fontSize = 13.sp) }
        }
    }
}

