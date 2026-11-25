package com.expensetracker.app

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.expensetracker.app.ui.pureBlack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PureBlackTest {
    private val base = darkColorScheme(
        surface = Color(0xFF0E1512), background = Color(0xFF0E1512),
        surfaceContainerLow = Color(0xFF161E1A), surfaceContainer = Color(0xFF1A231F),
        surfaceContainerHigh = Color(0xFF242E29), surfaceContainerHighest = Color(0xFF2F3934),
        primary = Color(0xFF8FD9B2),
    )
    private val amoled = base.pureBlack()

    @Test fun backgroundsAreTrueBlack() {
        assertEquals(Color.Black, amoled.background)
        assertEquals(Color.Black, amoled.surface)
        assertEquals(Color.Black, amoled.surfaceContainerLowest)
    }

    @Test fun cardsStayDistinguishableAndOrdered() {
        val levels = listOf(amoled.surfaceContainerLow, amoled.surfaceContainer, amoled.surfaceContainerHigh, amoled.surfaceContainerHighest)
        assertTrue(levels.first().luminance() > 0f)
        levels.zipWithNext().forEach { (a, b) -> assertTrue("$a !< $b", a.luminance() < b.luminance()) }
        // Darker than the original theme's surfaces.
        assertTrue(amoled.surfaceContainer.luminance() < base.surfaceContainer.luminance())
    }

    @Test fun accentsUntouched() = assertEquals(base.primary, amoled.primary)
}
