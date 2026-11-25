package com.expensetracker.app

import com.expensetracker.app.ui.formatRupeesCompact
import org.junit.Assert.assertEquals
import org.junit.Test

class CompactRupeesTest {
    private fun r(rupees: Double) = formatRupeesCompact(Math.round(rupees * 100))

    @Test fun underAThousandIsWholeRupees() {
        assertEquals("₹950", r(950.0))
        assertEquals("₹60", r(59.6))
    }

    @Test fun thousandsKeepOneDecimalBelowTenK() {
        assertEquals("₹6.4k", r(6_420.0))
        assertEquals("₹2k", r(2_010.0))
    }

    @Test fun tensOfThousandsAreWhole() {
        assertEquals("₹46k", r(45_600.0))
        assertEquals("₹75k", r(75_432.0))
    }

    @Test fun lakhs() = assertEquals("₹1.2L", r(1_23_456.0))
}
