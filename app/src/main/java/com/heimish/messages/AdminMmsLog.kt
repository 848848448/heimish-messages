package com.heimish.messages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Admin only: the incoming-MMS step log, with Copy / Clear / Retry. */
@Composable
internal fun MmsLogCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(open, tick) {
        if (!open) return@LaunchedEffect
        text = withContext(Dispatchers.IO) {
            if (tick == 0) MmsLog.startup(ctx, force = true)
            MmsLog.read(ctx)
        }
    }
    SettingsHeader("MMS log")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(SettCardBg)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (open) "Hide" else "Show", fontSize = 16.sp, color = TextPrimary, modifier = Modifier.weight(1f))
            Text("${if (open) text.lines().count { it.isNotBlank() } else ""}", fontSize = 13.sp, color = TextHint)
        }
        if (open) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton({
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("MMS log", text.ifBlank { "(empty)" }))
                    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy", color = Brand, fontWeight = FontWeight.Medium) }
                TextButton({ scope.launch { withContext(Dispatchers.IO) { MmsLog.clear(ctx) }; tick++ } }) { Text("Clear", color = Brand) }
                TextButton({ scope.launch { withContext(Dispatchers.IO) { runCatching { MmsIncoming.retryNow(ctx.applicationContext) } }; tick++ } }) { Text("Retry now", color = Brand) }
                TextButton({ tick++ }) { Text("Refresh", color = Brand) }
            }
            SelectionContainer {
                Text(text.ifBlank { "(empty)" }, fontSize = 11.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace, color = TextPrimary,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    Spacer(Modifier.height(8.dp))
}
