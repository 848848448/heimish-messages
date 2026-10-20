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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
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


/** The chat that is open on screen right now (no notification for it while you are looking at it). */
object ActiveThread { @Volatile var id: Long = -1L }

/** Optional short tone after a message goes out (Settings > Advanced > "Hear outgoing message sounds"). */
object SendSound {
    fun play(ctx: Context) {
        if (!ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE).getBoolean("sent_sound", false)) return
        runCatching {
            val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 35)
            tg.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 120)
            Handler(Looper.getMainLooper()).postDelayed({ runCatching { tg.release() } }, 400)
        }
    }
}

/** SMS length helper for the composer counter: (characters left in this segment, number of segments). */
fun smsCounter(text: String): Pair<Int, Int> = runCatching {
    val r = android.telephony.SmsMessage.calculateLength(text, false)
    r[2] to r[0]
}.getOrDefault(0 to 1)

@Composable
fun MessageDetailsDialog(m: Message, name: String, address: String, onClose: () -> Unit) {
    val fmt = remember { SimpleDateFormat("EEE, MMM d yyyy, h:mm:ss a", Locale.getDefault()) }
    val rows = buildList {
        add("Type" to if (m.isMms) "MMS" + (m.mediaMime?.let { " · $it" } ?: "") else "SMS (text)")
        if (m.incoming) add("From" to (m.address ?: address).let { a -> if (name != a && !a.contains(";")) "$name ($a)" else a })
        else add("To" to (m.address?.takeIf { it.isNotBlank() } ?: address).replace(";", ", "))
        if (m.incoming) {
            if (m.dateSent > 0) add("Sent" to fmt.format(Date(m.dateSent)))
            add("Received" to fmt.format(Date(m.date)))
        } else add("Sent" to fmt.format(Date(m.date)))
        if (!m.incoming) add("Status" to when { m.failed -> "Not sent"; m.sending -> "Sending"; m.delivered -> "Delivered"; else -> "Sent" })
        if (!m.isMms && m.body.isNotEmpty()) {
            val (_, segs) = smsCounter(m.body)
            add("Length" to "${m.body.length} characters · $segs ${if (segs == 1) "part" else "parts"}")
        }
        if (m.subId >= 0) add("SIM" to simLabel(LocalContext.current, m.subId))
    }
    androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Chrome) {
            Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).navigationBarsPadding()) {
                Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Icons.Default.ArrowBack, "Back", tint = TextPrimary) }
                    Text("Message details", fontSize = 22.sp, color = TextPrimary, modifier = Modifier.padding(start = 4.dp))
                }
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) { MessageBubble(m, 0, onLongClick = {}, showMeta = false) }
                Column(Modifier.fillMaxWidth().padding(16.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp)).background(Surf).padding(vertical = 6.dp)) {
                    rows.forEach { (k, v) ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(when (k) { "Type" -> Icons.Default.Sms; "From", "To" -> Icons.Default.Person; "Sent", "Received" -> Icons.Default.Schedule; "Status" -> Icons.Default.DoneAll; "Length" -> Icons.Default.Notes; else -> Icons.Default.SimCard },
                                null, tint = TextSecond, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(18.dp))
                            Column { Text(k, fontSize = 13.sp, color = TextSecond); Text(v, fontSize = 16.sp, color = TextPrimary) }
                        }
                    }
                }
            }
        }
    }
}

@android.annotation.SuppressLint("MissingPermission")
fun simLabel(ctx: Context, subId: Int): String = runCatching {
    val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as android.telephony.SubscriptionManager
    val info = sm.getActiveSubscriptionInfo(subId)
    if (info != null) "SIM ${info.simSlotIndex + 1} · ${info.displayName}" else "SIM $subId"
}.getOrDefault("SIM $subId")

/** "Warn about suspicious links": only for links from non-contacts that look like spam / shorteners / raw IPs. */
object LinkSafety {
    private val risky = listOf(
        Regex("(?i)https?://(bit\\.ly|tinyurl\\.com|cutt\\.ly|t\\.co|rb\\.gy|is\\.gd|shorturl\\.at|tiny\\.cc|ow\\.ly)/"),
        Regex("(?i)https?://\\d{1,3}(\\.\\d{1,3}){3}"),
        Regex("(?i)https?://[^/]*(-secure|-verify|-login|-account|xn--)[^/]*"),
        Regex("(?i)\\.(top|xyz|click|zip|mov|icu|cyou|rest|buzz)(/|$)")
    )
    fun shouldWarn(ctx: Context, m: Message, url: String): Boolean {
        val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("warn_links", true)) return false
        val from = m.address ?: return false
        if (SmsRepository.getContactName(ctx, from) != null) return false
        return risky.any { it.containsMatchIn(url) } || SpamFilter.isSpam(ctx, from, m.body)
    }
}

/** On-device reply suggestions (no internet, no AI service): picks chips from the last message, in Yiddish when it is in Hebrew letters. */
object SmartReplies {
    private fun hebrew(t: String) = t.any { it in '\u0590'..'\u05FF' }
    fun suggest(last: String): List<String> {
        val t = last.trim()
        val yi = hebrew(t)
        val q = t.endsWith("?") || t.endsWith("?")
        val thanks = Regex("(?i)\\b(thanks|thank you|thx)\\b|א דאנק|שכח|ת\"ח").containsMatchIn(t)
        val where = Regex("(?i)\\b(where|when|what time)\\b|וואו|ווען|וויפיל אזייגער").containsMatchIn(t)
        val code = Regex("\\b\\d{4,8}\\b").containsMatchIn(t) && Regex("(?i)code|otp|verif|קאוד|קוד").containsMatchIn(t)
        return when {
            code -> emptyList()
            yi && thanks -> listOf("👍", "ס'איז גארנישט", "❤️")
            yi && where -> listOf("איך בין אויפן וועג", "באלד", "איך וועל דיר לאזן וויסן")
            yi && q -> listOf("יא", "ניין", "אפשר", "איך וועל קוקן")
            yi -> listOf("אקעי", "👍", "א דאנק!", "איך בין אויפן וועג")
            thanks -> listOf("You're welcome!", "👍", "❤️")
            where -> listOf("On my way", "Soon", "I'll let you know")
            q -> listOf("Yes", "No", "Maybe", "Let me check")
            else -> listOf("OK", "👍", "Thanks!", "On my way")
        }
    }
}

object Translate {
    fun open(ctx: Context, text: String) {
        // Android's system translate (Google Translate / Lens) first, then the web as a fallback
        val tries = buildList {
            if (Build.VERSION.SDK_INT >= 29) add(Intent(Intent.ACTION_TRANSLATE).putExtra(Intent.EXTRA_TEXT, text))
            add(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").setPackage("com.google.android.apps.translate").putExtra(Intent.EXTRA_PROCESS_TEXT, text).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true))
            add(Intent(Intent.ACTION_VIEW, Uri.parse("https://translate.google.com/?sl=auto&tl=en&op=translate&text=" + Uri.encode(text))))
        }
        for (i in tries) { if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return }
    }
}
