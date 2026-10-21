package com.heimish.messages

import com.heimish.messages.SmartReplies.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartRepliesTest {
    private fun s(m: String) = SmartReplies.suggest(m)
    private fun kind(m: String) = SmartReplies.analyze(m).kind
    private val thanksWords = listOf("א דאנק", "יישר כח", "ישר כח", "Thank", "Thanks", "Appreciate")
    private fun hasThanks(l: List<String>) = l.any { r -> thanksWords.any { r.contains(it) } && !r.contains("פארן לאזן וויסן") }

    // ── the examples from the report ──
    @Test fun beisMedrashQuestion() {
        assertEquals(listOf("יא, איך בין דא", "ניין, איך בין אין שטוב", "איך קום באלד"), s("ביסטו אין בית מדרש?"))
    }
    @Test fun beisMedrashMessySpellings() {
        for (m in listOf("ביסטו אין ביהמ\"ד", "ביסטע אין שול??", "ביסט דו אין בית המדרש", "ביסטו אין ביהמ״ד ?", "ביזטו אין בית מדרש", "בִּיסטוּ אין בית מדרש?"))
            assertEquals(m, Kind.LOCATION_PLACE, kind(m))
    }
    @Test fun whenAreYouComing() {
        assertEquals(listOf("באלד", "אין 10 מינוט", "שפעטער"), s("ווען קומסטו?"))
        assertEquals(Kind.TIME, kind("וען קומסטו"))
        assertEquals(Kind.TIME, kind("וויפיל אזייגער קומסטו?"))
    }
    @Test fun bringMilkRequest() {
        assertEquals(listOf("זיכער", "יא, קיין פראבלעם", "איך קען נישט יעצט"), s("קענסטו מיר ברענגען מילך?"))
        assertEquals(Kind.REQUEST, kind("קענסטי מיר ברענגען מילך"))
        assertEquals(Kind.REQUEST, kind("ביטע ברענג מילך"))
    }
    @Test fun gutShabbos() {
        assertEquals(listOf("א גוטן שבת!", "א גוט שבת, א גוט יאר", "שבת שלום"), s("א גוטן שבת"))
        assertEquals(Kind.GREETING_SHABBOS, kind("גוט שבת!!"))
        assertEquals(Kind.GREETING_SHABBOS, kind("גוט שאבעס 🕯️"))
    }
    @Test fun aDank() {
        assertEquals(listOf("ביטע", "נישט קיין פארוואס", "מיט פארגעניגן"), s("א דאנק"))
        assertEquals(Kind.THANKS, kind("אדאנק 🙏"))
        assertEquals(Kind.THANKS_YASHER_KOACH, kind("יישר כח"))
        assertEquals(Kind.THANKS_YASHER_KOACH, kind("יש\"כ"))
    }
    @Test fun areYouHomeEnglish() {
        assertEquals(listOf("Yes, I'm home", "Not yet", "On my way home"), s("are you home?"))
        assertEquals(Kind.LOCATION_HOME, kind("r u home"))
    }

    // ── more question types ──
    @Test fun locationYiddish() {
        assertEquals(Kind.LOCATION_HOME, kind("ביסטו אינדערהיים?"))
        assertEquals(Kind.LOCATION_HOME, kind("ביסט אין שטוב"))
        assertEquals(Kind.LOCATION_WHERE, kind("וואו ביסטו?"))
        assertEquals(Kind.LOCATION_WHERE, kind("ווו ביסטו"))
        assertEquals(Kind.LOCATION_HERE, kind("שוין דא?"))
        assertEquals(listOf("איך בין אין שטוב", "איך בין אויפן וועג", "איך קום באלד"), s("וואו ביסטו?"))
    }
    @Test fun locationEnglish() {
        assertEquals(listOf("At home", "On my way", "Be there soon"), s("where are you"))
        assertEquals(Kind.LOCATION_PLACE, kind("are you at shul?"))
        assertEquals(Kind.LOCATION_WHERE, kind("wya"))
    }
    @Test fun yesNoYiddish() {
        assertEquals(listOf("יא", "ניין", "נאך נישט"), s("האסטו שוין געגעסן?"))
        assertEquals(listOf("יא", "נאך נישט", "באלד"), s("ביסטו שוין גרייט?"))
        assertEquals(Kind.COMING, kind("קומסטו היינט?"))
        assertEquals(Kind.KNOW, kind("ווייסטו ווען די חתונה איז?"))
        assertEquals(Kind.WANT, kind("ווילסטו עסן?"))
        assertEquals(Kind.AVAILABILITY, kind("ביסטו פריי?"))
    }
    @Test fun yesNoEnglish() {
        assertEquals(listOf("Yes", "No", "Not yet"), s("did you see my email?"))
        assertEquals(Kind.AVAILABILITY, kind("can you talk?"))
        assertEquals(Kind.COMING, kind("you coming?"))
        assertEquals(Kind.REQUEST, kind("can you bring milk?"))
        assertEquals(Kind.TIME, kind("what time is the meeting?"))
        assertEquals(Kind.YES_NO, kind("does it work?"))
    }
    @Test fun howAreYou() {
        assertEquals(listOf("ברוך השם גוט", "גוט, און דו?", "ב״ה, אלעס גוט"), s("וויאזוי גייט עס?"))
        assertEquals(Kind.HOW_ARE_YOU, kind("וואס הערט זיך"))
        assertEquals(Kind.HOW_ARE_YOU, kind("Hello, how are you?"))
    }
    @Test fun openQuestions() {
        assertEquals(Kind.OPEN_QUESTION, kind("וואס עסן מיר היינט?"))
        assertEquals(Kind.OPEN_QUESTION, kind("וואו איז דער שליסל?"))
        assertEquals(Kind.OPEN_QUESTION, kind("where is the key?"))
        assertEquals(Kind.WHAT_DOING, kind("וואס טוסטו?"))
    }
    @Test fun callMe() {
        assertEquals(listOf("איך רוף דיר באלד", "זיכער", "איך קען יעצט נישט רעדן"), s("ביטע רוף מיר צוריק"))
        assertEquals(Kind.CALL_ME, kind("call me when you can"))
    }

    // ── greetings / wishes ──
    @Test fun greetings() {
        assertEquals(listOf("מזל טוב! זאלסט האבן נחת", "מזל טוב! 🎉", "א דאנק, בשמחות ביי דיר"), s("מזל טוב!"))
        assertEquals(Kind.GREETING_MORNING, kind("גוט מארגן"))
        assertEquals(Kind.GREETING_YOMTOV, kind("א גוטן יום טוב"))
        assertEquals(Kind.GREETING_WEEK, kind("א גוטע וואך"))
        assertEquals(Kind.GREETING_NIGHT, kind("גוט נאכט"))
        assertEquals(Kind.GREETING_HELLO, kind("hey"))
        assertEquals(Kind.GREETING_SHABBOS, kind("have a good shabbos"))
        assertEquals(Kind.GREETING_SHABBOS, kind("האט א גוטן שבת"))
    }

    // ── thanks only when it fits ──
    @Test fun thanksWorthy() {
        assertEquals(Kind.FAVOR_DONE, kind("איך האב דיר געשיקט די בילדער"))
        assertTrue(hasThanks(s("איך האב דיר געשיקט די בילדער")))
        assertEquals(Kind.GOOD_NEWS, kind("ברוך השם, מיר האבן באקומען די דירה"))
        assertEquals(Kind.INFO, kind("דער נומער איז 718-555-1234"))
        assertTrue(hasThanks(s("I sent you the file")))
    }
    @Test fun neverThanksForQuestions() {
        val questions = listOf(
            "ביסטו אין בית מדרש?", "ווען קומסטו?", "קענסטו מיר ברענגען מילך?", "are you home?", "ביסטו אין שטוב?",
            "וואו ביסטו", "האסטו געשיקט?", "האסטו געקויפט די מילך?", "א דאנק! ביסטו שוין אינדערהיים?", "thanks, are you coming tonight?",
            "did you send it?", "can you call me?", "וואס הערט זיך?", "ביסטו פריי?", "ווייסטו וואו ער איז?", "?", "מזל טוב! ווען איז די חתונה?",
            "וואס א שיינע בילד?", "is it done?", "ביסטו", "קומסטו", "ווילסטו", "where are you", "u there?", "are you at work"
        )
        for (q in questions) assertFalse("thanks offered for: $q -> ${s(q)}", hasThanks(s(q)))
    }
    @Test fun noThanksForBareOkOrGeneric() {
        assertFalse(hasThanks(s("אקעי")))
        assertFalse(hasThanks(s("lol that's funny")))
        assertEquals(listOf("👍", "אקעי", "שיין"), s("מארגן איז א נייער טאג"))
        assertEquals(listOf("👍", "OK", "Sounds good"), s("the weather is cold today"))
    }

    // ── language ──
    @Test fun replyLanguageFollowsScript() {
        for (m in listOf("ביסטו אין בית מדרש?", "א דאנק", "ווען קומסטו?", "גוט שבת", "סתם א מעסעדזש"))
            assertTrue(m, SmartReplies.analyze(m).yiddish)
        for (m in listOf("are you home?", "thanks", "when are you coming", "good shabbos", "just a message"))
            assertFalse(m, SmartReplies.analyze(m).yiddish)
        // English answer chips are Latin, Yiddish ones are Hebrew script or emoji
        assertTrue(s("are you home?").none { r -> r.any { it in '\u05D0'..'\u05EA' } })
        assertTrue(s("ביסטו אין שטוב?").none { r -> r.any { it in 'a'..'z' } })
    }

    // ── codes, emoji, punctuation ──
    @Test fun codesGetNoChips() {
        assertEquals(emptyList<String>(), s("Your verification code is 123456"))
        assertEquals(emptyList<String>(), s("דער קאוד איז 482913"))
    }
    @Test fun emojiAndEmpty() {
        assertEquals(listOf("👍", "❤️", "😂"), s(""))
        assertEquals(listOf("😂", "🤣", "😄"), s("😂😂"))
        assertEquals(Kind.FUNNY, kind("😂 גוט"))
        assertEquals(Kind.LOCATION_PLACE, kind("🙂 ביסטו אין שול???"))
    }
    @Test fun ligaturesAndFinalLetters() {
        assertEquals("ואו ביסטו", SmartReplies.norm("װאו ביסטו?"))
        assertEquals(Kind.LOCATION_WHERE, kind("װאו ביסטו?"))
        assertEquals(SmartReplies.norm("שלום"), SmartReplies.norm("שלומ"))
    }

    // ── shape ──
    @Test fun alwaysThreeDistinctAndDeterministic() {
        val all = listOf(
            "ביסטו אין בית מדרש?", "ווען קומסטו?", "קענסטו מיר ברענגען מילך?", "א גוטן שבת", "א דאנק", "are you home?", "", "😂", "?",
            "אקעי", "ok", "hi", "גוט מארגן", "will do", "sorry", "אנטשולדיגט", "איך בין אויפן וועג", "running late", "ניין א דאנק",
            "no thanks", "רפואה שלמה", "המקום ינחם", "https://example.com/x", "lol", "וואס א שיינע בילד", "x", "1", "?!?!"
        )
        for (m in all) {
            val r = s(m)
            assertEquals("count for '$m': $r", 3, r.size)
            assertEquals("duplicates for '$m': $r", r.size, r.toSet().size)
            assertEquals(r, s(m))
        }
    }

    @Test fun thumbsDownGetsConcernNotLaughs() {
        val r = SmartReplies.chips("👎", true)
        assertFalse(r.contains("😂")); assertFalse(r.contains("❤️"))
        assertTrue(r.contains("וואס איז?"))
        assertTrue(SmartReplies.chips("👎", false).contains("What's wrong?"))
    }

    @Test fun noChipsWithoutAFittingReply() {
        assertEquals(emptyList<String>(), SmartReplies.chips("the weather is cold today", false))
        assertEquals(emptyList<String>(), SmartReplies.chips("מארגן איז א נייער טאג", true))
        assertEquals(emptyList<String>(), SmartReplies.chips("", true))                // photo / voice note alone
        assertEquals(emptyList<String>(), SmartReplies.chips("🙃", false))             // emoji with no obvious answer
        assertEquals(emptyList<String>(), SmartReplies.chips("ok", false))
    }

    @Test fun chipsWhenTheyFit() {
        assertTrue(SmartReplies.chips("ביסטו אין שטוב?", true).isNotEmpty())
        assertTrue(SmartReplies.chips("are you home?", false).isNotEmpty())
        assertTrue(SmartReplies.chips("גוט שבת", true).isNotEmpty())
        assertTrue(SmartReplies.chips("thank you so much!", false).isNotEmpty())
        assertTrue(SmartReplies.chips("ביסטו אין שטוב?", true).all { r -> r.none { it in 'a'..'z' } }) // Yiddish answers
    }

    @Test fun chatLanguageDetection() {
        assertEquals(true, SmartReplies.isYiddish(listOf("וואו ביסטו", "ok")))
        assertEquals(false, SmartReplies.isYiddish(listOf("where are you")))
        assertEquals(null, SmartReplies.isYiddish(listOf("👍", "")))
    }

    @Test fun genericNeverOffersCallBack() {
        assertFalse(s("the weather is cold today").contains("I'll call you back"))
        assertFalse(s("מארגן איז א נייער טאג").contains("איך וועל דיר צוריק רופן"))
    }
}
