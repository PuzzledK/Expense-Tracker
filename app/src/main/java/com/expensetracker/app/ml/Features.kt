package com.expensetracker.app.ml

import com.expensetracker.app.data.Categorizer
import com.expensetracker.app.data.Category
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.ln
import kotlin.math.floor

/** One weighted feature of an entry. [group] selects the per-group multinomial (see [G]). */
internal class Feat(val id: String, val group: Int, val w: Double)

/** Feature groups. Each is its own multinomial in the Naive Bayes model, with its own smoothing. */
internal object G {
    const val TOK = 0      // note words ("lunch", "rapido")
    const val TRI = 1      // character trigrams of each word (typos, "rapido(galleria)")
    const val KW = 2       // what the keyword rules (Categorizer) say — the model learns how far to trust them
    const val AMT = 3      // log-scale amount bucket
    const val HR = 4       // 3-hour time-of-day bucket (only when the clock time is known)
    const val WD = 5       // weekday
    const val PL = 6       // place (city)
    const val COUNT = 7

    /** Additive smoothing per group. */
    val ALPHA = doubleArrayOf(0.2, 0.2, 0.5, 0.5, 0.5, 0.5, 0.5)
}

/** Text features of a note, cached per distinct note string (notes repeat a lot). */
internal class TextFeats(val tokens: List<String>, val feats: List<Feat>, val kw: Category)

internal object Text {
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val STOP = setOf("and", "with", "the", "for", "of", "to", "a", "an", "in", "at", "from", "on", "my", "x")

    private const val TRI_WEIGHT = 0.8   // total trigram weight per word
    private const val KW_WEIGHT = 2.0

    fun norm(s: String): String =
        s.lowercase().replace('’', '\'').replace(Regex("""'s\b"""), "").replace("'", "")
            .replace(NON_ALNUM, " ").trim()

    fun tokens(s: String): List<String> =
        norm(s).split(' ').filter { it.length >= 2 && it !in STOP && !it.all(Char::isDigit) }.map(::stem)

    /** Tiny plural stemmer: "parathes"→"parathe", "chips"→"chip"; "glass" stays. */
    fun stem(t: String): String = if (t.length > 3 && t.endsWith("s") && !t.endsWith("ss")) t.dropLast(1) else t

    fun trigrams(tok: String): List<String> {
        val p = "^$tok$"
        return (0..p.length - 3).map { p.substring(it, it + 3) }
    }

    private val cache = HashMap<String, TextFeats>()

    /** Not thread-safe on its own; only called under Predictor's lock. */
    fun feats(note: String): TextFeats {
        cache[note]?.let { return it }
        if (cache.size > 20_000) cache.clear()
        val toks = tokens(note).distinct()
        val out = ArrayList<Feat>(toks.size * 8 + 1)
        for (t in toks) {
            out += Feat("t:$t", G.TOK, 1.0)
            val tri = trigrams(t)
            for (g in tri) out += Feat("g:$g", G.TRI, TRI_WEIGHT / tri.size)
        }
        val kw = Categorizer.guess(note)
        out += Feat("k:${kw.id}", G.KW, KW_WEIGHT)
        return TextFeats(toks, out, kw).also { cache[note] = it }
    }

    fun titleCase(s: String): String =
        s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            .joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
}

internal object Ctx {
    /** ₹ buckets ×1.5 apart: 60–90, 90–135, … Neighbours get partial credit (soft binning). */
    fun amountBucket(paise: Long): Int = floor(ln((paise / 100.0).coerceAtLeast(1.0)) / ln(1.5)).toInt()

    fun feats(amountPaise: Long?, date: LocalDate?, hour: Int?, place: String?): List<Feat> {
        val out = ArrayList<Feat>(6)
        if (amountPaise != null && amountPaise > 0) {
            val b = amountBucket(amountPaise)
            out += Feat("a:$b", G.AMT, 1.0)
            out += Feat("a:${b - 1}", G.AMT, 0.35)
            out += Feat("a:${b + 1}", G.AMT, 0.35)
        }
        if (date != null) out += Feat("d:${date.dayOfWeek.value}", G.WD, 0.3)
        if (hour != null) out += Feat("h:${hour / 3}", G.HR, 0.6)
        val p = place?.let { Text.norm(it) }
        if (!p.isNullOrEmpty()) out += Feat("p:$p", G.PL, 1.0)
        return out
    }

    fun isWeekend(d: DayOfWeek) = d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY

    /** Typical hour implied by the words of a note, for entries logged without a clock time. */
    fun impliedHour(tokens: List<String>): Int? = when {
        "breakfast" in tokens -> 9
        "lunch" in tokens -> 13
        "dinner" in tokens -> 21
        "snack" in tokens || "chai" in tokens || "tea" in tokens -> 17
        else -> null
    }
}

/** Payee names from UPI/bank notifications → a stable key and, for well-known merchants, a name you'd write. */
internal object Payee {
    private val NOISE = setOf(
        "limited", "ltd", "pvt", "private", "technologies", "technology", "tech", "india", "marketplace",
        "services", "service", "solutions", "retail", "payments", "payment", "pay", "upi", "store", "stores",
        "enterprises", "enterprise", "co", "company", "the", "online", "digital", "commerce", "internet",
        "llp", "inc", "corp", "corporation", "mr", "mrs", "ms", "shri", "smt", "ventures", "international",
    )

    /** Distinctive token → canonical merchant name. Payee legal names often differ from the brand. */
    private val ALIASES: Map<String, String> = mapOf(
        "bundl" to "Swiggy", "swiggy" to "Swiggy", "instamart" to "Instamart",
        "zomato" to "Zomato", "blinkit" to "Blinkit", "grofers" to "Blinkit",
        "zepto" to "Zepto", "kiranakart" to "Zepto", "rapido" to "Rapido", "roppen" to "Rapido",
        "uber" to "Uber", "olacabs" to "Ola", "ola" to "Ola", "bigbasket" to "BigBasket",
        "supermarts" to "DMart", "dmart" to "DMart", "airtel" to "Airtel", "bharti" to "Airtel",
        "jio" to "Jio", "netflix" to "Netflix", "spotify" to "Spotify", "amazon" to "Amazon",
        "flipkart" to "Flipkart", "myntra" to "Myntra", "irctc" to "Train ticket", "dmrc" to "Metro",
        "pvr" to "PVR", "bookmyshow" to "BookMyShow", "bigtree" to "BookMyShow", "district" to "District",
        "eatclub" to "EatClub", "dominos" to "Domino's", "jubilant" to "Domino's", "mcdonald" to "McDonald's",
        "starbucks" to "Starbucks", "mullvad" to "Mullvad", "anthropic" to "Claude", "openai" to "ChatGPT",
        "nykaa" to "Nykaa", "ajio" to "Ajio", "decathlon" to "Decathlon", "cult" to "Cult gym",
    )

    /** "ZOMATO LIMITED" → "zomato", "rapido@ybl" → "rapido", "Richa Sharma" → "richa sharma". */
    fun key(payee: String): String {
        val raw = payee.substringBefore('@')
        val toks = Text.norm(raw).split(' ').filter { it.isNotEmpty() && !it.all(Char::isDigit) }
        val kept = toks.filter { it !in NOISE }
        return (kept.ifEmpty { toks }).joinToString(" ")
    }

    fun canonical(key: String): String? = key.split(' ').firstNotNullOfOrNull { ALIASES[it] }
}
