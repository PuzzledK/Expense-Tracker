package com.expensetracker.app.capture

import kotlin.math.exp
import kotlin.math.ln

/**
 * Learning layer on top of [PaymentParser] (pure Kotlin, unit-tested; no android.*).
 *
 * The regex parser stays the backbone; this learns from how you review drafts:
 *
 *  1. **Formats.** Every message is reduced to a [MessageShape]: its payment words kept
 *     ("paid you", "debited from a/c", "₹"), names masked as `*`, numbers as `#`, UPI IDs as `@`.
 *     Two Google Pay "X paid you ₹N" messages share a shape whoever paid and however much. When you
 *     confirm a draft, the model remembers for that app + shape which number was the amount, the
 *     direction, and which name slot held the payee. If you had to correct any of them, the next
 *     message in that format is read your way instead of the regex way. A format you've confirmed
 *     before is also read when the regex rules reject it.
 *
 *  2. **Payment or not.** A Naive Bayes classifier over the shape's words (+ word pairs and the app)
 *     learns from drafts you save (payment) and ones you dismiss as "Not a payment". Once it has seen
 *     enough of both, it hides messages it's very sure are junk (you can still see and restore them)
 *     and rescues messages the regex rejected but that look like payments, as "unsure" drafts.
 *
 * Privacy: the model only ever stores shapes (payment words and symbols), never names, amounts or
 * the message text. OTPs, failed payments and payment requests are never turned into drafts.
 */
class ParserModel {

    /** A message with its variable parts pulled out. [tokens] is the shape; [numbers]/[names] fill its `#`/`*` slots in order. */
    data class MessageShape(val tokens: List<String>, val numbers: List<Long?>, val names: List<String>) {
        val signature: String get() = tokens.joinToString(" ")
    }

    /** What the model learned about one app + shape. */
    data class FormatRule(
        /** Index into [MessageShape.numbers] holding the amount. */
        val amountSlot: Int,
        val isCredit: Boolean,
        /** Index into [MessageShape.names] holding the payee, if you named one that appears in the text. */
        val payeeSlot: Int?,
        /** True once you've corrected the regex reading for this format: the rule then wins. */
        val corrected: Boolean,
        /** Times confirmed. */
        val seen: Int,
    )

    enum class Origin(val code: Int) {
        /** Read by the regex rules, unchanged. */
        REGEX(0),
        /** Read with a format rule you taught (overrode or replaced the regex reading). */
        LEARNED(1),
        /** The regex rules rejected it; the classifier thinks it's a payment. Shown as unsure. */
        UNSURE(2);

        companion object { fun of(code: Int) = entries.firstOrNull { it.code == code } ?: REGEX }
    }

    sealed interface Decision {
        /** Make a draft. [hidden] = the classifier is sure it's junk: keep it out of the inbox. */
        data class Draft(val payment: ParsedPayment, val origin: Origin, val hidden: Boolean) : Decision
        data object Drop : Decision
    }

    private val rules = HashMap<String, FormatRule>()
    /** Times each app + shape was dismissed as "Not a payment". */
    private val notPayment = HashMap<String, Int>()
    private val featureCounts = arrayOf(HashMap<String, Int>(), HashMap<String, Int>()) // [NOT, PAYMENT]
    private val docs = intArrayOf(0, 0)

    val formatsLearned: Int get() = rules.size
    val examples: Int get() = docs[0] + docs[1]

    // ------------------------------------------------------------------ decide

    /**
     * @param regex what [PaymentParser.parse] made of it (null = rejected / no match).
     * @param requireBankContext SMS: classifier rescues need a bank-looking message.
     */
    fun decide(group: String, title: String?, text: String?, regex: ParsedPayment?, requireBankContext: Boolean = false): Decision {
        val shape = shape(PaymentParser.combined(title, text))
        val key = key(group, shape)
        val rule = rules[key]
        val confirmedBefore = (rule?.seen ?: 0) > 0
        val pPayment = probabilityPayment(features(group, shape))

        if (regex != null) {
            val learned = rule?.takeIf { it.corrected }?.let { read(it, shape, regex) }
            val hidden = !confirmedBefore && (
                (notPayment[key] ?: 0) >= 2 || (pPayment != null && pPayment <= 1 - HIDE_CONFIDENCE)
            )
            return Decision.Draft(learned ?: regex, if (learned != null && learned != regex) Origin.LEARNED else Origin.REGEX, hidden)
        }

        // The regex rules said no. Money that didn't move is never rescued.
        if (PaymentParser.isHardReject(title, text)) return Decision.Drop
        if (rule != null && (notPayment[key] ?: 0) == 0) {
            val loose = PaymentParser.parseLoose(title, text)
            read(rule, shape, loose)?.let { return Decision.Draft(it, if (rule.seen >= 2) Origin.LEARNED else Origin.UNSURE, hidden = false) }
        }
        // SMS apps also carry personal messages: only rescue bank-looking ones on a hunch.
        if (pPayment != null && pPayment >= RESCUE_CONFIDENCE && hasMoney(shape) &&
            (!requireBankContext || PaymentParser.looksLikeBank(title, text))
        ) {
            PaymentParser.parseLoose(title, text)?.let { return Decision.Draft(it, Origin.UNSURE, hidden = false) }
        }
        return Decision.Drop
    }

    /** [base] with the rule's amount / direction / payee applied; null if the message doesn't fit the rule. */
    private fun read(rule: FormatRule, shape: MessageShape, base: ParsedPayment?): ParsedPayment? {
        val amount = shape.numbers.getOrNull(rule.amountSlot) ?: return null
        if (amount <= 0) return null
        val name = rule.payeeSlot?.let { shape.names.getOrNull(it) }?.let(PaymentParser::cleanName)?.takeIf { it.length >= 2 }
        return ParsedPayment(
            amountPaise = amount,
            payee = name ?: base?.payee ?: "Unknown",
            isCredit = rule.isCredit,
            payeeQuality = if (name != null) PayeeQuality.NAME else base?.payeeQuality ?: PayeeQuality.UNKNOWN,
            account = base?.account,
        )
    }

    // ------------------------------------------------------------------ learn

    /**
     * You saved this draft with these values. [parsed] is what the draft showed before your edits;
     * [note] is what you wrote (used to find the payee in the text when it's there).
     */
    fun learnPayment(group: String, title: String?, text: String?, amountPaise: Long, isCredit: Boolean, note: String, parsed: ParsedPayment?) {
        val shape = shape(PaymentParser.combined(title, text))
        val key = key(group, shape)
        addExample(PAYMENT, features(group, shape))
        notPayment.remove(key)

        val amountSlot = shape.numbers.indexOf(amountPaise).takeIf { it >= 0 } ?: return // amount typed by hand: nothing to locate
        val payeeSlot = payeeSlot(shape, note)
        val disagrees = parsed == null || parsed.amountPaise != amountPaise || parsed.isCredit != isCredit ||
            (payeeSlot != null && !sameName(parsed.payee, shape.names[payeeSlot]))
        val old = rules[key]
        rules[key] = FormatRule(
            amountSlot = amountSlot,
            isCredit = isCredit,
            payeeSlot = payeeSlot ?: old?.payeeSlot,
            corrected = disagrees || (old?.corrected == true),
            seen = (old?.seen ?: 0) + 1,
        )
    }

    /** You dismissed this draft as "Not a payment". */
    fun learnNotPayment(group: String, title: String?, text: String?) {
        val shape = shape(PaymentParser.combined(title, text))
        val key = key(group, shape)
        addExample(NOT, features(group, shape))
        notPayment[key] = (notPayment[key] ?: 0) + 1
        // A format you've never confirmed and now call junk shouldn't keep rescuing messages.
        rules[key]?.let { if (it.seen <= 1 && !it.corrected) rules.remove(key) }
    }

    /** Undo of [learnNotPayment]. */
    fun unlearnNotPayment(group: String, title: String?, text: String?) {
        val shape = shape(PaymentParser.combined(title, text))
        val key = key(group, shape)
        addExample(NOT, features(group, shape), -1)
        notPayment[key]?.let { if (it <= 1) notPayment.remove(key) else notPayment[key] = it - 1 }
    }

    fun clear() {
        rules.clear(); notPayment.clear(); featureCounts.forEach { it.clear() }; docs[0] = 0; docs[1] = 0
    }

    private fun payeeSlot(shape: MessageShape, note: String): Int? {
        val n = note.trim()
        if (n.length < 2) return null
        return shape.names.indexOfFirst { sameName(it, n) }.takeIf { it >= 0 }
    }

    private fun sameName(a: String, b: String): Boolean {
        val x = a.lowercase().filter(Char::isLetterOrDigit)
        val y = b.lowercase().filter(Char::isLetterOrDigit)
        return x.length >= 2 && y.length >= 2 && (x == y || (y.length >= 3 && x.contains(y)) || (x.length >= 3 && y.contains(x)))
    }

    // ------------------------------------------------------------------ classifier

    private fun features(group: String, shape: MessageShape): Set<String> = buildSet {
        add("app:$group")
        shape.tokens.forEach { if (it != "*" && it != "#") add(it) }
        shape.tokens.zipWithNext().forEach { (a, b) -> add("$a $b") }
    }

    private fun addExample(cls: Int, f: Set<String>, delta: Int = 1) {
        docs[cls] = (docs[cls] + delta).coerceAtLeast(0)
        val m = featureCounts[cls]
        f.forEach { k -> val v = (m[k] ?: 0) + delta; if (v <= 0) m.remove(k) else m[k] = v }
    }

    /** P(payment) for a message, or null until there are enough examples of both kinds. */
    internal fun probabilityPayment(group: String, title: String?, text: String?): Double? =
        probabilityPayment(features(group, shape(PaymentParser.combined(title, text))))

    /** P(payment) for these features, or null until there are enough examples of both kinds. */
    internal fun probabilityPayment(f: Set<String>): Double? {
        if (docs[NOT] < MIN_EXAMPLES || docs[PAYMENT] < MIN_EXAMPLES) return null
        // Bernoulli-style naive Bayes over the features present, Laplace-smoothed.
        val score = DoubleArray(2) { cls ->
            val n = docs[cls].toDouble()
            var s = ln(n / (docs[0] + docs[1]))
            f.forEach { k -> s += ln(((featureCounts[cls][k] ?: 0) + 1.0) / (n + 2.0)) }
            s
        }
        return 1.0 / (1.0 + exp(score[NOT] - score[PAYMENT]))
    }

    private fun hasMoney(shape: MessageShape): Boolean = shape.tokens.withIndex().any { (i, t) ->
        t == "#" && (shape.tokens.getOrNull(i - 1) == "₹" || shape.tokens.getOrNull(i + 1) == "₹")
    }

    // ------------------------------------------------------------------ persistence

    /** Line format, one fact per line; shapes contain no tabs or newlines. */
    fun encode(): String = buildString {
        appendLine("v1")
        rules.forEach { (k, r) -> appendLine("R\t$k\t${r.amountSlot}\t${r.isCredit}\t${r.payeeSlot ?: "-"}\t${r.corrected}\t${r.seen}") }
        notPayment.forEach { (k, n) -> appendLine("N\t$k\t$n") }
        docs.forEachIndexed { cls, n -> appendLine("D\t$cls\t$n") }
        featureCounts.forEachIndexed { cls, m -> m.forEach { (k, n) -> appendLine("F\t$cls\t$k\t$n") } }
    }

    companion object {
        private const val NOT = 0
        private const val PAYMENT = 1
        /** Examples of each kind before the classifier is used at all. */
        const val MIN_EXAMPLES = 5
        /** Hide a regex-accepted message only when this sure it's not a payment. */
        const val HIDE_CONFIDENCE = 0.97
        /** Rescue a regex-rejected message only when this sure it is one. */
        const val RESCUE_CONFIDENCE = 0.9

        fun decode(text: String): ParserModel {
            val m = ParserModel()
            text.lineSequence().forEach { line ->
                val p = line.split('\t')
                runCatching {
                    when (p[0]) {
                        "R" -> m.rules[p[1]] = FormatRule(p[2].toInt(), p[3].toBooleanStrict(), p[4].toIntOrNull(), p[5].toBooleanStrict(), p[6].toInt())
                        "N" -> m.notPayment[p[1]] = p[2].toInt()
                        "D" -> m.docs[p[1].toInt()] = p[2].toInt()
                        "F" -> m.featureCounts[p[1].toInt()][p[2]] = p[3].toInt()
                    }
                }
            }
            return m
        }

        private fun key(group: String, shape: MessageShape) = group + "|" + shape.signature

        // ------------------------------------------------------------------ shapes

        private val tokenRe = Regex(
            """([A-Za-z0-9._-]{2,64}@[A-Za-z][A-Za-z0-9.]{1,30})""" +   // 1 UPI ID
                """|((?:[xX*]{2,}|\*+)\d{2,6})""" +                     // 2 masked account / card
                """|(\d[\d,]*(?:\.\d+)?)""" +                           // 3 number
                """|([A-Za-z][A-Za-z'/&]*)""" +                         // 4 word
                """|(₹)""" +                                            // 5 rupee sign
                """|(\n)""",                                            // 6 title / body break
        )

        /**
         * Words that make up payment messages; anything else (names, merchants, bank names) is a `*`.
         * Currency words all become `₹`.
         */
        private val vocab: Set<String> = """
            paid pay pays paying payment payments you your yours to from received receive receiving sent send sending
            debited credited debit credit dr cr upi vpa id a/c ac acct account card ending ends with bank via using on at
            for by of the a an is has have been was were be will with ref refno reference txn transaction transactions no
            num number avl avbl available bal balance limit successful successfully success done completed complete
            money transfer transferred trf refund refunded reversed reversal deposited withdrawn withdrawal deducted
            spent purchase info dear customer amount amt towards and in if not call sms block report fraud autopay mandate
            due request requested collect approve failed pending declined offer offers reward rewards cashback coins
            wallet linked mobile net banking imps neft rtgs utr p2p p2a p2m merchant self your's
            google phonepe paytm bhim amazon cred gpay
            jan feb mar apr may jun jul aug sep sept oct nov dec
            today yesterday am pm hrs ist
        """.trim().split(Regex("\\s+")).toSet()

        private val currencyWords = setOf("rs", "inr")

        /** Reduces [text] (as from [PaymentParser.combined]) to its shape. */
        fun shape(text: String): MessageShape {
            val tokens = ArrayList<String>()
            val numbers = ArrayList<Long?>()
            val names = ArrayList<String>()
            var nameRun: StringBuilder? = null
            fun endName() { nameRun?.let { names.add(it.toString()); nameRun = null } }

            for (m in tokenRe.findAll(text)) {
                val g = m.groups
                when {
                    g[1] != null -> { endName(); tokens.add("@") }
                    g[2] != null -> { endName(); tokens.add("xx") }
                    g[3] != null -> { endName(); tokens.add("#"); numbers.add(PaymentParser.toPaise(g[3]!!.value)) }
                    g[4] != null -> {
                        val raw = g[4]!!.value
                        val w = raw.lowercase().trimEnd('/', '\'', '&')
                        when {
                            w in currencyWords -> { endName(); tokens.add("₹") }
                            w in vocab -> { endName(); tokens.add(w) }
                            else -> {
                                // Consecutive unknown words are one name slot: "RICHA KHATRI" → one `*`.
                                val run = nameRun
                                if (run == null) { tokens.add("*"); nameRun = StringBuilder(raw) } else run.append(' ').append(raw)
                            }
                        }
                    }
                    g[5] != null -> { endName(); tokens.add("₹") }
                    g[6] != null -> { endName(); tokens.add("/") }
                }
            }
            endName()
            return MessageShape(tokens, numbers, names)
        }
    }
}
