package com.expensetracker.app.capture

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Bank SMS straight from Android, not from the SMS app's notification.
 *
 * Why: from Android 15, the system marks bank/OTP-like SMS notifications as "sensitive" and
 * third-party notification listeners only get "Sensitive notification content hidden" — so
 * notification capture can't see them. With the RECEIVE_SMS permission the app gets the full
 * text of each incoming SMS. Only messages that parse as a bank payment are kept (the same
 * parser + bank-context check as SMS notifications); everything else is ignored, never stored.
 */
object SmsAccess {
    fun granted(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    /** Same group/label as SMS-app notifications so settings and dedupe treat them alike. */
    internal val SOURCE = CaptureSource(SourceGroup.SMS, "SMS")
    internal const val PKG = "android.sms"
}

class SmsCaptureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val ctx = context.applicationContext
        if (!CapturePrefs.captureEnabled(ctx) || !CapturePrefs.appEnabled(ctx, SourceGroup.SMS)) return
        try {
            val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
            // A long SMS arrives in several parts; join them per sender.
            parts.filterNotNull()
                .groupBy { it.displayOriginatingAddress.orEmpty() }
                .forEach { (sender, msgs) ->
                    val body = msgs.joinToString("") { it.displayMessageBody.orEmpty() }
                    val at = msgs.minOf { it.timestampMillis }.takeIf { it > 0 } ?: System.currentTimeMillis()
                    CaptureStore.ingest(ctx, SmsAccess.PKG, SmsAccess.SOURCE, sender, body, at)
                }
        } catch (e: Exception) {
            Log.w("SmsCapture", "couldn't read SMS", e)
        }
    }
}
