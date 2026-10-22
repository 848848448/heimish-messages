package com.heimish.messages

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ━━━━━━━━━━━━ Conversation details (Google Messages layout) ━━━━━━━━━━━━

@Composable
private fun DCard(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clip(RoundedCornerShape(24.dp)).background(Surf), content = content)

@Composable
private fun DRow(icon: ImageVector?, label: String, sub: String? = null, color: Color = TextPrimary, enabled: Boolean = true, trailing: (@Composable () -> Unit)? = null, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { click() }.heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = if (color == TextPrimary) TextSecond else color); Spacer(Modifier.width(18.dp)) }
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 16.sp, color = if (enabled) color else TextSecond, style = listTextStyle())
            if (sub != null) Text(sub, fontSize = 14.sp, color = TextSecond, style = listTextStyle())
        }
        trailing?.invoke()
    }
}

@Composable
private fun DHeader(text: String) = Text(text, fontSize = 14.sp, color = Brand, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 14.dp, bottom = 4.dp))

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatDetailsDialog(c: Conversation, msgs: List<Message>, onClose: () -> Unit, onTheme: () -> Unit = {}, onSearch: () -> Unit = {}, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val tid = c.threadId
    val isGroup = c.address.contains(";")
    val members = remember(c.address) { c.address.split(";").map { it.trim() }.filter { it.isNotBlank() } }
    var tick by remember { mutableIntStateOf(0) }
    val muted = remember(tick) { Mute.isMuted(ctx, tid) }
    val snooze = remember(tick) { snoozeLabel(ctx, tid) }
    val pinned = remember(tick) { PinStore.isPinned(ctx, tid) }
    val archived = remember(tick) { ArchiveStore.isArchived(ctx, tid) }
    var blocked by remember { mutableStateOf(false) }
    LaunchedEffect(tick) { blocked = withContext(Dispatchers.IO) { Blocker.isBlocked(ctx, c.address) } }
    var groupName by remember { mutableStateOf(if (isGroup) GroupInfo.name(ctx, tid) else null) }
    val title = groupName ?: c.displayName
    val contactUri by produceState<Uri?>(null, c.address, tick) { value = if (isGroup) null else withContext(Dispatchers.IO) { runCatching {
        val lu = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(c.address))
        ctx.contentResolver.query(lu, arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.LOOKUP_KEY), null, null, null)?.use { cu -> if (cu.moveToFirst()) ContactsContract.Contacts.getLookupUri(cu.getLong(0), cu.getString(1)) else null }
    }.getOrNull() } }
    val saved = contactUri != null || (c.displayName != c.address && !isGroup)

    var media by remember { mutableStateOf<Int?>(null) }
    var notifPage by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var member by remember { mutableStateOf<String?>(null) }
    var viewing by remember { mutableStateOf<Message?>(null) }
    var iconTick by remember { mutableIntStateOf(0) }

    val photos = remember(msgs) { msgs.filter { it.imageUri != null && (it.mediaMime ?: "image/").startsWith("image") }.sortedByDescending { it.date } }
    val linkCount = remember(msgs) { MediaRepo.links(msgs).size }
    val videoCount = remember(msgs) { msgs.count { (it.mediaMime ?: "").startsWith("video/") } }

    val pickPerson = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val u = r.data?.data ?: return@rememberLauncherForActivityResult
        val num = runCatching { ctx.contentResolver.query(u, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { cu -> if (cu.moveToFirst()) cu.getString(0) else null } }.getOrNull()
        if (num.isNullOrBlank()) { Toast.makeText(ctx, "That contact has no phone number", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
        openChatWith(ctx, members + num); onClose()
    }
    val pickIcon = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) {
            if (GroupInfo.setIcon(ctx, tid, u, c.address)) iconTick++ else Toast.makeText(ctx, "Couldn't use that picture", Toast.LENGTH_SHORT).show()
        }
    }
    // Take a new group photo with the camera (the app declares CAMERA, so the camera app only opens once it's granted)
    val camFile = remember { java.io.File(ctx.cacheDir, "group_photo_capture.jpg") }
    val takeIcon = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok && camFile.length() > 0) {
            if (GroupInfo.setIcon(ctx, tid, Uri.fromFile(camFile), c.address)) iconTick++ else Toast.makeText(ctx, "Couldn't use that picture", Toast.LENGTH_SHORT).show()
        }
        camFile.delete()
    }
    fun launchCamera() = runCatching {
        camFile.delete()
        takeIcon.launch(androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", camFile))
    }.onFailure { Toast.makeText(ctx, "No camera app found", Toast.LENGTH_SHORT).show() }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) launchCamera() }
    fun takeGroupPhoto() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) launchCamera()
        else camPerm.launch(android.Manifest.permission.CAMERA)
    }
    fun addPeople() = runCatching { pickPerson.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }.onFailure { Toast.makeText(ctx, "No contacts app found", Toast.LENGTH_SHORT).show() }

    androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Chrome) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
                Row(Modifier.padding(4.dp)) { IconButton(onClose) { Icon(Ms.ArrowBack, "Back", tint = TextPrimary) } }
                // ── Header: photo, name, number ──
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.clip(CircleShape).clickable(enabled = isGroup) { dialog = "icon" }) {
                        key(iconTick) { Avatar(title, c.address, 112.dp, 44.sp) }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(title, fontSize = 26.sp, color = TextPrimary, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(textDirection = TextDirection.Content), modifier = if (isGroup) Modifier.clickable { dialog = "name" } else Modifier)
                    if (!isGroup && c.displayName != c.address) Text(c.address, fontSize = 15.sp, color = TextSecond, style = TextStyle(textDirection = TextDirection.Ltr),
                        modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(8.dp)).combinedClickable(onLongClick = { copyText(ctx, c.address, "Number copied") }) {}.padding(horizontal = 6.dp, vertical = 2.dp))
                    if (isGroup) Text("${members.size} people", fontSize = 15.sp, color = TextSecond)
                    if (blocked) Text("Blocked", fontSize = 14.sp, color = Red, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(18.dp))
                // ── Quick actions ──
                @Composable fun Pill(icon: ImageVector, label: String, mod: Modifier, click: () -> Unit) {
                    Column(mod, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(Tonal).clickable { click() }, contentAlignment = Alignment.Center) { Icon(icon, label, tint = OnTonal) }
                        Spacer(Modifier.height(6.dp)); Text(label, fontSize = 13.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!isGroup) {
                        Pill(Ms.Call, "Call", Modifier.weight(1f)) { Caller.call(ctx, c.address) }
                        Pill(Ms.Videocam, "Video", Modifier.weight(1f)) { Caller.call(ctx, c.address, video = true) }
                        if (saved) Pill(Ms.AccountCircle, "Contact info", Modifier.weight(1f)) { openContact(ctx, c.address) }
                        else Pill(Ms.PersonAdd, "Add contact", Modifier.weight(1f)) { openContact(ctx, c.address) }
                    } else {
                        Pill(Ms.Edit, "Name", Modifier.weight(1f)) { dialog = "name" }
                        Pill(Ms.PersonAdd, "Add people", Modifier.weight(1f)) { addPeople() }
                    }
                    Pill(Ms.Search, "Search", Modifier.weight(1f)) { onSearch() }
                }
                Spacer(Modifier.height(14.dp))
                // ── Media ──
                DCard {
                    if (photos.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().clickable { media = 0 }.padding(start = 20.dp, end = 12.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Images", fontSize = 16.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                            Text("${photos.size}", fontSize = 14.sp, color = TextSecond); Icon(Ms.ChevronRight, null, tint = TextSecond)
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            photos.take(12).forEach { m -> coil.compose.AsyncImage(m.imageUri, null, Modifier.size(width = 92.dp, height = 120.dp).clip(RoundedCornerShape(16.dp)).background(FieldBg).clickable { viewing = m }, contentScale = ContentScale.Crop) }
                        }
                    }
                    val parts = listOfNotNull(if (videoCount > 0) "$videoCount videos" else null, if (linkCount > 0) "$linkCount links" else null)
                    DRow(Ms.PermMedia, "Videos, links, & more", parts.joinToString(" · ").ifBlank { null }, trailing = { Icon(Ms.ChevronRight, null, tint = TextSecond) }) { media = if (photos.isEmpty() && videoCount > 0) 1 else if (photos.isEmpty() && linkCount > 0) 2 else 0 }
                }
                // ── Look & notifications ──
                DCard {
                    DRow(Ms.Palette, "Change colors") { onTheme() }
                    DRow(Ms.NotificationsPaused, "Snooze chat", snooze?.takeIf { it.startsWith("Snoozed") }) { if (snooze != null) { Mute.clear(ctx, tid); tick++ } else dialog = "snooze" }
                    DRow(if (muted) Ms.NotificationsOff else Ms.Notifications, "Notifications", if (snooze != null) snooze else if (ConvNotif.custom(ctx, tid)) "On · " + ConvNotif.toneName(ctx, tid) else "On", trailing = { Icon(Ms.ChevronRight, null, tint = TextSecond) }) { notifPage = true }
                    if (Build.VERSION.SDK_INT >= 30) {
                        var bub by remember { mutableStateOf(ConvNotif.bubbleOn(ctx, tid)) }
                        val appOn = hp(ctx).getBoolean("bubbles", true)
                        val sysOff = Build.VERSION.SDK_INT >= 31 && runCatching { (ctx.getSystemService(android.app.NotificationManager::class.java)).bubblePreference == android.app.NotificationManager.BUBBLE_PREFERENCE_NONE }.getOrDefault(false)
                        DRow(Ms.Bubble, "Bubble this conversation", when { !appOn -> "Turned off in Settings › Notifications"; sysOff -> "Bubbles are off in Android settings"; else -> "Shows new messages as a floating chat head" },
                            trailing = { Switch(bub && appOn, { bub = it; ConvNotif.setBubble(ctx, tid, it) }, enabled = appOn && !sysOff) }) {
                            if (sysOff) runCatching { ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)) }
                            else if (appOn) { bub = !bub; ConvNotif.setBubble(ctx, tid, bub) }
                        }
                    }
                    val sims = remember { ConvSim.sims(ctx) }
                    if (sims.size > 1) {
                        var simTick by remember { mutableIntStateOf(0) }
                        val chosen = remember(simTick) { ConvSim.get(ctx, c.address) }
                        val last = remember(msgs) { msgs.lastOrNull { it.subId >= 0 }?.subId ?: -1 }
                        val sub = when { chosen >= 0 -> simLabel(ctx, chosen); last >= 0 -> "Last used: " + simLabel(ctx, last); else -> "Default SIM" }
                        DRow(Ms.SimCard, "SIM for this conversation", sub) { dialog = "sim" }
                        if (dialog == "sim") AlertDialog(onDismissRequest = { dialog = null }, containerColor = Surf, title = { Text("Send from") },
                            text = { Column {
                                @Composable fun Opt(label: String, id: Int) = Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { ConvSim.set(ctx, c.address, id); simTick++; dialog = null }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(chosen == id, { ConvSim.set(ctx, c.address, id); simTick++; dialog = null }); Spacer(Modifier.width(8.dp)); Text(label, fontSize = 16.sp) }
                                Opt("Default (from Settings)", -1)
                                sims.forEach { si -> Opt("SIM ${si.simSlotIndex + 1} · ${si.displayName}", si.subscriptionId) }
                            } }, confirmButton = { TextButton({ dialog = null }) { Text("Close", color = Brand) } })
                    }
                }
                // ── People ──
                DCard {
                    Text(if (isGroup) "${members.size} people" else "1 other person", fontSize = 14.sp, color = TextSecond, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 2.dp))
                    members.forEach { num ->
                        val name = remember(num) { SmsRepository.getContactName(ctx, num) ?: num }
                        Row(Modifier.fillMaxWidth().clickable { if (isGroup) member = num else openContact(ctx, num) }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(name, num, 44.dp, 18.sp); Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, fontSize = 16.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, style = listTextStyle())
                                if (name != num) Text(num, fontSize = 13.sp, color = TextSecond, style = TextStyle(textDirection = TextDirection.Ltr))
                            }
                        }
                    }
                    DRow(Ms.GroupAdd, if (isGroup) "Add people" else "Create group with ${c.displayName}") { addPeople() }
                }
                // ── Conversation ──
                DCard {
                    DRow(if (pinned) Ms.PushPinFill else Ms.PushPin, if (pinned) "Unpin conversation" else "Pin conversation") { PinStore.toggle(ctx, tid); tick++; Toast.makeText(ctx, if (pinned) "Unpinned" else "Pinned to the top", Toast.LENGTH_SHORT).show() }
                    DRow(if (muted) Ms.Notifications else Ms.NotificationsOff, if (muted) "Unmute" else "Mute") { if (muted) { Mute.clear(ctx, tid); tick++ } else dialog = "mute" }
                    DRow(if (archived) Ms.Unarchive else Ms.Archive, if (archived) "Unarchive" else "Archive") {
                        if (archived) { ArchiveStore.setArchived(ctx, tid, false); tick++; Toast.makeText(ctx, "Moved back to your chats", Toast.LENGTH_SHORT).show() }
                        else dialog = "archive"
                    }
                    DRow(Ms.Delete, "Delete conversation", color = Red) { dialog = "delete" }
                }
                DCard {
                    if (isGroup) {}
                    else if (blocked) DRow(Ms.Block, "Unblock", "You'll get calls and texts from ${c.displayName} again") { dialog = "unblock" }
                    else DRow(Ms.Block, "Block & report spam", color = Red) { dialog = "block" }
                }
                Text("Texts here go over SMS/MMS. Chat features like typing, read receipts and end-to-end encryption need RCS.", fontSize = 12.sp, color = TextSecond, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 14.dp))
            }
        }
    }

    // ── Sub-pages and dialogs ──
    media?.let { t -> MediaDialog(msgs, t) { media = null } }
    if (notifPage) ConvNotificationsPage(c, title) { notifPage = false; tick++ }
    viewing?.let { m -> ChatPhotoViewer(msgs, msgKey(m), c.address.contains(";"), title, onClose = { viewing = null }, onReacted = {}) }
    member?.let { num -> MemberSheet(num, members, onDone = { member = null; tick++ }, onLeaveScreen = onClose) }
    when (dialog) {
        "snooze", "mute" -> {
            val opts = listOf("1 hour" to 3_600_000L, "8 hours" to 28_800_000L, "24 hours" to 86_400_000L, if (dialog == "mute") "Always" to 0L else "1 week" to 604_800_000L)
            AlertDialog(onDismissRequest = { dialog = null }, containerColor = Surf, icon = { Icon(Ms.NotificationsPaused, null, tint = Brand) },
                title = { Text(if (dialog == "mute") "Mute notifications" else "Snooze notifications") },
                text = { Column { opts.forEach { (l, ms) -> Text(l, fontSize = 16.sp, color = TextPrimary, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { Mute.setFor(ctx, tid, ms); dialog = null; tick++ }.padding(vertical = 14.dp, horizontal = 8.dp)) } } },
                confirmButton = { TextButton({ dialog = null }) { Text("Cancel", color = Brand) } })
        }
        "archive" -> AlertDialog(onDismissRequest = { dialog = null }, containerColor = Surf, title = { Text("Archive conversation?") }, text = { Text("It leaves your chat list. New messages bring it back.") },
            confirmButton = { TextButton({ dialog = null; ArchiveStore.setArchived(ctx, tid, true); Toast.makeText(ctx, "Archived", Toast.LENGTH_SHORT).show(); onDelete() }) { Text("Archive", color = Brand) } },
            dismissButton = { TextButton({ dialog = null }) { Text("Cancel", color = Brand) } })
        "delete" -> AlertDialog(onDismissRequest = { dialog = null }, containerColor = Surf, title = { Text("Delete this conversation?") }, text = { Text("It moves to Trash, where you can restore it for 30 days.") },
            confirmButton = { TextButton({ dialog = null; TrashStore.add(ctx, tid); onDelete() }) { Text("Delete", color = Red) } },
            dismissButton = { TextButton({ dialog = null }) { Text("Cancel", color = Brand) } })
        "unblock" -> AlertDialog(onDismissRequest = { dialog = null }, containerColor = Surf, title = { Text("Unblock ${c.displayName}?") },
            confirmButton = { TextButton({ dialog = null; Blocker.unblock(ctx, c.address); SpamStore.remove(ctx, tid); tick++; Toast.makeText(ctx, "Unblocked", Toast.LENGTH_SHORT).show() }) { Text("Unblock", color = Brand) } },
            dismissButton = { TextButton({ dialog = null }) { Text("Cancel", color = Brand) } })
        "block" -> BlockDialog(c.displayName, onDismiss = { dialog = null }) { report, delete ->
            dialog = null
            Blocker.block(ctx, c.address)
            if (report) SpamStore.add(ctx, tid)
            if (delete) TrashStore.add(ctx, tid)
            Toast.makeText(ctx, if (report) "Blocked and reported as spam" else "Blocked", Toast.LENGTH_SHORT).show()
            onDelete()
        }
        "name" -> {
            var t by remember { mutableStateOf(groupName ?: "") }
            AlertDialog(onDismissRequest = { dialog = null }, containerColor = Surf, title = { Text("Group name") },
                text = { OutlinedTextField(t, { t = it }, singleLine = true, placeholder = { Text(c.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) }, textStyle = TextStyle(textDirection = TextDirection.Content, fontSize = 16.sp)) },
                confirmButton = { TextButton({ GroupInfo.setName(ctx, tid, t); groupName = GroupInfo.name(ctx, tid); dialog = null }) { Text("Save", color = Brand) } },
                dismissButton = { TextButton({ if (groupName != null) { GroupInfo.setName(ctx, tid, null); groupName = null }; dialog = null }) { Text(if (groupName != null) "Remove name" else "Cancel", color = Brand) } })
        }
        "icon" -> AlertDialog(onDismissRequest = { dialog = null }, containerColor = Surf, title = { Text("Group photo") },
            text = {
                Column {
                    @Composable fun Choice(icon: ImageVector, label: String, click: () -> Unit) {
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { dialog = null; click() }.padding(horizontal = 8.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, null, tint = TextSecond); Spacer(Modifier.width(16.dp)); Text(label, color = TextPrimary, fontSize = 16.sp)
                        }
                    }
                    Choice(Ms.Image, "Choose from gallery") { runCatching { pickIcon.launch("image/*") } }
                    Choice(Ms.PhotoCamera, "Take photo") { takeGroupPhoto() }
                    if (GroupInfo.iconUri(ctx, tid) != null) Choice(Ms.Delete, "Remove photo") { GroupInfo.setIcon(ctx, tid, null, c.address); iconTick++ }
                }
            },
            confirmButton = { TextButton({ dialog = null }) { Text("Cancel", color = Brand) } })
    }
}

@Composable
private fun BlockDialog(name: String, onDismiss: () -> Unit, onBlock: (report: Boolean, delete: Boolean) -> Unit) {
    var report by remember { mutableStateOf(true) }
    var delete by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Surf, icon = { Icon(Ms.Block, null, tint = Red) },
        title = { Text("Block $name?", textAlign = TextAlign.Center) },
        text = { Column {
            Text("You won't get calls or texts from this number. It's blocked for the whole phone.", color = TextSecond, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { report = !report }, verticalAlignment = Alignment.CenterVertically) { Checkbox(report, { report = it }); Text("Report as spam", fontSize = 16.sp) }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { delete = !delete }, verticalAlignment = Alignment.CenterVertically) { Checkbox(delete, { delete = it }); Text("Delete conversation", fontSize = 16.sp) }
        } },
        confirmButton = { TextButton({ onBlock(report, delete) }) { Text("Block", color = Red) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = Brand) } })
}

/** A person in a group: message, call, contact, copy, remove (starts a new group), block. */
@Composable
private fun MemberSheet(num: String, members: List<String>, onDone: () -> Unit, onLeaveScreen: () -> Unit) {
    val ctx = LocalContext.current
    val name = remember(num) { SmsRepository.getContactName(ctx, num) ?: num }
    var confirmRemove by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(Blocker.isBlocked(ctx, num)) }
    if (confirmRemove) {
        AlertDialog(onDismissRequest = { confirmRemove = false }, containerColor = Surf, title = { Text("Remove $name?") },
            text = { Text("SMS/MMS groups can't change who's in them. This starts a new group without $name, and this one stays as it is.") },
            confirmButton = { TextButton({ confirmRemove = false; onDone(); openChatWith(ctx, members - num); onLeaveScreen() }) { Text("Start new group", color = Brand) } },
            dismissButton = { TextButton({ confirmRemove = false }) { Text("Cancel", color = Brand) } })
        return
    }
    AlertDialog(onDismissRequest = onDone, containerColor = Surf,
        icon = { Avatar(name, num, 56.dp, 22.sp) },
        title = { Text(name, textAlign = TextAlign.Center, style = TextStyle(textDirection = TextDirection.Content)) },
        text = { Column {
            @Composable fun Act(icon: ImageVector, label: String, color: Color = TextPrimary, f: () -> Unit) = Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { f() }.padding(vertical = 12.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = if (color == TextPrimary) TextSecond else color); Spacer(Modifier.width(16.dp)); Text(label, fontSize = 16.sp, color = color) }
            Act(Ms.Chat, "Message") { onDone(); openChatWith(ctx, listOf(num)); onLeaveScreen() }
            Act(Ms.Call, "Call") { Caller.call(ctx, num); onDone() }
            Act(if (name == num) Ms.PersonAdd else Ms.AccountCircle, if (name == num) "Add contact" else "View contact") { openContact(ctx, num); onDone() }
            Act(Ms.ContentCopy, "Copy number") { copyText(ctx, num, "Number copied"); onDone() }
            if (members.size > 2) Act(Ms.PersonRemove, "Remove from group") { confirmRemove = true }
            if (blocked) Act(Ms.Block, "Unblock") { Blocker.unblock(ctx, num); blocked = false; Toast.makeText(ctx, "Unblocked", Toast.LENGTH_SHORT).show(); onDone() }
            else Act(Ms.Block, "Block", Red) { Blocker.block(ctx, num); blocked = true; Toast.makeText(ctx, "Blocked $name", Toast.LENGTH_SHORT).show(); onDone() }
        } },
        confirmButton = { TextButton(onDone) { Text("Close", color = Brand) } })
}

/** Details › Notifications: on/off, Priority / Default / Silent, sound, vibrate, bubble, Android's own channel page. */
@Composable
fun ConvNotificationsPage(c: Conversation, title: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val tid = c.threadId
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { ConvNotif.syncFromSystem(ctx, tid); tick++ }
    val on = remember(tick) { !Mute.isMuted(ctx, tid) }
    val snooze = remember(tick) { snoozeLabel(ctx, tid) }
    val imp = remember(tick) { ConvNotif.importance(ctx, tid) }
    val vib = remember(tick) { ConvNotif.vibrate(ctx, tid) }
    val toneName = remember(tick) { ConvNotif.toneName(ctx, tid) }
    val toneL = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) {
            val u = if (Build.VERSION.SDK_INT >= 33) r.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) else @Suppress("DEPRECATION") r.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            val t = when { u == null -> ""; u == Settings.System.DEFAULT_NOTIFICATION_URI -> "default"; else -> u.toString() }
            hp(ctx).edit().putString("cn_name_$tid", title).apply()
            ConvNotif.set(ctx, tid, tone = t, importance = if (t.isNotEmpty() && imp == ConvNotif.IMP_SILENT) ConvNotif.IMP_DEFAULT else null); tick++
        }
    }
    fun pickTone() = runCatching {
        val cur = ConvNotif.tone(ctx, tid)
        toneL.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Notification sound")
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true).putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_NOTIFICATION_URI)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, when (cur) { "default" -> Settings.System.DEFAULT_NOTIFICATION_URI; "" -> null; else -> Uri.parse(cur) }))
    }
    fun setImp(v: Int) { hp(ctx).edit().putString("cn_name_$tid", title).apply(); ConvNotif.set(ctx, tid, importance = v); tick++ }

    androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Chrome) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
                Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Ms.ArrowBack, "Back", tint = TextPrimary) }
                    Text("Notifications", fontSize = 22.sp, color = TextPrimary)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(title, c.address, 40.dp, 16.sp); Spacer(Modifier.width(14.dp))
                    Text(title, fontSize = 16.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, style = listTextStyle())
                }
                // Big on/off, like Android's own conversation page
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(28.dp)).background(if (on) Tonal else FieldBg)
                    .clickable { if (on) Mute.setFor(ctx, tid, 0) else Mute.clear(ctx, tid); tick++ }.padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Show notifications", fontSize = 18.sp, color = if (on) OnTonal else TextPrimary, modifier = Modifier.weight(1f))
                    Switch(on, { if (it) Mute.clear(ctx, tid) else Mute.setFor(ctx, tid, 0); tick++ })
                }
                if (snooze?.startsWith("Snoozed") == true) Text(snooze, fontSize = 13.sp, color = TextSecond, modifier = Modifier.padding(horizontal = 32.dp))
                if (on) {
                    DHeader("Behavior")
                    DCard {
                        @Composable fun Choice(v: Int, icon: ImageVector, label: String, sub: String) {
                            val sel = imp == v
                            Row(Modifier.fillMaxWidth().clickable { setImp(v) }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(40.dp).clip(CircleShape).background(if (sel) Brand else FieldBg), contentAlignment = Alignment.Center) { Icon(icon, null, tint = if (sel) Surf else TextSecond, modifier = Modifier.size(22.dp)) }
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) { Text(label, fontSize = 16.sp, color = TextPrimary, fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal); Text(sub, fontSize = 13.sp, color = TextSecond) }
                                RadioButton(sel, { setImp(v) })
                            }
                        }
                        Choice(ConvNotif.IMP_PRIORITY, Ms.NotificationImportant, "Priority", "Pops on screen, with sound or vibration")
                        Choice(ConvNotif.IMP_DEFAULT, Ms.Notifications, "Default", "Sound or vibration, no pop-up")
                        Choice(ConvNotif.IMP_SILENT, Ms.NotificationsOff, "Silent", "No sound or vibration")
                    }
                    DHeader("Sound & vibration")
                    DCard {
                        DRow(Ms.MusicNote, "Sound", toneName, enabled = imp != ConvNotif.IMP_SILENT) { pickTone() }
                        DRow(Ms.Vibration, "Vibrate", enabled = imp != ConvNotif.IMP_SILENT, trailing = {
                            Switch(vib && imp != ConvNotif.IMP_SILENT, { hp(ctx).edit().putString("cn_name_$tid", title).apply(); ConvNotif.set(ctx, tid, vibrate = it); tick++ }, enabled = imp != ConvNotif.IMP_SILENT)
                        }) { ConvNotif.set(ctx, tid, vibrate = !vib); tick++ }
                        if (Build.VERSION.SDK_INT >= 30) {
                            var bub by remember { mutableStateOf(ConvNotif.bubbleOn(ctx, tid)) }
                            DRow(Ms.Bubble, "Bubble", "Floating chat head for this conversation", trailing = { Switch(bub, { bub = it; ConvNotif.setBubble(ctx, tid, it) }) }) { bub = !bub; ConvNotif.setBubble(ctx, tid, bub) }
                        }
                    }
                }
                DHeader("More")
                DCard {
                    DRow(Ms.OpenInNew, "More settings", "Android's notification page for this conversation") { ConvNotif.openSystemSettings(ctx, tid, title) }
                    if (ConvNotif.custom(ctx, tid)) DRow(Ms.Refresh, "Reset to app defaults") { ConvNotif.reset(ctx, tid); tick++; Toast.makeText(ctx, "Uses the app's notification settings again", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }
}

private fun hp(ctx: android.content.Context) = ctx.getSharedPreferences("heimish_prefs", android.content.Context.MODE_PRIVATE)
