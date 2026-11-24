package com.expensetracker.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import java.time.LocalTime

/** Plain SQLite storage; no annotation processing needed. */
class ExpenseDb(context: Context) : SQLiteOpenHelper(context, "expenses.db", null, 4) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE expenses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                amount_paise INTEGER NOT NULL,
                note TEXT NOT NULL,
                category TEXT NOT NULL,
                epoch_day INTEGER NOT NULL,
                is_income INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                spend_override TEXT,
                place TEXT,
                lat REAL,
                lon REAL,
                time_min INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_expenses_day ON expenses(epoch_day)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2: manual "counts as" choice. Existing rows keep null = automatic.
        if (oldVersion < 2) db.execSQL("ALTER TABLE expenses ADD COLUMN spend_override TEXT")
        // v3: where it was spent.
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE expenses ADD COLUMN place TEXT")
            db.execSQL("ALTER TABLE expenses ADD COLUMN lat REAL")
            db.execSQL("ALTER TABLE expenses ADD COLUMN lon REAL")
        }
        // v4: time of day (minutes since midnight) for quick entries.
        if (oldVersion < 4) db.execSQL("ALTER TABLE expenses ADD COLUMN time_min INTEGER")
    }

    fun all(): List<Expense> = readableDatabase.rawQuery(
        "SELECT id, amount_paise, note, category, epoch_day, is_income, created_at, spend_override, place, lat, lon, time_min FROM expenses " +
            "ORDER BY epoch_day DESC, created_at DESC, id DESC",
        null,
    ).use { c -> buildList { while (c.moveToNext()) add(c.toExpense()) } }

    /** Inserts and returns the new row id. A non-zero id is kept (used for undo). */
    fun insert(e: Expense): Long = writableDatabase.insertOrThrow("expenses", null, e.toValues(keepId = e.id != 0L))

    /** Inserts in one transaction and returns the new row ids in order. */
    fun insertAll(list: List<Expense>): List<Long> {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val ids = list.map { db.insertOrThrow("expenses", null, it.toValues(keepId = false)) }
            db.setTransactionSuccessful()
            return ids
        } finally {
            db.endTransaction()
        }
    }

    fun deleteIds(ids: List<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { db.delete("expenses", "id = ?", arrayOf(it.toString())) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Sets place (and optionally coordinates) on the given rows. */
    fun setPlace(ids: List<Long>, place: String?, lat: Double? = null, lon: Double? = null) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        val v = ContentValues().apply {
            put("place", place)
            if (lat != null && lon != null) {
                put("lat", lat)
                put("lon", lon)
            }
        }
        db.beginTransaction()
        try {
            ids.forEach { db.update("expenses", v, "id = ?", arrayOf(it.toString())) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Tags every entry between [from] and [to] (inclusive) with [place]; returns rows changed. */
    fun tagRange(from: LocalDate, to: LocalDate, place: String, overwrite: Boolean): Int {
        val v = ContentValues().apply { put("place", place) }
        val where = "epoch_day BETWEEN ? AND ?" + if (overwrite) "" else " AND (place IS NULL OR place = '')"
        return writableDatabase.update("expenses", v, where, arrayOf(from.toEpochDay().toString(), to.toEpochDay().toString()))
    }

    fun update(e: Expense) {
        writableDatabase.update("expenses", e.toValues(keepId = false), "id = ?", arrayOf(e.id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("expenses", "id = ?", arrayOf(id.toString()))
    }

    fun deleteAll() {
        writableDatabase.delete("expenses", null, null)
    }

    private fun Expense.toValues(keepId: Boolean) = ContentValues().apply {
        if (keepId) put("id", id)
        put("amount_paise", amountPaise)
        put("note", note)
        put("category", category.name)
        put("epoch_day", date.toEpochDay())
        put("is_income", if (isIncome) 1 else 0)
        put("created_at", createdAt)
        put("spend_override", spendOverride?.name)
        put("place", place)
        put("lat", lat)
        put("lon", lon)
        put("time_min", time?.let { it.hour * 60 + it.minute })
    }

    private fun Cursor.toExpense() = Expense(
        id = getLong(0),
        amountPaise = getLong(1),
        note = getString(2),
        category = runCatching { Category.valueOf(getString(3)) }.getOrDefault(Category.OTHER),
        date = LocalDate.ofEpochDay(getLong(4)),
        isIncome = getInt(5) == 1,
        createdAt = getLong(6),
        spendOverride = if (isNull(7)) null else SpendType.entries.firstOrNull { it.name == getString(7) },
        place = if (isNull(8)) null else getString(8),
        lat = if (isNull(9)) null else getDouble(9),
        lon = if (isNull(10)) null else getDouble(10),
        time = if (isNull(11)) null else getInt(11).let { LocalTime.of(it / 60, it % 60) },
    )
}
