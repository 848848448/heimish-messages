package com.heimish.messages

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// ━━━━━━━━━━━━━━━━ ADMIN LOCK ━━━━━━━━━━━━━━━━
// The Admin entry is hidden until the version line in Settings is tapped 7 times.
// Opening Admin asks for the owner's password; the contacts worker checks it (POST /admin/login) and hands back a
// signed 30-day session. That session is the only thing that can search or read photos; it is stored encrypted with an
// Android Keystore AES-GCM key (never leaves the device's keystore). The key baked into the APK (SYNC_TOKEN) can only upload.

sealed class AdminLogin {
    data object Ok : AdminLogin()
    data class Wrong(val triesLeft: Int?) : AdminLogin()
    data class Locked(val minutes: Int) : AdminLogin()
    data object NotSet : AdminLogin()
    data class Failed(val message: String) : AdminLogin()
}

object AdminAuth {
    private const val PREFS = "heimish_admin"
    private const val KEY_ALIAS = "heimish_admin_session"
    private const val TAPS_NEEDED = 7

    /** Whether the Admin entry is visible in the menu (tap-7 reveal). Compose state so the menu updates right away. */
    var revealed by mutableStateOf(false); private set
    private var loaded = false
    private var memSession: String? = null   // used if the keystore is unavailable on this phone
    private var taps = 0
    private var lastTap = 0L

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        revealed = prefs(ctx).getBoolean("revealed", false)
    }

    /** Set by AppRoot: opens the Admin screen. */
    var openScreen: (() -> Unit)? = null

    /** Version line tapped. Completely silent: no toast, no counter, no visual feedback. The 7th quick tap reveals the
     *  menu entry and opens Admin directly. */
    fun onVersionTap(ctx: Context) {
        init(ctx)
        val now = System.currentTimeMillis()
        if (now - lastTap > 3000) taps = 0
        lastTap = now
        taps++
        if (taps >= TAPS_NEEDED) {
            taps = 0
            if (!revealed) setRevealed(ctx, true)
            openScreen?.invoke()
        }
    }

    private fun setRevealed(ctx: Context, on: Boolean) {
        revealed = on
        prefs(ctx).edit().putBoolean("revealed", on).apply()
    }

    /** The current admin session, or null when there is none or it expired. */
    fun session(ctx: Context): String? {
        val p = prefs(ctx)
        val exp = p.getLong("exp", 0L)
        if (exp != 0L && exp < System.currentTimeMillis()) { clearSession(ctx); return null }
        memSession?.let { return it }
        val blob = p.getString("session", null) ?: return null
        return runCatching { decrypt(blob) }.getOrNull().also { if (it == null) clearSession(ctx) }
    }

    fun hasSession(ctx: Context) = session(ctx) != null

    fun clearSession(ctx: Context) {
        memSession = null
        prefs(ctx).edit().remove("session").remove("exp").apply()
    }

    /** "Lock admin": forget the session and hide the menu entry again. */
    fun lock(ctx: Context) {
        clearSession(ctx)
        setRevealed(ctx, false)
        AdminSearchMemory.q = ""; AdminSearchMemory.last = CloudSearch.Idle
        runCatching { java.io.File(ctx.cacheDir, "cloud_photos").deleteRecursively() }
    }

    /** Blocking; call on Dispatchers.IO. */
    fun login(ctx: Context, password: String): AdminLogin {
        if (BuildConfig.WORKER_URL.isBlank()) return AdminLogin.Failed("Contact search is not configured in this build.")
        val conn = java.net.URL("${BuildConfig.WORKER_URL}/admin/login").openConnection() as java.net.HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000; conn.readTimeout = 20000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val body = org.json.JSONObject().put("password", password).toString().toByteArray()
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            val j = runCatching { org.json.JSONObject(text) }.getOrNull()
            when {
                code == 200 && j != null && j.optString("token").isNotBlank() -> {
                    store(ctx, j.optString("token"), j.optLong("exp", System.currentTimeMillis() + 30L * 86400000))
                    AdminLogin.Ok
                }
                code == 503 && (j?.optString("error") ?: "").contains("password", true) -> AdminLogin.NotSet
                code == 401 -> AdminLogin.Wrong(j?.takeIf { it.has("triesLeft") }?.optInt("triesLeft"))
                code == 429 -> AdminLogin.Locked(((j?.optInt("retryAfter", 900) ?: 900) + 59) / 60)
                else -> AdminLogin.Failed("Sign-in failed (HTTP $code)" + (j?.optString("error")?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""))
            }
        } catch (e: java.net.UnknownHostException) { AdminLogin.Failed("Can't reach the contacts server (no internet?)")
        } catch (e: java.net.SocketTimeoutException) { AdminLogin.Failed("The contacts server took too long to answer")
        } catch (e: Exception) { AdminLogin.Failed(e.message ?: e.javaClass.simpleName)
        } finally { conn.disconnect() }
    }

    private fun store(ctx: Context, token: String, exp: Long) {
        val enc = runCatching { encrypt(token) }.getOrNull()
        if (enc == null) { memSession = token; prefs(ctx).edit().remove("session").putLong("exp", exp).apply(); return }
        memSession = null
        prefs(ctx).edit().putString("session", enc).putLong("exp", exp).apply()
    }

    // ── Keystore AES-256-GCM ──
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val (iv, data) = blob.split(':', limit = 2).let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(c.doFinal(data), Charsets.UTF_8)
    }
}

/** Password screen shown inside Admin when there is no valid session. */
@Composable
fun AdminLoginCard(note: String? = null, onSignedIn: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(note) }
    var notSet by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun submit() {
        if (pw.isEmpty() || busy) return
        busy = true; error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { AdminAuth.login(ctx, pw) }
            busy = false
            when (r) {
                AdminLogin.Ok -> { pw = ""; notSet = false; onSignedIn() }
                is AdminLogin.Wrong -> { pw = ""; error = "Wrong password" + (r.triesLeft?.let { if (it == 1) " · 1 try left before a 15-minute lock" else if (it <= 3) " · $it tries left" else "" } ?: "") }
                is AdminLogin.Locked -> error = "Too many wrong passwords. Try again in ${r.minutes} min."
                AdminLogin.NotSet -> { notSet = true; error = null }
                is AdminLogin.Failed -> error = r.message
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(Tonal), contentAlignment = Alignment.Center) {
            Icon(Ms.LockFill, null, tint = OnTonal, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text("Admin is locked", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text("Enter the admin password", fontSize = 14.sp, color = TextSecond)
        Spacer(Modifier.height(20.dp))
        TextField(pw, { pw = it; error = null }, Modifier.fillMaxWidth().focusRequester(focus),
            placeholder = { Text("Password", color = TextHint) }, singleLine = true, shape = RoundedCornerShape(28.dp),
            leadingIcon = { Icon(Ms.Password, null, tint = TextHint) },
            trailingIcon = { TextButton({ show = !show }) { Text(if (show) "Hide" else "Show", color = Brand) } },
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            isError = error != null,
            colors = TextFieldDefaults.colors(focusedContainerColor = SearchBg, unfocusedContainerColor = SearchBg, errorContainerColor = SearchBg,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, errorIndicatorColor = Color.Transparent,
                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, errorTextColor = TextPrimary, cursorColor = Brand))
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        if (notSet) {
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Tonal).padding(16.dp)) {
                Text("The admin password isn't set yet", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = OnTonal)
                Spacer(Modifier.height(4.dp))
                Text("Set it in GitHub: the app's repo → Settings → Secrets and variables → Actions → New repository secret, name ADMIN_PASSWORD. It goes live within about 10 minutes.",
                    fontSize = 13.sp, color = OnTonal.copy(alpha = .85f))
            }
        }
        Spacer(Modifier.height(16.dp))
        Button({ submit() }, enabled = pw.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = OnBrand)) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = OnBrand) else Text("Unlock")
        }
    }
}
