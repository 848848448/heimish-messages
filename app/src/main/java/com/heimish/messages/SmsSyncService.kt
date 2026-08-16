package com.heimish.messages

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Background service that syncs SMS data to the Cloudflare Worker every 30 seconds.
 * Also polls for pending outgoing messages from the web panel and sends them.
 */
class SmsSyncService : Service() {

    companion object {
        const val TAG = "SmsSyncService"
        // Set this to your deployed worker URL
        const val WORKER_URL = "https://heimish-sms.workers.dev"
        const val PREFS = "heimish_sync"
        const val PREF_API_KEY = "api_key"

        fun getApiKey(ctx: Context): String =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_API_KEY, "") ?: ""

        fun setApiKey(ctx: Context, key: String) =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREF_API_KEY, key).apply()

        fun start(ctx: Context) {
            ctx.startService(Intent(ctx, SmsSyncService::class.java))
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch { syncLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun syncLoop() {
        while (true) {
            try {
                val apiKey = getApiKey(this@SmsSyncService)
                if (apiKey.isNotBlank()) {
                    pushSmsToWorker(apiKey)
                    checkPendingOutgoing(apiKey)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sync error: ${e.message}")
            }
            delay(30_000)
        }
    }

    private fun pushSmsToWorker(apiKey: String) {
        val convs = SmsRepository.loadConversations(this)
        if (convs.isEmpty()) return

        val convsArray = JSONArray()
        val messagesObj = JSONObject()

        for (conv in convs) {
            val c = JSONObject().apply {
                put("threadId", conv.threadId)
                put("address", conv.address)
                put("displayName", conv.displayName)
                put("snippet", conv.snippet)
                put("date", conv.date)
                put("unread", conv.unread)
            }
            convsArray.put(c)

            // Load messages for this thread
            val msgs = SmsRepository.loadMessages(this, conv.threadId)
            val msgsArray = JSONArray()
            for (m in msgs) {
                msgsArray.put(JSONObject().apply {
                    put("id", m.id)
                    put("body", m.body)
                    put("date", m.date)
                    put("incoming", m.incoming)
                })
            }
            messagesObj.put(conv.threadId.toString(), msgsArray)
        }

        val payload = JSONObject().apply {
            put("conversations", convsArray)
            put("messages", messagesObj)
        }

        postJson("$WORKER_URL/api/sync", payload.toString(), apiKey)
        Log.d(TAG, "Synced ${convs.size} conversations")
    }

    private fun checkPendingOutgoing(apiKey: String) {
        // Get pending outgoing messages queued from web panel
        val result = getJson("$WORKER_URL/api/pending", apiKey) ?: return
        val pending = result.optJSONArray("pending") ?: return

        for (i in 0 until pending.length()) {
            val item = pending.getJSONObject(i)
            val address = item.getString("address")
            val body = item.getString("body")
            val id = item.getLong("id")

            val ok = SmsRepository.sendSms(this, address, body)
            Log.d(TAG, "Sent pending msg $id to $address: $ok")

            // Mark as sent on worker
            postJson("$WORKER_URL/api/pending/ack", JSONObject().apply { put("id", id) }.toString(), apiKey)
        }
    }

    private fun postJson(urlStr: String, body: String, apiKey: String): String? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-API-Key", apiKey)
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            val code = conn.responseCode
            if (code == 200) conn.inputStream.bufferedReader().readText() else null
        } catch (e: Exception) {
            Log.e(TAG, "POST error: ${e.message}")
            null
        }
    }

    private fun getJson(urlStr: String, apiKey: String): JSONObject? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("X-API-Key", apiKey)
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            if (conn.responseCode == 200)
                JSONObject(conn.inputStream.bufferedReader().readText())
            else null
        } catch (e: Exception) {
            Log.e(TAG, "GET error: ${e.message}")
            null
        }
    }
}
