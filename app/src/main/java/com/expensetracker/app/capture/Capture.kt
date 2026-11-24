package com.expensetracker.app.capture

import android.app.Notification
import android.content.Context
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.expensetracker.app.data.ExpenseDb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

/** A payment seen in a notification, waiting for you to confirm, edit or dismiss. */
data class CapturedPayment(
    val id: Long,
    val payee: String,
    val amountPaise: Long,
    val atMillis: Long,
    /** "Google Pay", or "Google Pay + Messages" when several notifications were merged. */
    val sourceApp: String,
    /** Text of the matched notification(s); empty once confirmed/dismissed. */
    val rawText: String,
    /** Money received (credit / refund) → saved as income. */
    val isIncome: Boolean = false,
    val sourcePackage: String = "",
    /** Masked account/card ("XX1234") if the notification named one. */
    val account: String? = null,
)

/**
 * Holds drafts in `capture.db` (see [CaptureDb]) and exposes the pending ones live.
 * The listener runs in the app process, so [pending] updates while the app is open.
 * All disk work happens on one background thread, in order.
 */
object CaptureStore {
    private const val TAG = "CaptureStore"
    internal const val KEEP_MS = 30L * 24 * 3_600_000

    private val _pending = MutableStateFlow<List<CapturedPayment>>(emptyList())
    /** Payments awaiting review, newest first. */
    val pending: StateFlow<List<CapturedPayment>> = _pending

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "capture-io").apply { isDaemon = true } }
    @Volatile private var db: CaptureDb? = null

    private fun db(ctx: Context): CaptureDb =
        db ?: synchronized(this) { db ?: CaptureDb(ctx.applicationContext).also { db = it } }

    /** Reload from storage (call on app resume). Also drops drafts you've since logged by hand. */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        io.execute { safely { reloadNow(app, checkManual = true) } }
    }

    private fun reloadNow(ctx: Context, checkManual: Boolean) {
        val d = db(ctx)
        d.purge(KEEP_MS)
        var list = d.pending()
        if (checkManual && list.isNotEmpty()) {
            val manual = manualExpenses(ctx, d)
            val logged = list.filter { CaptureDedupe.matchesManual(manual, it.amountPaise, it.isIncome, it.atMillis) }
            if (logged.isNotEmpty()) {
                logged.forEach { d.setStatus(it.id, CaptureDb.Status.DISMISSED) }
                list = d.pending()
            }
        }
        _pending.value = list
        CaptureNotifier.sync(ctx, list)
    }

    /** Your expenses, minus ones that were saved from capture drafts. */
    private fun manualExpenses(ctx: Context, d: CaptureDb) = try {
        val stamps = d.confirmedExpenseStamps()
        val edb = ExpenseDb(ctx)
        try { edb.all().filter { it.createdAt !in stamps } } finally { edb.close() }
    } catch (e: Exception) {
        Log.w(TAG, "couldn't read expenses", e)
        emptyList()
    }

    /** From [PaymentListenerService]: parse, dedupe and store. Runs off the main thread. */
    internal fun ingest(ctx: Context, pkg: String, source: CaptureSource, title: String?, text: String?, atMillis: Long) {
        val app = ctx.applicationContext
        io.execute { safely { ingestNow(app, pkg, source, title, text, atMillis) } }
    }

    private fun ingestNow(ctx: Context, pkg: String, source: CaptureSource, title: String?, text: String?, atMillis: Long) {
        // Text that doesn't parse as a payment is dropped here and never stored.
        val parsed = PaymentParser.parse(title, text, requireBankContext = source.group == SourceGroup.SMS) ?: return
        val d = db(ctx)
        val fp = CaptureDedupe.fingerprint(pkg, title, text)
        val nearby = d.around(atMillis - CaptureDedupe.WINDOW_MS, atMillis + CaptureDedupe.WINDOW_MS, fp)
        val candidate = DraftKey(-1, parsed.amountPaise, atMillis, parsed.isCredit, parsed.payee, pkg, fp)
        val raw = listOfNotNull(title?.trim()?.takeIf { it.isNotEmpty() }, text?.trim()).joinToString("\n").take(1000)

        val dup = CaptureDedupe.findDuplicate(nearby.map { it.toKey() }, candidate)
        if (dup != null) {
            val row = nearby.first { it.payment.id == dup.id }
            if (dup.fingerprint != fp) d.merge(row, parsed, source.label, raw)
            reloadNow(ctx, checkManual = false)
            return
        }
        val id = d.insert(parsed, atMillis, pkg, source.label, raw, fp)
        // Already logged by hand? Keep a (text-less) dismissed row so the bank SMS etc. merge into it.
        if (CaptureDedupe.matchesManual(manualExpenses(ctx, d), parsed.amountPaise, parsed.isCredit, atMillis)) {
            d.setStatus(id, CaptureDb.Status.DISMISSED)
            return
        }
        reloadNow(ctx, checkManual = false)
        if (CapturePrefs.notifyOnCapture(ctx)) CaptureNotifier.show(ctx, _pending.value, newest = d.get(id)?.payment)
    }

    /** Mark confirmed after the expense was saved; [expenseCreatedAt] is the saved expense's createdAt. */
    fun confirm(ctx: Context, p: CapturedPayment, expenseCreatedAt: Long) {
        _pending.value = _pending.value.filterNot { it.id == p.id }
        val app = ctx.applicationContext
        io.execute { safely { db(app).setStatus(p.id, CaptureDb.Status.CONFIRMED, expenseCreatedAt); reloadNow(app, checkManual = false) } }
    }

    fun dismiss(ctx: Context, p: CapturedPayment) {
        _pending.value = _pending.value.filterNot { it.id == p.id }
        val app = ctx.applicationContext
        io.execute { safely { db(app).setStatus(p.id, CaptureDb.Status.DISMISSED); reloadNow(app, checkManual = false) } }
    }

    /** Undo for [dismiss]; [p] still carries the text, so it comes back as it was. */
    fun undoDismiss(ctx: Context, p: CapturedPayment) {
        val app = ctx.applicationContext
        io.execute { safely { db(app).restore(p); reloadNow(app, checkManual = false) } }
    }

    private inline fun safely(block: () -> Unit) {
        try { block() } catch (e: Exception) { Log.e(TAG, "capture store error", e) }
    }
}

/** Capture-only preferences (the on/off switch itself is `Settings.captureEnabled`). */
internal object CapturePrefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("capture", Context.MODE_PRIVATE)

    /** Read straight from the app's settings file so the listener doesn't need the ViewModel. */
    fun captureEnabled(ctx: Context) =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("captureEnabled", false)

    fun appLock(ctx: Context) =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("appLock", false)

    fun appEnabled(ctx: Context, g: SourceGroup) = prefs(ctx).getBoolean("app_${g.name}", true)
    fun setAppEnabled(ctx: Context, g: SourceGroup, on: Boolean) = prefs(ctx).edit().putBoolean("app_${g.name}", on).apply()

    fun notifyOnCapture(ctx: Context) = prefs(ctx).getBoolean("notify", true)
    fun setNotifyOnCapture(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("notify", on).apply()
}

/** Declared in the manifest; needs the user to grant notification access. */
class PaymentListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handle(sbn)
        } catch (e: Exception) {
            Log.w("PaymentListener", "skipped a notification", e)
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        val ctx = applicationContext
        if (!CapturePrefs.captureEnabled(ctx)) return
        val pkg = sbn.packageName ?: return
        if (pkg == ctx.packageName) return
        // Only allow-listed payment/bank apps and SMS apps; everything else is never even read.
        val defaultSms = try { Telephony.Sms.getDefaultSmsPackage(ctx) } catch (_: Exception) { null }
        val source = CaptureSources.of(pkg, defaultSms) ?: return
        if (!CapturePrefs.appEnabled(ctx, source.group)) return

        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        // `when` is the payment/SMS time; fall back to post time if it looks wrong.
        val posted = sbn.postTime
        val at = n.`when`.takeIf { it in (posted - 86_400_000L)..(posted + 60_000L) } ?: posted
        CaptureStore.ingest(ctx, pkg, source, title, text, at)
    }
}
