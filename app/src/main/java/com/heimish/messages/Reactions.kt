package com.heimish.messages

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A reaction someone else put on a message (from an iPhone / Google Messages "Liked “…”" text, or your own sent fallback). */
data class RemoteReaction(val emoji: String, val from: String)

/** A message list with the reaction texts folded into the messages they react to. */
class FoldedChat(val msgs: List<Message>, val reactions: Map<String, List<RemoteReaction>>, val hidden: Int)

/**
 * SMS reactions, Google Messages style.
 *  - The reaction is always shown on the bubble (ReactionStore = your own).
 *  - Over SMS/MMS the standard text fallback can be sent too ("Liked “…”"), Settings > Chat features > "Send reactions as text".
 *  - Incoming "Liked/Loved/Laughed at … “…”" and "Reacted 😮 to “…”" texts from iPhones and Google Messages become a reaction on
 *    the quoted message instead of a separate bubble.
 */
object Reactions {
    val quick = listOf("\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDE2E", "\uD83D\uDE22", "\uD83D\uDE4F")
    val more = listOf("\uD83D\uDD25", "\uD83D\uDC4E", "\u203C\uFE0F", "\u2753", "\uD83C\uDF89", "\uD83D\uDE0D", "\uD83D\uDE21", "\uD83D\uDE4C",
        "\uD83D\uDCAF", "\uD83D\uDE0A", "\uD83E\uDD23", "\uD83D\uDE2D", "\uD83D\uDE31", "\uD83E\uDD14", "\uD83D\uDC4F", "\uD83D\uDE0E", "\u2705", "\uD83E\uDD70")

    /** Apple tapback verbs (also what Google Messages sends for these six). */
    private val verbToEmoji = linkedMapOf(
        "Liked" to "\uD83D\uDC4D", "Loved" to "\u2764\uFE0F", "Laughed at" to "\uD83D\uDE02",
        "Emphasized" to "\u203C\uFE0F", "Disliked" to "\uD83D\uDC4E", "Questioned" to "\u2753")
    private val removedNoun = linkedMapOf(
        "a like" to "\uD83D\uDC4D", "a heart" to "\u2764\uFE0F", "a laugh" to "\uD83D\uDE02",
        "an exclamation" to "\u203C\uFE0F", "an exclamation mark" to "\u203C\uFE0F", "a dislike" to "\uD83D\uDC4E", "a question mark" to "\u2753")
    private fun emojiVerb(e: String): String? = verbToEmoji.entries.firstOrNull { norm(it.value) == norm(e) }?.key
    private fun norm(e: String) = e.replace("\uFE0F", "")

    private const val Q = "[\"“”„«»]"
    private val rxVerb = Regex("^(Liked|Loved|Laughed at|Emphasized|Disliked|Questioned) $Q(.+)$Q\\s*$", RegexOption.DOT_MATCHES_ALL)
    private val rxVerbMedia = Regex("^(Liked|Loved|Laughed at|Emphasized|Disliked|Questioned) (an? (image|photo|picture|video|movie|attachment|audio message|GIF|sticker|link))\\s*$", RegexOption.IGNORE_CASE)
    private val rxReacted = Regex("^Reacted (?:with )?(\\S{1,16}) to $Q(.+)$Q\\s*$", RegexOption.DOT_MATCHES_ALL)
    private val rxReactedMedia = Regex("^Reacted (?:with )?(\\S{1,16}) to (an? (image|photo|picture|video|movie|attachment|audio message|GIF|sticker|link))\\s*$", RegexOption.IGNORE_CASE)
    private val rxRemovedNoun = Regex("^Removed (a like|a heart|a laugh|an exclamation mark|an exclamation|a dislike|a question mark) from $Q(.+)$Q\\s*$", RegexOption.DOT_MATCHES_ALL)
    private val rxRemovedEmoji = Regex("^Removed (?:a )?(\\S{1,16})(?: reaction)? from $Q(.+)$Q\\s*$", RegexOption.DOT_MATCHES_ALL)

    /** A parsed reaction text. [quoted] is null when it reacts to a photo/video ("Liked an image"). */
    data class Parsed(val emoji: String, val quoted: String?, val removed: Boolean)

    private val starts = listOf("Liked ", "Loved ", "Laughed at ", "Emphasized ", "Disliked ", "Questioned ", "Reacted ", "Removed ")

    fun parse(body: String?): Parsed? {
        val b = body?.trim() ?: return null
        if (b.length < 8 || starts.none { b.startsWith(it) }) return null
        rxVerb.find(b)?.let { return Parsed(verbToEmoji[it.groupValues[1]]!!, it.groupValues[2], false) }
        rxVerbMedia.find(b)?.let { m -> verbToEmoji.entries.firstOrNull { it.key.equals(m.groupValues[1], true) }?.let { return Parsed(it.value, null, false) } }
        rxReacted.find(b)?.let { if (looksEmoji(it.groupValues[1])) return Parsed(it.groupValues[1], it.groupValues[2], false) }
        rxReactedMedia.find(b)?.let { if (looksEmoji(it.groupValues[1])) return Parsed(it.groupValues[1], null, false) }
        rxRemovedNoun.find(b)?.let { return Parsed(removedNoun[it.groupValues[1]]!!, it.groupValues[2], true) }
        rxRemovedEmoji.find(b)?.let { if (looksEmoji(it.groupValues[1])) return Parsed(it.groupValues[1], it.groupValues[2], true) }
        return null
    }
    private fun looksEmoji(s: String) = s.isNotEmpty() && s.none { it.isLetterOrDigit() }

    /** "👍 to “See you at 8”" — for notifications and the chat list. */
    fun describe(p: Parsed): String = (if (p.removed) "Removed " + p.emoji + " from " else "Reacted " + p.emoji + " to ") +
        (p.quoted?.let { "“" + it.take(60) + (if (it.length > 60) "…" else "") + "”" } ?: "a photo")

    /** Text for the chat list / notification: a reaction text becomes "Reacted 👍 to “…”", anything else is unchanged. */
    fun pretty(body: String): String = parse(body)?.let { describe(it) } ?: body

    /** The standard SMS fallback text Google Messages and iPhones understand. */
    fun fallbackText(emoji: String, target: Message, removed: Boolean): String {
        val body = target.body.trim()
        val mt = target.mediaMime ?: ""
        val what = when {
            body.isNotEmpty() -> "“" + (if (body.length > 120) body.take(120) + "…" else body) + "”"
            mt.startsWith("video") -> "a video"
            mt.startsWith("audio") -> "an audio message"
            else -> "an image"
        }
        val verb = emojiVerb(emoji)
        if (removed) {
            val noun = removedNoun.entries.firstOrNull { norm(it.value) == norm(emoji) }?.key
            return if (noun != null) "Removed $noun from $what" else "Removed $emoji from $what"
        }
        return if (verb != null) "$verb $what" else "Reacted $emoji to $what"
    }

    fun sendAsText(ctx: Context): Boolean = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE).getBoolean("reaction_sms", true)

    /** Sends the fallback text for a reaction you placed or removed (runs on a background thread). */
    fun sendFallback(ctx: Context, address: String, target: Message, emoji: String, removed: Boolean) {
        if (!sendAsText(ctx) || address.isBlank()) return
        val text = fallbackText(emoji, target, removed)
        Thread {
            runCatching {
                val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
                val ok = address.contains(";") && prefs.getBoolean("group_mms_send", true) && SmsRepository.sendGroupText(ctx, address, text)
                if (!ok) SmsRepository.sendSms(ctx, address, text)
                Unit
            }
        }.start()
    }

    private fun matches(target: Message, p: Parsed): Boolean {
        val q = p.quoted ?: return target.body.isBlank() && target.imageUri != null
        val body = target.body.trim()
        if (body.isEmpty()) return false
        val qt = q.trim()
        if (body == qt) return true
        val cut = qt.removeSuffix("…").removeSuffix("...").trimEnd()
        if (cut.length != qt.length && cut.length >= 3 && body.startsWith(cut)) return true
        // some phones collapse new lines / spaces in the quote
        val a = body.replace(Regex("\\s+"), " "); val bq = cut.replace(Regex("\\s+"), " ")
        return bq.length >= 4 && (a == bq || a.startsWith(bq))
    }

    /**
     * Folds reaction texts into the messages they react to. A reaction text whose target isn't found in [msgs] stays a normal bubble.
     * Your own sent fallbacks are folded too (your reaction already shows from ReactionStore).
     */
    fun fold(ctx: Context, msgs: List<Message>): FoldedChat {
        if (!ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE).getBoolean("reaction_parse", true)) return FoldedChat(msgs, emptyMap(), 0)
        var any = false
        val parsed = arrayOfNulls<Parsed>(msgs.size)
        for (i in msgs.indices) { val p = parse(msgs[i].body); if (p != null && msgs[i].imageUri == null) { parsed[i] = p; any = true } }
        if (!any) return FoldedChat(msgs, emptyMap(), 0)
        val hide = BooleanArray(msgs.size)
        val map = HashMap<String, MutableList<RemoteReaction>>()
        for (i in msgs.indices) {
            val p = parsed[i] ?: continue
            // nearest earlier message (not itself a reaction) that the quote matches; look back at most 400 messages
            var t = -1
            var j = i - 1
            while (j >= 0 && j >= i - 400) { if (parsed[j] == null && matches(msgs[j], p)) { t = j; break }; j-- }
            if (t < 0) continue
            hide[i] = true
            val m = msgs[i]
            val from = if (m.incoming) (m.address ?: "them") else "me"
            val key = msgKey(msgs[t])
            val l = map.getOrPut(key) { mutableListOf() }
            l.removeAll { it.from == from && (!p.removed || norm(it.emoji) == norm(p.emoji)) }
            if (!p.removed) l.add(RemoteReaction(p.emoji, from))
        }
        // Your own reactions are shown from ReactionStore; don't show them twice
        val out = HashMap<String, List<RemoteReaction>>()
        for ((k, l) in map) {
            val mine = ReactionStore.get(ctx, k)
            val keep = if (mine != null) l.filter { it.from != "me" } else l
            if (keep.isNotEmpty()) out[k] = keep
        }
        return FoldedChat(msgs.filterIndexed { i, _ -> !hide[i] }, out, hide.count { it })
    }
}

/**
 * Swipe-to-reply quotes. SMS has no reply field, so the link "this sent message replied to that one" is kept on the phone
 * (like Google Messages does for SMS). A send registers a pending quote; the next reload binds it to the sent row.
 */
object ReplyStore {
    data class Quote(val key: String, val snippet: String, val who: String)

    private fun p(ctx: Context) = ctx.getSharedPreferences("heimish_replies", Context.MODE_PRIVATE)
    @Volatile private var cache: HashMap<String, Quote>? = null

    private fun load(ctx: Context): HashMap<String, Quote> {
        cache?.let { return it }
        val m = HashMap<String, Quote>()
        for ((k, v) in p(ctx).all) if (k.startsWith("q_") && v is String) runCatching {
            val o = JSONObject(v); m[k.removePrefix("q_")] = Quote(o.getString("k"), o.optString("t"), o.optString("w"))
        }
        cache = m; return m
    }

    fun get(ctx: Context, msgKey: String): Quote? = load(ctx)[msgKey]

    /** Call right before sending [body] (or a photo when [body] is blank) as a reply to [target]. */
    fun pending(ctx: Context, threadId: Long, body: String, target: Message, who: String) {
        val snippet = target.body.trim().take(140).ifBlank { if (target.mediaMime?.startsWith("video") == true) "\uD83C\uDFA5 Video" else if (target.mediaMime?.startsWith("audio") == true) "\uD83C\uDFA4 Voice message" else "\uD83D\uDCF7 Photo" }
        val arr = runCatching { JSONArray(p(ctx).getString("pending_$threadId", "[]")) }.getOrElse { JSONArray() }
        arr.put(JSONObject().put("b", body.trim()).put("at", System.currentTimeMillis()).put("k", msgKey(target)).put("t", snippet).put("w", who))
        p(ctx).edit().putString("pending_$threadId", arr.toString()).apply()
    }

    /** Binds pending quotes of this chat to the sent messages they belong to. Returns true when something new was bound. */
    fun bind(ctx: Context, threadId: Long, msgs: List<Message>): Boolean {
        val raw = p(ctx).getString("pending_$threadId", null) ?: return false
        val arr = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        val left = JSONArray(); var bound = false
        val now = System.currentTimeMillis()
        val m = load(ctx)
        val ed = p(ctx).edit()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val at = o.optLong("at"); val b = o.optString("b")
            val hit = msgs.lastOrNull { !it.incoming && it.date >= at - 15_000 && msgKey(it) !in m &&
                (if (b.isEmpty()) it.isMms else it.body.trim() == b || (it.isMms && it.body.trim() == b)) }
            if (hit != null) {
                val q = Quote(o.getString("k"), o.optString("t"), o.optString("w"))
                m[msgKey(hit)] = q
                ed.putString("q_" + msgKey(hit), JSONObject().put("k", q.key).put("t", q.snippet).put("w", q.who).toString())
                bound = true
            } else if (now - at < 2 * 24 * 3600_000L) left.put(o)
        }
        if (left.length() == 0) ed.remove("pending_$threadId") else ed.putString("pending_$threadId", left.toString())
        ed.apply()
        return bound
    }
}
