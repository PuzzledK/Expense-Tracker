package com.expensetracker.app.capture

import com.expensetracker.app.capture.ParserModel.Decision
import com.expensetracker.app.capture.ParserModel.Origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserModelTest {

    private fun shapeOf(title: String?, text: String) = ParserModel.shape(PaymentParser.combined(title, text))

    private fun ParserModel.draft(group: String, title: String?, text: String, sms: Boolean = false): Decision.Draft? =
        decide(group, title, text, PaymentParser.parse(title, text, sms), sms) as? Decision.Draft

    /** Confirm a message the way a user would after reviewing it. */
    private fun ParserModel.confirm(group: String, title: String?, text: String, amountPaise: Long, isCredit: Boolean, note: String) =
        learnPayment(group, title, text, amountPaise, isCredit, note, draft(group, title, text)?.payment)

    // ---------------- shapes

    @Test fun sameFormatSameShapeWhateverTheNameAndAmount() {
        val a = shapeOf("RICHA KHATRI paid you ₹1.00", "Sent using Paytm UPI")
        val b = shapeOf("Anu Sharma paid you ₹5,000", "Sent using Paytm UPI")
        assertEquals(a.signature, b.signature)
        assertEquals(listOf("RICHA KHATRI"), a.names)
        assertEquals(listOf<Long?>(100), a.numbers)
        assertEquals(listOf<Long?>(500000), b.numbers)
    }

    @Test fun shapeKeepsPaymentWordsOnly() {
        val s = shapeOf("AX-BOBSMS", "Rs.1.00 Dr. from A/C XX6932 and Cr. to richa@ybl. Ref:123456789012")
        assertTrue(s.signature, s.signature.contains("dr from a/c xx and cr to @"))
        assertFalse(s.signature.contains("6932"))
        assertFalse(s.signature.contains("richa"))
    }

    // ---------------- format learning

    @Test fun correctedAmountIsLearnedForThatFormat() {
        val m = ParserModel()
        val t1 = "Order ₹1,299 · You paid ₹999 to Croma"
        // Regex takes the first amount; you correct it to ₹999.
        assertEquals(129900L, m.draft("AMAZON", null, t1)!!.payment.amountPaise)
        m.confirm("AMAZON", null, t1, 99900, isCredit = false, note = "Croma")

        val next = m.draft("AMAZON", null, "Order ₹500 · You paid ₹450 to Flipkart")!!
        assertEquals(45000L, next.payment.amountPaise)
        assertEquals(Origin.LEARNED, next.origin)
        assertEquals("Flipkart", next.payment.payee)
    }

    @Test fun correctedDirectionIsLearned() {
        val m = ParserModel()
        val t = "₹200 sent to you by Mohan"
        val first = m.draft("PHONEPE", null, t)!!.payment
        if (!first.isCredit) m.confirm("PHONEPE", null, t, 20000, isCredit = true, note = "Mohan")
        assertTrue(m.draft("PHONEPE", null, "₹350 sent to you by Geeta")!!.payment.isCredit)
    }

    @Test fun unchangedConfirmationsDontOverrideTheRegex() {
        val m = ParserModel()
        m.confirm("GPAY", null, "You paid ₹66 to Rapido", 6600, false, "Rapido")
        val d = m.draft("GPAY", null, "You paid ₹120 to Zomato")!!
        assertEquals(Origin.REGEX, d.origin)
        assertEquals("Zomato", d.payment.payee)
    }

    @Test fun aConfirmedFormatIsRescuedWhenTheRegexRejectsIt() {
        val m = ParserModel()
        m.confirm("GPAY", null, "You paid ₹66 to Rapido", 6600, false, "Rapido")
        // "Win" trips the regex's offer filter, but it's the same format you've confirmed.
        val text = "You paid ₹80 to Win Mart"
        assertNull(PaymentParser.parse(null, text))
        val d = m.draft("GPAY", null, text)
        assertNotNull(d)
        assertEquals(8000L, d!!.payment.amountPaise)
        assertEquals(Origin.UNSURE, d.origin) // seen once: still flagged
    }

    // ---------------- payment / not-payment

    @Test fun formatDismissedTwiceAsNotAPaymentIsHidden() {
        val m = ParserModel()
        val junk = "Rs 299 paid for your Jio number via UPI"
        assertFalse(m.draft("SMS", "JM-JIOINF", junk, sms = true)!!.hidden)
        m.learnNotPayment("SMS", "JM-JIOINF", junk)
        m.learnNotPayment("SMS", "JM-JIOINF", "Rs 199 paid for your Jio number via UPI")
        assertTrue(m.draft("SMS", "JM-JIOINF", "Rs 349 paid for your Jio number via UPI", sms = true)!!.hidden)
    }

    @Test fun classifierHidesJunkItIsSureAbout() {
        val m = ParserModel()
        val payments = listOf(
            "You paid ₹66 to Rapido", "Paid ₹120 to Zomato via UPI", "₹500 sent to Anu Sharma",
            "Rs 142.00 debited from A/c XX1234 to VPA zomato@icici", "You have successfully paid Rs. 50.00 to SHARMA STORES",
            "Payment of ₹299 to Swiggy was successful",
        )
        payments.forEach { t -> m.learnPayment("GPAY", null, t, PaymentParser.parse(null, t)!!.amountPaise, false, "", null) }
        val junk = listOf(
            "Dear customer, ₹50 cashback credited to your wallet", "Dear customer ₹120 cashback has been credited to wallet",
            "₹25 cashback credited to your Paytm wallet. Dear customer, enjoy", "Dear customer, ₹10 cashback credited in wallet",
            "Cashback of ₹75 credited to your wallet, dear customer", "Dear customer ₹30 cashback credited to wallet balance",
        )
        junk.forEach { m.learnNotPayment("GPAY", null, it) }
        val p = m.probabilityPayment("GPAY", null, "Dear customer, ₹90 cashback credited to your wallet")!!
        assertTrue("p=$p", p < 0.05)
        val pay = m.probabilityPayment("GPAY", null, "You paid ₹45 to Chai Point")!!
        assertTrue("p=$pay", pay > 0.9)
    }

    @Test fun classifierWaitsForEnoughExamples() {
        val m = ParserModel()
        m.learnNotPayment("GPAY", null, "Statement points ₹999 credited")
        assertNull(m.probabilityPayment(setOf("statement")))
    }

    @Test fun moneyThatDidntMoveIsNeverRescued() {
        val m = ParserModel()
        m.confirm("GPAY", null, "You paid ₹66 to Rapido", 6600, false, "Rapido")
        assertEquals(Decision.Drop, m.decide("GPAY", null, "Payment of ₹66 to Rapido failed", null))
        assertEquals(Decision.Drop, m.decide("GPAY", null, "Your OTP is 482913 for paying ₹66", null))
        assertEquals(Decision.Drop, m.decide("GPAY", null, "Rapido requested ₹66 from you", null))
    }

    @Test fun undoingNotAPaymentForgetsIt() {
        val m = ParserModel()
        val junk = "Rs 299 paid for your Jio number via UPI"
        repeat(2) { m.learnNotPayment("SMS", "JM-JIOINF", junk) }
        m.unlearnNotPayment("SMS", "JM-JIOINF", junk)
        assertFalse(m.draft("SMS", "JM-JIOINF", junk, sms = true)!!.hidden)
    }

    // ---------------- persistence & privacy

    @Test fun savedModelBehavesTheSame() {
        val m = ParserModel()
        m.confirm("AMAZON", null, "Order ₹1,299 · You paid ₹999 to Croma", 99900, false, "Croma")
        repeat(2) { m.learnNotPayment("SMS", "JM-JIOINF", "Rs 299 paid for your Jio number via UPI") }
        val back = ParserModel.decode(m.encode())
        assertEquals(m.encode().lines().toSet(), back.encode().lines().toSet())
        assertEquals(45000L, back.draft("AMAZON", null, "Order ₹500 · You paid ₹450 to Flipkart")!!.payment.amountPaise)
    }

    @Test fun savedModelHoldsNoNamesOrAmounts() {
        val m = ParserModel()
        m.confirm("GPAY", "RICHA KHATRI paid you ₹1,234.00", "Sent using Paytm UPI", 123400, true, "Richa")
        m.learnNotPayment("SMS", "AX-HDFCBK", "Rs 5,678 credited to A/c XX9012 by NEFT from ACME CORP")
        val saved = m.encode().lowercase()
        listOf("richa", "khatri", "1234", "1,234", "5678", "9012", "acme", "hdfcbk").forEach {
            assertFalse("leaked '$it':\n$saved", saved.contains(it))
        }
    }
}
