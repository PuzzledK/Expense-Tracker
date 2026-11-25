package com.expensetracker.app.capture

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Keeps the [ParserModel] in app-private storage (`files/capture_parser_model.txt`) and feeds it
 * your review decisions. Called only from [CaptureStore]'s single background thread.
 */
internal object ParserLearning {
    private const val TAG = "ParserLearning"
    private const val FILE = "capture_parser_model.txt"
    @Volatile private var model: ParserModel? = null

    fun model(ctx: Context): ParserModel = model ?: synchronized(this) {
        model ?: run {
            val f = File(ctx.filesDir, FILE)
            try { if (f.exists()) ParserModel.decode(f.readText()) else ParserModel() } catch (e: Exception) {
                Log.w(TAG, "couldn't read the parser model; starting fresh", e)
                ParserModel()
            }
        }.also { model = it }
    }

    private fun save(ctx: Context) {
        val m = model ?: return
        try {
            val f = File(ctx.filesDir, FILE)
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.writeText(m.encode())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) {
            Log.w(TAG, "couldn't save the parser model", e)
        }
    }

    /** The source group a stored draft came from (its first notification's app). */
    fun groupOf(pkg: String): String = when (pkg) {
        SmsAccess.PKG -> SourceGroup.SMS.name
        else -> CaptureSources.of(pkg)?.group?.name ?: SourceGroup.SMS.name // unknown = the phone's SMS app
    }

    /** The first notification's title/text from a draft's stored text (merged extras follow "— label —"). */
    fun primaryText(raw: String): Pair<String?, String>? {
        val first = raw.substringBefore("\n— ").trim()
        if (first.isEmpty()) return null
        val title = first.substringBefore('\n', "").takeIf { '\n' in first }
        val text = if ('\n' in first) first.substringAfter('\n') else first
        return title to text
    }

    fun learnPayment(ctx: Context, p: CapturedPayment, amountPaise: Long, isIncome: Boolean, note: String) {
        val (title, text) = primaryText(p.rawText) ?: return
        val shown = ParsedPayment(p.amountPaise, p.payee, p.isIncome, PayeeQuality.UNKNOWN)
        model(ctx).learnPayment(groupOf(p.sourcePackage), title, text, amountPaise, isIncome, note, shown)
        save(ctx)
    }

    fun learnNotPayment(ctx: Context, p: CapturedPayment) {
        val (title, text) = primaryText(p.rawText) ?: return
        model(ctx).learnNotPayment(groupOf(p.sourcePackage), title, text)
        save(ctx)
    }

    fun unlearnNotPayment(ctx: Context, p: CapturedPayment) {
        val (title, text) = primaryText(p.rawText) ?: return
        model(ctx).unlearnNotPayment(groupOf(p.sourcePackage), title, text)
        save(ctx)
    }

    fun reset(ctx: Context) {
        model(ctx).clear()
        save(ctx)
    }
}
