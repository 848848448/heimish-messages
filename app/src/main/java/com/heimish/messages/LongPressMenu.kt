package com.heimish.messages

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One row of the Google Messages long-press menu. */
class LongPressAction(val icon: ImageVector, val label: String, val tint: Color? = null, val onClick: () -> Unit)

internal val QuickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "😡")

/**
 * Google Messages (2025) long-press: the chat behind is blurred and dimmed, the message is lifted
 * in place with a pill of reactions above it and a rounded menu card under it, on the message's side.
 */
@Composable
fun LongPressOverlay(
    m: Message,
    reactTick: Int,
    bubbleY: Float?,
    currentReaction: String?,
    actions: List<LongPressAction>,
    onReact: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    var appear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appear = true }
    val a by animateFloatAsState(if (appear) 1f else 0f, tween(160), label = "lp")
    var moreEmoji by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val pad = with(density) { 24.dp.roundToPx() }
    val barH = with(density) { 64.dp.roundToPx() }
    // bubbleY is measured from the top of the window, but this layout starts below the status bar
    val topInset = WindowInsets.systemBars.getTop(density)
    Box(
        // Android 12+ blurs the chat behind; older phones can't blur, so they get a darker dim instead
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = (if (android.os.Build.VERSION.SDK_INT >= 31) (if (D) .45f else .22f) else (if (D) .62f else .45f)) * a))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .systemBarsPadding()
    ) {
        Layout(content = {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp).alpha(a).scale(.96f + .04f * a),
                horizontalAlignment = if (m.incoming) Alignment.Start else Alignment.End
            ) {
                // Reactions pill
                Surface(shape = RoundedCornerShape(32.dp), color = MenuSurf, shadowElevation = 3.dp, tonalElevation = 0.dp,
                    modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        QuickReactions.forEach { e ->
                            val on = currentReaction == e
                            Box(Modifier.size(46.dp).clip(CircleShape).background(if (on) Tonal else Color.Transparent).clickable { onReact(e) },
                                contentAlignment = Alignment.Center) { Text(e, fontSize = 27.sp) }
                        }
                        Box(Modifier.size(46.dp).clip(CircleShape).clickable { moreEmoji = !moreEmoji }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.AddReaction, "More reactions", tint = TextSecond, modifier = Modifier.size(26.dp))
                        }
                    }
                }
                if (moreEmoji) {
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.widthIn(max = 380.dp).clip(RoundedCornerShape(28.dp))) { EmojiPicker { onReact(it) } }
                }
                Spacer(Modifier.height(10.dp))
                // The message itself, lifted
                // A very long message is cut here so the menu under it always stays on screen
                Box(Modifier.heightIn(max = if (moreEmoji) 120.dp else 260.dp).clipToBounds().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
                    MessageBubble(m, reactTick, onLongClick = {}, showMeta = false)
                }
                Spacer(Modifier.height(10.dp))
                // Menu card
                Surface(shape = RoundedCornerShape(24.dp), color = MenuSurf, shadowElevation = 3.dp, modifier = Modifier.width(232.dp)) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        actions.forEach { act ->
                            Row(Modifier.fillMaxWidth().clickable { act.onClick() }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(act.icon, null, tint = act.tint ?: TextPrimary, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(16.dp))
                                Text(act.label, color = act.tint ?: TextPrimary, fontSize = 16.sp)
                            }
                        }
                    }
                }
            }
        }) { measurables, c ->
            val p = measurables.first().measure(c.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
            val desired = (bubbleY?.let { it - topInset } ?: (c.maxHeight / 3f)).toInt() - barH
            val maxY = (c.maxHeight - p.height - pad).coerceAtLeast(pad)
            val y = desired.coerceIn(pad, maxY)
            layout(c.maxWidth, c.maxHeight) { p.place(0, y) }
        }
    }
}
