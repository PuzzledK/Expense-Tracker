# Shared contract (read before editing)

Package root: `app/src/main/java/com/expensetracker/app/`

`data/Models.kt` is FIXED: Category, Expense, ParsedEntry, ParseResult, QuickEntry, Settings, QuickTemplate.
Ask the main session before changing it.

## File ownership
| Owner | Files |
|---|---|
| Session "importer" | `data/NotesParser.kt`, `data/Categorizer.kt`, `data/BackupIo.kt`, everything under `app/src/test/` |
| Session "ui" | `MainActivity.kt`, `ui/**`, `Reminders.kt`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/**` |
| Main session | Gradle files, `data/Models.kt`, `data/ExpenseDb.kt`, `data/Repository.kt`, `ExpenseViewModel.kt` |

## APIs the importer session provides (object singletons in package `com.expensetracker.app.data`)
```kotlin
object NotesParser {
    fun parse(text: String, firstYear: Int): ParseResult
    fun guessFirstYear(text: String, today: java.time.LocalDate): Int   // so last entry is <= today
    fun parseLine(line: String): QuickEntry?                            // quick-add box, "120 - Lunch", "lunch 120", "+5000 Mammi"
}
object Categorizer { fun guess(note: String): Category }
object BackupIo {
    fun toJson(expenses: List<Expense>, settings: Settings): String
    fun fromJson(json: String): Pair<List<Expense>, Settings?>   // ids reset to 0
    fun toCsv(expenses: List<Expense>): String
    fun toNotesText(expenses: List<Expense>): String               // back to the notes-app format
}
```

## API the main session provides: `com.expensetracker.app.ExpenseViewModel` (AndroidViewModel)
```kotlin
val expenses: StateFlow<List<Expense>>          // all, newest date first
val settings: StateFlow<Settings>
val templates: StateFlow<List<QuickTemplate>>   // top frequent entries
fun add(e: Expense); fun update(e: Expense); fun delete(e: Expense); fun restore(e: Expense) // undo
fun quickAdd(text: String, date: LocalDate = LocalDate.now()): Expense?   // null if unparseable
fun suggestCategory(note: String): Category      // learned from your history, falls back to Categorizer
fun previewImport(text: String, firstYear: Int): ParseResult
fun guessFirstYear(text: String): Int
fun importEntries(entries: List<ParsedEntry>, skipDuplicates: Boolean): Int  // count inserted (suspend-free, runs async; returns via callback below)
fun importEntries(entries: List<ParsedEntry>, skipDuplicates: Boolean, onDone: (Int) -> Unit)
fun exportJson(): String; fun exportCsv(): String; fun exportNotesText(): String
fun restoreJson(json: String, replaceExisting: Boolean, onDone: (Int) -> Unit)
fun deleteAll()
fun updateSettings(transform: (Settings) -> Settings)
```
UI computes stats (monthly totals, category breakdown, etc.) from `expenses` itself.
