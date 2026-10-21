package com.heimish.messages

/**
 * One-time / verification codes in a message ("Your code is 123456", "G-123456", "123-456", Yiddish/Hebrew "קוד").
 * Used by the "Copy code" chip in chats, the long-press menu and the notification button.
 */
object Otp {
    private val hint = Regex("(?i)(\\bcode\\b|codes?:|\\botp\\b|verif|passcode|pass code|one[- ]time|\\b2fa\\b|\\bpin\\b|security code|log ?in|sign[- ]?in|authenticat|קאוד|קוד|קאד|קָאד)")
    // 4–8 digits, or 3+3 split by a space or dash; not part of a longer number, amount, time or decimal
    private val num = Regex("(?<![\\d.,:/$€£₪+#-])(?:[A-Z]{1,3}-)?(\\d{3}[- ]\\d{3}|\\d{4,8})(?![\\d]|[.,]\\d|:\\d|%|[- ]\\d)")

    /** The code in [body], or null. Picks the number nearest to a "code" word; skips years and long texts. */
    fun find(body: String?): String? {
        if (body.isNullOrBlank() || body.length > 500) return null
        val hints = hint.findAll(body).map { it.range.first }.toList()
        if (hints.isEmpty()) return null
        var best: String? = null; var bestDist = Int.MAX_VALUE
        for (m in num.findAll(body)) {
            val code = m.groupValues[1].filter { it.isDigit() }
            if (code.length == 4 && code.toIntOrNull()?.let { it in 1990..2099 } == true && Regex("(?i)(19|20)\\d\\d\\b").matches(code)) {
                // a year like 2026 next to a month name is a date, not a code
                val around = body.substring(maxOf(0, m.range.first - 12), minOf(body.length, m.range.last + 12))
                if (Regex("(?i)(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec|/|©)").containsMatchIn(around)) continue
            }
            val d = hints.minOf { h -> kotlin.math.abs(h - m.range.first) }
            if (d < bestDist) { bestDist = d; best = code }
        }
        return best
    }
}
