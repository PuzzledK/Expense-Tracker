package com.expensetracker.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentParserTest {

    private fun p(title: String?, text: String?, sms: Boolean = false) = PaymentParser.parse(title, text, requireBankContext = sms)

    private fun debit(title: String?, text: String, paise: Long, payee: String, sms: Boolean = false) {
        val r = p(title, text, sms)
        assertNotNull("should parse: $text", r)
        assertEquals("amount for: $text", paise, r!!.amountPaise)
        assertFalse("direction for: $text", r.isCredit)
        assertEquals("payee for: $text", payee, r.payee)
    }

    private fun credit(title: String?, text: String, paise: Long, payee: String, sms: Boolean = false) {
        val r = p(title, text, sms)
        assertNotNull("should parse: $text", r)
        assertEquals("amount for: $text", paise, r!!.amountPaise)
        assertTrue("direction for: $text", r.isCredit)
        assertEquals("payee for: $text", payee, r.payee)
    }

    private fun ignored(title: String?, text: String, sms: Boolean = false) =
        assertNull("should ignore: $text", p(title, text, sms))

    // ---------------- UPI apps: debits ----------------

    @Test fun gpayYouPaid() = debit(null, "You paid ₹66 to Rapido", 6600, "Rapido")
    @Test fun gpayTitleAmountPaidTo() = debit("₹249 paid to Zomato", "Paid via HDFC Bank ••1234", 24900, "Zomato")
    @Test fun gpaySentToPerson() = debit(null, "Sent ₹1,200 to Anu Sharma", 120000, "Anu Sharma")
    @Test fun gpayTitleIsPayee() = debit("Rapido", "You paid ₹66", 6600, "Rapido")
    @Test fun phonePePaidSuccessfully() = debit("Payment Successful", "Paid ₹142 to ZOMATO LIMITED successfully", 14200, "Zomato Limited")
    @Test fun phonePeVpaPayee() = debit("Payment Successful", "Paid ₹45 to swiggy.stores@icici", 4500, "Swiggy Stores")
    @Test fun paytmRsDot() = debit(null, "Paid Rs.66 to Rapido from Paytm UPI", 6600, "Rapido")
    @Test fun paytmSentSuccessfully() = debit("Money Sent", "Rs.350 sent to Ramesh Kumar successfully via UPI", 35000, "Ramesh Kumar")
    @Test fun bhimPaid() = debit("BHIM", "You have successfully paid Rs. 50.00 to SHARMA STORES", 5000, "Sharma Stores")
    @Test fun amazonPayPayment() = debit("Amazon Pay", "Payment of ₹299 to Swiggy was successful", 29900, "Swiggy")
    @Test fun credCardBill() = debit("CRED", "payment of ₹2,345.50 to HDFC Bank credit card successful", 234550, "HDFC Bank credit card")
    @Test fun decimalsAndLakhGrouping() = debit(null, "You paid ₹1,23,456.75 to Brigade Builders", 12345675, "Brigade Builders")
    @Test fun singleDecimalDigit() = debit(null, "You paid ₹12.5 to Chai Point", 1250, "Chai Point")
    @Test fun inrPrefix() = debit("Payment successful", "INR 800 paid to Dr Mehta Clinic", 80000, "Dr Mehta Clinic")

    // ---------------- UPI apps: credits ----------------

    @Test fun receivedFrom() = credit(null, "₹500 received from Richa", 50000, "Richa")
    @Test fun gpayPaidYouTitle() = credit("Richa Sharma", "Paid you ₹500", 50000, "Richa Sharma")
    @Test fun sentYou() = credit(null, "Mammi sent you ₹5,000", 500000, "Mammi")
    @Test fun refundFromMerchant() = credit("Refund", "Refund of ₹142 from Zomato has been credited to your account", 14200, "Zomato")

    // ---------------- Bank SMS ----------------

    @Test fun genericBankDebitToVpa() =
        debit("AX-HDFCBK", "Rs 142.00 debited from A/c XX1234 to VPA zomato@icici on 24-11-26", 14200, "Zomato", sms = true)

    @Test fun hdfcMultilineSent() = debit(
        "VM-HDFCBK",
        "Sent Rs.142.00\nFrom HDFC Bank A/C *1234\nTo ZOMATO\nOn 24/11/26\nRef 432112345678\nNot You?\nCall 18002586161/SMS BLOCK UPI to 7308080808",
        14200, "Zomato", sms = true,
    )

    @Test fun sbiDebitedByNoCurrency() = debit(
        "JD-SBIUPI",
        "Dear UPI user A/C X1234 debited by 142.0 on date 24Nov26 trf to ZOMATO Refno 432112345678. If not u? call 1800111109. -SBI",
        14200, "Zomato", sms = true,
    )

    @Test fun iciciSemicolonCredited() = debit(
        "AD-ICICIB",
        "ICICI Bank Acct XX234 debited for Rs 142.00 on 24-Nov-26; ZOMATO credited. UPI:432112345678. Call 18002662 for dispute. SMS BLOCK 234 to 9215676766.",
        14200, "Zomato", sms = true,
    )

    @Test fun axisUpiInfo() = debit(
        "AX-AXISBK",
        "INR 142.00 debited\nA/c no. XX1234\n24-11-26, 13:05:11\nUPI/P2M/432112345678/ZOMATO LTD\nNot you? SMS BLOCKUPI Cust ID to 919951860002\nAxis Bank",
        14200, "Zomato", sms = true,
    )

    @Test fun kotakSentToVpa() = debit(
        "BP-KOTAKB",
        "Sent Rs.66.00 from Kotak Bank AC X1234 to rapido.bike@axisbank on 24-11-26.UPI Ref 432112345678. Not you, https://kotak.com/KBANKT/Fraud",
        6600, "Rapido Bike", sms = true,
    )

    @Test fun cardSpendAtMerchantIgnoresBalance() = debit(
        "VK-HDFCBK",
        "Rs 1,499.00 spent on HDFC Bank Card XX1234 at AMAZON on 2026-11-24. Avl Lmt: Rs 85,000",
        149900, "Amazon", sms = true,
    )

    @Test fun balanceBeforeAmountIsSkipped() {
        val r = p("AX-SBIINB", "Avl Bal Rs 5,000.00. Your A/c X1234 debited Rs 300 to VPA chaiwala@ybl", sms = true)
        assertNotNull(r)
        assertEquals(30000, r!!.amountPaise)
        assertEquals("Chaiwala", r.payee)
    }

    @Test fun bankCreditFromVpa() = credit(
        "AD-HDFCBK",
        "Received Rs.500.00 in your HDFC Bank A/c xx1234 from richa.sharma@okaxis on 24-11-26 by UPI Ref No 432112345678",
        50000, "Richa Sharma", sms = true,
    )

    @Test fun sbiCreditFromName() = credit(
        "BX-SBIINB",
        "Dear SBI User, your A/c X1234-credited by Rs.500 on 24Nov26 transfer from RICHA SHARMA Ref No 432112345678 -SBI",
        50000, "Richa Sharma", sms = true,
    )

    @Test fun accountIsExtracted() {
        val r = p("AX-HDFCBK", "Rs 142.00 debited from A/c XX1234 to VPA zomato@icici on 24-11-26", sms = true)
        assertEquals("XX1234", r!!.account)
        assertEquals(PayeeQuality.VPA, r.payeeQuality)
    }

    @Test fun merchantQrVpaKeepsTheUpiIdAsLabel() {
        val r = p("JM-BOBTXN", "Rs.40 debited from A/c XX5678 to VPA paytmqr2810050501xyz@paytm on 24-11-26 UPI Ref 4321", sms = true)
        assertNotNull(r)
        assertEquals(4000, r!!.amountPaise)
        assertEquals("paytmqr2810050501xyz@paytm", r.payee)
        assertEquals(PayeeQuality.VPA, r.payeeQuality)
    }

    // ---------------- Things to ignore ----------------

    @Test fun ignoresOtp() = ignored("AX-HDFCBK", "123456 is your OTP for payment of Rs 142.00 to Zomato. Do not share it with anyone.", sms = true)
    @Test fun ignoresPromoCashback() = ignored("Google Pay", "Get flat ₹100 cashback on your first Zomato order!")
    @Test fun ignoresWonCashback() = ignored("Google Pay", "You've won ₹10 cashback")
    @Test fun ignoresOffer() = ignored("Paytm", "Pay electricity bill & win up to ₹500. Offer valid till Sunday")
    @Test fun ignoresLoanPromo() = ignored("CRED", "Pre-approved loan of ₹2,00,000 is waiting for you")
    @Test fun ignoresBalanceAlert() = ignored("AX-SBIINB", "Your A/c XX1234 balance is Rs 5,000.00 as on 24-11-26", sms = true)
    @Test fun ignoresAvailableBalanceOnly() = ignored("VM-ICICIB", "Avl Bal for A/c XX1234 is INR 12,345.00 on 24-Nov-26", sms = true)
    @Test fun ignoresFailed() = ignored("PhonePe", "Payment of ₹66 to Rapido failed. Money, if debited, will be refunded in 3-5 days")
    @Test fun ignoresPending() = ignored("Google Pay", "Your payment of ₹66 to Rapido is pending")
    @Test fun ignoresDeclined() = ignored("VK-HDFCBK", "Txn of Rs 500 on HDFC Bank Card XX1234 at AMAZON declined due to insufficient funds", sms = true)
    @Test fun ignoresCollectRequest() = ignored("Google Pay", "Richa has requested ₹500 from you")
    @Test fun ignoresCollectApprove() = ignored("PhonePe", "Approve collect request of ₹250 from Swiggy")
    @Test fun ignoresPaymentReminder() = ignored("Paytm", "Payment reminder: ₹499 for Netflix is due on 28 Nov")
    @Test fun ignoresBillDue() = ignored("CRED", "Your HDFC credit card bill of ₹12,340 is due tomorrow. Pay now")
    @Test fun ignoresAutopayUpcoming() = ignored("AX-HDFCBK", "Rs 199 will be debited from A/c XX1234 for Spotify mandate on 01-12-26", sms = true)
    @Test fun ignoresWalletTopUp() = ignored("Paytm", "₹500 added to your Paytm Wallet")
    @Test fun ignoresRefundInitiated() = ignored("Amazon Pay", "Refund of ₹299 initiated, it will be credited in 3-5 days")
    @Test fun ignoresFriendSmsWithoutBankContext() = ignored("Richa", "sent ₹500 to Anu for dinner", sms = true)
    @Test fun ignoresNoAmount() = ignored("PhonePe", "Payment successful")
    @Test fun ignoresEmpty() = ignored(null, "")

    // ---------------- Helpers ----------------

    @Test fun vpaNames() {
        assertEquals("Zomato", PaymentParser.vpaName("zomato@icici"))
        assertEquals("Anu Sharma", PaymentParser.vpaName("anu.sharma92@okaxis"))
        assertNull(PaymentParser.vpaName("9876543210@ybl"))
        assertNull(PaymentParser.vpaName("q123456789@ybl"))
    }

    @Test fun toPaise() {
        assertEquals(120000L, PaymentParser.toPaise("1,200"))
        assertEquals(14205L, PaymentParser.toPaise("142.05"))
        assertEquals(1250L, PaymentParser.toPaise("12.5"))
        assertEquals(14200L, PaymentParser.toPaise("142."))
    }

    @Test fun sources() {
        assertEquals(SourceGroup.GPAY, CaptureSources.of("com.google.android.apps.nbu.paisa.user")!!.group)
        assertEquals(SourceGroup.PHONEPE, CaptureSources.of("com.phonepe.app")!!.group)
        assertEquals(SourceGroup.SMS, CaptureSources.of("com.example.sms", defaultSmsPackage = "com.example.sms")!!.group)
        assertNull(CaptureSources.of("com.whatsapp"))
    }
}
