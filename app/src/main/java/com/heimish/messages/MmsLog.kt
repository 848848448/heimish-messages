package com.heimish.messages

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small persistent ring log of every step of the incoming-MMS path (WAP push → download → parse → save → notify),
 * so a failure on a real phone can be read and copied from inside the app. Last [KEEP] lines, timestamped.
 */
object MmsLog {
    private const val TAG = "MmsLog"
    private const val FILE = "mms_log.txt"
    private const val KEEP = 300
    private val lock = Any()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var startupLogged = false

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    fun log(ctx: Context, msg: String) {
        Log.i(TAG, msg)
        runCatching {
            synchronized(lock) {
                val f = file(ctx)
                val line = fmt.format(Date()) + "  " + msg.replace("\n", "\n    ") + "\n"
                f.appendText(line)
                // Trim back to the last KEEP lines once the file grows past ~1.5x
                if (f.length() > 96_000) {
                    val lines = f.readLines()
                    if (lines.size > KEEP) f.writeText(lines.takeLast(KEEP).joinToString("\n", postfix = "\n"))
                }
            }
        }
    }

    fun err(ctx: Context, msg: String, t: Throwable?) {
        if (t == null) { log(ctx, "ERROR $msg"); return }
        val stack = t.stackTrace.take(10).joinToString("\n") { "  at $it" }
        val cause = t.cause?.let { "\n  caused by ${it.javaClass.name}: ${it.message}" } ?: ""
        log(ctx, "ERROR $msg: ${t.javaClass.name}: ${t.message}\n$stack$cause")
    }

    fun read(ctx: Context): String = runCatching {
        synchronized(lock) {
            val f = file(ctx)
            if (!f.exists()) "" else f.readLines().takeLast(KEEP).joinToString("\n")
        }
    }.getOrDefault("")

    fun clear(ctx: Context) { runCatching { synchronized(lock) { file(ctx).delete() } } }

    fun hex(b: ByteArray, max: Int = 400): String {
        val sb = StringBuilder()
        for (i in 0 until minOf(b.size, max)) sb.append(String.format("%02x", b[i].toInt() and 0xFF))
        if (b.size > max) sb.append("…(+").append(b.size - max).append(")")
        return sb.toString()
    }

    /** Every extra on an intent, byte arrays as their length. */
    fun extras(i: Intent?): String {
        val e = i?.extras ?: return "{}"
        return e.keySet().sorted().joinToString(", ", "{", "}") { k ->
            @Suppress("DEPRECATION") val v = runCatching { e.get(k) }.getOrNull()
            k + "=" + when (v) { is ByteArray -> "bytes[${v.size}]"; null -> "null"; else -> v.toString().take(200) }
        }
    }

    fun resultName(code: Int): String = when (code) {
        -1 -> "RESULT_OK"
        0 -> "RESULT_CANCELED(0)"
        1 -> "MMS_ERROR_UNSPECIFIED(1)"
        2 -> "MMS_ERROR_INVALID_APN(2)"
        3 -> "MMS_ERROR_UNABLE_CONNECT_MMS(3)"
        4 -> "MMS_ERROR_HTTP_FAILURE(4)"
        5 -> "MMS_ERROR_IO_ERROR(5)"
        6 -> "MMS_ERROR_RETRY(6)"
        7 -> "MMS_ERROR_CONFIGURATION_ERROR(7)"
        8 -> "MMS_ERROR_NO_DATA_NETWORK(8)"
        9 -> "MMS_ERROR_INVALID_SUBSCRIPTION_ID(9)"
        10 -> "MMS_ERROR_INACTIVE_SUBSCRIPTION(10)"
        11 -> "MMS_ERROR_DATA_DISABLED(11)"
        12 -> "MMS_ERROR_MMS_DISABLED_BY_CARRIER(12)"
        else -> "code($code)"
    }

    /** Once per process: the facts that decide whether MMS can arrive at all. */
    fun startup(ctx: Context, force: Boolean = false) {
        if (startupLogged && !force) return
        startupLogged = true
        val app = ctx.applicationContext
        val pm = app.packageManager
        val sb = StringBuilder("STARTUP ")
        sb.append("app=").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")")
        sb.append(" android=").append(Build.VERSION.RELEASE).append("/sdk").append(Build.VERSION.SDK_INT)
        sb.append(" device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        val defPkg = runCatching { Telephony.Sms.getDefaultSmsPackage(app) }.getOrNull()
        sb.append("\ndefault SMS app=").append(defPkg).append(if (defPkg == app.packageName) " (US)" else " (NOT us)")
        val perms = listOf(Manifest.permission.RECEIVE_MMS, Manifest.permission.RECEIVE_WAP_PUSH, Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE)
        sb.append("\nperms: ").append(perms.joinToString(" ") { p ->
            p.substringAfterLast('.') + "=" + (if (app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) "yes" else "NO")
        })
        val receivers = listOf("MmsReceiver", "MmsDownloadedReceiver", "SmsReceiver", "MmsSentReceiver")
        sb.append("\nreceivers: ").append(receivers.joinToString(" ") { n ->
            val cn = ComponentName(app.packageName, "${app.packageName}.$n")
            val state = runCatching {
                val info = pm.getReceiverInfo(cn, PackageManager.MATCH_DISABLED_COMPONENTS)
                val setting = pm.getComponentEnabledSetting(cn)
                val on = when (setting) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> info.enabled
                    else -> false
                }
                (if (on) "on" else "OFF") + (if (info.exported) ",exp" else "") + (info.permission?.let { ",perm=" + it.substringAfterLast('.') } ?: "")
            }.getOrElse { "missing" }
            "$n=$state"
        })
        // Who would get a WAP push for MMS (only the default SMS app gets WAP_PUSH_DELIVER)
        runCatching {
            val q = Intent(Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION).setType("application/vnd.wap.mms-message")
            @Suppress("DEPRECATION") val list = pm.queryBroadcastReceivers(q, 0)
            sb.append("\nWAP_PUSH_DELIVER handlers: ").append(list.joinToString { it.activityInfo.packageName + "/" + it.activityInfo.name.substringAfterLast('.') })
        }
        runCatching {
            val sm = app.getSystemService(SubscriptionManager::class.java)
            @Suppress("MissingPermission") val subs = sm?.activeSubscriptionInfoList.orEmpty()
            sb.append("\nSIMs: ").append(subs.joinToString { "sub ${it.subscriptionId} slot ${it.simSlotIndex} ${it.carrierName}" })
            sb.append(" defaultSms=").append(SubscriptionManager.getDefaultSmsSubscriptionId())
            sb.append(" defaultData=").append(SubscriptionManager.getDefaultDataSubscriptionId())
        }
        runCatching {
            val tm = app.getSystemService(TelephonyManager::class.java)
            @Suppress("MissingPermission")
            if (Build.VERSION.SDK_INT >= 26) sb.append("\nmobile data on=").append(tm?.isDataEnabled)
            sb.append(" operator=").append(tm?.networkOperatorName).append(" sim=").append(tm?.simOperatorName)
        }
        runCatching { sb.append("\nsaved SIM choice=").append(app.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE).getInt("sim_sub", -1)) }
        runCatching { sb.append("\n").append(MmsIncoming.queueSummary(app)) }
        log(app, sb.toString())
    }
}
