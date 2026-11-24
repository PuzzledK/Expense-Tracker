package com.expensetracker.app.capture

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.Composable
import com.expensetracker.app.ExpenseViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * STUBS — owned by the "capture" session (UPI/bank payment auto-capture).
 * Keep these names/signatures: they're referenced from the manifest, App.kt, HomeScreen.kt.
 */

/** A payment seen in a notification, waiting for you to confirm, edit or dismiss. */
data class CapturedPayment(
    val id: Long,
    val payee: String,
    val amountPaise: Long,
    val atMillis: Long,
    val sourceApp: String,
    val rawText: String,
)

object CaptureStore {
    private val _pending = MutableStateFlow<List<CapturedPayment>>(emptyList())
    /** Payments awaiting review, newest first. */
    val pending: StateFlow<List<CapturedPayment>> = _pending

    /** Reload from storage (call on app resume). */
    fun refresh(ctx: Context) = Unit
}

/** Declared in the manifest; needs the user to grant notification access. */
class PaymentListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) = Unit
}

/** Banner on Home: "3 payments to review". Renders nothing when there are none. */
@Composable
fun CaptureBanner(onOpen: () -> Unit) = Unit

/** Review inbox + capture settings (toggle, notification-access permission, which apps). */
@Composable
fun CaptureInboxScreen(vm: ExpenseViewModel, onBack: () -> Unit) = PlaceholderScreen("Auto-capture", onBack)
