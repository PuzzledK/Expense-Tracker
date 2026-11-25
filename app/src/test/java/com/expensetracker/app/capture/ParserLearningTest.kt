package com.expensetracker.app.capture

import org.junit.Assert.assertEquals
import org.junit.Test

/** Learning reads a draft's stored text back; it must give the same shape the original message had. */
class ParserLearningTest {
    private fun stored(title: String?, text: String) =
        listOfNotNull(title?.trim()?.takeIf { it.isNotEmpty() }, text.trim()).joinToString("\n")

    private fun assertSameShape(title: String?, text: String, raw: String = stored(title, text)) {
        val (t, b) = ParserLearning.primaryText(raw)!!
        assertEquals(
            ParserModel.shape(PaymentParser.combined(title, text)).signature,
            ParserModel.shape(PaymentParser.combined(t, b)).signature,
        )
    }

    @Test fun gpayTitleAndBody() = assertSameShape("RICHA KHATRI paid you ₹1.00", "Sent using Paytm UPI")
    @Test fun noTitle() = assertSameShape(null, "You paid ₹66 to Rapido")
    @Test fun smsSender() = assertSameShape("AX-BOBSMS", "Rs.1.00 Dr. from A/C XX6932 and Cr. to richa@ybl.")
    @Test fun mergedDraftUsesTheFirstMessage() =
        assertSameShape("RICHA KHATRI paid you ₹1.00", "Sent using Paytm UPI",
            stored("RICHA KHATRI paid you ₹1.00", "Sent using Paytm UPI") + "\n— SMS —\nAX-BOBSMS\nRs.1.00 Cr. to A/C XX6932")

    @Test fun groups() {
        assertEquals("SMS", ParserLearning.groupOf(SmsAccess.PKG))
        assertEquals("GPAY", ParserLearning.groupOf("com.google.android.apps.nbu.paisa.user"))
    }
}
