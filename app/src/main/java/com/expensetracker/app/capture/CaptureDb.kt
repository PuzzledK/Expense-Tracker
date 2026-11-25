package com.expensetracker.app.capture

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Captured drafts live in their own on-device database (`capture.db`), separate from your expenses.
 * Only parsed fields are kept; the raw notification text is kept while a draft is pending (so you
 * can see what was matched) and wiped as soon as it's confirmed or dismissed. Resolved rows are
 * kept 30 days (without text) so a re-posted notification doesn't come back.
 */
internal class CaptureDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "capture.db", null, 2) {

    /** HIDDEN: the learning model is sure it's not a payment; kept (with text) for 7 days so you can restore it. */
    enum class Status(val code: Int) { PENDING(0), CONFIRMED(1), DISMISSED(2), HIDDEN(3) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE drafts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                payee TEXT NOT NULL,
                payee_quality INTEGER NOT NULL,
                amount_paise INTEGER NOT NULL,
                at_millis INTEGER NOT NULL,
                is_income INTEGER NOT NULL,
                source_pkg TEXT NOT NULL,
                source_label TEXT NOT NULL,
                account TEXT,
                raw_text TEXT,
                fingerprint TEXT NOT NULL,
                status INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                resolved_at INTEGER,
                expense_created_at INTEGER,
                origin INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX drafts_at ON drafts(at_millis)")
        db.execSQL("CREATE INDEX drafts_fp ON drafts(fingerprint)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE drafts ADD COLUMN origin INTEGER NOT NULL DEFAULT 0")
    }

    internal data class Row(val payment: CapturedPayment, val quality: PayeeQuality, val fingerprint: String, val status: Status)

    fun pending(): List<CapturedPayment> = query("status = ?", arrayOf(Status.PENDING.code.toString())).map { it.payment }

    fun hidden(): List<CapturedPayment> = query("status = ?", arrayOf(Status.HIDDEN.code.toString())).map { it.payment }

    /**
     * Drafts timed within [from]..[to], plus any with this fingerprint. Hidden ones only count as a
     * re-post of the same text, so a real payment never merges into something filtered as junk.
     */
    fun around(from: Long, to: Long, fingerprint: String): List<Row> =
        query(
            "((at_millis BETWEEN ? AND ?) AND status != ?) OR fingerprint = ?",
            arrayOf(from.toString(), to.toString(), Status.HIDDEN.code.toString(), fingerprint),
        )

    /** createdAt of expenses saved from confirmed drafts, so they aren't mistaken for manual entries. */
    fun confirmedExpenseStamps(): Set<Long> =
        readableDatabase.rawQuery("SELECT expense_created_at FROM drafts WHERE expense_created_at IS NOT NULL", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getLong(0)) }
        }

    fun get(id: Long): Row? = query("id = ?", arrayOf(id.toString())).firstOrNull()

    private fun query(where: String, args: Array<String>): List<Row> =
        readableDatabase.query("drafts", null, where, args, null, null, "at_millis DESC").use { c ->
            buildList { while (c.moveToNext()) add(c.toRow()) }
        }

    fun insert(
        p: ParsedPayment, atMillis: Long, pkg: String, label: String, rawText: String, fingerprint: String,
        origin: ParserModel.Origin = ParserModel.Origin.REGEX, hidden: Boolean = false,
    ): Long =
        writableDatabase.insertOrThrow("drafts", null, ContentValues().apply {
            put("payee", p.payee)
            put("payee_quality", p.payeeQuality.ordinal)
            put("amount_paise", p.amountPaise)
            put("at_millis", atMillis)
            put("is_income", if (p.isCredit) 1 else 0)
            put("source_pkg", pkg)
            put("source_label", label)
            put("account", p.account)
            put("raw_text", rawText)
            put("fingerprint", fingerprint)
            put("status", if (hidden) Status.HIDDEN.code else Status.PENDING.code)
            put("created_at", System.currentTimeMillis())
            put("origin", origin.code)
        })

    /** Folds a duplicate notification into [into]: better payee name, combined source label, extra text. */
    fun merge(into: Row, p: ParsedPayment, label: String, rawText: String) {
        val v = ContentValues()
        if (p.payeeQuality > into.quality) {
            v.put("payee", p.payee)
            v.put("payee_quality", p.payeeQuality.ordinal)
        }
        val labels = into.payment.sourceApp.split(" + ")
        if (label !in labels) v.put("source_label", (labels + label).joinToString(" + "))
        if (into.payment.account == null && p.account != null) v.put("account", p.account)
        if (into.status == Status.PENDING && rawText !in into.payment.rawText) {
            v.put("raw_text", (into.payment.rawText + "\n— " + label + " —\n" + rawText).take(2000))
        }
        if (v.size() > 0) writableDatabase.update("drafts", v, "id = ?", arrayOf(into.payment.id.toString()))
    }

    fun setStatus(id: Long, status: Status, expenseCreatedAt: Long? = null) {
        val v = ContentValues().apply {
            put("status", status.code)
            if (expenseCreatedAt != null) put("expense_created_at", expenseCreatedAt)
            if (status == Status.PENDING) putNull("resolved_at") else {
                put("resolved_at", System.currentTimeMillis())
                putNull("raw_text") // privacy: no notification text once you've dealt with it
            }
        }
        writableDatabase.update("drafts", v, "id = ?", arrayOf(id.toString()))
    }

    /** Undo of a dismiss: back to pending with its text restored. */
    fun restore(p: CapturedPayment) {
        val v = ContentValues().apply {
            put("status", Status.PENDING.code)
            putNull("resolved_at")
            put("raw_text", p.rawText)
        }
        writableDatabase.update("drafts", v, "id = ?", arrayOf(p.id.toString()))
    }

    /** Forget resolved drafts older than [keepMillis] (30 days), and hidden ones (with their text) after 7 days. */
    fun purge(keepMillis: Long) {
        val now = System.currentTimeMillis()
        val cutoff = (now - keepMillis).toString()
        writableDatabase.delete("drafts", "status != ? AND status != ? AND resolved_at < ?", arrayOf(Status.PENDING.code.toString(), Status.HIDDEN.code.toString(), cutoff))
        writableDatabase.delete("drafts", "status = ? AND created_at < ?", arrayOf(Status.HIDDEN.code.toString(), (now - HIDDEN_KEEP_MS).toString()))
    }

    companion object {
        const val HIDDEN_KEEP_MS = 7L * 24 * 3_600_000
    }

    fun deleteAll() {
        writableDatabase.delete("drafts", null, null)
    }

    private fun Cursor.toRow(): Row {
        val status = getInt(getColumnIndexOrThrow("status")).let { s -> Status.entries.first { it.code == s } }
        val accIdx = getColumnIndexOrThrow("account")
        val rawIdx = getColumnIndexOrThrow("raw_text")
        val p = CapturedPayment(
            id = getLong(getColumnIndexOrThrow("id")),
            payee = getString(getColumnIndexOrThrow("payee")),
            amountPaise = getLong(getColumnIndexOrThrow("amount_paise")),
            atMillis = getLong(getColumnIndexOrThrow("at_millis")),
            sourceApp = getString(getColumnIndexOrThrow("source_label")),
            rawText = if (isNull(rawIdx)) "" else getString(rawIdx),
            isIncome = getInt(getColumnIndexOrThrow("is_income")) == 1,
            sourcePackage = getString(getColumnIndexOrThrow("source_pkg")),
            account = if (isNull(accIdx)) null else getString(accIdx),
            origin = ParserModel.Origin.of(getInt(getColumnIndexOrThrow("origin"))),
        )
        val q = PayeeQuality.entries.getOrElse(getInt(getColumnIndexOrThrow("payee_quality"))) { PayeeQuality.UNKNOWN }
        return Row(p, q, getString(getColumnIndexOrThrow("fingerprint")), status)
    }
}

internal fun CaptureDb.Row.toKey() = DraftKey(payment.id, payment.amountPaise, payment.atMillis, payment.isIncome, payment.payee, payment.sourcePackage, fingerprint)
