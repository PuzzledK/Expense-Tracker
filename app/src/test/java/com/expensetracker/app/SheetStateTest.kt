package com.expensetracker.app

import com.expensetracker.app.data.Category
import com.expensetracker.app.data.Expense
import com.expensetracker.app.ui.SheetState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class SheetStateTest {
    private val lunch = Expense(id = 42, amountPaise = 14200, note = "Lunch", category = Category.FOOD, date = LocalDate.of(2026, 11, 25))
    private fun roundTrip(s: SheetState) = SheetState.decode(SheetState.encode(s)) { id -> lunch.takeIf { it.id == id } }

    @Test fun everyStateRoundTrips() {
        assertEquals(SheetState.Closed, roundTrip(SheetState.Closed))
        assertEquals(SheetState.Adding, roundTrip(SheetState.Adding))
        assertEquals(SheetState.Bulk(LocalDate.of(2026, 11, 3)), roundTrip(SheetState.Bulk(LocalDate.of(2026, 11, 3))))
        assertEquals(SheetState.Editing(lunch), roundTrip(SheetState.Editing(lunch)))
    }

    @Test fun editingADeletedEntryCloses() =
        assertEquals(SheetState.Closed, SheetState.decode("edit:7") { null })

    @Test fun junkCloses() {
        assertEquals(SheetState.Closed, SheetState.decode("bulk:notadate") { null })
        assertEquals(SheetState.Closed, SheetState.decode("whatever") { null })
    }
}
