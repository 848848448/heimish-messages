package com.heimish.messages

import androidx.compose.ui.composed
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
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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


// ━━━━━━━━━━━━━━━━ HELPERS ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
fun List<Message>.groupByDate(): List<Pair<String, List<Message>>> {
    if (isEmpty()) return emptyList()
    val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    val today = fmt.format(Date()); val yest = fmt.format(Date(System.currentTimeMillis() - 86_400_000))
    val g = LinkedHashMap<String, MutableList<Message>>()
    forEach { m -> val l = when (val d = fmt.format(Date(m.date))) { today -> "Today"; yest -> "Yesterday"; else -> if (Calendar.getInstance().apply { timeInMillis = m.date }.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)) SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(m.date)) else d }; g.getOrPut(l) { mutableListOf() }.add(m) }
    return g.map { it.key to it.value }
}

@Composable fun DayHeader(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 13.sp, color = TextSecond, fontWeight = FontWeight.Medium)
    }
}
/** Letter-avatar hues, Google Messages style: (solid tone, light container, on light, dark container, on dark). */
private class AvatarHue(val solid: Color, val lightBg: Color, val lightFg: Color, val darkBg: Color, val darkFg: Color)
private val avatarHues = listOf(
    AvatarHue(Color(0xFF0B57D0), Color(0xFFD3E3FD), Color(0xFF0842A0), Color(0xFF0842A0), Color(0xFFD3E3FD)), // blue
    AvatarHue(Color(0xFF146C2E), Color(0xFFC4EED0), Color(0xFF0A3818), Color(0xFF0F5223), Color(0xFFC4EED0)), // green
    AvatarHue(Color(0xFFB3261E), Color(0xFFFFDAD6), Color(0xFF8C1D18), Color(0xFF8C1D18), Color(0xFFFFDAD6)), // red
    AvatarHue(Color(0xFF7438D2), Color(0xFFEADDFF), Color(0xFF4F1B9A), Color(0xFF4F1B9A), Color(0xFFEADDFF)), // purple
    AvatarHue(Color(0xFF9C4A00), Color(0xFFFFDCC2), Color(0xFF6E3400), Color(0xFF6E3400), Color(0xFFFFDCC2)), // orange
    AvatarHue(Color(0xFF006A6A), Color(0xFFB2EBEB), Color(0xFF004F4F), Color(0xFF004F4F), Color(0xFFB2EBEB)), // teal
    AvatarHue(Color(0xFFA0306F), Color(0xFFFFD8EA), Color(0xFF7A1A52), Color(0xFF7A1A52), Color(0xFFFFD8EA)), // pink
    AvatarHue(Color(0xFF5B6300), Color(0xFFE0EA9C), Color(0xFF424A00), Color(0xFF424A00), Color(0xFFE0EA9C)), // olive
)
private fun hueFor(seed: String) = avatarHues[seed.hashCode().mod(avatarHues.size)]
internal val palette: List<Color> get() = avatarHues.map { it.solid }
/** Accent for a person (group-chat sender names): readable on light and dark backgrounds. */
fun avatarColor(seed: String): Color = hueFor(seed).let { if (D) it.lightBg else it.solid }
/** Solid tone that takes white text (notification avatars, which are drawn outside the app theme). */
fun avatarSolid(seed: String): Color = hueFor(seed).solid
/** Tonal letter-avatar container and letter colors, following light/dark mode. */
fun avatarBg(seed: String): Color = hueFor(seed).let { if (D) it.darkBg else it.lightBg }
fun avatarFg(seed: String): Color = hueFor(seed).let { if (D) it.darkFg else it.lightFg }

/** Shared empty state: a soft tonal circle with an icon, a title, and an optional hint. */
@Composable
fun EmptyState(icon: ImageVector, title: String, hint: String? = null, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(horizontal = 40.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(112.dp).clip(CircleShape).background(Tonal.copy(alpha = if (D) .55f else .7f)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(Tonal), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = OnTonal, modifier = Modifier.size(36.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(title, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
            if (hint != null) {
                Spacer(Modifier.height(6.dp))
                Text(hint, color = TextSecond, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
            }
        }
    }
}
fun initial(name: String): String = (name.firstOrNull()?.uppercaseChar() ?: '#').toString()
fun msgTime(ms: Long): String { if (ms <= 0) return ""; return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms)) }
fun shortTime(ms: Long): String {
    if (ms <= 0) return ""
    val now = Calendar.getInstance(); val t = Calendar.getInstance().apply { timeInMillis = ms }
    val dayDiff = ((now.apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis) - Calendar.getInstance().apply { timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis) / 86_400_000L
    return when {
        dayDiff <= 0 -> SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
        dayDiff == 1L -> "Yesterday"
        dayDiff < 7 -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(ms))
        t.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR) -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ms))
        else -> SimpleDateFormat("M/d/yy", Locale.getDefault()).format(Date(ms))
    }
}


object PhotoCache { val map = java.util.concurrent.ConcurrentHashMap<String, Uri>(); val miss: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet() }



/** Expressive press feedback: the tile squishes a little under the finger and springs back, with the normal ripple. */
fun Modifier.pressClick(shape: androidx.compose.ui.graphics.Shape, onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.93f else 1f,
        androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 700f), label = "press")
    this.graphicsLayer(scaleX = s, scaleY = s)
        .clip(shape)
        .clickable(interactionSource = src, indication = LocalIndication.current, onClick = onClick)
}
