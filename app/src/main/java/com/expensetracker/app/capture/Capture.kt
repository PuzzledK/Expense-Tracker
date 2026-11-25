package com.expensetracker.app.capture

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
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
    /** How it was read: regex rules, a format you taught, or rescued as unsure (see [ParserModel]). */
    val origin: ParserModel.Origin = ParserModel.Origin.REGEX,
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

    private val _hidden = MutableStateFlow<List<CapturedPayment>>(emptyList())
    /** Drafts the learning model filtered out as not payments (last 7 days), newest first. */
    val hidden: StateFlow<List<CapturedPayment>> = _hidden

    /** "12 formats learned from 40 reviews"; refreshed with the lists. */
    data class LearningStats(val formats: Int, val examples: Int)
    private val _learning = MutableStateFlow(LearningStats(0, 0))
    val learning: StateFlow<LearningStats> = _learning

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
        _hidden.value = d.hidden()
        ParserLearning.model(ctx).let { _learning.value = LearningStats(it.formatsLearned, it.examples) }
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
        // Text that isn't a payment is dropped here and never stored. The regex rules read it first;
        // the learning model can correct that reading, rescue a rejected one, or hide junk.
        val sms = source.group == SourceGroup.SMS
        val regex = PaymentParser.parse(title, text, requireBankContext = sms)
        val decision = try {
            ParserLearning.model(ctx).decide(source.group.name, title, text, regex, requireBankContext = sms)
        } catch (e: Exception) {
            Log.w(TAG, "learning model failed; using the regex reading", e)
            regex?.let { ParserModel.Decision.Draft(it, ParserModel.Origin.REGEX, hidden = false) } ?: ParserModel.Decision.Drop
        }
        if (decision !is ParserModel.Decision.Draft) return
        val parsed = decision.payment
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
        val id = d.insert(parsed, atMillis, pkg, source.label, raw, fp, decision.origin, decision.hidden)
        if (decision.hidden) {
            reloadNow(ctx, checkManual = false)
            return
        }
        // Already logged by hand? Keep a (text-less) dismissed row so the bank SMS etc. merge into it.
        if (CaptureDedupe.matchesManual(manualExpenses(ctx, d), parsed.amountPaise, parsed.isCredit, atMillis)) {
            d.setStatus(id, CaptureDb.Status.DISMISSED)
            return
        }
        reloadNow(ctx, checkManual = false)
        if (CapturePrefs.notifyOnCapture(ctx)) CaptureNotifier.show(ctx, _pending.value, newest = d.get(id)?.payment)
    }

    /**
     * Mark confirmed after the expense was saved; [expenseCreatedAt] is the saved expense's createdAt.
     * [amountPaise], [isIncome] and [note] are what you saved: the parser learns from any difference.
     */
    fun confirm(ctx: Context, p: CapturedPayment, expenseCreatedAt: Long, amountPaise: Long = p.amountPaise, isIncome: Boolean = p.isIncome, note: String = p.payee) {
        _pending.value = _pending.value.filterNot { it.id == p.id }
        val app = ctx.applicationContext
        io.execute {
            safely { ParserLearning.learnPayment(app, p, amountPaise, isIncome, note) }
            safely { db(app).setStatus(p.id, CaptureDb.Status.CONFIRMED, expenseCreatedAt); reloadNow(app, checkManual = false) }
        }
    }

    /** [notPayment] = you said it isn't a payment at all (teaches the model); false = duplicate / already logged. */
    fun dismiss(ctx: Context, p: CapturedPayment, notPayment: Boolean = false) {
        _pending.value = _pending.value.filterNot { it.id == p.id }
        val app = ctx.applicationContext
        io.execute {
            if (notPayment) safely { ParserLearning.learnNotPayment(app, p) }
            safely { db(app).setStatus(p.id, CaptureDb.Status.DISMISSED); reloadNow(app, checkManual = false) }
        }
    }

    /** Undo for [dismiss]; [p] still carries the text, so it comes back as it was. */
    fun undoDismiss(ctx: Context, p: CapturedPayment, notPayment: Boolean = false) {
        val app = ctx.applicationContext
        io.execute {
            if (notPayment) safely { ParserLearning.unlearnNotPayment(app, p) }
            safely { db(app).restore(p); reloadNow(app, checkManual = false) }
        }
    }

    /** "It's a payment": a hidden draft goes back to the inbox (you then confirm it, which teaches the model). */
    fun unhide(ctx: Context, p: CapturedPayment) {
        _hidden.value = _hidden.value.filterNot { it.id == p.id }
        val app = ctx.applicationContext
        io.execute { safely { db(app).restore(p); reloadNow(app, checkManual = false) } }
    }

    /** Forget everything the parser learned (formats and the payment classifier). */
    fun resetLearning(ctx: Context) {
        val app = ctx.applicationContext
        io.execute { safely { ParserLearning.reset(app); reloadNow(app, checkManual = false) } }
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

/**
 * Keeps the notification listener bound. Android kills the app's process on every update, and
 * many phones (ColorOS, OxygenOS, MIUI…) then never rebind the listener: Settings still says
 * "allowed" but nothing arrives. We ask for a rebind whenever the app starts or is updated, and
 * if that doesn't connect within a few seconds, flip the service component off and on, which
 * makes the system bind it afresh.
 */
object CaptureListener {
    private val _connected = MutableStateFlow(false)
    /** True while Android has the listener bound and delivering notifications. */
    val connected: StateFlow<Boolean> = _connected

    internal fun setConnected(on: Boolean) { _connected.value = on }

    fun granted(ctx: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    /** Call on app start/resume, boot and after an update. No-op when capture is off or access isn't granted. */
    fun ensureRunning(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        if (!CapturePrefs.captureEnabled(app) || !granted(app)) return
        if (_connected.value && !force) return
        val cn = ComponentName(app, PaymentListenerService::class.java)
        try { NotificationListenerService.requestRebind(cn) } catch (e: Exception) { Log.w(TAG, "rebind failed", e) }
        Handler(Looper.getMainLooper()).postDelayed({
            if (_connected.value) return@postDelayed
            try {
                val pm = app.packageManager
                pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                NotificationListenerService.requestRebind(cn)
            } catch (e: Exception) { Log.w(TAG, "component toggle failed", e) }
        }, 4_000)
    }
}

/** Declared in the manifest; needs the user to grant notification access. */
/** Debug-build logging switch for the listener (release builds stay quiet). */
internal object BuildConfigDebug { @JvmField var on = false }

private const val TAG = "PaymentListener"

class PaymentListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        BuildConfigDebug.on = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        CaptureListener.setConnected(true)
        if (BuildConfigDebug.on) Log.d(TAG, "listener connected")
    }

    override fun onListenerDisconnected() {
        CaptureListener.setConnected(false)
        if (BuildConfigDebug.on) Log.d(TAG, "listener disconnected")
        // Ask to be bound again (e.g. after the system dropped us under memory pressure).
        try { requestRebind(ComponentName(this, PaymentListenerService::class.java)) } catch (_: Exception) { }
    }


    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handle(sbn)
        } catch (e: Exception) {
            Log.w("PaymentListener", "skipped a notification", e)
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        val ctx = applicationContext
        fun skip(why: String) { if (Log.isLoggable(TAG, Log.DEBUG) || BuildConfigDebug.on) Log.d(TAG, "skip ${sbn.packageName}: $why") }
        if (!CapturePrefs.captureEnabled(ctx)) return skip("capture off")
        val pkg = sbn.packageName ?: return
        if (pkg == ctx.packageName) return
        // Only allow-listed payment/bank apps and SMS apps; everything else is never even read.
        val defaultSms = try { Telephony.Sms.getDefaultSmsPackage(ctx) } catch (_: Exception) { null }
        val source = CaptureSources.of(pkg, defaultSms) ?: return
        if (!CapturePrefs.appEnabled(ctx, source.group)) return skip("source ${source.group} off")
        // With SMS access the receiver reads the full SMS; the SMS app's notification would be a
        // duplicate (and on Android 15+ its text is hidden from us anyway).
        if (source.group == SourceGroup.SMS && SmsAccess.granted(ctx)) return skip("sms read directly")

        val n = sbn.notification ?: return skip("no notification")
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return skip("group summary")
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return skip("ongoing")
        val ex = n.extras ?: return skip("no extras")
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        if (title.isNullOrBlank() && text.isNullOrBlank()) return skip("empty")
        skip("-> ingest (title=$title, ${text?.length} chars)")

        // `when` is the payment/SMS time; fall back to post time if it looks wrong.
        val posted = sbn.postTime
        val at = n.`when`.takeIf { it in (posted - 86_400_000L)..(posted + 60_000L) } ?: posted
        CaptureStore.ingest(ctx, pkg, source, title, text, at)
    }
}
