package com.expensetracker.app

import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.NotesParser
import com.expensetracker.app.data.SpendClassifier
import com.expensetracker.app.data.SpendType
import org.junit.Assert.assertEquals
import org.junit.Test

class SpendClassifierTest {

    private val expenses: List<Expense> = run {
        val text = javaClass.classLoader!!.getResource("history.txt")!!.readText(Charsets.UTF_8)
        NotesParser.parse(text, 2026).entries.mapIndexed { i, p ->
            Expense(id = i + 1L, amountPaise = p.amountPaise, note = p.note, category = p.category, date = p.date, isIncome = p.isIncome)
        }
    }
    private val verdicts = SpendClassifier.classify(expenses)
    private fun typeOf(note: String, rupees: Long): SpendType {
        val i = expenses.indexOfFirst { it.note.equals(note, true) && it.amountPaise == rupees * 100 }
        require(i >= 0) { "no entry $note $rupees" }
        return verdicts[i]!!.type
    }

    @Test fun printReport() {
        SpendType.entries.filter { it != SpendType.EVERYDAY }.forEach { t ->
            println("== $t")
            expenses.indices.filter { verdicts[it]?.type == t }.forEach { i ->
                val e = expenses[i]
                println("  ${e.date}  ${e.amountPaise / 100}  ${e.note}  [${e.category}]  — ${verdicts[i]!!.reason}")
            }
        }
    }

    @Test fun bigTravelAndPurchasesAreOneOff() {
        assertEquals(SpendType.ONE_OFF, typeOf("Flight to BLR", 11201))
        assertEquals(SpendType.ONE_OFF, typeOf("Train Ticket ( to BMR )", 1519))
        assertEquals(SpendType.ONE_OFF, typeOf("Watch", 5315))
        assertEquals(SpendType.ONE_OFF, typeOf("Prestige Induction", 2128))
    }

    @Test fun rentAndSubscriptionsAreFixed() {
        assertEquals(SpendType.FIXED, typeOf("Rent + Security", 29495))
        assertEquals(SpendType.FIXED, typeOf("Mullvad", 490))
    }

    @Test fun dailyStuffIsEveryday() {
        assertEquals(SpendType.EVERYDAY, typeOf("Lunch + Diet coke", 142))
        assertEquals(SpendType.EVERYDAY, typeOf("Zepto Groceries", 571))
        assertEquals(SpendType.EVERYDAY, typeOf("Rapido", 141))
        assertEquals(SpendType.EVERYDAY, typeOf("Metro ( INA )", 108))
    }

    @Test fun savingsAndOverride() {
        assertEquals(SpendType.SAVINGS, typeOf("2x5000 FDs", 10000))
        val i = expenses.indexOfFirst { it.note == "Watch" }
        val changed = expenses.toMutableList().also { it[i] = it[i].copy(spendOverride = SpendType.EVERYDAY) }
        val v = SpendClassifier.classify(changed)[i]!!
        assertEquals(SpendType.EVERYDAY, v.type)
        assertEquals(false, v.auto)
    }
}
