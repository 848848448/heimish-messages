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


// ━━━━━━━━━━━━━━━━ SETTINGS ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onSub: (String) -> Unit, onBack: () -> Unit) {
    Scaffold(containerColor = Bg,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary),
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
            title = { Text("Settings", fontWeight = FontWeight.SemiBold) }) }
    ) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            item { SettingsHeader("General") }
            item { SettRow(Icons.Default.Notifications, "Notifications", "Sounds, vibration, previews") { onSub("notifications") } }
            item { SettRow(Icons.Default.Shield, "Protection & safety", "Spam and blocked numbers") { onSub("safety") } }
            item { SettRow(Icons.Default.SwipeRight, "Swipe actions", "What swiping a chat does") { onSub("swipe") } }
            item { SettRow(Icons.Default.Tune, "Advanced", "Delivery reports, MMS") { onSub("advanced") } }
            item { SettingsHeader("Appearance") }
            item { SettRow(Icons.Default.Palette, "Theme", "System, light or dark") { onSub("theme") } }
            item { SettRow(Icons.Default.ChatBubble, "Chat bubbles", "Bubble color") { onSub("bubbles") } }
            item { SettRow(Icons.Default.FormatSize, "Text size", "Message text size") { onSub("textsize") } }
            item { SettRow(Icons.Default.Link, "Automatic previews", "Web link previews") { onSub("previews") } }
            item { SettingsHeader("Privacy & storage") }
            item { SettRow(Icons.Default.Report, "Spam protection", "Filter scam texts") { onSub("x_spam") } }
            item { SettRow(Icons.Default.Lock, "App lock", "Fingerprint, face or PIN") { onSub("x_lock") } }
            item { SettRow(Icons.Default.DeleteSweep, "Verification codes", "Auto-delete old codes") { onSub("x_otp") } }
            item { SettRow(Icons.Default.SimCard, "SIM card & group texts", "Choose SIM, group MMS") { onSub("x_sim") } }
            item { SettRow(Icons.Default.NotificationsOff, "Quiet hours", "Silent messages at night") { onSub("x_dnd") } }
            item { SettRow(Icons.Default.Reply, "Quick replies", "Edit your canned replies") { onSub("x_quick") } }
            item { SettRow(Icons.Default.TextFields, "Large text & display", "Bigger text everywhere") { onSub("x_access") } }
            item { SettRow(Icons.Default.Palette, "Chat style", "Bubble shape and backgrounds") { onSub("x_style") } }
            item { SettRow(Icons.Default.Backup, "Backup & restore", "Save or restore your texts") { onSub("x_backup") } }
            item { SettingsHeader("About") }
            item { SettRow(Icons.Default.Info, "About Message", "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.BUILD_NUMBER})") { onSub("about") } }
        }
    }
}

@Composable fun SettingsHeader(label: String) {
    Text(label.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Brand, letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 18.dp, top = 18.dp, bottom = 6.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingSubScreen(key: String, onThemeChange: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
    val title = when (key) { "notifications" -> "Notifications"; "bubbles" -> "Chat bubbles"; "theme" -> "Theme"; "textsize" -> "Text size"
        "previews" -> "Automatic previews"; "safety" -> "Protection & safety"; "swipe" -> "Swipe actions"; "advanced" -> "Advanced"; "about" -> "About"; else -> "Settings" }
    var blockedTick by remember { mutableIntStateOf(0) }
    val blocked = remember(blockedTick) { BlockedStore.getAll(ctx).toList() }
    var themeNow by remember { mutableStateOf(prefs.getString("theme", "system") ?: "system") }

    Scaffold(containerColor = Bg,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary),
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } }, title = { Text(title, fontWeight = FontWeight.SemiBold) }) }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).padding(horizontal = 18.dp)) {
            when (key) {
                "notifications" -> {
                    item { ToggleRow("Allow notifications", "notif_allow", prefs) }
                    item { ToggleRow("Sound", "notif_sound", prefs) }
                    item { ToggleRow("Vibrate", "notif_vibrate", prefs) }
                    item { ToggleRow("Show sender name", "notif_sender", prefs) }
                    item { ToggleRow("Show message preview", "notif_preview", prefs) }
                    item {
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = {
                            Notifications.showTestNotification(ctx)
                            Toast.makeText(ctx, "Test notification sent", Toast.LENGTH_SHORT).show()
                        }, colors = ButtonDefaults.buttonColors(Tonal, OnTonal), shape = RoundedCornerShape(50)) {
                            Text("Send test notification")
                        }
                    }
                }
                "bubbles" -> {
                    item { Spacer(Modifier.height(10.dp)); Text("Your bubble color", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPrimary); Spacer(Modifier.height(12.dp)) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf(Color(0xFF0B57D0), Color(0xFF1E8E3E), Color(0xFF8C4A2E), Color(0xFF6A2E8C), Color(0xFFD93025), Color(0xFF185ABC), Color(0xFFE65100), Color(0xFF00897B)).forEach { clr ->
                                val sel = prefs.contains("bubble_color") && prefs.getInt("bubble_color", 0) == clr.toArgb()
                                Box(Modifier.size(44.dp).clip(CircleShape).background(clr).border(if (sel) 3.dp else 0.dp, if (sel) TextPrimary else Color.Transparent, CircleShape).clickable {
                                    prefs.edit().putInt("bubble_color", clr.toArgb()).apply()
                                    Toast.makeText(ctx, "Color updated — open a chat to see it", Toast.LENGTH_SHORT).show()
                                })
                            }
                        }
                    }
                    item {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = {
                            prefs.edit().remove("bubble_color").apply()
                            Toast.makeText(ctx, "Back to theme default", Toast.LENGTH_SHORT).show()
                        }) { Text("Reset to theme default", color = Brand) }
                    }
                    item {
                        Spacer(Modifier.height(14.dp))
                        Text("Preview", fontSize = 13.sp, color = TextHint)
                        Spacer(Modifier.height(8.dp))
                        val pvColor = if (prefs.contains("bubble_color")) Color(prefs.getInt("bubble_color", BubbleOut.toArgb())) else BubbleOut
                        val pvText = if (prefs.contains("bubble_color")) Color.White else BubbleOutText
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp, 20.dp, 5.dp, 20.dp)).background(pvColor).padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Column {
                                Text("Hello! This is how your messages look.", color = pvText, fontSize = 15.sp)
                                Text("3:25 PM ✓", color = pvText.copy(.72f), fontSize = 11.sp, modifier = Modifier.align(Alignment.End))
                            }
                        }
                    }
                }
                "theme" -> {
                    item { RadioRow("System default", themeNow == "system") { prefs.edit().putString("theme", "system").apply(); themeNow = "system"; onThemeChange("system") } }
                    item { RadioRow("Light", themeNow == "light") { prefs.edit().putString("theme", "light").apply(); themeNow = "light"; onThemeChange("light") } }
                    item { RadioRow("Dark", themeNow == "dark") { prefs.edit().putString("theme", "dark").apply(); themeNow = "dark"; onThemeChange("dark") } }
                    item { HorizontalDivider(Modifier.padding(vertical = 8.dp), color = DivLt) }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) item {
                        var dyn by remember { mutableStateOf(prefs.getBoolean("dynamic_color", true)) }
                        SettingSwitch("Wallpaper colors", "Material You colors from your wallpaper", dyn) { dyn = it; prefs.edit().putBoolean("dynamic_color", it).apply(); ThemeState.version++ }
                    }
                    item {
                        var black by remember { mutableStateOf(prefs.getBoolean("amoled", false)) }
                        SettingSwitch("Pure black dark mode", "True black background in dark mode, easy on OLED screens", black) { black = it; prefs.edit().putBoolean("amoled", it).apply(); ThemeState.version++ }
                    }
                }
                "textsize" -> {
                    item { RadioRow("Small", prefs.getString("text_size", "default") == "small") { prefs.edit().putString("text_size", "small").apply() } }
                    item { RadioRow("Default", prefs.getString("text_size", "default") == "default") { prefs.edit().putString("text_size", "default").apply() } }
                    item { RadioRow("Large", prefs.getString("text_size", "default") == "large") { prefs.edit().putString("text_size", "large").apply() } }
                    item { RadioRow("Largest", prefs.getString("text_size", "default") == "largest") { prefs.edit().putString("text_size", "largest").apply() } }
                    item { Spacer(Modifier.height(8.dp)); Text("Applies to message bubbles.", fontSize = 13.sp, color = TextHint) }
                }
                "previews" -> {
                    item { RadioRow("All previews", prefs.getString("preview_mode", "links") == "all") { prefs.edit().putString("preview_mode", "all").apply() } }
                    item { RadioRow("Links only", prefs.getString("preview_mode", "links") == "links") { prefs.edit().putString("preview_mode", "links").apply() } }
                    item { RadioRow("No previews", prefs.getString("preview_mode", "links") == "none") { prefs.edit().putString("preview_mode", "none").apply() } }
                }
                "safety" -> {
                    item { ToggleRow("Spam protection", "spam_filter", prefs) }
                    item { KeywordEditor() }
                    item { AutoReplyEditor() }
                    item { ToggleRow("Warn about suspicious links", "warn_links", prefs) }
                    item {
                        Spacer(Modifier.height(18.dp))
                        Text("Blocked numbers", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPrimary)
                        Spacer(Modifier.height(4.dp))
                    }
                    if (blocked.isEmpty()) {
                        item { Text("No blocked numbers", color = TextHint, fontSize = 14.sp) }
                    } else {
                        items(blocked, key = { it }) { num ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(num, fontSize = 14.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                                TextButton(onClick = { BlockedStore.unblock(ctx, num); blockedTick++ }) { Text("Unblock", color = Brand) }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(4.dp)); Text("Blocked numbers are hidden and won't notify you.", fontSize = 12.sp, color = TextHint) }
                }
                "swipe" -> {
                    item { RadioRow("Archive", prefs.getString("swipe_action", "archive") == "archive") { prefs.edit().putString("swipe_action", "archive").apply() } }
                    item { RadioRow("Delete", prefs.getString("swipe_action", "archive") == "delete") { prefs.edit().putString("swipe_action", "delete").apply() } }
                    item { RadioRow("Mark as read", prefs.getString("swipe_action", "archive") == "read") { prefs.edit().putString("swipe_action", "read").apply() } }
                    item { RadioRow("Nothing", prefs.getString("swipe_action", "archive") == "none") { prefs.edit().putString("swipe_action", "none").apply() } }
                }
                "advanced" -> {
                    item { ToggleRow("SMS delivery reports", "sms_delivery", prefs, false) }
                    item { ToggleRow("MMS delivery reports", "mms_delivery", prefs, false) }
                    item { ToggleRow("Group MMS", "group_mms", prefs) }
                    item { ToggleRow("Hear outgoing message sounds", "sent_sound", prefs, false) }
                    item { ToggleRow("Press Enter to send", "enter_send", prefs, false) }
                    item { ToggleRow("Notify me about app updates", "upd_notify", prefs) }
                    item {
                        val scope = rememberCoroutineScope()
                        var showConfirm by remember { mutableStateOf(false) }
                        Spacer(Modifier.height(20.dp))
                        OutlinedButton(onClick = { showConfirm = true }, shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Red),
                            border = BorderStroke(1.dp, Red.copy(.5f))) {
                            Text("Delete all conversations")
                        }
                        if (showConfirm) {
                            AlertDialog(
                                onDismissRequest = { showConfirm = false },
                                containerColor = Surf,
                                shape = RoundedCornerShape(28.dp),
                                title = { Text("Delete all conversations?", color = TextPrimary) },
                                text = { Text("This permanently deletes every SMS and MMS on this device. This can't be undone.", color = TextSecond) },
                                confirmButton = {
                                    TextButton(onClick = {
                                        showConfirm = false
                                        scope.launch(Dispatchers.IO) { SmsRepository.deleteAllConversations(ctx) }
                                        Toast.makeText(ctx, "All conversations deleted", Toast.LENGTH_SHORT).show()
                                    }) { Text("Delete", color = Red) }
                                },
                                dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Cancel", color = Brand) } }
                            )
                        }
                    }
                }
                "about" -> {
                    item {
                        Spacer(Modifier.height(20.dp))
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(72.dp).clip(CircleShape).background(Brand), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Chat, null, tint = OnBrand, modifier = Modifier.size(36.dp))
                            }
                            Spacer(Modifier.height(14.dp))
                            Text("Message", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = TextPrimary)
                            Text("Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.BUILD_NUMBER})", fontSize = 14.sp, color = TextHint)
                            Spacer(Modifier.height(14.dp))
                            Text("Simple, fast text messaging.", fontSize = 14.sp, color = TextSecond, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(20.dp))
                            var checking by remember { mutableStateOf(false) }
                            var found by remember { mutableStateOf<UpdateInfo?>(null) }
                            var msg by remember { mutableStateOf("") }
                            val sc = rememberCoroutineScope()
                            found?.let { UpdateCard(it, null) }
                            if (found == null) {
                                Button(onClick = {
                                    if (!checking) { checking = true; msg = ""; sc.launch {
                                        val i = withContext(Dispatchers.IO) { Updater.fetchLatest() }
                                        checking = false
                                        if (i == null) msg = "Could not check: " + Updater.lastError.ifBlank { "unknown error" }
                                        else if (Updater.isNewer(i)) found = i else msg = "You're up to date"
                                    } }
                                }, colors = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = OnBrand)) { Text(if (checking) "Checking…" else "Check for updates") }
                                if (msg.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(msg, fontSize = 13.sp, color = TextHint) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable fun ToggleRow(label: String, key: String, prefs: android.content.SharedPreferences, default: Boolean = true) {
    var on by remember { mutableStateOf(prefs.getBoolean(key, default)) }
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 15.sp, color = TextPrimary); Switch(on, { on = it; prefs.edit().putBoolean(key, it).apply() }, colors = SwitchDefaults.colors(checkedTrackColor = Brand, checkedThumbColor = OnBrand))
    }
}
@Composable fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick, colors = RadioButtonDefaults.colors(selectedColor = Brand)); Spacer(Modifier.width(12.dp)); Text(label, fontSize = 15.sp, color = TextPrimary)
    }
}
@Composable fun SettRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = TextSecond, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) { Text(title, fontSize = 16.sp, color = TextPrimary); Spacer(Modifier.height(1.dp)); Text(sub, fontSize = 13.sp, color = TextHint) }
    }
}

// ━━━━━━━━━━━━━━━━ ADMIN ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    var resLines by remember { mutableStateOf(emptyList<String>()) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val configured = BuildConfig.WORKER_URL.isNotBlank() && BuildConfig.ADMIN_TOKEN.isNotBlank()

    fun search(query: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val url = java.net.URL("${BuildConfig.WORKER_URL}/contacts?q=${java.net.URLEncoder.encode(query, "UTF-8")}")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.ADMIN_TOKEN}")
                conn.connectTimeout = 10000; conn.readTimeout = 10000
                val r = conn.inputStream.bufferedReader().readText(); conn.disconnect()
                val json = org.json.JSONObject(r)
                val arr = json.optJSONArray("contacts")
                val lines = mutableListOf<String>()
                if (arr != null) for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    lines.add("${c.optString("name")}  ·  ${c.optString("addr", c.optString("phone"))}" + (if (c.has("_device")) "  (${c.optString("_device")})" else ""))
                }
                withContext(Dispatchers.Main) {
                    resLines = lines
                    status = "${json.optInt("results")} of ${json.optInt("total")} contacts"
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { resLines = emptyList(); status = "Error: ${e.message}" } }
        }
    }

    Scaffold(containerColor = Bg,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary),
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } }, title = { Text("Admin panel", fontWeight = FontWeight.SemiBold) }) }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).padding(16.dp)) {
            item {
                SettingsHeader("Contact search")
                if (!configured) {
                    Text("Contact search is not configured in this build.", fontSize = 14.sp, color = TextSecond)
                } else {
                    TextField(q, {
                        q = it; if (it.isNotBlank()) search(it) else { resLines = emptyList(); status = "" }
                    }, Modifier.fillMaxWidth(), placeholder = { Text("Search name or number…", color = TextHint) }, singleLine = true, shape = RoundedCornerShape(28.dp),
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = TextHint) },
                        colors = TextFieldDefaults.colors(focusedContainerColor = SearchBg, unfocusedContainerColor = SearchBg, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Brand))
                }
                Spacer(Modifier.height(12.dp))
            }
            items(resLines) { line -> Text(line, fontSize = 14.sp, color = TextPrimary, modifier = Modifier.padding(vertical = 4.dp)) }
            if (status.isNotBlank()) item { Text(status, fontSize = 12.sp, color = TextHint, modifier = Modifier.padding(vertical = 6.dp)) }
            item {
                SettingsHeader("Device info")
                Text("Model: ${Build.MODEL}", fontSize = 14.sp, color = TextPrimary)
                Text("Android: ${Build.VERSION.RELEASE}", fontSize = 14.sp, color = TextSecond)
                Text("Default SMS app: ${if (isDefaultSmsApp(ctx)) "yes" else "no"}", fontSize = 14.sp, color = TextSecond); Spacer(Modifier.height(12.dp))
                if (configured) {
                    Button({ ContactsSyncService.syncNow(ctx); Toast.makeText(ctx, "Syncing…", Toast.LENGTH_SHORT).show() }, colors = ButtonDefaults.buttonColors(Tonal, OnTonal), shape = RoundedCornerShape(50)) { Text("Sync contacts now") }
                }
            }
        }
    }
}


@Composable fun SettingSwitch(label: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label, fontSize = 15.sp, color = TextPrimary); Text(sub, fontSize = 13.sp, color = TextSecond) }
        Spacer(Modifier.width(12.dp))
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Brand, checkedThumbColor = OnBrand))
    }
}
