package com.expensetracker.app

import com.expensetracker.app.data.AutoRule
import com.expensetracker.app.data.AutoRules
import com.expensetracker.app.data.BackupIo
import com.expensetracker.app.data.Category
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.RuleKind
import com.expensetracker.app.data.Settings
import com.expensetracker.app.data.SpendType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDate

class AutoRulesTest {

    private fun e(rs: Long, note: String, cat: Category = Category.OTHER, income: Boolean = false, id: Long = 0) =
        Expense(id = id, amountPaise = rs * 100, note = note, category = cat, date = LocalDate.of(2026, 11, 20), isIncome = income)

    private val swiggy = AutoRule(1, listOf("swiggy", "zomato"), categoryId = "FOOD", rename = "Food delivery")
    private val bigRapido = AutoRule(2, listOf("rapido"), minPaise = 500_00, countsAs = SpendType.ONE_OFF, categoryId = "TRANSPORT")
    private val rapido = AutoRule(3, listOf("rapido"), categoryId = "TRANSPORT", countsAs = SpendType.EVERYDAY)
    private val salary = AutoRule(4, listOf("acme"), kind = RuleKind.INCOME, categoryId = "PEOPLE")

    @Test fun matchesAnyKeywordIgnoringCase() {
        assertEquals(1L, AutoRules.firstMatch(listOf(swiggy), "SWIGGY*ORD123", 250_00, false)?.id)
        assertEquals(1L, AutoRules.firstMatch(listOf(swiggy), "zomato dinner", 250_00, false)?.id)
        assertNull(AutoRules.firstMatch(listOf(swiggy), "Blinkit", 250_00, false))
    }

    @Test fun firstMatchingRuleWins() {
        val rules = listOf(bigRapido, rapido)
        assertEquals(2L, AutoRules.firstMatch(rules, "Rapido airport", 800_00, false)?.id)
        assertEquals(3L, AutoRules.firstMatch(rules, "Rapido", 90_00, false)?.id)
        assertEquals(2L, AutoRules.firstMatch(listOf(rapido, bigRapido).reversed(), "rapido", 800_00, false)?.id)
        assertEquals(3L, AutoRules.firstMatch(listOf(rapido, bigRapido), "rapido", 800_00, false)?.id)
    }

    @Test fun amountRangeIsInclusive() {
        val r = AutoRule(9, listOf("x"), minPaise = 100_00, maxPaise = 200_00, categoryId = "FOOD")
        assertNull(AutoRules.firstMatch(listOf(r), "x", 99_99, false))
        assertEquals(9L, AutoRules.firstMatch(listOf(r), "x", 100_00, false)?.id)
        assertEquals(9L, AutoRules.firstMatch(listOf(r), "x", 200_00, false)?.id)
        assertNull(AutoRules.firstMatch(listOf(r), "x", 200_01, false))
    }

    @Test fun kindFilter() {
        assertNull(AutoRules.firstMatch(listOf(salary), "ACME payroll", 50000_00, false))
        assertEquals(4L, AutoRules.firstMatch(listOf(salary), "ACME payroll", 50000_00, true)?.id)
    }

    @Test fun ruleWithoutActionNeverMatches() {
        assertNull(AutoRules.firstMatch(listOf(AutoRule(5, listOf("swiggy"))), "swiggy", 1, false))
    }

    @Test fun applySetsCategoryRenameAndCountsAs() {
        val out = AutoRules.apply(listOf(rapido, swiggy), e(250, "swiggy*ord"))
        assertEquals(Category.FOOD, out.category)
        assertEquals("Food delivery", out.note)
        val ride = AutoRules.apply(listOf(rapido), e(90, "rapido"))
        assertEquals(Category.TRANSPORT, ride.category)
        assertEquals(SpendType.EVERYDAY, ride.spendOverride)
    }

    @Test fun applyKeepsWhatYouTypedAndYourOwnCountsAs() {
        val mine = e(90, "rapido", Category.SHOPPING).copy(spendOverride = SpendType.ONE_OFF)
        val out = AutoRules.apply(listOf(rapido), mine, setCategory = false, renameNote = false)
        assertEquals(Category.SHOPPING, out.category)
        assertEquals(SpendType.ONE_OFF, out.spendOverride)
        assertEquals("rapido", out.note)
        // Income never gets a "counts as"; transfers keep their category.
        assertNull(AutoRules.apply(listOf(rapido), e(90, "rapido", income = true)).spendOverride)
        assertEquals(Category.OTHER, AutoRules.apply(listOf(rapido), e(90, "rapido").copy(isTransfer = true)).category)
    }

    @Test fun noMatchReturnsSameEntry() {
        val x = e(10, "chai")
        assertSame(x, AutoRules.apply(listOf(swiggy), x))
    }

    @Test fun pastChangesCountOnlyRealChanges() {
        val all = listOf(
            e(250, "Swiggy lunch", id = 1),                                       // changes
            e(250, "Food delivery", Category.FOOD, id = 2),                       // no keyword
            e(90, "rapido", Category.TRANSPORT, id = 3).copy(spendOverride = SpendType.EVERYDAY), // already right
            e(90, "rapido", id = 4),                                              // changes
            e(90, "rapido", id = 5).copy(isTransfer = true),                     // transfers left alone
        )
        val ch = AutoRules.changesForPast(listOf(swiggy, rapido), all)
        assertEquals(listOf(1L, 4L), ch.map { it.first.id })
        assertEquals("Food delivery", ch[0].second.note)
        assertEquals(SpendType.EVERYDAY, ch[1].second.spendOverride)
    }

    @Test fun encodeDecodeRoundTrip() {
        val odd = AutoRule(7, listOf("a|b", "tab\there", "back\\slash p"), minPaise = 1, maxPaise = null, kind = RuleKind.EXPENSE,
            categoryId = null, countsAs = SpendType.SAVINGS, rename = "line\nbreak")
        val rules = listOf(swiggy, bigRapido, salary, odd)
        assertEquals(rules, AutoRules.decode(AutoRules.encode(rules)))
        assertEquals(emptyList<AutoRule>(), AutoRules.decode(""))
        assertEquals(emptyList<AutoRule>(), AutoRules.decode("garbage\nmore"))
    }

    @Test fun backupCarriesRulesAndOldBackupsKeepThem() {
        val s = Settings(autoRules = listOf(swiggy, rapido))
        val json = BackupIo.toJson(emptyList(), s)
        assertEquals(s.autoRules, BackupIo.parse(json).settingsPatch!!(Settings()).autoRules)
        val old = json.replace(Regex(""", "autoRules": "[^"]*""""), "")
        assertEquals(listOf(salary), BackupIo.parse(old).settingsPatch!!(Settings(autoRules = listOf(salary))).autoRules)
    }
}
