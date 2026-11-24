package com.expensetracker.app.capture

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.expensetracker.app.MainActivity
import com.expensetracker.app.R
import com.expensetracker.app.ui.formatRupees

/** Optional quiet "₹142 to Zomato captured — tap to review" notification (own low-priority channel). */
object CaptureNotifier {
    const val CHANNEL_ID = "capture"
    private const val NOTIFICATION_ID = 4242

    /** Intent extra MainActivity should handle by opening the capture inbox (Overlay.CAPTURE). */
    const val EXTRA_OPEN_CAPTURE = "open_capture"

    internal fun show(ctx: Context, pending: List<CapturedPayment>, newest: CapturedPayment?) {
        if (pending.isEmpty() || newest == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Captured payments", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "A quiet note when a UPI/bank payment was captured for review"
                    setShowBadge(true)
                },
            )
        }
        // With app lock on, don't show amounts/payees on the lock screen or shade.
        val hide = CapturePrefs.appLock(ctx)
        val line = when {
            hide -> "Payment captured — tap to review"
            newest.isIncome -> "${formatRupees(newest.amountPaise)} from ${newest.payee} captured — tap to review"
            else -> "${formatRupees(newest.amountPaise)} to ${newest.payee} captured — tap to review"
        }
        val title = if (pending.size == 1) "Payment to review" else "${pending.size} payments to review"
        val open = PendingIntent.getActivity(
            ctx,
            NOTIFICATION_ID,
            Intent(ctx, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_CAPTURE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(line)
            .setNumber(pending.size)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(if (hide) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { nm.notify(NOTIFICATION_ID, n) } catch (_: SecurityException) { }
    }

    /** Clears the notification once nothing is left to review. */
    internal fun sync(ctx: Context, pending: List<CapturedPayment>) {
        if (pending.isEmpty()) ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}
