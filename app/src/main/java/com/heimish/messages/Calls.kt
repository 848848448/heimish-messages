package com.heimish.messages

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Every "Call" in the app goes through here: the call is placed directly (ACTION_CALL), no dialer screen first.
 * Without the phone permission Android's dialer opens with the number (ACTION_DIAL). The permission is asked at the first call.
 * Dual-SIM: the SIM picked for that chat, else the phone's default calling SIM.
 */
object Caller {
    /** Set by MainActivity: asks for CALL_PHONE, then calls [onResult] with the answer. */
    var requestPermission: ((onResult: (Boolean) -> Unit) -> Unit)? = null

    fun canCallDirect(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    private fun clean(number: String) = number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }

    fun call(ctx: Context, number: String, video: Boolean = false) {
        val n = clean(number)
        if (n.isEmpty()) return
        if (canCallDirect(ctx)) { direct(ctx, n, video); return }
        val ask = requestPermission
        if (ask != null) ask { ok -> if (ok) direct(ctx, n, video) else dial(ctx, n, video) }
        else dial(ctx, n, video)
    }

    private fun direct(ctx: Context, n: String, video: Boolean) {
        val i = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", n, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (video) i.putExtra(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, 3)
        accountFor(ctx, n)?.let { i.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        try { ctx.startActivity(i); Log.d("Caller", "direct call placed") }
        catch (e: Exception) { Log.w("Caller", "direct call failed, opening dialer", e); dial(ctx, n, video) }
    }

    private fun dial(ctx: Context, n: String, video: Boolean) {
        val i = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", n, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (video) i.putExtra(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, 3)
        runCatching { ctx.startActivity(i) }
    }

    /** The calling account of the SIM chosen for this number's chat (Details → SIM), or null for the phone's default. */
    private fun accountFor(ctx: Context, n: String): PhoneAccountHandle? = runCatching {
        if (Build.VERSION.SDK_INT < 30) return null
        val sub = ConvSim.get(ctx, n)
        if (sub < 0) return null
        val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val tel = ctx.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        tm.callCapablePhoneAccounts.firstOrNull { h -> runCatching { tel.getSubscriptionId(h) }.getOrDefault(-1) == sub }
    }.getOrNull()
}
