package com.expensetracker.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A category. Identity is the [id] only ("FOOD", or "c_1732451234" for one you created), so
 * renaming or recolouring never breaks stored entries. Label/emoji/colour come from
 * [CategoryRegistry], which holds the built-ins plus your edits and custom categories.
 *
 * Kept source-compatible with the old enum: `Category.FOOD`, `.name`, `.label`, `.emoji`,
 * `.color`, `Category.entries`, `Category.valueOf(...)` all still work.
 */
class Category private constructor(val id: String) : java.io.Serializable {
    val name: String get() = id
    val label: String get() = CategoryRegistry.info(id).label
    val emoji: String get() = CategoryRegistry.info(id).emoji
    /** ARGB. */
    val color: Long get() = CategoryRegistry.info(id).color
    val isBuiltIn: Boolean get() = id in CategoryRegistry.DEFAULTS

    override fun equals(other: Any?) = other is Category && other.id == id
    override fun hashCode() = id.hashCode()
    override fun toString() = id

    companion object {
        val FOOD = Category("FOOD")
        val GROCERIES = Category("GROCERIES")
        val TRANSPORT = Category("TRANSPORT")
        val RENT = Category("RENT")
        val BILLS = Category("BILLS")
        val SHOPPING = Category("SHOPPING")
        val PEOPLE = Category("PEOPLE")
        val HEALTH = Category("HEALTH")
        val ENTERTAINMENT = Category("ENTERTAINMENT")
        val SAVINGS = Category("SAVINGS")
        val OTHER = Category("OTHER")

        val builtIns: List<Category> = listOf(FOOD, GROCERIES, TRANSPORT, RENT, BILLS, SHOPPING, PEOPLE, HEALTH, ENTERTAINMENT, SAVINGS, OTHER)

        /** Categories to offer in pickers: visible built-ins, then your own, in your order. */
        val entries: List<Category> get() = CategoryRegistry.visible()

        /** Built-in or custom id; throws for an unknown id (like the old enum did). */
        fun valueOf(id: String): Category =
            if (CategoryRegistry.exists(id)) Category(id) else throw IllegalArgumentException("Unknown category $id")

        /** Unknown ids (e.g. a deleted custom category) fall back to Other. */
        fun fromId(id: String?): Category = if (id != null && CategoryRegistry.exists(id)) Category(id) else OTHER

        /** For creating a brand-new custom category id. */
        internal fun create(id: String) = Category(id)

    }
}

data class CategoryInfo(
    val id: String,
    val label: String,
    val emoji: String,
    val color: Long,
    /** Hidden categories stay valid for old entries but aren't offered in pickers. */
    val hidden: Boolean = false,
)

/**
 * Built-in defaults + your overrides and custom categories (SharedPreferences "categories").
 * Works without loading (defaults only) so plain JVM unit tests need no Android context.
 */
object CategoryRegistry {

    val DEFAULTS: Map<String, CategoryInfo> = listOf(
        CategoryInfo("FOOD", "Food", "🍔", 0xFFEF6C00),
        CategoryInfo("GROCERIES", "Groceries", "🛒", 0xFF43A047),
        CategoryInfo("TRANSPORT", "Transport", "🛺", 0xFF1E88E5),
        CategoryInfo("RENT", "Rent & Bills", "🏠", 0xFF6D4C41),
        CategoryInfo("BILLS", "Recharge & Subs", "📱", 0xFF8E24AA),
        CategoryInfo("SHOPPING", "Shopping", "🛍️", 0xFFD81B60),
        CategoryInfo("PEOPLE", "Family & Gifts", "🎁", 0xFFE53935),
        CategoryInfo("HEALTH", "Health & Fitness", "💪", 0xFF00897B),
        CategoryInfo("ENTERTAINMENT", "Fun & Outings", "🎉", 0xFFFDD835),
        CategoryInfo("SAVINGS", "Savings", "🏦", 0xFF546E7A),
        CategoryInfo("OTHER", "Other", "📦", 0xFF757575),
    ).associateBy { it.id }

    /** Overrides of built-ins and custom categories, in display order (customs after built-ins). */
    @Volatile private var edits: List<CategoryInfo> = emptyList()
    @Volatile private var loaded = false

    private val _version = MutableStateFlow(0)
    /** Bumps on every change; collect it in Compose to redraw labels/colours. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun info(id: String): CategoryInfo =
        edits.firstOrNull { it.id == id } ?: DEFAULTS[id] ?: CategoryInfo(id, "Other", "📦", 0xFF757575)

    fun exists(id: String) = id in DEFAULTS || edits.any { it.id == id }

    /** Every category incl. hidden: built-ins first, then custom ones in the order saved. */
    fun allInfo(): List<CategoryInfo> =
        DEFAULTS.keys.map { info(it) } + edits.filter { it.id !in DEFAULTS }

    fun visible(): List<Category> = allInfo().filter { !it.hidden }.map { Category.fromId(it.id) }

    fun ensureLoaded(ctx: Context) {
        if (!loaded) load(ctx)
    }

    fun load(ctx: Context) {
        val text = ctx.getSharedPreferences("categories", Context.MODE_PRIVATE).getString("data", "") ?: ""
        edits = decode(text)
        loaded = true
        _version.value++
    }

    /** Saves the full list (as returned by [allInfo], edited). Built-ins identical to defaults aren't stored. */
    fun save(ctx: Context, infos: List<CategoryInfo>) {
        edits = infos.filter { it != DEFAULTS[it.id] }
        loaded = true
        ctx.getSharedPreferences("categories", Context.MODE_PRIVATE).edit().putString("data", encode(edits)).apply()
        _version.value++
    }

    /** For tests and backup restore without a Context. */
    fun setForTesting(infos: List<CategoryInfo>) {
        edits = infos.filter { it != DEFAULTS[it.id] }
        _version.value++
    }

    fun newCustomId(): String = "c_" + System.currentTimeMillis().toString(36)

    /** One category per line: id \t label \t emoji \t colorHex \t hidden(0/1). Tabs/newlines in labels are stripped. */
    fun encode(list: List<CategoryInfo>): String = list.joinToString("\n") {
        listOf(it.id, clean(it.label), clean(it.emoji), java.lang.Long.toHexString(it.color), if (it.hidden) "1" else "0").joinToString("\t")
    }

    fun decode(text: String): List<CategoryInfo> = text.lines().mapNotNull { line ->
        val p = line.split('\t')
        if (p.size < 5 || p[0].isBlank()) return@mapNotNull null
        CategoryInfo(p[0], p[1], p[2], p[3].toLongOrNull(16) ?: 0xFF757575, p[4] == "1")
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')
}
