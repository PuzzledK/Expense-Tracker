package com.expensetracker.app.capture

/**
 * Pure-Kotlin parser for UPI / bank payment notifications (no android.* — unit-tested on the JVM).
 *
 * Given a notification's title + text it returns the amount, payee and direction, or null when
 * the text isn't a completed payment (OTPs, offers, balance alerts, failed/pending payments,
 * collect requests, bill reminders…).
 */

/** How trustworthy a parsed payee name is; used to pick the best one when merging duplicates. */
enum class PayeeQuality { UNKNOWN, VPA, NAME }

data class ParsedPayment(
    val amountPaise: Long,
    /** Display name, e.g. "Zomato", "Anu Sharma"; "Unknown" when none was found. */
    val payee: String,
    /** true = money in (received / credited / refund). */
    val isCredit: Boolean,
    val payeeQuality: PayeeQuality,
    /** Masked account/card if mentioned ("XX1234"), for the review screen. */
    val account: String? = null,
)

/** Where a notification came from; [group] is what the per-app toggle controls. */
data class CaptureSource(val group: SourceGroup, val label: String)

enum class SourceGroup(val label: String) {
    GPAY("Google Pay"),
    PHONEPE("PhonePe"),
    PAYTM("Paytm"),
    BHIM("BHIM"),
    AMAZON("Amazon Pay"),
    CRED("CRED"),
    BANK_APPS("Bank apps"),
    SMS("SMS (bank messages)"),
}

object CaptureSources {
    private val byPackage: Map<String, CaptureSource> = buildMap {
        put("com.google.android.apps.nbu.paisa.user", CaptureSource(SourceGroup.GPAY, "Google Pay"))
        put("com.phonepe.app", CaptureSource(SourceGroup.PHONEPE, "PhonePe"))
        put("net.one97.paytm", CaptureSource(SourceGroup.PAYTM, "Paytm"))
        put("in.org.npci.upiapp", CaptureSource(SourceGroup.BHIM, "BHIM"))
        put("in.amazon.mShop.android.shopping", CaptureSource(SourceGroup.AMAZON, "Amazon Pay"))
        put("com.dreamplug.androidapp", CaptureSource(SourceGroup.CRED, "CRED"))
        // Bank apps
        put("com.sbi.lotusintouch", CaptureSource(SourceGroup.BANK_APPS, "SBI YONO"))
        put("com.sbi.upi", CaptureSource(SourceGroup.BANK_APPS, "BHIM SBI Pay"))
        put("com.snapwork.hdfc", CaptureSource(SourceGroup.BANK_APPS, "HDFC Bank"))
        put("com.hdfcbank.payzapp", CaptureSource(SourceGroup.BANK_APPS, "PayZapp"))
        put("com.csam.icici.bank.imobile", CaptureSource(SourceGroup.BANK_APPS, "ICICI iMobile"))
        put("com.axis.mobile", CaptureSource(SourceGroup.BANK_APPS, "Axis Mobile"))
        put("com.msf.kbank.mobile", CaptureSource(SourceGroup.BANK_APPS, "Kotak"))
        put("com.kotak811mobilebankingapp.instantsavingsupiscanandpayrecharge", CaptureSource(SourceGroup.BANK_APPS, "Kotak 811"))
        put("com.bankofbaroda.mconnect", CaptureSource(SourceGroup.BANK_APPS, "Bank of Baroda"))
        put("com.infrasofttech.indianbank", CaptureSource(SourceGroup.BANK_APPS, "Indian Bank"))
        put("com.fss.pnbpsp", CaptureSource(SourceGroup.BANK_APPS, "PNB"))
        put("com.idfcfirstbank.optimus", CaptureSource(SourceGroup.BANK_APPS, "IDFC FIRST"))
        put("com.mobikwik_new", CaptureSource(SourceGroup.BANK_APPS, "MobiKwik"))
        put("money.super.payments", CaptureSource(SourceGroup.BANK_APPS, "super.money"))
        put("com.naviapp", CaptureSource(SourceGroup.BANK_APPS, "Navi"))
        // Well-known SMS apps (the phone's default SMS app is added at runtime).
        put("com.google.android.apps.messaging", CaptureSource(SourceGroup.SMS, "Messages"))
        put("com.samsung.android.messaging", CaptureSource(SourceGroup.SMS, "Messages"))
        put("com.android.mms", CaptureSource(SourceGroup.SMS, "Messages"))
        put("com.android.messaging", CaptureSource(SourceGroup.SMS, "Messages"))
        put("com.oneplus.mms", CaptureSource(SourceGroup.SMS, "Messages"))
        put("com.miui.smsextra", CaptureSource(SourceGroup.SMS, "Messages"))
        put("com.truecaller", CaptureSource(SourceGroup.SMS, "Truecaller"))
    }

    /** Source for [pkg], or null if we never read its notifications. [defaultSmsPackage] counts as SMS. */
    fun of(pkg: String, defaultSmsPackage: String? = null): CaptureSource? =
        byPackage[pkg] ?: if (pkg == defaultSmsPackage) CaptureSource(SourceGroup.SMS, "Messages") else null
}

object PaymentParser {

    private const val CUR = """(?:₹|\brs\.?|\binr)"""
    private const val NUM = """([0-9][0-9,]*(?:\.[0-9]{1,2})?)"""

    /** "₹1,200.50", "Rs.142", "INR 500", "Rs 1,23,456.00", also "142.00 INR". */
    private val amountRe = Regex("""$CUR\s*$NUM|(?<![\w*/.:-])$NUM[ \t]*(?:/-[ \t]*)?(?:₹|\brs\b\.?|\binr\b)""", RegexOption.IGNORE_CASE)
    /** SBI-style "debited by 142.0" with no currency. */
    private val bareAmountRe = Regex("""\b(?:debited|credited)\s+(?:by|with|for)\s+$NUM""", RegexOption.IGNORE_CASE)

    private val accountRe = Regex("""\b(?:a/c|acct|account|card)\s*(?:no\.?\s*)?(?:ending\s*(?:with|in)?\s*)?[:\-]?\s*([xX*]+\s?[0-9]{3,6}|[0-9]{4}\b)""", RegexOption.IGNORE_CASE)

    // ---- Rejection rules ----------------------------------------------------------------
    private val otpRe = Regex("""\botp\b|one[- ]time password|verification code|security code|\bcode is\b""", RegexOption.IGNORE_CASE)
    private val failedRe = Regex("""\bfail(?:ed|ure)?\b|\bdeclined\b|\bunsuccessful\b|\bpending\b|\bnot (?:be )?(?:completed|processed|successful)\b|could not be|couldn't be|\bin progress\b|\bprocessing\b|\btimed out\b|\bcancell?ed\b|\binitiated\b|\bwill be (?:credited|refunded|reversed|processed)\b""",RegexOption.IGNORE_CASE)
    private val requestRe = Regex("""\brequest(?:ed|ing|s)?\b|\bcollect\b|\bapprove\b|\bpay now\b|\bpay your\b""", RegexOption.IGNORE_CASE)
    private val reminderRe = Regex("""\breminder\b|\bis due\b|\bdue (?:on|by|date|today|tomorrow)\b|\bdue in\b|\boverdue\b|\bwill be (?:debited|deducted|auto[- ]?debited)\b|\bscheduled\b|\bupcoming\b|\bmandate\b|\bautopay set\b""", RegexOption.IGNORE_CASE)
    private val promoRe = Regex("""\boffers?\b|\bcoupon\b|%\s*off\b|\boff on\b|pre[- ]?approved|\bloan\b|\bup ?to\b|\bhurry\b|\blimited time\b|\bclaim\b|\bwin\b|\bwon\b|scratch ?card|\bvoucher\b|\bunlock\b|\binstant credit\b|\bcredit limit\b|\bapply now\b|\bshop now\b|\bexpir(?:e|es|ing)\b|\bdiscount\b""", RegexOption.IGNORE_CASE)
    private val cashbackRe = Regex("""\bcash ?back\b|\brewards?\b|\bsupercoins?\b|\bcoins\b|\bpoints\b""", RegexOption.IGNORE_CASE)
    private val balanceWordRe = Regex("""\b(?:avl|avbl|avail(?:able)?|available|clear|closing|total)?\s*bal(?:ance)?\b|\blimit\b""", RegexOption.IGNORE_CASE)
    /** A wallet top-up or self transfer isn't spending. */
    private val selfRe = Regex("""\badded to (?:your )?(?:paytm |amazon pay |phonepe )?(?:wallet|balance)\b|\bself transfer\b|\bto self\b""", RegexOption.IGNORE_CASE)

    // ---- Direction ----------------------------------------------------------------------
    private val strongDebitRe = Regex("""\bdebited\b|\bdeducted\b|\bwithdrawn\b|\bwithdrawal\b""", RegexOption.IGNORE_CASE)
    private val creditRe = Regex("""\breceived\b|\bcredited\b|\brefund(?:ed)?\b|\breversed\b|\breversal\b|\bdeposited\b|\b(?:paid|sent) you\b|\bgot ₹|\bgot rs""", RegexOption.IGNORE_CASE)
    private val debitRe = Regex("""\bpaid\b|\bsent\b|\bspent\b|\bpayment (?:of|to|for|successful|done|complete)|\bpurchase\b|\btransferred\b|\btrf to\b|\btransfer(?:red)? to\b|\btxn (?:of|at|for)\b|\btransaction (?:of|at)\b|\bused (?:for|at)\b|\bpaying\b|\bpay(?:ment)? success""", RegexOption.IGNORE_CASE)

    /**
     * Bank shorthand (Bank of Baroda, several PSU banks): "Rs.1.00 Dr. from A/C XX6932 and Cr. to x@upi"
     * = money left your account; "Cr. to A/C XX6932 … Dr. from x@upi" = money came in.
     * The side your A/C is on decides; a lone Dr./Cr. is the fallback.
     */
    private val drYourAccountRe = Regex("""\bdr\b\.?\s*(?:from|to|in)?\s*(?:your\s+)?(?:a/c|acct|account)\b""", RegexOption.IGNORE_CASE)
    private val crYourAccountRe = Regex("""\bcr\b\.?\s*(?:to|in|into)?\s*(?:your\s+)?(?:a/c|acct|account)\b""", RegexOption.IGNORE_CASE)
    /** Lone "Dr." / "Cr." followed by from/to — excludes "Dr. Mehta" (a doctor) and balances like "20459.64CR". */
    private val drAloneRe = Regex("""\bdr\b\.?\s+(?:from|to)\b""", RegexOption.IGNORE_CASE)
    private val crAloneRe = Regex("""\bcr\b\.?\s+(?:from|to)\b""", RegexOption.IGNORE_CASE)

    /** Bank SMS must look like a bank message, not a friend saying "sent ₹500 to Anu". */
    private val bankContextRe = Regex("""\ba/c\b|\bacct\b|\baccount\b|\bupi\b|\bvpa\b|\bcard\b|\bbank\b|\bimps\b|\bneft\b|\brtgs\b|\bref(?:\.|erence)?\s*(?:no|num|id)?\b|\btxn\b|\butr\b|\bwallet\b""", RegexOption.IGNORE_CASE)
    private val smsSenderRe = Regex("""^[A-Z]{2}-[A-Z0-9]{3,9}(?:-[A-Z])?$""")

    // ---- Payee --------------------------------------------------------------------------
    private const val STOP = """(?=\s+(?:on|via|using|from|for|at|ref|refno|ref\.|upi|txn|through|with|is|has|was|in|and|by|to)\b|\s*[.,!;:()\n|]|\s+-\s|\s+\d{1,2}[-/]\d|\s*$)"""
    private const val NAME = """([A-Za-z0-9&'@._ -]{2,60}?)"""

    private val vpaRe = Regex("""\b([A-Za-z0-9._-]{2,64}@[A-Za-z][A-Za-z0-9.]{1,30})\b""")
    private val toVpaRe = Regex("""\b(?:cr\.?\s+to|to|trf to|towards)\s+(?:(?:vpa|upi(?:\s*id)?)\s*[:\-]?\s*)?([A-Za-z0-9._-]{2,64}@[A-Za-z][A-Za-z0-9.]{1,30})""", RegexOption.IGNORE_CASE)
    private val fromVpaRe = Regex("""\b(?:from|by)\s+(?:(?:vpa|upi(?:\s*id)?)\s*[:\-]?\s*)?([A-Za-z0-9._-]{2,64}@[A-Za-z][A-Za-z0-9.]{1,30})""", RegexOption.IGNORE_CASE)
    /** "...to VPA zomato@icici ZOMATO LTD on" — a merchant name sometimes follows the VPA. */
    private val nameAfterVpaRe = Regex("""@[A-Za-z][A-Za-z0-9.]{1,30}\s+\(?([A-Z][A-Za-z&' .]{2,40}?)\)?$STOP""")
    private val semicolonNameRe = Regex(""";\s*([A-Za-z][A-Za-z&' .]{1,40}?)\s+credited\b""", RegexOption.IGNORE_CASE)
    private val upiInfoRe = Regex("""\bUPI/(?:P2[AM]|CR|DR)?/?\d{6,}/([A-Za-z][A-Za-z0-9 &.]{1,40}?)(?:/|\s|$)""", RegexOption.IGNORE_CASE)
    private val toNameRe = Regex("""\b(?:paid|sent|transferred|trf|payment(?: of)?|spent|debited)\b[^\n]{0,40}?\bto\s+(?:m/s\.?\s+|mr\.?\s+|ms\.?\s+|mrs\.?\s+)?$NAME$STOP""", RegexOption.IGNORE_CASE)
    private val bareToRe = Regex("""\bto\s+(?:m/s\.?\s+|mr\.?\s+|ms\.?\s+|mrs\.?\s+)?$NAME$STOP""", RegexOption.IGNORE_CASE)
    private val atRe = Regex("""\bat\s+(?:m/s\.?\s+)?([A-Z][A-Za-z0-9&'*._ -]{1,40}?)$STOP""")
    private val onRe = Regex("""\b(?:on|towards|for)\s+(?:m/s\.?\s+)?([A-Z][A-Za-z0-9&'*._ -]{1,40}?)$STOP""")
    private val fromNameRe = Regex("""\b(?:from|by)\s+(?:mr\.?\s+|ms\.?\s+|mrs\.?\s+)?$NAME$STOP""", RegexOption.IGNORE_CASE)
    private val sentYouRe = Regex("""^\s*([A-Za-z][A-Za-z .'&]{1,40}?)\s+(?:has\s+)?(?:paid|sent)\s+you\b""", RegexOption.IGNORE_CASE)

    private val notANameRe = Regex("""^(?:your|you|a/c|ac|acct|account|bank|card|the|beneficiary|vpa|upi|wallet|xx+\d*|\*+\d*|\d[\d ,./-]*|self|me|merchant|payee|mobile|number|linked|sb|savings|rs|inr|on|at|via|for|from|to|ref|not|dear|info|avl|bal)\b|^₹""", RegexOption.IGNORE_CASE)
    private val maskedRe = Regex("""\b(?:a/c|acct|account)\b|\bxx\d|\*\d|\bcard (?:no|ending|xx)""", RegexOption.IGNORE_CASE)
    private val genericTitleRe = Regex("""payment|paid|sent|received|success|transaction|debited|credited|alert|money|upi|google pay|phonepe|paytm|bhim|cred|amazon|bank|₹|\brs\b|^\s*$|[A-Z]{2}-[A-Z0-9]{3,9}""", RegexOption.IGNORE_CASE)

    /** Title + text as one string, the way every rule sees it. */
    internal fun combined(title: String?, text: String?): String {
        val t = title.orEmpty().trim()
        val body = text.orEmpty().trim()
        // A title that is just a phone number / short code (SMS sender) carries no payment info.
        val titleUseful = t.isNotEmpty() && !Regex("""^\+?[\d\s-]{3,15}$""").matches(t)
        return (if (titleUseful && !body.contains(t, ignoreCase = true)) "$t\n$body" else body)
            .replace(' ', ' ')
            .replace(Regex("[ \t]+"), " ")
    }

    /**
     * Money didn't move (OTP, failed/pending payment, collect request). Never a payment, whatever
     * the learning model thinks.
     */
    fun isHardReject(title: String?, text: String?): Boolean {
        val all = combined(title, text)
        return otpRe.containsMatchIn(all) || failedRe.containsMatchIn(all) || requestRe.containsMatchIn(all)
    }

    /** Bank SMS look: account / UPI / reference words, or a bank sender ID like "AX-HDFCBK". */
    fun looksLikeBank(title: String?, text: String?): Boolean =
        bankContextRe.containsMatchIn(combined(title, text)) || smsSenderRe.matches(title.orEmpty().trim())

    /** Money in (true), out (false), or no direction words (null). */
    private fun direction(all: String): Boolean? = when {
        drYourAccountRe.containsMatchIn(all) -> false
        crYourAccountRe.containsMatchIn(all) -> true
        strongDebitRe.containsMatchIn(all) -> false
        creditRe.containsMatchIn(all) -> true
        debitRe.containsMatchIn(all) -> false
        drAloneRe.containsMatchIn(all) -> false
        crAloneRe.containsMatchIn(all) -> true
        else -> null
    }

    /**
     * @param requireBankContext true for SMS apps: only bank-looking messages count.
     */
    fun parse(title: String?, text: String?, requireBankContext: Boolean = false): ParsedPayment? {
        val t = title.orEmpty().trim()
        val body = text.orEmpty().trim()
        if (body.isEmpty() && t.isEmpty()) return null
        val all = combined(title, text)

        if (otpRe.containsMatchIn(all)) return null
        if (failedRe.containsMatchIn(all)) return null
        if (reminderRe.containsMatchIn(all)) return null
        if (promoRe.containsMatchIn(all)) return null
        if (selfRe.containsMatchIn(all)) return null
        // Collect requests ("Richa requested ₹500"). "Received payment request" etc.
        if (requestRe.containsMatchIn(all)) return null
        if (requireBankContext && !bankContextRe.containsMatchIn(all) && !smsSenderRe.matches(t)) return null

        val isCredit = direction(all) ?: return null
        // Cashback / rewards notifications aren't payments you made or money you'd log.
        if (cashbackRe.containsMatchIn(all) && (isCredit || !debitRe.containsMatchIn(all))) return null

        val amount = findAmount(all) ?: return null
        if (amount <= 0) return null

        val (payee, quality) = findPayee(all, t, body, isCredit)
        val account = accountRe.find(all)?.groupValues?.get(1)?.replace(" ", "")?.uppercase()?.replace('*', 'X')
        return ParsedPayment(amount, payee, isCredit, quality, account)
    }

    /**
     * Best-effort reading with the cautious rules switched off (offers, reminders, bank-context,
     * missing direction words…), for messages the learning model says are payments anyway.
     * Hard rejects ([isHardReject]) still return null. No direction words → money out.
     */
    fun parseLoose(title: String?, text: String?): ParsedPayment? {
        val t = title.orEmpty().trim()
        val body = text.orEmpty().trim()
        if (body.isEmpty() && t.isEmpty()) return null
        if (isHardReject(title, text)) return null
        val all = combined(title, text)
        val isCredit = direction(all) ?: false
        val amount = findAmount(all)?.takeIf { it > 0 } ?: return null
        val (payee, quality) = findPayee(all, t, body, isCredit)
        val account = accountRe.find(all)?.groupValues?.get(1)?.replace(" ", "")?.uppercase()?.replace('*', 'X')
        return ParsedPayment(amount, payee, isCredit, quality, account)
    }

    /** First amount that isn't a balance / limit figure. */
    internal fun findAmount(s: String): Long? {
        for (m in amountRe.findAll(s)) {
            val before = s.substring(maxOf(0, m.range.first - 24), m.range.first)
            if (balanceWordRe.containsMatchIn(before.substringAfterLast('.', before).takeLast(24))) continue
            val num = m.groups[1]?.value ?: m.groups[2]?.value ?: continue
            toPaise(num)?.let { return it }
        }
        bareAmountRe.find(s)?.let { m -> toPaise(m.groupValues[1])?.let { return it } }
        return null
    }

    internal fun toPaise(num: String): Long? {
        val clean = num.replace(",", "").trimEnd('.')
        if (clean.isEmpty()) return null
        val parts = clean.split('.')
        val rupees = parts[0].toLongOrNull() ?: return null
        val frac = parts.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLongOrNull() ?: 0
        if (rupees > 100_000_000) return null
        return rupees * 100 + frac
    }

    private fun findPayee(all: String, title: String, body: String, isCredit: Boolean): Pair<String, PayeeQuality> {
        fun ok(raw: String?): String? {
            val c = cleanName(raw ?: return null)
            return c.takeIf {
                it.length >= 2 && '@' !in it && !notANameRe.containsMatchIn(it) && !maskedRe.containsMatchIn(it) &&
                    it.count(Char::isLetter) > it.count(Char::isDigit)
            }
        }

        // "Richa sent you ₹500" / GPay "Richa · Paid you ₹500" (name in title).
        if (isCredit) {
            // GPay puts the whole line in the title ("RICHA KHATRI paid you ₹1.00" / "Sent using Paytm UPI").
            for (line in listOf(body, title) + all.lines()) {
                sentYouRe.find(line)?.let { m -> ok(m.groupValues[1])?.let { return it to PayeeQuality.NAME } }
            }
            if (Regex("""\b(?:paid|sent) you\b""", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
                ok(title)?.takeIf { !genericTitleRe.containsMatchIn(title) }?.let { return it to PayeeQuality.NAME }
            }
        }

        // Merchant name printed after the VPA, or "UPI/P2M/123456/ZOMATO" info strings.
        nameAfterVpaRe.find(all)?.let { m -> ok(m.groupValues[1])?.let { return it to PayeeQuality.NAME } }
        semicolonNameRe.find(all)?.let { m -> ok(m.groupValues[1])?.let { return it to PayeeQuality.NAME } }
        upiInfoRe.find(all)?.let { m -> ok(m.groupValues[1])?.let { return it to PayeeQuality.NAME } }

        if (!isCredit) {
            toVpaRe.find(all)?.let { m -> return vpaLabel(m.groupValues[1]) to PayeeQuality.VPA }
            toNameRe.find(all)?.let { m ->
                val raw = m.groupValues[1]
                if (vpaRe.matches(raw.trim())) vpaName(raw.trim())?.let { return it to PayeeQuality.VPA }
                ok(raw)?.let { return it to PayeeQuality.NAME }
            }
            bareToRe.findAll(all).forEach { m ->
                val raw = m.groupValues[1]
                if (vpaRe.matches(raw.trim())) vpaName(raw.trim())?.let { return it to PayeeQuality.VPA }
                ok(raw)?.let { return it to PayeeQuality.NAME }
            }
            for (re in listOf(atRe, onRe)) {
                re.findAll(all).forEach { m -> ok(m.groupValues[1])?.takeIf { !looksLikeDate(it) }?.let { return it to PayeeQuality.NAME } }
            }
        } else {
            fromVpaRe.find(all)?.let { m -> return vpaLabel(m.groupValues[1]) to PayeeQuality.VPA }
            fromNameRe.findAll(all).forEach { m ->
                val raw = m.groupValues[1]
                if (vpaRe.matches(raw.trim())) vpaName(raw.trim())?.let { return it to PayeeQuality.VPA }
                ok(raw)?.let { return it to PayeeQuality.NAME }
            }
        }
        vpaRe.find(all)?.let { m -> return vpaLabel(m.groupValues[1]) to PayeeQuality.VPA }
        // App notifications whose title is the person/merchant ("Rapido" / "₹66 paid").
        if (title.isNotEmpty() && !genericTitleRe.containsMatchIn(title)) ok(title)?.let { return it to PayeeQuality.NAME }
        return "Unknown" to PayeeQuality.UNKNOWN
    }

    private fun looksLikeDate(s: String) = Regex("""^\d|^(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\b""", RegexOption.IGNORE_CASE).containsMatchIn(s)

    /** "zomato@icici" → "Zomato"; "anu.sharma92@okaxis" → "Anu Sharma"; "paytmqr2810050501@paytm" → null. */
    /** Readable name from a UPI ID, else the ID itself (sentence-ending dots trimmed). */
    internal fun vpaLabel(raw: String): String {
        val vpa = raw.trimEnd('.')
        return vpaName(vpa) ?: vpa.lowercase()
    }

    internal fun vpaName(vpa: String): String? {
        val local = vpa.substringBefore('@').lowercase()
        val prefix = Regex("""^(?:paytmqr|paytm-|bharatpe|q\d|upi|mab|gpay-|bpe)""")
        if (prefix.containsMatchIn(local) && local.count(Char::isDigit) >= 4) return null
        val words = local.replace(Regex("""\d+"""), " ")
            .split('.', '_', '-', ' ')
            .filter { it.length >= 2 }
        if (words.isEmpty()) return null
        if (local.count(Char::isDigit) >= 8 && words.sumOf { it.length } < 4) return null
        return words.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }

    internal fun cleanName(raw: String): String {
        var s = raw.trim().trim('.', ',', '-', ':', '*', '\'', '"').trim()
        s = s.replace(Regex("""\s+(?:successfully|successful|is successful|done)$""", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("""\s+"""), " ")
        if (s.length > 40) s = s.take(40).trimEnd()
        // "ZOMATO LTD" → "Zomato Ltd"; keep mixed case as typed.
        if (s.any(Char::isLetter) && s.none(Char::isLowerCase)) {
            s = s.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        }
        return s
    }
}
