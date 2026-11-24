package com.expensetracker.app.ml

import com.expensetracker.app.data.Category
import com.expensetracker.app.data.Expense
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PredictorTest {

    @Before fun setUp() = Predictor.resetForTesting()
    @After fun tearDown() = Predictor.resetForTesting()

    private var nextId = 1L
    private val day0 = LocalDate.of(2026, 10, 1)

    private fun e(note: String, rupees: Long, cat: Category, daysAgo: Long = 0, time: LocalTime? = null, place: String? = null) =
        Expense(
            id = nextId++, amountPaise = rupees * 100, note = note, category = cat,
            date = day0.minusDays(daysAgo), time = time, place = place, createdAt = 1_000_000L + nextId,
        )

    // ---------------- empty history ----------------

    @Test fun emptyHistoryFallsBackToRules() {
        assertEquals(Category.TRANSPORT, Predictor.predictCategory("Rapido"))
        assertEquals(Category.FOOD, Predictor.predictCategory("Lunch + Diet coke", 14200, LocalDateTime.now()))
        assertEquals(Category.OTHER, Predictor.predictCategory(""))
        assertEquals(emptyList<Any>(), Predictor.suggestions(LocalDateTime.now()))
        val p = Predictor.predictPayment("ZOMATO LIMITED", 30000, LocalDateTime.now())
        assertEquals("Zomato", p.note)
        assertEquals(Category.FOOD, p.category)
        assertTrue(p.confidence < 0.5)
        Predictor.train(emptyList())
        assertEquals(Category.TRANSPORT, Predictor.predictCategory("rapido"))
        Predictor.learn(e("Haircut", 200, Category.HEALTH))   // learn without any training must not crash
        assertEquals(Category.HEALTH, Predictor.predictCategory("haircut"))
    }

    // ---------------- model generalises beyond exact notes ----------------

    @Test fun learnsCustomCategoryFromSimilarNotes() {
        val office = Category.create("c_office_food")
        val list = listOf(
            e("Lunch, Rajma Rice", 80, office, 1), e("Lunch, Veg Meal", 120, office, 2),
            e("Lunch, Egg Biryani", 135, office, 3), e("Lunch + Coke", 120, office, 4),
            e("Dinner, Paneer roll", 146, Category.FOOD, 1), e("Dinner", 178, Category.FOOD, 2),
            e("Rapido", 66, Category.TRANSPORT, 1),
        )
        Predictor.train(list)
        assertEquals(office, Predictor.predictCategory("Lunch, Chole Kulche", 11000))
        assertEquals(office, Predictor.predictCategory("lnuch rajma", 9000))        // typo via trigrams
        assertEquals(Category.FOOD, Predictor.predictCategory("Dinner, Dal Makhani", 17600))
        assertEquals(Category.TRANSPORT, Predictor.predictCategory("rapido (galleria)", 6000))
    }

    // ---------------- corrections ----------------

    @Test fun correctionFlipsNextPrediction() {
        val list = mutableListOf(
            e("Groceries", 300, Category.GROCERIES, 5), e("Protein bar", 120, Category.HEALTH, 4), e("Rapido", 70, Category.TRANSPORT, 3),
        )
        Predictor.train(list.toList())
        assertEquals(Category.HEALTH, Predictor.predictCategory("Protein bar"))

        // You re-save "Protein bar" as Food: the model said Health → a correction.
        val saved = e("Protein bar", 120, Category.FOOD, 0)
        list.add(0, saved)
        Predictor.train(list.toList())
        Predictor.learn(saved)
        assertEquals(1, Predictor.correctionCount())
        assertEquals(Category.FOOD, Predictor.predictCategory("Protein bar"))
        assertEquals(Category.FOOD, Predictor.predictCategory("protein  BAR"))
    }

    @Test fun editingCategoryIsACorrectionEvenIfModelAgreed() {
        val gym = e("Gym", 1500, Category.HEALTH, 2)
        Predictor.train(listOf(gym, e("Gym", 1500, Category.HEALTH, 40)))
        Predictor.predictCategory("warm")   // build the model with the old category
        val edited = gym.copy(category = Category.BILLS)
        val list2 = listOf(edited, e("Gym", 1500, Category.HEALTH, 40))
        Predictor.train(list2)
        Predictor.learn(edited)
        assertEquals(Category.BILLS, Predictor.predictCategory("gym"))
    }

    @Test fun correctionPersistsAcrossRestart() {
        val f = File.createTempFile("ml_predictor", ".tsv").apply { delete() }
        try {
            Predictor.initFile(f)
            Predictor.train(listOf(e("Mullvad", 490, Category.BILLS, 3)))
            val saved = e("Mullvad", 490, Category.SHOPPING, 0)
            Predictor.train(listOf(saved, e("Mullvad", 490, Category.BILLS, 3)))
            Predictor.learn(saved)
            assertTrue(f.exists())

            Predictor.resetForTesting()             // "restart"
            Predictor.initFile(f)
            assertEquals(Category.SHOPPING, Predictor.predictCategory("Mullvad"))
        } finally {
            f.delete()
        }
    }

    @Test fun newerHistoryOverridesStaleCorrection() {
        val saved = e("Snacks", 50, Category.GROCERIES, 5)
        Predictor.train(listOf(e("Snacks", 50, Category.FOOD, 9)))
        Predictor.train(listOf(saved, e("Snacks", 50, Category.FOOD, 9)))
        Predictor.learn(saved)
        assertEquals(Category.GROCERIES, Predictor.predictCategory("snacks"))
        // Later entries (e.g. imported) consistently say Food again, and are newer than the correction.
        val later = e("Snacks", 60, Category.FOOD, 0).copy(createdAt = System.currentTimeMillis() + 60_000)
        Predictor.train(listOf(later, saved, e("Snacks", 50, Category.FOOD, 9)))
        assertEquals(Category.FOOD, Predictor.predictCategory("snacks"))
    }

    // ---------------- payments ----------------

    @Test fun payeeToNoteIsLearntFromConfirmations() {
        val hist = listOf(e("Dinner", 300, Category.FOOD, 3), e("Lunch + Diet coke", 142, Category.FOOD, 2))
        Predictor.train(hist)
        val at = LocalDateTime.of(2026, 10, 1, 21, 5)
        val before = Predictor.predictPayment("BUNDL TECHNOLOGIES PRIVATE LIMITED", 31200, at)
        assertEquals("Swiggy", before.note)
        assertTrue(before.confidence < 0.5)

        // You confirm it as "Dinner, Swiggy" (explicit payee), twice.
        repeat(2) {
            val saved = e("Dinner, Swiggy", 312, Category.FOOD, 0, LocalTime.of(21, 5))
            Predictor.learn(saved, payee = "BUNDL TECHNOLOGIES PRIVATE LIMITED")
        }
        val after = Predictor.predictPayment("Bundl Technologies Pvt Ltd", 29000, at)
        assertEquals("Dinner, Swiggy", after.note)
        assertEquals(Category.FOOD, after.category)
        assertTrue("confidence ${after.confidence}", after.confidence > before.confidence && after.confidence >= 0.6)
    }

    @Test fun payeeLinkedImplicitlyByAmount() {
        Predictor.train(listOf(e("Rent", 18806, Category.RENT, 30)))
        val at = LocalDateTime.of(2026, 10, 1, 10, 0)
        Predictor.predictPayment("SHARMA PROPERTIES", 1_880_600, at)
        Predictor.learn(e("Rent + Electricity", 18806, Category.RENT, 0))   // capture didn't pass payee
        val p = Predictor.predictPayment("Sharma Properties", 1_880_600, at.plusMonths(1))
        assertEquals("Rent + Electricity", p.note)
        assertEquals(Category.RENT, p.category)
    }

    @Test fun payeeMatchedToHistoryNote() {
        Predictor.train(listOf(e("Rapido", 66, Category.TRANSPORT, 1), e("Rapido", 90, Category.TRANSPORT, 2), e("Richa", 500, Category.PEOPLE, 3)))
        val at = LocalDateTime.of(2026, 10, 1, 19, 0)
        val r = Predictor.predictPayment("rapido@ybl", 7000, at)
        assertEquals("Rapido", r.note); assertEquals(Category.TRANSPORT, r.category)
        val p = Predictor.predictPayment("RICHA SHARMA", 50000, at)
        assertEquals("Richa", p.note); assertEquals(Category.PEOPLE, p.category)
        val unknown = Predictor.predictPayment("RAMESH KUMAR", 4000, at)
        assertEquals("Ramesh Kumar", unknown.note)
        assertTrue(unknown.confidence <= 0.15)
    }

    // ---------------- suggestions ----------------

    @Test fun suggestionsFollowTheHour() {
        val list = buildList {
            for (d in 1L..6L) {
                add(e("Lunch + Diet coke", 142, Category.FOOD, d, LocalTime.of(13, 10)))
                add(e("Rapido", 66, Category.TRANSPORT, d, LocalTime.of(19, 30)))
            }
            for (d in 1L..9L) add(e("Coffee", 123, Category.FOOD, d, LocalTime.of(10, 0)))
        }.sortedByDescending { it.date }
        Predictor.train(list)
        val day = day0.plusDays(1)
        assertEquals("Lunch + Diet coke", Predictor.suggestions(day.atTime(13, 0)).first().note)
        assertEquals("Rapido", Predictor.suggestions(day.atTime(19, 45)).first().note)
        assertEquals("Coffee", Predictor.suggestions(day.atTime(9, 50)).first().note)
        assertEquals(14200L, Predictor.suggestions(day.atTime(13, 0)).first().amountPaise)
    }

    @Test fun suggestionsUseNoteWordsWhenTimeUnknown() {
        val list = buildList {
            for (d in 1L..4L) { add(e("Lunch", 120, Category.FOOD, d)); add(e("Dinner", 200, Category.FOOD, d)) }
        }
        Predictor.train(list)
        val day = day0.plusDays(1)
        assertEquals("Lunch", Predictor.suggestions(day.atTime(13, 0)).first().note)
        assertEquals("Dinner", Predictor.suggestions(day.atTime(21, 0)).first().note)
    }

    // ---------------- thread safety ----------------

    @Test fun concurrentUseDoesNotCrash() {
        val base = (0 until 400).map { i ->
            val notes = listOf("Lunch" to Category.FOOD, "Rapido" to Category.TRANSPORT, "Zepto" to Category.GROCERIES, "Mullvad" to Category.BILLS)
            val (n, c) = notes[i % notes.size]
            e(n, 50L + i % 7, c, (i / 4).toLong(), LocalTime.of(8 + i % 12, 0))
        }
        val pool = Executors.newFixedThreadPool(8)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        repeat(400) { i ->
            pool.execute {
                try {
                    when (i % 5) {
                        0 -> Predictor.train(base.drop(i % 50))
                        1 -> Predictor.learn(base[i % base.size].copy(category = Category.OTHER))
                        2 -> Predictor.predictCategory("lunch special", 12000, LocalDateTime.now())
                        3 -> Predictor.predictPayment("Zepto Marketplace", 25000, LocalDateTime.now())
                        else -> Predictor.suggestions(LocalDateTime.now())
                    }
                } catch (t: Throwable) { errors += t }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals(emptyList<Throwable>(), errors.toList())
        assertNotEquals(null, Predictor.predictCategory("Rapido"))
    }
}
