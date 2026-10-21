package com.heimish.messages

import java.text.Normalizer
import java.util.Locale

/**
 * On-device reply suggestions (no internet, no AI service, no Google Play).
 *
 * Reads the last incoming message, works out what kind of message it is (a yes/no question, "where are you",
 * "when", a request, a greeting, thanks, good news…) and offers 3 answers that fit it, in Yiddish when the message is
 * in Hebrew letters and in English when it is in Latin letters. "Thanks" chips are only offered for messages that
 * deserve thanks (a favor done, good news, information shared, a congratulation), never for a question.
 *
 * Pure Kotlin (no Android classes) so it runs in JVM unit tests. Deterministic: same message, same chips, same order.
 */
object SmartReplies {
    const val MAX = 3

    /** Kinds of message, mostly for tests and debugging. */
    enum class Kind {
        EMPTY, CODE, BAD_NEWS, CALL_ME, AVAILABILITY, LOCATION_WHERE, LOCATION_HOME, LOCATION_HERE, LOCATION_PLACE,
        TIME, REQUEST, LET_ME_KNOW, HOW_ARE_YOU, WHAT_DOING, READY, COMING, DID, KNOW, WANT, YES_NO, OPEN_QUESTION,
        GREETING_SHABBOS, GREETING_WEEK, GREETING_YOMTOV, GREETING_CHODESH, GREETING_NEW_YEAR, GREETING_MORNING,
        GREETING_NIGHT, GREETING_EVENING, GREETING_HELLO, MAZEL_TOV, REFUAH, THANKS_YASHER_KOACH, THANKS, NO_THANKS,
        SORRY, ON_MY_WAY, LATE, FUNNY, COMPLIMENT, MISSED_CALL, GOOD_NEWS, FAVOR_DONE, INFO, ACK, GENERIC
    }

    data class Result(val kind: Kind, val yiddish: Boolean, val replies: List<String>)

    fun suggest(last: String): List<String> = analyze(last).replies

    fun analyze(last: String): Result {
        val raw = last.trim()
        val heb = raw.count { it in '\u05D0'..'\u05EA' || it in '\u05F0'..'\u05F2' || it in '\uFB1D'..'\uFB4F' }
        val lat = raw.count { it in 'a'..'z' || it in 'A'..'Z' }
        val yi = heb > 0 && heb >= lat
        val m = Msg(raw)
        val kind = when {
            m.words.isEmpty() -> Kind.EMPTY
            looksLikeCode(raw) -> Kind.CODE
            else -> if (yi) yiddishKind(m) else englishKind(m)
        }
        if (kind == Kind.CODE) return Result(kind, yi, emptyList())
        val base = if (kind == Kind.EMPTY) EMOJI_ONLY else if (yi) yiddish(kind, m) else english(kind)
        val fallback = if (kind == Kind.EMPTY) EMOJI_ONLY else if (yi) YI_GENERIC else EN_GENERIC
        val out = LinkedHashSet<String>()
        for (r in base + fallback) {
            if (out.size >= MAX) break
            if (m.question && isThanks(r)) continue // never "thanks / yasher koach" for a question
            out.add(r)
        }
        return Result(kind, yi, out.toList())
    }

    // ── Normalizing messy typing ────────────────────────────────────────────
    /**
     * Lower case, no nekudos, Yiddish ligatures split (װ → וו), final letters made regular (ם → מ), quotes and
     * apostrophes dropped (ב"ה → בה, ס'איז → סאיז, can't → cant), punctuation/emoji turned into spaces, and doubled
     * letters collapsed (וואו → ואו, ביי → בי, heyyy → hey). Patterns go through the same function, so a pattern
     * written the normal way matches every spelling that collapses to the same thing.
     */
    fun norm(s: String): String {
        var t = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
        val sb = StringBuilder(t.length + 8)
        for (c in t) {
            when {
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> {}
                c == '\u05F0' -> sb.append("וו")
                c == '\u05F1' -> sb.append("וי")
                c == '\u05F2' -> sb.append("יי")
                c == 'ך' -> sb.append('כ'); c == 'ם' -> sb.append('מ'); c == 'ן' -> sb.append('נ')
                c == 'ף' -> sb.append('פ'); c == 'ץ' -> sb.append('צ')
                c == '\'' || c == '"' || c == '`' || c == '´' || c == '\u2018' || c == '\u2019' || c == '\u201C' ||
                    c == '\u201D' || c == '\u05F3' || c == '\u05F4' || c == '\u201E' -> {}
                Character.isLetterOrDigit(c) -> sb.append(c)
                else -> sb.append(' ')
            }
        }
        t = sb.toString().replace(Regex("(\\p{L})\\1+"), "$1")
        return t.trim().replace(Regex("\\s+"), " ")
    }

    private class Msg(val raw: String) {
        val n = norm(raw)
        val padded = " $n "
        val words = if (n.isEmpty()) emptyList() else n.split(' ')
        val first = words.firstOrNull() ?: ""
        val qMark = raw.any { it == '?' || it == '؟' || it == '⸮' || it == '？' }
        var question = qMark

        /** Whole word or whole phrase anywhere. */
        fun has(vararg p: String) = p.any { val k = norm(it); k.isNotEmpty() && padded.contains(" $k ") }
        /** A word that starts with one of these. */
        fun hasStart(vararg p: String) = p.any { val k = norm(it); k.isNotEmpty() && padded.contains(" $k") }
        /** The message starts with this word/phrase. */
        fun starts(vararg p: String) = p.any { val k = norm(it); k.isNotEmpty() && "$n ".startsWith("$k ") }
        /** The message starts with a word that begins with one of these. */
        fun startsStem(vararg p: String) = p.any { val k = norm(it); k.isNotEmpty() && n.startsWith(k) }
    }

    private fun looksLikeCode(raw: String): Boolean =
        Regex("(?<!\\d)\\d{4,8}(?!\\d)").containsMatchIn(raw) &&
            Regex("(?i)code|otp|verif|pin\\b|passcode|קאוד|קוד|סיסמ|אימות").containsMatchIn(raw)

    private fun isThanks(r: String): Boolean {
        val k = norm(r)
        return listOf("א דאנק", "יישר כח", "thank", "thanks", "appreciate").any { k.contains(norm(it)) } &&
            !k.contains(norm("נישט קיין פארוואס")) && !k.contains(norm("פארן לאזן וויסן")) && !k.contains(norm("for letting"))
    }

    // ── Yiddish / Hebrew-script ─────────────────────────────────────────────
    private val YI_PLACES = arrayOf(
        "בית מדרש", "בית המדרש", "ביהמד", "בהמד", "ביהמ", "שול", "שוהל", "שיל", "כולל", "ישיבה", "מקוה", "חדר",
        "ארבעט", "אפיס", "אפיס", "געשעפט", "סטאר", "קאר", "חתונה", "שמחה", "מנין", "דאווענען", "סטאפ", "ספרים"
    )
    private val YI_HOME = arrayOf("שטוב", "שטיב", "אינדערהיים", "אינדערהים", "אינדער היים", "היים", "אהיים", "הויז")

    /** First word of a verb-first question: ביסטו, האסטו, קענסטו… and the messy ביסטע/ביזטו/קענסטי. */
    private fun yiVerbQuestion(m: Msg): Boolean {
        val f = m.first
        if (listOf("סטו", "סטע", "סטי", "זטו", "סטא").any { f.endsWith(norm(it)) } && f.length > 3) return true
        val second = m.words.getOrNull(1) ?: ""
        if (f.endsWith("סט") && second in listOf("דו", "די", "טו", "דוא")) return true
        if (f in listOf("ביסט", "ביזט", "האסט", "קענסט", "ווילסט", "וועסט", "קומסט", "גייסט", "ווייסט", "דארפסט", "מעגסט",
                "טוסט", "האלטסט", "פארסט", "זעסט", "הערסט", "שלאפסט", "עסט", "זענסט").map { norm(it) }) return true
        if (!m.qMark && (m.starts("קען זיין", "קען שוין זיין", "האט א גוט", "האט א גוטן", "האט א גוטע", "האט א פריילי") ||
                m.has("שבת", "יום טוב", "גוטע וואך", "מזל טוב", "חודש"))) return false
        return m.starts("צי", "איז", "האט", "האבן", "קען", "קענען", "וועט", "וועלן", "זענט", "זענען", "זענסטו",
            "טוט", "מעג", "דארף", "וויל", "קומט", "גייט")
    }

    private fun yiddishKind(m: Msg): Kind {
        val whWords = arrayOf("וואס", "ואס", "פארוואס", "פאר וואס", "ווער", "וועמען", "וועלכע", "וועלכער", "וועלכן",
            "וויאזוי", "ווי אזוי", "וויפיל", "וויפל", "וועמענס", "פון וואנען")
        val verbQ = yiVerbQuestion(m)
        val exclaim = !m.qMark && m.starts("וואס א", "ווי שיין", "וויאזוי שיין", "וואס פאר א שיינע")
        // "וואו"/"ווי" is also "wow"/"how": count it as a question word only with a "?" or in a longer message
        val where = (m.qMark || m.words.size > 2) && m.starts("וואו", "ווו", "ווא", "ווי", "וואו איז", "ווי איז")
        val whFirst = !exclaim && (m.starts(*whWords) || where || m.starts("ווען"))
        if (verbQ || (whFirst && m.words.size <= 12)) m.question = true
        val q = m.question
        // "are you (in/at)…": ביסטו/זענט איר, or a short question with no other verb ("אין שטוב?", "שוין דא?")
        val areYou = m.hasStart("ביסט", "ביזט", "זענט", "זענסט") || (!verbQ && m.words.size <= 4)
        val coming = m.hasStart("קומסט", "קומסטו", "קומען", "קומט") || m.has("קומסט דו", "קום שוין")
        return when {
            m.has("נפטר", "נפטרה", "לע", "לא עלינו", "ברוך דיין האמת", "בדה", "לוי", "לויה", "שבעה", "המקום ינחם") -> Kind.BAD_NEWS
            m.has("רוף מיר", "רופ מיר", "רוף מיך", "רוף צוריק", "רוף מיר צוריק", "קענסטו מיר רופן", "קענסט מיר רופן",
                "גיב א קלאנג", "גיב מיר א קלאנג", "גיב מיר א רוף", "רוף אן", "רופסטו מיר") -> Kind.CALL_ME
            q && (m.has("קענסטו רעדן", "קענסט רעדן", "קענסטו יעצט רעדן", "קען מען רעדן", "ביסטו פריי", "ביסטו פרי",
                "ביסטו פארנומען", "ביסט פארנומען", "ביסטו ביזי", "האסטו צייט", "האסט צייט", "האסטו א מינוט",
                "ביסטו פנוי", "פריי", "פארנומען", "ביזי") ) -> Kind.AVAILABILITY
            m.has("וואו ביסטו", "ווו ביסטו", "ווא ביסטו", "ווי ביסטו", "וואו ביסט", "ווו ביסט", "וואו ביסט דו",
                "וואו זענט איר", "ווו זענט איר", "וואו זענסטו", "ביסטו וואו", "וואו געפינסטו זיך", "וואו האלטסטו",
                "ווו האלטסטו", "וואו האלסטו", "וואו ביזטו", "ווו ביזטו", "וואו ביסטע") -> Kind.LOCATION_WHERE
            q && areYou && m.hasStart(*YI_HOME) -> Kind.LOCATION_HOME
            q && areYou && (m.has(*YI_PLACES) || m.hasStart("ביהמ", "בהמ")) -> Kind.LOCATION_PLACE
            q && areYou && m.has("דא", "שוין דא", "דארט", "דארטן", "אנגעקומען") -> Kind.LOCATION_HERE
            q && m.startsStem("ווייסט", "ווייזט", "ווייס") -> Kind.KNOW
            m.has("ווען", "וויפיל אזייגער", "וויפל אזייגער", "ווי פיל אזייגער", "וואס צייט", "וועלכע צייט", "וויאזוי שפעט",
                "ווי לאנג", "וויפיל צייט", "וויפל צייט", "באלד", "ביז ווען") && q -> Kind.TIME
            m.has("לאז מיר וויסן", "לאז וויסן", "לאזט מיר וויסן", "זאג מיר", "זאג מיר ביטע") && !m.qMark -> Kind.LET_ME_KNOW
            m.hasStart("קענסט", "קענזט", "קאנסט", "וועסטו מיר", "ווילסטו מיר", "מעגסטו", "מעגסט") ||
                m.has("ביטע", "זיי מוחל", "זייט מוחל", "זי מוחל", "טוה מיר א טובה", "טו מיר א טובה", "קען איך ביטן",
                    "קענט איר", "וואלסטו געקענט", "וואלסט געקענט", "קען איך באקומען", "פלעז", "פליז") ||
                m.starts("ברענג", "קויף", "שיק מיר", "גיב מיר", "נעם מיט", "ברענג מיט", "כאפ", "לייג", "שיק", "נעם", "גיב",
                    "מאך", "גיי", "קום", "רוף") -> Kind.REQUEST
            m.has("וויאזוי גייט עס", "וויאזוי גייטס", "ווי גייט עס", "ווי גייטס", "וויאזוי גייט", "וואס הערט זיך",
                "וואס הערט", "וואס מאכסטו", "וואס מאכסט דו", "וואס מאכט איר", "וויאזוי פילסטו", "וויאזוי פילסט דו",
                "וויאזוי האלטסטו", "וואס איז נייעס", "וואס נייעס", "מה נשמע", "מה שלומך", "מה המצב", "וויאזוי ביסטו") -> Kind.HOW_ARE_YOU
            m.has("וואס טוסטו", "וואס טוסט דו", "וואס טוסטע", "וואס טוט איר", "וואס טוסטו יעצט", "וואס מאכסטו יעצט") -> Kind.WHAT_DOING
            whFirst && !m.starts("ווען") -> Kind.OPEN_QUESTION
            q && (m.startsStem("האסט", "האזט", "האט", "האבן") || m.hasStart("האסטו")) -> Kind.DID
            q && m.has("גרייט", "פארטיג", "פערטיג", "שוין", "אנגעקומען", "פארטיק", "גרייט שוין", "פאטיג") -> Kind.READY
            q && coming -> Kind.COMING
            q && (m.startsStem("ווילסט", "וויל", "וועסט", "וועט", "וועלן", "גייסט", "ווילזט")) -> Kind.WANT
            q && m.has(*whWords) -> Kind.OPEN_QUESTION
            q -> Kind.YES_NO
            m.has("א גוטן שבת", "א גוט שבת", "גוט שבת", "גוטן שבת", "שבת שלום", "גוט שאבעס", "א גוטן שאבעס", "גוט שבס",
                "א גוטע שבת", "גוטע שבת", "שבתא טבא", "גוטען שבת", "א גיטן שבת", "גיט שבת", "א גוטן שבת קודש") -> Kind.GREETING_SHABBOS
            m.has("א גוטע וואך", "גוטע וואך", "א גוט וואך", "גוט וואך", "שבוע טוב", "א גיטע וואך", "גיטע וואך") -> Kind.GREETING_WEEK
            m.has("גוט יום טוב", "א גוטן יום טוב", "גוטן יום טוב", "גוט יוט", "א גוט יוט", "א גוטן יוט", "חג שמח", "גוט יומטוב",
                "א גוט יום טוב", "גוט יאנטעף", "א פרייליכן יום טוב", "פרייליכן יום טוב", "א פרייליכן פורים", "פרייליכן פורים",
                "א פרייליכן חנוכה", "פרייליכן חנוכה", "א כשר און פרייליכן פסח", "כשר און פרייליכן פסח", "גוט מועד",
                "א גוטן מועד", "מועדים לשמחה", "א פרייליכן סוכות", "גוט יו״ט") -> Kind.GREETING_YOMTOV
            m.has("א גוטן חודש", "גוטן חודש", "גוט חודש", "חודש טוב", "א גוט חודש") -> Kind.GREETING_CHODESH
            m.has("כתיבה וחתימה טובה", "כוחט", "כתיבה וחתימה", "גמר חתימה טובה", "גמח", "גמר טוב", "שנה טובה",
                "א גוט געבענטשט יאר", "גוט געבענטשט יאר", "א גוט יאר", "א גוטן יאר", "לשנה טובה") -> Kind.GREETING_NEW_YEAR
            m.has("מזל טוב", "מזלטוב", "מזל טוף", "מזל", "מזט", "מזל טוב מזל טוב") -> Kind.MAZEL_TOV
            m.has("רפואה שלמה", "רפואה", "רפוש", "רפואה שלימה", "זאלסט זיין געזונט", "זיי געזונט", "גוט בעסער", "זאלסט זיך בעסער פילן") -> Kind.REFUAH
            m.has("גוט מארגן", "א גוטן מארגן", "גוטן מארגן", "גוט מאָרגן", "גוט מערגן", "בוקר טוב", "גוט מארגען", "גיט מארגן") -> Kind.GREETING_MORNING
            m.has("גוט נאכט", "א גוטע נאכט", "גוטע נאכט", "גוטע נאכט", "לילה טוב", "גוט נאכט שלאף גוט", "שלאף גוט", "א גיטע נאכט") -> Kind.GREETING_NIGHT
            m.has("גוטן אווענט", "א גוטן אווענט", "גוט אווענט", "ערב טוב") -> Kind.GREETING_EVENING
            m.has("ניין א דאנק", "ניין דאנקע", "ניין אדאנק", "ניין דאנק", "נישט נויטיג", "ס'איז נישט נויטיג") -> Kind.NO_THANKS
            m.has("יישר כח", "ישר כח", "ישכ", "יישכ", "יש כח", "יישר כוח", "ישר כוח", "שכח", "יישר כחך", "ישר כחך", "יישר כחכם", "ישכ״ח") -> Kind.THANKS_YASHER_KOACH
            m.has("א דאנק", "אדאנק", "א שיינעם דאנק", "א גרויסן דאנק", "שיינעם דאנק", "דאנקע", "דאנקע שיין", "דאנק", "תודה",
                "תודה רבה", "תח", "א דאנק דיר", "דאנקען", "א שיין דאנק") -> Kind.THANKS
            m.has("אנטשולדיגט", "אנטשולדיג", "אנטשולדיגן", "אנטשולדיגט מיר", "זיי מוחל", "מחילה", "סארי", "סאררי", "עס טוט מיר באנג",
                "ס'טוט מיר באנג", "סטוט מיר באנג") -> Kind.SORRY
            m.has("איך קום", "כקום", "איך קום שוין", "איך בין אויפן וועג", "אויפן וועג", "כבין אויפן וועג", "איך גיי שוין",
                "מיר קומען", "איך פאר שוין", "בין גלייך דא", "איך בין גלייך דא", "בין באלד דא", "איך בין באלד דא") -> Kind.ON_MY_WAY
            m.hasStart("שפעטיג", "פארשפעטיג") || m.has("איך וועל שפעטיגן", "איך וועל זיין שפעט", "שפעט", "א ביסל שפעטער") -> Kind.LATE
            m.has("איך האב דיר גערופן", "כהאב דיר גערופן", "גערופן", "מיסד קאל", "מיסט קאל", "האב געקלונגען", "געקלונגען") -> Kind.MISSED_CALL
            m.has("ברוך השם", "בה", "בח", "ברוך ה", "גוטע נייעס", "גוטע נייס", "גוטע בשורות", "בשורות טובות", "באקומען", "דורכגעגאנגען",
                "עס ארבעט", "סארבעט", "עס האט געהאלפן", "סהאט געהאלפן", "געלונגען", "הצלחה", "מיר האבן באקומען", "געבוירן",
                "א בויבעלע", "א מיידעלע", "א אינגל", "א מיידל", "פארקנאסט", "תנאים", "ווארט", "סאיז געווען גוט", "עס איז געווען גוט",
                "סאיז געגאנגען גוט") -> Kind.GOOD_NEWS
            m.has("איך האב געשיקט", "כהאב געשיקט", "געשיקט", "געמאכט", "געקויפט", "באצאלט", "געצאלט", "געברענגט", "געבראכט",
                "אפגעטון", "געפונען", "צוגעשטעלט", "אריינגעלייגט", "איינגעשטעלט", "אפגעמאכט", "געפיקסט", "גרייט", "פארטיג", "דערלעדיגט",
                "אפגעלייגט", "אפגעגעבן", "אריינגעשיקט", "איבערגעגעבן", "באשטעלט", "סאיז שוין", "עס איז שוין", "שוין געמאכט",
                "איך האב עס", "כהאב עס", "עס איז דא") -> Kind.FAVOR_DONE
            infoShared(m) || m.has("דא איז", "אט איז", "דער נומער", "די נומער", "דער אדרעס", "די אדרעס", "אדרעס", "נומער",
                "דער לינק", "די לינק", "פאר דיין אינפא", "צו וויסן", "זיי וויסן") -> Kind.INFO
            funny(m) || m.has("האהא", "האהאהא", "חחח", "חח", "הההה", "קאמיש", "געלעכטער", "לאל") -> Kind.FUNNY
            m.has("שיין", "שיינע", "שיינער", "גאר שיין", "זייער שיין", "וואונדערבאר", "געשמאק", "מחיה", "מחי", "פרעכטיג",
                "וואו", "אמעיזינג", "נייס", "חן", "חנעוודיג", "חנעוודיג", "קיוט", "געוואלדיג", "געוואלד") -> Kind.COMPLIMENT
            m.words.size <= 3 && m.has("אקעי", "אוקי", "אקיי", "אוקיי", "אק", "גוט", "זייער גוט", "סגוט", "סאיז גוט", "עס איז גוט", "יא", "בסדר", "שוין",
                "זיכער", "קלאר", "גוט אזוי", "פיין", "טאקע", "ניין", "גוט גוט") -> Kind.ACK
            m.words.size <= 5 && m.has("שלום", "שלום עליכם", "הי", "הייא", "היי", "האלא", "העלא", "הייי", "שלום שלום", "ברוך הבא", "ברוכים הבאים") -> Kind.GREETING_HELLO
            else -> Kind.GENERIC
        }
    }

    private fun yiddish(k: Kind, m: Msg): List<String> = when (k) {
        Kind.BAD_NEWS -> listOf("ברוך דיין האמת", "המקום ינחם אתכם", "🙏")
        Kind.CALL_ME -> listOf("איך רוף דיר באלד", "זיכער", "איך קען יעצט נישט רעדן")
        Kind.AVAILABILITY -> listOf("יא, רוף מיר", "ניין, איך בין פארנומען", "איך וועל דיר צוריק רופן")
        Kind.LOCATION_WHERE -> listOf("איך בין אין שטוב", "איך בין אויפן וועג", "איך קום באלד")
        Kind.LOCATION_HOME -> listOf("יא, איך בין אין שטוב", "ניין, נאך נישט", "איך קום באלד אהיים")
        Kind.LOCATION_HERE -> listOf("יא, איך בין דא", "נאך נישט", "איך קום באלד")
        Kind.LOCATION_PLACE -> listOf("יא, איך בין דא", "ניין, איך בין אין שטוב", "איך קום באלד")
        Kind.TIME -> listOf("באלד", "אין 10 מינוט", "שפעטער")
        Kind.LET_ME_KNOW -> listOf("זיכער", "איך וועל דיר לאזן וויסן", "👍")
        Kind.REQUEST -> listOf("זיכער", "יא, קיין פראבלעם", "איך קען נישט יעצט")
        Kind.HOW_ARE_YOU -> listOf("ברוך השם גוט", "גוט, און דו?", "ב״ה, אלעס גוט")
        Kind.WHAT_DOING -> listOf("נישט קיין סאך", "איך בין פארנומען", "איך וועל דיר צוריק רופן")
        Kind.READY -> listOf("יא", "נאך נישט", "באלד")
        Kind.COMING -> listOf("יא, איך קום", "ניין, איך קען נישט", "באלד")
        Kind.DID -> listOf("יא", "ניין", "נאך נישט")
        Kind.KNOW -> listOf("יא", "איך ווייס נישט", "איך וועל קוקן")
        Kind.WANT -> listOf("יא", "ניין", "אפשר")
        Kind.YES_NO -> listOf("יא", "ניין", "איך ווייס נישט")
        Kind.OPEN_QUESTION -> listOf("איך וועל קוקן", "איך ווייס נישט", "איך וועל דיר צוריק רופן")
        Kind.GREETING_SHABBOS -> listOf("א גוטן שבת!", "א גוט שבת, א גוט יאר", "שבת שלום")
        Kind.GREETING_WEEK -> listOf("א גוטע וואך!", "א גוטע וואך, א גוט יאר", "שבוע טוב")
        Kind.GREETING_YOMTOV -> listOf("א גוטן יום טוב!", "א גוט יום טוב, א פרייליכן יום טוב", "חג שמח")
        Kind.GREETING_CHODESH -> listOf("א גוטן חודש!", "חודש טוב", "א גוט חודש, א גוט יאר")
        Kind.GREETING_NEW_YEAR -> if (m.has("גמר חתימה טובה", "גמח", "גמר טוב")) listOf("גמר חתימה טובה!", "א גוט געבענטשט יאר", "🙏")
            else listOf("כתיבה וחתימה טובה!", "א גוט געבענטשט יאר", "🙏")
        Kind.MAZEL_TOV -> listOf("מזל טוב! זאלסט האבן נחת", "מזל טוב! 🎉", "א דאנק, בשמחות ביי דיר")
        Kind.REFUAH -> listOf("אמן!", "א דאנק", "🙏")
        Kind.GREETING_MORNING -> listOf("גוט מארגן!", "א גוטן מארגן, וואס הערט זיך?", "☀️")
        Kind.GREETING_NIGHT -> listOf("א גוטע נאכט!", "גוט נאכט, שלאף גוט", "😴")
        Kind.GREETING_EVENING -> listOf("א גוטן אווענט!", "וואס הערט זיך?", "👋")
        Kind.GREETING_HELLO -> listOf("שלום!", "וואס הערט זיך?", "👋")
        Kind.THANKS_YASHER_KOACH -> listOf("ברוך תהיה", "נישט קיין פארוואס", "👍")
        Kind.THANKS -> listOf("ביטע", "נישט קיין פארוואס", "מיט פארגעניגן")
        Kind.NO_THANKS -> listOf("אקעי", "👍", "קיין פראבלעם")
        Kind.SORRY -> listOf("קיין פראבלעם", "ס׳איז גארנישט", "👍")
        Kind.ON_MY_WAY -> listOf("אקעי, איך ווארט", "👍", "פאר געזונטערהייט")
        Kind.LATE -> listOf("אקעי, קיין פראבלעם", "ווען וועסטו זיין דא?", "👍")
        Kind.MISSED_CALL -> listOf("איך רוף דיר באלד צוריק", "איך קען יעצט נישט רעדן", "👍")
        Kind.GOOD_NEWS -> listOf("ברוך השם! 🙌", "זייער גוט!", "א דאנק פארן לאזן וויסן")
        Kind.FAVOR_DONE -> listOf("א דאנק!", "יישר כח!", "👍")
        Kind.INFO -> listOf("א דאנק!", "👍", "געכאפט")
        Kind.FUNNY -> listOf("😂", "😄", "👍")
        Kind.COMPLIMENT -> listOf("א דאנק!", "😊", "❤️")
        Kind.ACK -> listOf("👍", "אקעי", "😊")
        else -> YI_GENERIC
    }

    // ── English / Latin ─────────────────────────────────────────────────────
    private fun enVerbQuestion(m: Msg): Boolean {
        if (m.qMark) return true
        if (m.starts("will do", "have a", "have fun", "have a good", "can do", "do it", "did it", "is ok", "is fine", "should be",
                "could be", "would be", "what a", "how nice", "how cute", "how cool", "how sweet", "how awesome", "how funny",
                "how beautiful", "was great", "was nice", "was good", "is good", "is great")) return false
        if (m.has("shabbos", "shabbat", "yom tov", "good week", "mazel tov", "mazal tov")) return false
        return m.starts("are", "r", "is", "isnt", "do", "does", "dont", "did", "didnt", "can", "could", "will", "would",
            "should", "have", "havent", "has", "was", "were", "am", "shall", "hows", "how", "whats", "what", "wat", "wut", "why",
            "who", "whos", "which", "where", "wheres", "when", "whens", "wya", "wyd", "hru", "wru")
    }

    private fun englishKind(m: Msg): Kind {
        val whWords = arrayOf("what", "whats", "wat", "wut", "why", "who", "whos", "whom", "which", "how", "hows", "whose", "how much", "how many", "where", "wheres")
        if (enVerbQuestion(m)) m.question = true
        val q = m.question
        val asks = m.starts("can", "could", "would", "will", "please", "pls", "plz")
        val short = m.words.size <= 3
        return when {
            m.has("passed away", "rip", "funeral", "shiva", "levaya", "nifter", "condolences", "boruch dayan haemes", "baruch dayan haemet", "bdh") -> Kind.BAD_NEWS
            m.has("call me", "call me back", "give me a call", "gimme a call", "can you call", "could you call", "pls call", "please call", "call back", "ring me") -> Kind.CALL_ME
            q && m.has("are you free", "r u free", "u free", "you free", "are you busy", "r u busy", "u busy", "you busy", "are you available",
                "can you talk", "can u talk", "can we talk", "got a minute", "got a sec", "do you have a minute", "do you have time", "free now", "busy") -> Kind.AVAILABILITY
            m.has("where are you", "where r u", "where r you", "where are u", "where u at", "where you at", "wya", "wru", "where are you now", "where r u now") -> Kind.LOCATION_WHERE
            q && !asks && m.has("home", "at home", "house", "home yet") -> Kind.LOCATION_HOME
            q && !asks && m.has("here", "there", "there yet", "here yet", "arrived", "outside") && !m.hasStart("anything", "anyone", "anybody", "is there") -> Kind.LOCATION_HERE
            q && !asks && (m.has("are you at", "r u at", "u at", "you at", "are you in", "r u in", "are you still at", "are you still in", "r u still at") ||
                ((m.starts("are you", "r u", "are u", "r you", "u", "you", "still") || m.words.size <= 2) &&
                    m.has("shul", "office", "work", "school", "the store", "the shop", "kollel", "yeshiva", "beis medrash", "bais medrash", "shtiebel", "mikvah", "the hospital"))) -> Kind.LOCATION_PLACE
            q && m.starts("do you know", "do u know", "you know", "u know", "any idea") -> Kind.KNOW
            q && m.has("when", "what time", "whens", "how long", "how soon", "eta", "until when", "till when", "by when") -> Kind.TIME
            m.has("let me know", "lmk", "keep me posted", "tell me") && !m.qMark -> Kind.LET_ME_KNOW
            m.has("can you", "can u", "could you", "could u", "would you", "would u", "will you", "will u", "can ya", "cud u", "please", "pls", "plz",
                "would you mind", "do me a favor", "mind if", "can i ask") ||
                m.starts("bring", "pick up", "grab", "buy", "send me", "get me", "take", "come") -> Kind.REQUEST
            m.has("how are you", "how r u", "how are u", "how r you", "hru", "how you doing", "how are you doing", "hows it going", "how is it going",
                "how are things", "whats up", "wassup", "sup", "whats new", "how you been", "how have you been", "hows everything", "how u doing", "hows life") -> Kind.HOW_ARE_YOU
            m.has("what are you doing", "what r u doing", "wyd", "what you doing", "what u doing", "whatcha doing", "what are you up to", "what r u up to") -> Kind.WHAT_DOING
            m.starts(*whWords) -> Kind.OPEN_QUESTION
            q && m.starts("did", "didnt", "have you", "havent", "has", "have u") -> Kind.DID
            q && m.has("ready", "done", "finished", "already", "yet") -> Kind.READY
            q && m.has("coming", "you coming", "u coming", "are you coming", "r u coming", "joining", "gonna come", "going to come", "make it") -> Kind.COMING
            q && m.starts("did", "didnt", "have", "havent", "has", "got", "did you") -> Kind.DID
            q && (m.has("do you know", "do u know", "you know", "u know", "any idea", "know if", "know where", "know when")) -> Kind.KNOW
            q && (m.starts("will", "wont", "wanna", "want", "would") || m.has("do you want", "do u want", "you want", "u want", "would you like", "want to", "wanna")) -> Kind.WANT
            q && m.has(*whWords) -> Kind.OPEN_QUESTION
            q -> Kind.YES_NO
            m.has("good shabbos", "gut shabbos", "good shabbes", "gut shabbes", "shabbat shalom", "shabbos", "good shabbat", "gut shabes") -> Kind.GREETING_SHABBOS
            m.has("good week", "gut voch", "a gutte voch", "shavua tov", "a good week", "have a good week") -> Kind.GREETING_WEEK
            m.has("good yom tov", "gut yom tov", "chag sameach", "happy holidays", "happy purim", "happy chanukah", "happy hanukkah", "happy pesach", "chag kasher vesameach", "happy sukkos", "gut yontif", "good yontif") -> Kind.GREETING_YOMTOV
            m.has("chodesh tov", "gut chodesh", "good chodesh") -> Kind.GREETING_CHODESH
            m.has("shana tova", "shanah tovah", "kesiva vachasima", "ksiva vachasima", "gmar chasima", "happy new year", "gut yohr", "good year") -> Kind.GREETING_NEW_YEAR
            m.has("mazel tov", "mazal tov", "mazeltov", "congrats", "congratulations", "congratz", "mazel") -> Kind.MAZEL_TOV
            m.has("refuah shleima", "refua shleima", "refuah shelema", "feel better", "get well", "get well soon", "hope you feel better") -> Kind.REFUAH
            m.has("good morning", "gut morgen") || (short && m.has("gm", "morning")) -> Kind.GREETING_MORNING
            m.has("good night", "goodnight", "sleep well", "sweet dreams", "gut nacht") || (short && m.has("gn", "nite", "night")) -> Kind.GREETING_NIGHT
            m.has("good evening") || (short && m.has("evening")) -> Kind.GREETING_EVENING
            m.has("no thanks", "no thank you", "no thx", "nah thanks", "im good thanks", "no need") -> Kind.NO_THANKS
            m.has("yasher koach", "yasher koiach", "yasher koyach", "yashar koach", "shkoach", "shkoyach", "yk") -> Kind.THANKS_YASHER_KOACH
            m.has("thanks", "thank you", "thank u", "thx", "thnx", "tnx", "ty", "tysm", "thanks so much", "much appreciated", "appreciate it", "appreciate", "a dank", "todah", "toda") -> Kind.THANKS
            m.has("sorry", "my bad", "apologies", "i apologize", "srry", "sry", "oops") -> Kind.SORRY
            m.has("on my way", "omw", "im coming", "coming", "be there soon", "leaving now", "heading over", "almost there", "be right there", "on the way") -> Kind.ON_MY_WAY
            m.has("late", "running late", "im late", "be late", "delayed", "stuck in traffic", "traffic") -> Kind.LATE
            m.has("i called you", "called you", "missed call", "tried calling", "tried to call", "i called") -> Kind.MISSED_CALL
            m.has("good news", "great news", "baruch hashem", "boruch hashem", "bh", "passed", "approved", "accepted", "it worked",
                "got the job", "engaged", "its a boy", "its a girl", "had a baby", "we did it", "success") -> Kind.GOOD_NEWS
            m.has("i sent", "sent it", "sent you", "just sent", "i paid", "paid", "i bought", "bought it", "done", "all set", "taken care of", "dropped off",
                "picked up", "booked", "ordered", "fixed", "here you go", "here it is", "i did it", "its ready", "ready") -> Kind.FAVOR_DONE
            infoShared(m) || m.has("heres", "here is", "the number is", "the address is", "address", "fyi", "for your info", "link") -> Kind.INFO
            funny(m) || m.has("lol", "haha", "hahaha", "lmao", "lmfao", "rofl", "funny", "hilarious", "hehe") -> Kind.FUNNY
            m.has("beautiful", "gorgeous", "cute", "adorable", "love it", "amazing", "awesome", "wow", "so nice", "looks great",
                "looks good", "stunning", "wonderful", "what a", "nice pic", "nice picture", "great pic") -> Kind.COMPLIMENT
            short && m.has("ok", "okay", "k", "kk", "okey", "will do", "sure", "cool", "great", "sounds good", "got it", "alright", "fine", "yes", "yeah", "yep", "no", "nope", "perfect", "nice", "good") -> Kind.ACK
            m.words.size <= 5 && m.starts("hi", "hello", "hey", "heya", "hiya", "yo", "shalom", "howdy", "hey there", "hi there") -> Kind.GREETING_HELLO
            else -> Kind.GENERIC
        }
    }

    private fun english(k: Kind): List<String> = when (k) {
        Kind.BAD_NEWS -> listOf("Baruch Dayan HaEmes", "I'm so sorry to hear", "🙏")
        Kind.CALL_ME -> listOf("I'll call you soon", "Sure", "Can't talk right now")
        Kind.AVAILABILITY -> listOf("Yes, call me", "No, I'm busy", "I'll call you back")
        Kind.LOCATION_WHERE -> listOf("At home", "On my way", "Be there soon")
        Kind.LOCATION_HOME -> listOf("Yes, I'm home", "Not yet", "On my way home")
        Kind.LOCATION_HERE -> listOf("Yes, I'm here", "Not yet", "Almost there")
        Kind.LOCATION_PLACE -> listOf("Yes, I'm here", "No, I'm home", "On my way")
        Kind.TIME -> listOf("Soon", "In 10 minutes", "Later")
        Kind.LET_ME_KNOW -> listOf("Sure", "Will do", "👍")
        Kind.REQUEST -> listOf("Sure", "Yes, no problem", "I can't right now")
        Kind.HOW_ARE_YOU -> listOf("Good, and you?", "All good 👍", "Doing great")
        Kind.WHAT_DOING -> listOf("Not much", "Busy right now", "I'll call you back")
        Kind.READY -> listOf("Yes", "Not yet", "Almost")
        Kind.COMING -> listOf("Yes, I'm coming", "Can't make it", "Soon")
        Kind.DID -> listOf("Yes", "No", "Not yet")
        Kind.KNOW -> listOf("Yes", "I don't know", "Let me check")
        Kind.WANT -> listOf("Yes", "No", "Maybe")
        Kind.YES_NO -> listOf("Yes", "No", "Not sure")
        Kind.OPEN_QUESTION -> listOf("Let me check", "I don't know", "I'll call you back")
        Kind.GREETING_SHABBOS -> listOf("Good Shabbos!", "Gut Shabbos, a gut yohr", "Shabbat Shalom")
        Kind.GREETING_WEEK -> listOf("A gutte voch!", "Have a great week", "Shavua tov")
        Kind.GREETING_YOMTOV -> listOf("Good Yom Tov!", "Chag sameach", "A freilichen Yom Tov")
        Kind.GREETING_CHODESH -> listOf("Chodesh tov!", "Gut chodesh", "🙏")
        Kind.GREETING_NEW_YEAR -> listOf("Kesiva vachasima tova!", "A gut gebentcht yohr", "🙏")
        Kind.MAZEL_TOV -> listOf("Mazel tov! 🎉", "Mazel tov, lots of nachas!", "Thank you!")
        Kind.REFUAH -> listOf("Amen!", "Thank you", "🙏")
        Kind.GREETING_MORNING -> listOf("Good morning!", "Morning! How are you?", "☀️")
        Kind.GREETING_NIGHT -> listOf("Good night!", "Sleep well", "😴")
        Kind.GREETING_EVENING -> listOf("Good evening!", "What's up?", "👋")
        Kind.GREETING_HELLO -> listOf("Hi!", "Hey, what's up?", "👋")
        Kind.THANKS_YASHER_KOACH -> listOf("Baruch tihyeh", "You're welcome!", "👍")
        Kind.THANKS -> listOf("You're welcome!", "Anytime", "No problem")
        Kind.NO_THANKS -> listOf("OK", "👍", "No problem")
        Kind.SORRY -> listOf("No problem", "No worries", "It's fine")
        Kind.ON_MY_WAY -> listOf("OK, see you soon", "👍", "Drive safe")
        Kind.LATE -> listOf("OK, no problem", "When will you be here?", "👍")
        Kind.MISSED_CALL -> listOf("I'll call you back soon", "Can't talk right now", "👍")
        Kind.GOOD_NEWS -> listOf("That's great!", "Awesome 🙌", "Thanks for letting me know")
        Kind.FAVOR_DONE -> listOf("Thank you!", "Appreciate it 🙏", "👍")
        Kind.INFO -> listOf("Thanks!", "👍", "Got it")
        Kind.FUNNY -> listOf("😂", "😄", "👍")
        Kind.COMPLIMENT -> listOf("Thank you!", "😊", "❤️")
        Kind.ACK -> listOf("👍", "OK", "😊")
        else -> EN_GENERIC
    }

    /** Laughing emoji in the message. */
    private fun funny(m: Msg): Boolean = listOf("😂", "🤣", "😆", "😹").any { m.raw.contains(it) }

    /** A link, an e-mail address, or a phone number in the message. */
    private fun infoShared(m: Msg): Boolean = Regex("(?i)https?://|www\\.").containsMatchIn(m.raw) ||
        Regex("(?i)[a-z0-9._-]+@[a-z0-9-]+\\.[a-z]{2,}|[a-z0-9-]+\\.(com|org|net|io|co|us)\\b").containsMatchIn(m.raw) ||
        Regex("\\d[\\d\\s().-]{6,}\\d").findAll(m.raw).any { r -> r.value.count { it.isDigit() } >= 7 }

    private val YI_GENERIC = listOf("👍", "אקעי", "איך וועל דיר צוריק רופן")
    private val EN_GENERIC = listOf("👍", "OK", "I'll call you back")
    private val EMOJI_ONLY = listOf("👍", "❤️", "😂")
}
