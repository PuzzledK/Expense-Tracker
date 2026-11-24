package com.expensetracker.app.data

import java.time.LocalDate
import java.time.LocalTime

/** Fixed category set. `color` is ARGB. */
enum class Category(val label: String, val emoji: String, val color: Long) {
    FOOD("Food", "🍔", 0xFFEF6C00),
    GROCERIES("Groceries", "🛒", 0xFF43A047),
    TRANSPORT("Transport", "🛺", 0xFF1E88E5),
    RENT("Rent & Bills", "🏠", 0xFF6D4C41),
    BILLS("Recharge & Subs", "📱", 0xFF8E24AA),
    SHOPPING("Shopping", "🛍️", 0xFFD81B60),
    PEOPLE("Family & Gifts", "🎁", 0xFFE53935),
    HEALTH("Health & Fitness", "💪", 0xFF00897B),
    ENTERTAINMENT("Fun & Outings", "🎉", 0xFFFDD835),
    SAVINGS("Savings", "🏦", 0xFF546E7A),
    OTHER("Other", "📦", 0xFF757575);
}

/** Money is always stored as paise (1 rupee = 100 paise) to avoid float errors. */
data class Expense(
    val id: Long = 0,
    val amountPaise: Long,
    val note: String,
    val category: Category,
    val date: LocalDate,
    /** true for money received, e.g. "+5000 - Mammi" in the notes. */
    val isIncome: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    /** Set when you pick "counts as" yourself; null = let [SpendClassifier] decide. */
    val spendOverride: SpendType? = null,
    /** City where it was spent ("Gurugram"); null = not tagged. */
    val place: String? = null,
    /** Raw coordinates when captured automatically (kept so the city can be resolved later offline). */
    val lat: Double? = null,
    val lon: Double? = null,
    /** Clock time it was spent, recorded by quick entries logged for today; null = unknown. */
    val time: LocalTime? = null,
)

/** How an expense should count towards averages. */
enum class SpendType(val label: String, val emoji: String) {
    EVERYDAY("Everyday", "☕"),
    ONE_OFF("One-off", "✈️"),
    FIXED("Fixed / recurring", "🔁"),
    SAVINGS("Savings", "🏦"),
}

/** Classifier verdict for one expense. [auto] is false when it came from your override. */
data class SpendInfo(val type: SpendType, val reason: String, val auto: Boolean = true)

/** One line parsed from the notes-app text. */
data class ParsedEntry(
    val date: LocalDate,
    val amountPaise: Long,
    val note: String,
    val isIncome: Boolean,
    val category: Category,
    /** 1-based line number in the pasted text. */
    val lineNumber: Int,
)

data class ParseResult(
    val entries: List<ParsedEntry>,
    /** Non-blank lines that were neither a date header nor an expense: (lineNumber, text). */
    val skippedLines: List<Pair<Int, String>>,
    /** Expense lines found before any date/month header (dated to the chosen month, or skipped). */
    val undatedEntries: Int = 0,
    /** Best guess for those: the month before the first header. */
    val suggestedUndatedMonth: java.time.YearMonth? = null,
)

/** Result of parsing a single quick-add line like "120 - Lunch" or "+5000 Mammi". */
data class QuickEntry(val amountPaise: Long, val note: String, val isIncome: Boolean)

data class Settings(
    val monthlyBudgetPaise: Long = 0,           // 0 = no budget
    val excludeSavingsFromSpend: Boolean = true,
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 22,
    val reminderMinute: Int = 0,
    /** Always-on notification for logging today's expenses. */
    val quickLogNotification: Boolean = false,
    /** Auto-tag new expenses with the current city (coarse location). */
    val locationTagging: Boolean = false,
    /** Your base city; "" = detect it as the city you spend in most. */
    val homeCity: String = "",
    /** Require the phone's unlock (fingerprint/face/PIN) to open the app. */
    val appLock: Boolean = false,
    /** Grace period after leaving the app before it locks again. 0 = immediately. */
    val lockTimeoutSec: Int = 30,
)

/** A frequently repeated entry offered as a one-tap chip, e.g. "Lunch + Diet coke ₹142". */
data class QuickTemplate(val note: String, val amountPaise: Long, val category: Category, val count: Int)
