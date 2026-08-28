package com.heimish.messages

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log

data class Conversation(
    val threadId: Long,
    val address: String,
    val displayName: String,
    val snippet: String,
    val date: Long,
    val unread: Boolean
)

data class Message(
    val id: Long,
    val body: String,
    val date: Long,
    val incoming: Boolean,
    val isMms: Boolean = false,
    val imageUri: Uri? = null,
    val address: String? = null
)

object SmsRepository {

    // ── Conversations ─────────────────────────────────────────────────────────

    fun loadConversations(ctx: Context): List<Conversation> {
        val out = ArrayList<Conversation>()
        val seen = HashSet<Long>()

        // SMS
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ),
            null, null, Telephony.Sms.DATE + " DESC"
        )?.use { c ->
            val iThread = c.getColumnIndex(Telephony.Sms.THREAD_ID)
            val iAddr   = c.getColumnIndex(Telephony.Sms.ADDRESS)
            val iBody   = c.getColumnIndex(Telephony.Sms.BODY)
            val iDate   = c.getColumnIndex(Telephony.Sms.DATE)
            val iRead   = c.getColumnIndex(Telephony.Sms.READ)
            while (c.moveToNext()) {
                val thread = c.getLong(iThread)
                if (!seen.add(thread)) continue
                val addr = c.getString(iAddr) ?: ""
                out.add(Conversation(
                    threadId    = thread,
                    address     = addr,
                    displayName = contactName(ctx, addr) ?: addr,
                    snippet     = c.getString(iBody) ?: "",
                    date        = c.getLong(iDate),
                    unread      = c.getInt(iRead) == 0
                ))
            }
        }

        // MMS threads not already in SMS list
        ctx.contentResolver.query(
            Uri.parse("content://mms"),
            arrayOf("thread_id", "date", "read"),
            null, null, "date DESC"
        )?.use { c ->
            val iThread = c.getColumnIndex("thread_id")
            val iDate   = c.getColumnIndex("date")
            val iRead   = c.getColumnIndex("read")
            while (c.moveToNext()) {
                val thread = c.getLong(iThread)
                if (!seen.add(thread)) continue
                val addr = mmsAddress(ctx, thread)
                out.add(Conversation(
                    threadId    = thread,
                    address     = addr,
                    displayName = contactName(ctx, addr) ?: addr,
                    snippet     = "📷 MMS",
                    date        = c.getLong(iDate) * 1000L,
                    unread      = c.getInt(iRead) == 0
                ))
            }
        }

        out.sortByDescending { it.date }
        return out
    }

    // ── Messages ──────────────────────────────────────────────────────────────

    fun loadMessages(ctx: Context, threadId: Long): List<Message> {
        val out = ArrayList<Message>()

        // SMS
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.ADDRESS),
            Telephony.Sms.THREAD_ID + " = ?", arrayOf(threadId.toString()),
            Telephony.Sms.DATE + " ASC"
        )?.use { c ->
            val iId   = c.getColumnIndex(Telephony.Sms._ID)
            val iBody = c.getColumnIndex(Telephony.Sms.BODY)
            val iDate = c.getColumnIndex(Telephony.Sms.DATE)
            val iType = c.getColumnIndex(Telephony.Sms.TYPE)
            val iAddr = c.getColumnIndex(Telephony.Sms.ADDRESS)
            while (c.moveToNext()) {
                out.add(Message(
                    id       = c.getLong(iId),
                    body     = c.getString(iBody) ?: "",
                    date     = c.getLong(iDate),
                    incoming = c.getInt(iType) == Telephony.Sms.MESSAGE_TYPE_INBOX,
                    address  = if (iAddr >= 0) c.getString(iAddr) else null
                ))
            }
        }

        // MMS
        ctx.contentResolver.query(
            Uri.parse("content://mms"),
            arrayOf("_id", "date", "msg_box"),
            "thread_id = ?", arrayOf(threadId.toString()),
            "date ASC"
        )?.use { c ->
            val iId   = c.getColumnIndex("_id")
            val iDate = c.getColumnIndex("date")
            val iBox  = c.getColumnIndex("msg_box")
            while (c.moveToNext()) {
                val mmsId    = c.getLong(iId)
                val date     = c.getLong(iDate) * 1000L
                val incoming = c.getInt(iBox) == 1
                val (text, imgUri) = mmsParts(ctx, mmsId)
                val mmsAddr = mmsAddress(ctx, mmsId)
                out.add(Message(
                    id       = mmsId + 1_000_000L,
                    body     = text,
                    date     = date,
                    incoming = incoming,
                    isMms    = true,
                    imageUri = imgUri,
                    address  = mmsAddr
                ))
            }
        }

        out.sortBy { it.date }
        return out
    }

    // ── Send SMS ──────────────────────────────────────────────────────────────

    fun sendSms(ctx: Context, address: String, body: String): Boolean {
        return runCatching {
            val sms = smsManager(ctx)
            val parts = sms.divideMessage(body)
            if (parts.size > 1)
                sms.sendMultipartTextMessage(address, null, parts, null, null)
            else
                sms.sendTextMessage(address, null, body, null, null)

            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                put(Telephony.Sms.THREAD_ID, getOrCreateThreadId(ctx, address))
            }
            ctx.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
            true
        }.getOrElse { e -> Log.e("SmsRepo", "sendSms: ${e.message}"); false }
    }

    // ── Send MMS (image) ──────────────────────────────────────────────────────

    fun sendMms(ctx: Context, address: String, imageUri: Uri, caption: String = ""): Boolean {
        return runCatching {
            val bytes = ctx.contentResolver.openInputStream(imageUri)?.readBytes() ?: return false
            val mimeType = ctx.contentResolver.getType(imageUri) ?: "image/jpeg"
            val sms = smsManager(ctx)
            val threadId = getOrCreateThreadId(ctx, address)

            // Build the MMS PDU manually (SendReq format)
            val pdu = buildSendReqPdu(address, bytes, mimeType, caption)

            // Write PDU to a temp file and get a content URI via FileProvider
            val pduFile = java.io.File(ctx.cacheDir, "mms_send_${System.currentTimeMillis()}.dat")
            pduFile.writeBytes(pdu)
            val pduUri = androidx.core.content.FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", pduFile
            )

            // Send via system MMS API
            val sendIntent = android.app.PendingIntent.getBroadcast(
                ctx, 0, Intent("com.heimish.messages.MMS_SENT"),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            sms.sendMultimediaMessage(ctx, pduUri, null, null, sendIntent)

            // Also store in the sent box so it shows in the thread
            val mmsValues = ContentValues().apply {
                put("thread_id", threadId)
                put("msg_box", 2) // sent
                put("read", 1)
                put("seen", 1)
                put("date", System.currentTimeMillis() / 1000)
                put("ct_t", "application/vnd.wap.multipart.related")
            }
            val mmsUri = ctx.contentResolver.insert(Uri.parse("content://mms"), mmsValues)
            val mmsId = mmsUri?.lastPathSegment?.toLong()
            if (mmsId != null) {
                val addrValues = ContentValues().apply {
                    put("address", address)
                    put("msg_id", mmsId)
                    put("type", 151)
                    put("charset", 106)
                }
                ctx.contentResolver.insert(Uri.parse("content://mms/$mmsId/addr"), addrValues)

                val imgValues = ContentValues().apply {
                    put("mid", mmsId)
                    put("ct", mimeType)
                    put("name", "image.jpg")
                    put("chset", 106)
                }
                val partUri = ctx.contentResolver.insert(Uri.parse("content://mms/$mmsId/part"), imgValues)
                if (partUri != null) {
                    ctx.contentResolver.openOutputStream(partUri)?.use { it.write(bytes) }
                }

                if (caption.isNotBlank()) {
                    val textValues = ContentValues().apply {
                        put("mid", mmsId)
                        put("ct", "text/plain")
                        put("chset", 106)
                        put("text", caption)
                    }
                    ctx.contentResolver.insert(Uri.parse("content://mms/$mmsId/part"), textValues)
                }
            }

            // Clean up temp file after a delay
            Thread { Thread.sleep(30_000); pduFile.delete() }.start()
            true
        }.getOrElse { e -> Log.e("SmsRepo", "sendMms: ${e.message}"); false }
    }

    private fun buildSendReqPdu(address: String, imageBytes: ByteArray, mimeType: String, caption: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()

        // MMS headers
        out.write(0x8C); out.write(0x80) // X-Mms-Message-Type: m-send-req
        out.write(0x98) // X-Mms-Transaction-Id
        val txnId = "T${System.currentTimeMillis()}"
        out.write(txnId.toByteArray()); out.write(0x00)
        out.write(0x8D); out.write(0x90) // X-Mms-MMS-Version: 1.0
        out.write(0x89) // To
        val encodedAddr = "+${address.replace(Regex("[^0-9+]"), "")}/TYPE=PLMN"
        out.write(encodedAddr.length + 2)
        out.write(encodedAddr.toByteArray()); out.write(0x00)
        out.write(0x96) // Subject (optional)
        if (caption.isNotBlank()) {
            val subj = caption.take(40)
            out.write(subj.toByteArray(Charsets.UTF_8)); out.write(0x00)
        } else {
            out.write(0x00)
        }
        out.write(0x84); out.write(0x83) // Content-Type: application/vnd.wap.multipart.related

        // Number of parts
        val partCount = if (caption.isNotBlank()) 2 else 1
        out.write(partCount)

        // Image part
        val imgContentType = mimeType.toByteArray()
        val imgName = "image.jpg"
        // Part headers length
        val imgHeaders = java.io.ByteArrayOutputStream()
        imgHeaders.write(imgContentType); imgHeaders.write(0x00)
        val imgHeaderLen = imgHeaders.size()
        // uintvar encoding of header length
        writeUintVar(out, imgHeaderLen)
        // uintvar encoding of data length
        writeUintVar(out, imageBytes.size)
        // Headers
        out.write(imgHeaders.toByteArray())
        // Data
        out.write(imageBytes)

        // Text part (if caption)
        if (caption.isNotBlank()) {
            val textBytes = caption.toByteArray(Charsets.UTF_8)
            val textCt = "text/plain".toByteArray()
            val textHeaders = java.io.ByteArrayOutputStream()
            textHeaders.write(textCt); textHeaders.write(0x00)
            writeUintVar(out, textHeaders.size())
            writeUintVar(out, textBytes.size)
            out.write(textHeaders.toByteArray())
            out.write(textBytes)
        }

        return out.toByteArray()
    }

    private fun writeUintVar(out: java.io.ByteArrayOutputStream, value: Int) {
        if (value < 0x80) {
            out.write(value)
        } else {
            val bytes = mutableListOf<Int>()
            var v = value
            bytes.add(v and 0x7F)
            v = v shr 7
            while (v > 0) {
                bytes.add((v and 0x7F) or 0x80)
                v = v shr 7
            }
            bytes.reversed().forEach { out.write(it) }
        }
    }

    // ── Mark read ─────────────────────────────────────────────────────────────

    fun markThreadRead(ctx: Context, threadId: Long) {
        runCatching {
            val values = ContentValues().apply { put(Telephony.Sms.READ, 1) }
            ctx.contentResolver.update(
                Telephony.Sms.CONTENT_URI, values,
                Telephony.Sms.THREAD_ID + " = ?", arrayOf(threadId.toString())
            )
            ctx.contentResolver.update(
                Uri.parse("content://mms"), values,
                "thread_id = ?", arrayOf(threadId.toString())
            )
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun smsManager(ctx: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ctx.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
        else
            SmsManager.getDefault()

    private fun getOrCreateThreadId(ctx: Context, address: String): Long {
        return runCatching {
            val uri = Uri.parse("content://mms-sms/threadID")
                .buildUpon().appendQueryParameter("recipient", address).build()
            ctx.contentResolver.query(uri, arrayOf("_id"), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun contactName(ctx: Context, number: String): String? {
        if (number.isBlank()) return null
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
        )
        return runCatching {
            ctx.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }

    private fun mmsAddress(ctx: Context, threadId: Long): String {
        return runCatching {
            ctx.contentResolver.query(
                Uri.parse("content://mms-sms/conversations/$threadId"),
                arrayOf("address"), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) ?: "" else "" } ?: ""
        }.getOrDefault("")
    }

    private fun mmsParts(ctx: Context, mmsId: Long): Pair<String, Uri?> {
        var text = ""
        var imgUri: Uri? = null
        runCatching {
            ctx.contentResolver.query(
                Uri.parse("content://mms/$mmsId/part"),
                arrayOf("_id", "ct", "text"), null, null, null
            )?.use { c ->
                val iId   = c.getColumnIndex("_id")
                val iCt   = c.getColumnIndex("ct")
                val iText = c.getColumnIndex("text")
                while (c.moveToNext()) {
                    val partId = c.getLong(iId)
                    val ct     = c.getString(iCt) ?: ""
                    when {
                        ct == "text/plain" -> text = c.getString(iText) ?: ""
                        ct.startsWith("image/") -> imgUri = Uri.parse("content://mms/part/$partId")
                    }
                }
            }
        }
        return text to imgUri
    }

    private fun mmsAddress(ctx: Context, mmsId: Long): String? {
        return runCatching {
            ctx.contentResolver.query(
                Uri.parse("content://mms/$mmsId/addr"),
                arrayOf("address", "type"), "type=137", null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(c.getColumnIndex("address")) else null
            }
        }.getOrNull()
    }

        fun getThreadIdForAddress(ctx: Context, address: String): Long {
            try {
                val uri = android.provider.Telephony.Sms.CONTENT_URI
                val cursor = ctx.contentResolver.query(uri,
                    arrayOf(android.provider.Telephony.Sms.THREAD_ID),
                    "${android.provider.Telephony.Sms.ADDRESS} = ?",
                    arrayOf(address), "${android.provider.Telephony.Sms.DATE} DESC")
                cursor?.use {
                    if (it.moveToFirst()) return it.getLong(0)
                }
            } catch (_: Exception) {}
            return address.hashCode().toLong()
        }

        fun getContactName(ctx: Context, address: String): String? {
            try {
                val uri = android.net.Uri.withAppendedPath(
                    android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    android.net.Uri.encode(address))
                val cursor = ctx.contentResolver.query(uri,
                    arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME),
                    null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) return it.getString(0)
                }
            } catch (_: Exception) {}
            return null
        }


        fun loadContactsList(ctx: Context): List<Pair<String, String>> {
            val contacts = mutableListOf<Pair<String, String>>()
            val seen = mutableSetOf<String>()
            try {
                val cursor = ctx.contentResolver.query(
                    android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                    ), null, null,
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
                )
                cursor?.use {
                    val ni = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val pi = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (it.moveToNext()) {
                        val name = it.getString(ni) ?: continue
                        val num = (it.getString(pi) ?: continue).replace(Regex("[^+0-9]"), "")
                        if (num.isBlank() || seen.contains(num)) continue
                        seen.add(num)
                        contacts.add(name to num)
                    }
                }
            } catch (_: Exception) {}
            return contacts
        }

        fun deleteMessage(ctx: Context, msgId: Long) {
            try {
                if (msgId >= 1_000_000L) {
                    val realMmsId = msgId - 1_000_000L
                    ctx.contentResolver.delete(Uri.parse("content://mms"), "_id = ?", arrayOf(realMmsId.toString()))
                } else {
                    ctx.contentResolver.delete(Telephony.Sms.CONTENT_URI, "_id = ?", arrayOf(msgId.toString()))
                }
            } catch (_: Exception) {}
        }

        fun deleteThread(ctx: Context, threadId: Long) {
            try {
                ctx.contentResolver.delete(Telephony.Sms.CONTENT_URI, "thread_id = ?", arrayOf(threadId.toString()))
                ctx.contentResolver.delete(Uri.parse("content://mms"), "thread_id = ?", arrayOf(threadId.toString()))
            } catch (_: Exception) {}
        }

}
