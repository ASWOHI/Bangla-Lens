package com.banglalens.app

/**
 * Banglish (Romanized Bengali) -> Bengali script.
 *
 * Context-aware, in three layers:
 *  1. A lexicon of common chat words with many spelling variants (tmi/tumi, kmn/kemon, valo/bhalo ...).
 *  2. Sentence-level context to resolve ambiguous words
 *     (asi/aso/ase = আছি/আছো/আছে "to be"  vs  আসি/আসো/আসে "to come"; ki = কী "what" vs কি yes/no).
 *  3. A phonetic (Avro-style) fallback for any word not in the lexicon.
 */
object BanglishTransliterator {

    private val TOKEN = Regex("[A-Za-z']+|[^A-Za-z']+")
    private val RUN3 = Regex("(.)\\1{2,}")
    private val RUN2 = Regex("(.)\\1+")

    private val lex: Map<String, String> = HashMap<String, String>().apply {
        fun add(keys: String, v: String) = keys.split(' ').filter { it.isNotEmpty() }.forEach { this[it] = v }

        // pronouns
        add("ami", "আমি"); add("amar amr", "আমার"); add("amake amk", "আমাকে"); add("amra", "আমরা")
        add("tmi tumi tmy tumy", "তুমি"); add("tomar tmr tomr", "তোমার"); add("tomake tomke tmk", "তোমাকে")
        add("tomra tmra", "তোমরা"); add("tui", "তুই"); add("tor", "তোর"); add("toke", "তোকে")
        add("apni", "আপনি"); add("apnar", "আপনার"); add("apnake", "আপনাকে"); add("apnara", "আপনারা")
        add("se she", "সে"); add("tar", "তার"); add("take", "তাকে"); add("tara", "তারা"); add("tader", "তাদের")
        add("eta eita", "এটা"); add("ota oita", "ওটা"); add("ei", "এই"); add("oi", "ঐ")
        add("ke", "কে"); add("kar", "কার"); add("kake", "কাকে"); add("kon", "কোন"); add("kono kuno", "কোনো")

        // question words
        add("kmn kemon kmon kemn kmne", "কেমন"); add("kno keno kn kenoo", "কেন")
        add("kothay kothai kotha kothaye", "কোথায়"); add("kokhon kkhn kokhn", "কখন")
        add("kokhono kkhono", "কখনো"); add("kobe", "কবে"); add("koto kto", "কত")
        add("kivabe kibhabe kemne kmne", "কীভাবে"); add("kisher kiser", "কিসের"); add("kisu kichu kichhu", "কিছু")

        // to be / come / go / do
        add("achi", "আছি"); add("acho", "আছো"); add("ache", "আছে"); add("achen", "আছেন")
        add("aschi aschi", "আসছি"); add("asteso astesi astechi", "আসতেছো"); add("aste", "আসতে")
        add("asbo ashbo", "আসবো"); add("asba asbe ashbe", "আসবে"); add("asbi", "আসবি"); add("asen", "আসেন")
        add("aiso aisho", "আইসো"); add("aisi", "আইসি"); add("aschos", "আসছিস")
        add("jabo jbo", "যাবো"); add("jabe jbe", "যাবে"); add("jaba", "যাবা"); add("jabi", "যাবি")
        add("jai", "যাই"); add("jao jaw", "যাও"); add("jas", "যাস"); add("jacchi jacci", "যাচ্ছি")
        add("jaitesi jaitechi", "যাইতেছি"); add("jaiteso", "যাইতেছো"); add("gelo gelo", "গেলো")
        add("geso gesho gecho", "গেছো"); add("gese gese gache", "গেছে"); add("gechi gesi", "গেছি")
        add("korbo krbo", "করবো"); add("korba krba", "করবে"); add("korbe krbe", "করবে"); add("korbi", "করবি")
        add("korteso kortesho kortecho kortaso", "করতেছো"); add("kortesi kortechi", "করতেছি")
        add("korcho korso korchos", "করছো"); add("korchi korsi", "করছি"); add("korecho koreso", "করেছো")
        add("korlam", "করলাম"); add("korle", "করলে"); add("korlo", "করলো"); add("koro", "করো"); add("kor", "কর")
        add("kore", "করে"); add("kora", "করা"); add("korte", "করতে"); add("korbona", "করবো না")
        add("hoyeche hoise hoyse hoyese", "হয়েছে"); add("hoilo holo", "হলো"); add("hobe hbe", "হবে")
        add("hoy hoi", "হয়"); add("hocche hocche hochche", "হচ্ছে"); add("hoye", "হয়ে"); add("hoyto hoito", "হয়তো")
        add("hoynai honai", "হয়নি"); add("hoyni", "হয়নি")
        add("khai", "খাই"); add("khabo", "খাবো"); add("khabi", "খাবি"); add("khaiso khaicho", "খাইছো")
        add("khaisi khaichi", "খাইছি"); add("khelo", "খেলো"); add("kheyecho kheyeso", "খেয়েছো")
        add("bolo", "বলো"); add("bolbo", "বলবো"); add("bolso bolcho", "বলছো"); add("bolsi bolchi", "বলছি")
        add("bolte", "বলতে"); add("bole", "বলে"); add("bolli", "বললি"); add("bolchi", "বলছি")
        add("dekho", "দেখো"); add("dekhbo", "দেখবো"); add("dekhsi dekhchi", "দেখছি"); add("dekhi", "দেখি")
        add("dekhte", "দেখতে"); add("dekha", "দেখা"); add("dekhso dekhcho", "দেখছো")
        add("dao", "দাও"); add("dibo", "দিবো"); add("dibi", "দিবি"); add("dilam", "দিলাম"); add("diyo", "দিও")
        add("dis", "দিস"); add("diye", "দিয়ে"); add("nao", "নাও"); add("nibo", "নিবো"); add("niye", "নিয়ে")
        add("paro paru", "পারো"); add("parbo", "পারবো"); add("parbe", "পারবে"); add("pari", "পারি")
        add("jani", "জানি"); add("janina", "জানি না"); add("jano", "জানো"); add("janbo", "জানবো")
        add("bujhi bujhi", "বুঝি"); add("bujhso bujhcho", "বুঝছো"); add("bujhsi bujhchi", "বুঝছি")
        add("chai", "চাই"); add("chao", "চাও"); add("chacchi", "চাচ্ছি"); add("lagbe", "লাগবে")
        add("lagche lagse lagchhe", "লাগছে"); add("thako", "থাকো"); add("thakbo", "থাকবো"); add("thaki", "থাকি")
        add("sono shono", "শোনো"); add("shunso shuncho", "শুনছো"); add("likho", "লেখো"); add("pathao", "পাঠাও")
        add("pathaisi pathaichi", "পাঠাইছি"); add("ashbo", "আসবো"); add("ghumao", "ঘুমাও"); add("ghumaiso", "ঘুমাইছো")

        // negatives / particles
        add("na nah", "না"); add("nai", "নাই"); add("nei", "নেই"); add("naki", "নাকি"); add("toh to", "তো")
        add("hya hyan hu ha", "হ্যাঁ"); add("hmm hum", "হুম"); add("ji", "জি"); add("ar", "আর"); add("o", "ও")
        add("ba", "বা"); add("ebong", "এবং"); add("jodi", "যদি"); add("tobe", "তবে"); add("tai", "তাই")
        add("karon", "কারণ"); add("kintu kintu", "কিন্তু"); add("tahole tahle", "তাহলে"); add("tarpor", "তারপর")
        add("abar", "আবার"); add("ebar", "এবার"); add("shudhu sudhu", "শুধু")

        // adjectives / adverbs / time / place
        add("valo bhalo balo bhal val", "ভালো"); add("kharap kharaap", "খারাপ"); add("thik thikache", "ঠিক")
        add("sundor shundor", "সুন্দর"); add("onek onk", "অনেক"); add("khub kub khoob", "খুব"); add("ektu aktu", "একটু")
        add("ekta akta", "একটা"); add("ekdom", "একদম"); add("shob sob", "সব"); add("sathe", "সাথে")
        add("ekhon akhon ekhn", "এখন"); add("tokhon", "তখন"); add("aj ajk", "আজ"); add("ajke", "আজকে")
        add("kal", "কাল"); add("kalke", "কালকে"); add("agamikal", "আগামীকাল"); add("ekhane ekhne", "এখানে")
        add("okhane okhne", "ওখানে"); add("sekhane", "সেখানে"); add("sokal", "সকাল"); add("rat", "রাত")
        add("bikel", "বিকেল"); add("dupur", "দুপুর"); add("sondha", "সন্ধ্যা"); add("taratari tadatadi", "তাড়াতাড়ি")
        add("pore", "পরে"); add("age", "আগে"); add("niche", "নিচে"); add("upore", "উপরে")

        // nouns
        add("bari", "বাড়ি"); add("basa basha", "বাসা"); add("basay", "বাসায়"); add("kaj kaaj", "কাজ")
        add("khabar", "খাবার"); add("bhat", "ভাত"); add("pani", "পানি"); add("taka", "টাকা"); add("din", "দিন")
        add("nam", "নাম"); add("khobor khobar", "খবর"); add("mon", "মন"); add("phone fon", "ফোন")
        add("bhai vai", "ভাই"); add("apu", "আপু"); add("bou", "বউ"); add("ma", "মা"); add("baba abbu", "বাবা")
        add("bondhu bondu", "বন্ধু"); add("dost", "দোস্ত"); add("bon", "বোন"); add("chele", "ছেলে"); add("meye", "মেয়ে")
        add("school", "স্কুল"); add("office", "অফিস"); add("class", "ক্লাস")

        // courtesy
        add("pls plz please", "প্লিজ"); add("thanks thank", "ধন্যবাদ"); add("sorry sry", "সরি")
        add("assalamualaikum salam", "আসসালামু আলাইকুম"); add("shubho", "শুভ"); add("dhonnobad dhonnobad", "ধন্যবাদ")
        add("ok okay", "ঠিক আছে"); add("hello hi", "হ্যালো"); add("bye", "বাই")
        // numbers
        add("ek", "এক"); add("dui", "দুই"); add("tin", "তিন"); add("char", "চার"); add("pach", "পাঁচ")
        add("choy", "ছয়"); add("shat", "সাত"); add("at", "আট"); add("noy", "নয়"); add("dosh", "দশ")
    }

    /** Words that are also normal English words; they never count as evidence of Banglish. */
    private val WEAK = setOf("ache", "ar", "ba", "ma", "ha", "mon", "sob", "tar", "bon", "kal", "age", "at", "o",
        "ok", "okay", "hi", "hello", "bye", "please", "pls", "plz", "thanks", "thank", "sorry", "school", "office",
        "class", "phone", "to", "take", "char", "pani", "din", "rat", "ji", "kor", "kora", "hum", "ei", "oi", "se", "she")

    private val AMBIG = mapOf(            // key -> (to-be form, come form)
        "asi" to ("আছি" to "আসি"), "ashi" to ("আছি" to "আসি"),
        "aso" to ("আছো" to "আসো"), "asho" to ("আছো" to "আসো"),
        "ase" to ("আছে" to "আসে"), "ashe" to ("আছে" to "আসে")
    )

    private val STATE_CTX = setOf("kmn", "kemon", "kmon", "kemn", "valo", "bhalo", "balo", "bhal", "val",
        "kharap", "kharaap", "thik", "kothay", "kothai", "kotha", "ekhane", "okhane", "basay", "bari", "barite", "sukhe")
    private val COME_CTX = setOf("kno", "keno", "kn", "kokhon", "kkhn", "kokhono", "kobe", "ajke", "aj", "ajk",
        "kal", "kalke", "abar", "ebar", "tahole", "tahle", "tarpor", "taratari", "tadatadi")

    private val VERB_STEMS = listOf("kor", "khab", "khai", "khe", "jab", "jai", "jac", "jao", "bol", "dekh", "hoy",
        "hob", "hoi", "hoc", "lag", "dib", "dil", "dis", "nib", "nao", "par", "jan", "bujh", "chai", "chao", "chac",
        "likh", "pori", "shun", "sun", "dhor", "asb", "ast", "asc", "ash", "thak", "ghum", "path", "bhab", "bhaw", "kin")

    // ---------------------------------------------------------------- public API

    /** Fraction (0..1) of words that are recognisably Banglish. Used for language detection. */
    fun knownRatio(text: String): Double {
        val words = TOKEN.findAll(text).map { it.value }.filter { isWord(it) }.map { norm(it) }.filter { it.length >= 2 }.toList()
        if (words.isEmpty()) return 0.0
        val hits = words.count { w ->
            if (w in WEAK) false
            else candidates(w).any { it in lex || it in AMBIG || it == "ki" }
        }
        return hits.toDouble() / words.size
    }

    fun containsBengaliScript(text: String): Boolean = text.any { it in '\u0980'..'\u09FF' }

    fun convert(text: String): String {
        val parts = TOKEN.findAll(text).map { it.value }.toList()
        val out = StringBuilder()
        var i = 0
        while (i < parts.size) {
            var j = i
            while (j < parts.size) {
                val p = parts[j]
                j++
                if (!isWord(p) && p.any { it in ".?!\n।" }) break
            }
            out.append(convertSentence(parts.subList(i, j)))
            i = j
        }
        return out.toString()
    }

    // ---------------------------------------------------------------- internals

    private fun isWord(p: String) = p.isNotEmpty() && (p[0] in 'a'..'z' || p[0] in 'A'..'Z' || p[0] == '\'')
    private fun norm(w: String) = w.lowercase().replace("'", "")

    private fun candidates(w: String): List<String> =
        listOf(w, w.replace(RUN3, "$1"), w.replace(RUN2, "$1")).distinct()

    private fun convertSentence(parts: List<String>): String {
        val words = parts.withIndex().filter { isWord(it.value) }.map { it.index to norm(it.value) }
        val set = words.map { it.second }.toSet()
        val toBe = set.count { it in STATE_CTX } > set.count { it in COME_CTX }
        val result = parts.toMutableList()
        for ((k, pair) in words.withIndex()) {
            val (idx, w) = pair
            val next = words.getOrNull(k + 1)?.second
            result[idx] = convertWord(w, next, k == 0, k == words.lastIndex, toBe)
        }
        return result.joinToString("")
    }

    private fun convertWord(w: String, next: String?, first: Boolean, last: Boolean, toBe: Boolean): String {
        val cands = candidates(w)
        if (cands.any { it == "ki" }) {
            val whatForm = first || last || (next != null && VERB_STEMS.any { next.startsWith(it) })
            return if (whatForm) "কী" else "কি"
        }
        for (c in cands) AMBIG[c]?.let { return if (toBe) it.first else it.second }
        for (c in cands) lex[c]?.let { return it }
        return phonetic(cands.getOrElse(1) { w })
    }

    // ---------------------------------------------------------------- phonetic fallback

    private val CONS3 = mapOf("chh" to "ছ")
    private val CONS2 = mapOf("kh" to "খ", "gh" to "ঘ", "ch" to "চ", "jh" to "ঝ", "th" to "থ", "dh" to "ধ",
        "ph" to "ফ", "bh" to "ভ", "sh" to "শ", "ng" to "ং")
    private val CONS1 = mapOf('k' to "ক", 'g' to "গ", 'c' to "চ", 'j' to "জ", 't' to "ত", 'd' to "দ", 'n' to "ন",
        'p' to "প", 'b' to "ব", 'm' to "ম", 'r' to "র", 'l' to "ল", 's' to "স", 'h' to "হ", 'y' to "য", 'z' to "য",
        'f' to "ফ", 'v' to "ভ", 'w' to "ও", 'x' to "ক্স", 'q' to "ক")
    private val VOWELS = listOf("oi", "ou", "aa", "ii", "ee", "oo", "a", "i", "u", "e", "o")
    private val VOWEL_FORMS = mapOf(
        "a" to ("আ" to "া"), "aa" to ("আ" to "া"), "i" to ("ই" to "ি"), "ii" to ("ঈ" to "ী"), "ee" to ("ঈ" to "ী"),
        "u" to ("উ" to "ু"), "oo" to ("ঊ" to "ূ"), "e" to ("এ" to "ে"), "oi" to ("ঐ" to "ৈ"), "ou" to ("ঔ" to "ৌ")
    )
    private val CONJ = setOf("s|t", "s|th", "s|k", "s|p", "k|t", "n|t", "n|d", "n|dh", "m|p", "m|b", "n|k", "n|g",
        "t|r", "p|r", "b|r", "k|r", "g|r", "d|r", "bh|r", "sh|r", "s|r", "ph|r", "t|t", "d|d", "l|l", "m|m", "n|n",
        "k|k", "p|p", "b|b", "j|j", "g|g", "s|s", "ch|ch", "l|t", "l|p", "l|k", "r|t", "r|k", "r|m", "sh|t", "sh|ch")

    private fun phonetic(w: String): String {
        val sb = StringBuilder()
        var afterCons = false
        var lastKey = ""
        var i = 0
        while (i < w.length) {
            val three = if (i + 3 <= w.length) w.substring(i, i + 3) else ""
            val two = if (i + 2 <= w.length) w.substring(i, i + 2) else ""
            var key: String? = null
            var glyph: String? = null
            if (three in CONS3) { key = three; glyph = CONS3[three] }
            else if (two in CONS2) { key = two; glyph = CONS2[two] }
            else if (w[i] in CONS1) { key = w[i].toString(); glyph = CONS1[w[i]] }

            if (key != null && glyph != null) {
                if (key == "ng") {
                    sb.append(glyph); afterCons = false; lastKey = key
                } else {
                    if (afterCons) {
                        when {
                            key == "y" -> { sb.append("্য"); i += key.length; lastKey = key; continue }
                            "$lastKey|$key" in CONJ -> sb.append("্")
                        }
                    } else if (key == "y" && sb.isNotEmpty()) {
                        sb.append("য়"); afterCons = false; lastKey = key; i += 1; continue
                    }
                    sb.append(glyph); afterCons = true; lastKey = key
                }
                i += key.length
                continue
            }

            val v = VOWELS.firstOrNull { w.startsWith(it, i) }
            if (v != null) {
                val atEnd = i + v.length == w.length
                if (v == "o") {
                    if (afterCons) { if (atEnd) sb.append("ো") }
                    else sb.append(if (sb.isEmpty()) "অ" else "ও")
                } else {
                    val f = VOWEL_FORMS.getValue(v)
                    sb.append(if (afterCons) f.second else f.first)
                }
                afterCons = false
                lastKey = v
                i += v.length
                continue
            }
            sb.append(w[i]); afterCons = false; i++   // unknown char: keep
        }
        return sb.toString()
    }
}
