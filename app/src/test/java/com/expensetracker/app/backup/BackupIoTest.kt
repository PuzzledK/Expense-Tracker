package com.expensetracker.app.backup

import com.expensetracker.app.categories.CategoryRules
import com.expensetracker.app.data.BackupIo
import com.expensetracker.app.data.Category
import com.expensetracker.app.data.CategoryInfo
import com.expensetracker.app.data.CategoryRegistry
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.Settings
import com.expensetracker.app.data.SpendType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class BackupIoTest {

    private val custom = CategoryInfo("c_abc123", "Chai \"&\" Snacks \\ ☕", "🫖", 0xFF00ACC1)
    private val editedFood = CategoryInfo("FOOD", "Khana", "🍛", 0xFFE53935)
    private val hiddenRent = CategoryRegistry.DEFAULTS.getValue("RENT").copy(hidden = true)

    @Before fun setUp() = CategoryRegistry.setForTesting(emptyList())
    @After fun tearDown() = CategoryRegistry.setForTesting(emptyList())

    private fun sample(): List<Expense> {
        CategoryRegistry.setForTesting(listOf(editedFood, hiddenRent, custom))
        return listOf(
            Expense(
                amountPaise = 14250, note = "Lunch \"thali\"\nwith\ttab — ₹ & ünïcödé 🍛", category = Category.FOOD,
                date = LocalDate.of(2026, 11, 24), createdAt = 1_732_400_000_123,
                spendOverride = SpendType.ONE_OFF, place = "Gurugram", lat = 28.4595, lon = 77.0266,
                time = LocalTime.of(13, 5), goalId = "g_goa",
            ),
            Expense(amountPaise = 500000, note = "Mammi", category = Category.OTHER, date = LocalDate.of(2026, 1, 1), isIncome = true, createdAt = 5),
            Expense(amountPaise = 3000, note = "Masala chai", category = Category.fromId(custom.id), date = LocalDate.of(2026, 11, 25), createdAt = 6),
            Expense(amountPaise = 1, note = "", category = Category.RENT, date = LocalDate.of(2025, 12, 31), createdAt = 7, spendOverride = SpendType.FIXED),
        )
    }

    @Test fun roundTripKeepsEveryField() {
        val list = sample()
        val settings = Settings(monthlyBudgetPaise = 2_500_000, excludeSavingsFromSpend = false, reminderEnabled = true, reminderHour = 21, reminderMinute = 45, homeCity = "Pune")
        val goals = "goals-v1\ng_goa\tGoa \"trip\"\t5000000\nline with \\ backslash and 🏖️"
        val json = BackupIo.toJson(list, settings, CategoryRules.forBackup(CategoryRegistry.allInfo()), goals)

        // Restoring on a fresh phone: the custom category doesn't exist yet.
        CategoryRegistry.setForTesting(emptyList())
        val c = BackupIo.parse(json)
        assertEquals(list.map { it.copy(id = 0) }, c.expenses)
        assertEquals(custom.id, c.expenses[2].category.id)
        assertEquals(goals, c.goalsText)
        assertEquals(listOf(editedFood, hiddenRent, custom), c.categories)
        val restored = c.settingsPatch!!(Settings(appLock = true, autoBackupTreeUri = "content://x", autoBackupEnabled = true))
        assertEquals(2_500_000, restored.monthlyBudgetPaise)
        assertEquals(false, restored.excludeSavingsFromSpend)
        assertEquals(true, restored.reminderEnabled)
        assertEquals(21, restored.reminderHour)
        assertEquals(45, restored.reminderMinute)
        assertEquals("Pune", restored.homeCity)
        // Device-specific settings are never overwritten by a backup.
        assertEquals(true, restored.appLock)
        assertEquals("content://x", restored.autoBackupTreeUri)
        assertEquals(true, restored.autoBackupEnabled)
    }

    @Test fun noExtrasWhenNotGiven() {
        val json = BackupIo.toJson(emptyList(), Settings())
        val c = BackupIo.parse(json)
        assertTrue(c.expenses.isEmpty())
        assertNull(c.categories)
        assertNull(c.goalsText)
    }

    @Test fun emptyCategoryListStillRoundTrips() {
        val c = BackupIo.parse(BackupIo.toJson(emptyList(), Settings(), emptyList(), ""))
        assertEquals(emptyList<CategoryInfo>(), c.categories)
        assertEquals("", c.goalsText)
    }

    @Test fun oldVersion1BackupStillParses() {
        val old = """
            {
              "app": "ExpenseTracker",
              "version": 1,
              "settings": {"monthlyBudgetPaise": 1000000, "excludeSavingsFromSpend": true, "reminderEnabled": false, "reminderHour": 22, "reminderMinute": 0},
              "expenses": [
                {"date": "2026-03-31", "amountPaise": 12000, "note": "Lunch", "category": "FOOD", "isIncome": false, "createdAt": 100},
                {"date": "2026-03-30", "amountPaise": 500000, "note": "Mammi", "category": "PEOPLE", "isIncome": true, "createdAt": 99, "place": "Delhi"},
                {"date": "2026-03-29", "amountPaise": 700, "note": "Gone category", "category": "c_deleted"}
              ]
            }
        """.trimIndent()
        val c = BackupIo.parse(old)
        assertEquals(3, c.expenses.size)
        assertEquals(Category.FOOD, c.expenses[0].category)
        assertEquals("Delhi", c.expenses[1].place)
        assertTrue(c.expenses[1].isIncome)
        assertEquals(Category.OTHER, c.expenses[2].category) // unknown id falls back
        assertNull(c.expenses[0].time)
        assertNull(c.categories)
        assertNull(c.goalsText)
        // homeCity wasn't in the old file, so it's left as it is.
        val s = c.settingsPatch!!(Settings(homeCity = "Gurugram"))
        assertEquals("Gurugram", s.homeCity)
        assertEquals(1_000_000, s.monthlyBudgetPaise)
        // Legacy API keeps working.
        val (list, settings) = BackupIo.fromJson(old)
        assertEquals(3, list.size)
        assertEquals(1_000_000, settings!!.monthlyBudgetPaise)
    }

    @Test fun backupWithoutSettingsHasNoPatch() {
        val c = BackupIo.parse("""{"expenses": []}""")
        assertNull(c.settingsPatch)
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedThrows() {
        BackupIo.parse("{\"expenses\": [ {\"date\": \"2026-01-01\"} ]}")
    }

    @Test(expected = IllegalArgumentException::class)
    fun truncatedThrows() {
        val json = BackupIo.toJson(sample(), Settings())
        BackupIo.parse(json.dropLast(10))
    }
}
