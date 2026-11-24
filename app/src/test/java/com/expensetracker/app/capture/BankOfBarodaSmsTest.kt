package com.expensetracker.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bank SMS that use "Dr."/"Cr." abbreviations instead of "debited"/"credited" (Bank of Baroda and others). */
class BankOfBarodaSmsTest {
    private fun sms(text: String, title: String? = "BOBTXN") = PaymentParser.parse(title, text, requireBankContext = true)

    @Test fun userReported_bobUpiDebitToPhoneNumberVpa() {
        // Exact SMS the user received that the app missed.
        val r = sms("Rs.1.00 Dr. from A/C XXXXXX6932 and Cr. to 8890193683@pthdfc. Ref:669540404753. AvlBal:Rs19959.64(2026:11:25 01:43:21). Not you? Call 18005700/5000-BOB")
        assertNotNull(r)
        assertEquals(100L, r!!.amountPaise)
        assertFalse("money left the user's account", r.isCredit)
        assertEquals("8890193683@pthdfc", r.payee)          // no name available → keep the UPI ID, not "Unknown"
        assertEquals(PayeeQuality.VPA, r.payeeQuality)
        assertEquals("XXXXXX6932", r.account)
    }

    @Test fun bobDebitToMerchantVpa_usesName() {
        val r = sms("Rs.142.00 Dr. from A/C XXXXXX6932 and Cr. to zomato@hdfcbank. Ref:669540404999. AvlBal:Rs19817.64(2026:11:25 13:05:11). Not you? Call 18005700/5000-BOB")
        assertEquals(14200L, r!!.amountPaise)
        assertFalse(r.isCredit)
        assertEquals("Zomato", r.payee)
    }

    @Test fun bobCredit_crToYourAccount() {
        val r = sms("Rs.500.00 Cr. to A/C XXXXXX6932 and Dr. from richa.s@okaxis. Ref:669540405555. AvlBal:Rs20317.64(2026:11:25 18:30:02)-BOB")
        assertNotNull(r)
        assertEquals(50000L, r!!.amountPaise)
        assertTrue(r.isCredit)
        assertEquals("Richa", r.payee)   // single letters in UPI IDs are dropped
    }

    @Test fun bobCreditedThruUpi_balanceWithCrSuffixIsIgnored() {
        val r = sms("Rs.500.00 Credited to A/C XXXXXX6932 thru UPI/669540404753 by richa@okaxis. Total Bal:Rs.20459.64CR. Avlbl Amt:Rs.20459.64(25-11-2026 01:43:21) - Bank of Baroda")
        assertEquals(50000L, r!!.amountPaise)
        assertTrue(r.isCredit)
    }

    @Test fun drCrWithoutDots_andLowercase() {
        val r = sms("INR 250 dr from a/c xx6932 cr to rapido@ybl ref 123456789012", title = "VM-BOBSMS")
        assertEquals(25000L, r!!.amountPaise)
        assertFalse(r.isCredit)
        assertEquals("Rapido", r.payee)
    }

    @Test fun drCrStillRespectsIgnoreRules() {
        assertNull(sms("Rs.1.00 Dr. from A/C XXXXXX6932 failed. Ref:669540404753. Not you? Call 18005700-BOB"))
        assertNull(sms("Your OTP for Rs.1.00 Dr. from A/C XXXXXX6932 is 482913 -BOB"))
    }

    @Test fun doctorInMerchantNameIsNotACreditSignal() {
        // "Dr." as a title after "paid to" must not flip direction or break parsing.
        val r = PaymentParser.parse(null, "You paid ₹500 to Dr. Mehta Clinic", requireBankContext = false)
        assertFalse(r!!.isCredit)
        assertEquals(50000L, r.amountPaise)
    }

    @Test fun numericSenderIsNotAnAmount() {
        // Found on the emulator: sender "5003" + "Rs.249.00 …" was read as ₹5,003.
        for (sender in listOf("5003", "56767", "+919876543210", "VM-BOBTXN")) {
            val r = sms("Rs.249.00 Dr. from A/C XXXXXX6932 and Cr. to zomato@hdfcbank. Ref:669540407777. AvlBal:Rs19502.64(2026:11:25 14:30:00). Not you? Call 18005700/5000-BOB", title = sender)
            assertEquals("sender $sender", 24900L, r!!.amountPaise)
        }
    }

    @Test fun amountBeforeCurrencyStillWorksOnOneLine() {
        val r = PaymentParser.parse("HDFC Bank", "500 Rs debited from A/c XX1234 to rapido@ybl", requireBankContext = true)
        assertEquals(50000L, r!!.amountPaise)
    }
}
