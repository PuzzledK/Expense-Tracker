package com.expensetracker.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.expensetracker.app.data.BackupIo
import com.expensetracker.app.data.Categorizer
import com.expensetracker.app.data.Category
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.ExpenseDb
import com.expensetracker.app.data.NotesParser
import com.expensetracker.app.data.ParseResult
import com.expensetracker.app.data.ParsedEntry
import com.expensetracker.app.data.QuickTemplate
import com.expensetracker.app.data.Settings
import com.expensetracker.app.data.SettingsStore
import com.expensetracker.app.data.SpendClassifier
import com.expensetracker.app.data.SpendInfo
import com.expensetracker.app.data.noteKey
import com.expensetracker.app.data.frequentTemplates
import com.expensetracker.app.quick.QuickLog
import com.expensetracker.app.location.LocationTagger
import com.expensetracker.app.data.Places
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class ExpenseViewModel(app: Application) : AndroidViewModel(app) {

    private val db = ExpenseDb(app)
    private val settingsStore = SettingsStore(app)

    private val _expenses = MutableStateFlow<List<Expense>>(emptyList())
    val expenses: StateFlow<List<Expense>> = _expenses.asStateFlow()

    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    /** Entries repeated at least twice in the last 90 days, most frequent first. */
    val templates: StateFlow<List<QuickTemplate>> = _expenses.map { frequentTemplates(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Classifier verdict per expense id (income has none). Recomputed whenever data changes. */
    val spendInfo: StateFlow<Map<Long, SpendInfo>> = _expenses.map { list ->
        withContext(Dispatchers.Default) {
            val verdicts = SpendClassifier.classify(list)
            buildMap { list.forEachIndexed { i, e -> verdicts[i]?.let { put(e.id, it) } } }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Distinct places you've used, most frequent first. */
    val recentPlaces: StateFlow<List<String>> = _expenses.map { Places.recent(it, 8) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        reload()
        LocationTagger.backfillAsync(app) { reload() }
    }

    /** New entries for today without a place get the current city, looked up after saving. */
    private fun autoTag(saved: List<Expense>, ids: List<Long>) {
        if (!_settings.value.locationTagging) return
        val today = LocalDate.now()
        val targets = ids.filterIndexed { i, _ -> saved.getOrNull(i)?.let { it.place == null && it.date == today } == true }
        LocationTagger.tagAsync(getApplication(), targets) { reload() }
    }

    /** Tags all entries in a date range with a place (for past trips). [onDone] gets the count. */
    fun tagTrip(from: LocalDate, to: LocalDate, place: String, overwrite: Boolean, onDone: (Int) -> Unit) {
        var n = 0
        write({ n = db.tagRange(from, to, place.trim(), overwrite) }) { onDone(n) }
    }

    /** Re-reads the DB; called on resume since the notification/widget can add entries meanwhile. */
    fun reload() {
        viewModelScope.launch {
            _expenses.value = withContext(Dispatchers.IO) { db.all() }
        }
    }

    /** Runs a DB write off the main thread, then refreshes the list. */
    private fun write(block: () -> Unit, after: () -> Unit = {}) {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                block()
                db.all().also { QuickLog.refreshAll(getApplication(), it) }
            }
            _expenses.value = list
            after()
        }
    }

    fun add(e: Expense) {
        var id = 0L
        write({ id = db.insert(e.copy(id = 0)) }) { autoTag(listOf(e), listOf(id)) }
    }
    fun update(e: Expense) = write({ db.update(e) })
    fun delete(e: Expense) = write({ db.delete(e.id) })
    fun restore(e: Expense) = write({ db.insert(e) })

    /** Adds several entries at once; [onDone] gets the new ids (for Undo). */
    fun addAll(list: List<Expense>, onDone: (List<Long>) -> Unit = {}) {
        if (list.isEmpty()) return
        val now = System.currentTimeMillis()
        var ids: List<Long> = emptyList()
        // createdAt offsets keep the typed order within the day.
        write({ ids = db.insertAll(list.mapIndexed { i, e -> e.copy(id = 0, createdAt = now + i) }) }) {
            onDone(ids)
            autoTag(list, ids)
        }
    }

    fun deleteIds(ids: List<Long>) = write({ db.deleteIds(ids) })

    /**
     * Parses a multi-line box for the bulk-add sheet: one "120 - Lunch" per line, all on [date].
     * Returns one item per non-blank line; `expense` is null when the line had no amount.
     */
    fun previewBulk(text: String, date: LocalDate): List<Pair<String, Expense?>> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            val q = NotesParser.parseLine(line)
            line to q?.let {
                Expense(amountPaise = it.amountPaise, note = it.note, category = suggestCategory(it.note), date = date, isIncome = it.isIncome)
            }
        }

    fun quickAdd(text: String, date: LocalDate = LocalDate.now()): Expense? {
        val q = NotesParser.parseLine(text) ?: return null
        val e = Expense(
            amountPaise = q.amountPaise,
            note = q.note,
            category = suggestCategory(q.note),
            date = date,
            isIncome = q.isIncome,
            time = if (date == LocalDate.now()) java.time.LocalTime.now().withSecond(0).withNano(0) else null,
        )
        add(e)
        return e
    }

    /** Uses the category you last gave the same note; otherwise the keyword guess. */
    fun suggestCategory(note: String): Category {
        val key = noteKey(note)
        if (key.isNotEmpty()) {
            _expenses.value.firstOrNull { noteKey(it.note) == key }?.let { return it.category }
        }
        return Categorizer.guess(note)
    }

    fun previewImport(text: String, firstYear: Int, undatedMonth: java.time.YearMonth? = null): ParseResult =
        NotesParser.parse(text, firstYear, undatedMonth)

    fun guessFirstYear(text: String): Int = NotesParser.guessFirstYear(text, LocalDate.now())

    fun importEntries(entries: List<ParsedEntry>, skipDuplicates: Boolean): Int {
        val toInsert = filterDuplicates(entries, skipDuplicates)
        write({ db.insertAll(toInsert) })
        return toInsert.size
    }

    fun importEntries(entries: List<ParsedEntry>, skipDuplicates: Boolean, onDone: (Int) -> Unit) {
        val toInsert = filterDuplicates(entries, skipDuplicates)
        write({ db.insertAll(toInsert) }) { onDone(toInsert.size) }
    }

    private fun filterDuplicates(entries: List<ParsedEntry>, skipDuplicates: Boolean): List<Expense> {
        val now = System.currentTimeMillis()
        // Keep order of the source text via createdAt offsets so same-day entries stay in note order.
        val mapped = entries.mapIndexed { i, p ->
            Expense(
                amountPaise = p.amountPaise,
                note = p.note,
                category = p.category,
                date = p.date,
                isIncome = p.isIncome,
                createdAt = now + i,
            )
        }
        if (!skipDuplicates) return mapped
        // Multiset match: importing the same text twice adds nothing, but genuine
        // same-day repeats inside one import (e.g. two "20 - Water Bottle") are kept.
        val existing = HashMap<String, Int>()
        _expenses.value.forEach { existing.merge(dupKey(it), 1, Int::plus) }
        return mapped.filter { e ->
            val k = dupKey(e)
            val n = existing[k] ?: 0
            if (n > 0) {
                existing[k] = n - 1
                false
            } else true
        }
    }

    private fun dupKey(e: Expense) = "${e.date}|${e.amountPaise}|${e.isIncome}|${noteKey(e.note)}"

    fun exportJson(): String = BackupIo.toJson(_expenses.value, _settings.value)
    fun exportCsv(): String = BackupIo.toCsv(_expenses.value)
    fun exportNotesText(): String = BackupIo.toNotesText(_expenses.value)

    fun restoreJson(json: String, replaceExisting: Boolean, onDone: (Int) -> Unit) {
        val (list, restoredSettings) = try {
            BackupIo.fromJson(json)
        } catch (t: Throwable) {
            onDone(-1)
            return
        }
        write({
            if (replaceExisting) db.deleteAll()
            db.insertAll(list.map { it.copy(id = 0) })
        }) {
            if (restoredSettings != null && replaceExisting) updateSettings { restoredSettings }
            onDone(list.size)
        }
    }

    fun deleteAll() = write({ db.deleteAll() })

    fun updateSettings(transform: (Settings) -> Settings) {
        val old = _settings.value
        val s = transform(old)
        _settings.value = s
        settingsStore.save(s)
        // Lock hides amounts on the notification/widget, so redraw them when it changes.
        if (s.appLock != old.appLock) QuickLog.refreshAsync(getApplication())
        if (s.quickLogNotification != old.quickLogNotification) {
            if (s.quickLogNotification) QuickLog.enable(getApplication()) else QuickLog.disable(getApplication())
        }
    }
}
