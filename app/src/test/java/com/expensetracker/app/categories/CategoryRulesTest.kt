package com.expensetracker.app.categories

import com.expensetracker.app.data.Category
import com.expensetracker.app.data.CategoryInfo
import com.expensetracker.app.data.CategoryRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CategoryRulesTest {

    @Before fun setUp() = CategoryRegistry.setForTesting(emptyList())
    @After fun tearDown() = CategoryRegistry.setForTesting(emptyList())

    private val defaults get() = CategoryRegistry.allInfo()
    private fun c(id: String, label: String = id, hidden: Boolean = false) = CategoryInfo(id, label, "🏷️", 0xFF123456, hidden)

    @Test fun encodeDecodeOddCharacters() {
        val list = listOf(
            CategoryInfo("c_1", "Chai \"&\" Snacks ☕ — ünï", "🫖", 0xFF00ACC1),
            CategoryInfo("c_2", "Tab\there\nnewline", "👨‍👩‍👧‍👦", 0xFF000000, hidden = true),
            CategoryInfo("FOOD", "Khana", "🍛", 0x80FFFFFF),
            CategoryInfo("c_3", "", "", 0xFFFFFFFF),
        )
        val back = CategoryRegistry.decode(CategoryRegistry.encode(list))
        assertEquals(4, back.size)
        assertEquals(list[0], back[0])
        assertEquals("Tab here newline", back[1].label) // tabs/newlines become spaces
        assertEquals("👨‍👩‍👧‍👦", back[1].emoji) // ZWJ sequence intact
        assertTrue(back[1].hidden)
        assertEquals(0x80FFFFFFL, back[2].color)
        assertEquals(list[3], back[3])
        assertEquals(emptyList<CategoryInfo>(), CategoryRegistry.decode(""))
        assertEquals(emptyList<CategoryInfo>(), CategoryRegistry.decode("garbage line\n\t\t"))
    }

    @Test fun cannotHideOtherOrLastVisible() {
        assertNotNull(CategoryRules.hideBlockedReason(defaults, "OTHER"))
        assertNull(CategoryRules.hideBlockedReason(defaults, "FOOD"))
        val onlyFoodAndOtherVisible = defaults.map { it.copy(hidden = it.id != "FOOD" && it.id != "OTHER") }
        assertNull(CategoryRules.hideBlockedReason(onlyFoodAndOtherVisible, "FOOD"))
        val onlyOneVisible = defaults.map { it.copy(hidden = it.id != "FOOD") }
        assertNotNull(CategoryRules.hideBlockedReason(onlyOneVisible, "FOOD"))
        assertEquals(onlyOneVisible, CategoryRules.setHidden(onlyOneVisible, "FOOD", true))
        // Unhiding is always fine.
        assertNull(CategoryRules.hideBlockedReason(onlyOneVisible, "RENT"))
        assertFalse(CategoryRules.setHidden(onlyOneVisible, "RENT", false).first { it.id == "RENT" }.hidden)
    }

    @Test fun hideThenSaveIsVisibleInRegistry() {
        CategoryRegistry.setForTesting(CategoryRules.setHidden(defaults, "RENT", true))
        assertFalse(Category.RENT in Category.entries)
        assertTrue(CategoryRegistry.exists("RENT")) // old entries keep it
        assertTrue(CategoryRegistry.info("RENT").hidden)
    }

    @Test fun resetToDefaultKeepsHiddenFlag() {
        val edited = CategoryRules.edit(defaults, "FOOD", "Khana", "🍛", 0xFF000000)
        val food = edited.first { it.id == "FOOD" }
        assertTrue(CategoryRules.isEdited(food))
        val hidden = CategoryRules.setHidden(edited, "FOOD", true)
        val reset = CategoryRules.resetToDefault(hidden, "FOOD").first { it.id == "FOOD" }
        assertEquals("Food", reset.label)
        assertEquals("🍔", reset.emoji)
        assertTrue(reset.hidden)
        assertFalse(CategoryRules.isEdited(reset.copy(hidden = false)))
        // Custom ones can't be "reset".
        val withCustom = defaults + c("c_1")
        assertEquals(withCustom, CategoryRules.resetToDefault(withCustom, "c_1"))
    }

    @Test fun savedListOnlyStoresDifferences() {
        val list = CategoryRules.add(CategoryRules.edit(defaults, "TRANSPORT", "Auto", "🛺", 0xFF1E88E5), "c_1", "  Pets\t", "", 0xFF00FF00)
        val stored = CategoryRules.forBackup(list)
        assertEquals(listOf("TRANSPORT", "c_1"), stored.map { it.id })
        assertEquals("Pets", stored[1].label)
        assertEquals("🏷️", stored[1].emoji) // blank emoji gets a default
        CategoryRegistry.setForTesting(list)
        assertEquals("Auto", Category.TRANSPORT.label)
        assertEquals("Pets", Category.fromId("c_1").label)
    }

    @Test fun labelValidation() {
        assertNotNull(CategoryRules.labelError(defaults, "  ", null))
        assertNotNull(CategoryRules.labelError(defaults, "food", null)) // duplicates are case-insensitive
        assertNull(CategoryRules.labelError(defaults, "Food", "FOOD")) // renaming to itself is fine
        assertNull(CategoryRules.labelError(defaults, "Pets", null))
        assertNotNull(CategoryRules.labelError(defaults, "x".repeat(CategoryRules.MAX_LABEL + 1), null))
    }

    @Test fun deleteOnlyCustom() {
        val list = defaults + c("c_1")
        assertEquals(defaults, CategoryRules.delete(list, "c_1"))
        assertEquals(list, CategoryRules.delete(list, "FOOD"))
    }

    @Test fun afterMergeDeletesCustomHidesBuiltInNeverOther() {
        val list = defaults + c("c_1")
        assertEquals(defaults, CategoryRules.afterMerge(list, "c_1"))
        assertTrue(CategoryRules.afterMerge(list, "SHOPPING").first { it.id == "SHOPPING" }.hidden)
        assertEquals(list, CategoryRules.afterMerge(list, "OTHER"))
        assertFalse(CategoryRules.moveTargets(list, "c_1").any { it.id == "c_1" })
    }

    @Test fun reorderCustomOnly() {
        val list = defaults + c("c_1") + c("c_2") + c("c_3")
        val up = CategoryRules.moveCustom(list, "c_3", -1)
        assertEquals(listOf("c_1", "c_3", "c_2"), up.filterNot { CategoryRules.isBuiltIn(it.id) }.map { it.id })
        assertEquals(list, CategoryRules.moveCustom(list, "c_1", -1)) // already first
        assertEquals(list, CategoryRules.moveCustom(list, "c_3", 1)) // already last
        assertEquals(list, CategoryRules.moveCustom(list, "FOOD", 1)) // built-ins don't move
    }

    @Test fun restoreReplaceUsesBackupLook() {
        val current = CategoryRules.edit(defaults, "FOOD", "Mine", "🍕", 0xFF111111) + c("c_local")
        val backup = listOf(CategoryRegistry.DEFAULTS.getValue("RENT").copy(hidden = true), c("c_b1", "Pets"))
        val out = CategoryRules.mergeForRestore(current, backup, replace = true)
        assertEquals("Food", out.first { it.id == "FOOD" }.label) // backup didn't edit it → default
        assertTrue(out.first { it.id == "RENT" }.hidden)
        assertEquals(listOf("c_b1", "c_local"), out.filterNot { CategoryRules.isBuiltIn(it.id) }.map { it.id })
    }

    @Test fun restoreMergeKeepsPhoneLook() {
        val current = CategoryRules.edit(defaults, "FOOD", "Mine", "🍕", 0xFF111111) + c("c_x", "Phone")
        val backup = listOf(CategoryInfo("FOOD", "Backup", "🍛", 0xFF222222), c("c_x", "Backup"), c("c_b1", "Pets"))
        val out = CategoryRules.mergeForRestore(current, backup, replace = false)
        assertEquals("Mine", out.first { it.id == "FOOD" }.label)
        assertEquals("Phone", out.first { it.id == "c_x" }.label)
        assertEquals(listOf("c_x", "c_b1"), out.filterNot { CategoryRules.isBuiltIn(it.id) }.map { it.id })
    }

    @Test fun restoreNeverLeavesNothingVisible() {
        val backup = CategoryRegistry.DEFAULTS.values.map { it.copy(hidden = true) }
        val out = CategoryRules.mergeForRestore(defaults, backup, replace = true)
        assertFalse(out.first { it.id == "OTHER" }.hidden)
    }
}
