# Parallel build contract — round 2

Read this whole file before editing anything. Several sessions work **in this same folder at
the same time**. Package root: `app/src/main/java/com/expensetracker/app/` (below: `app/`).

## Rules for every session
1. **Edit only the files your session owns** (table below). Everything else is read-only.
   If you truly need a change in a file you don't own, don't make it — put the exact change
   in your final report and the main session will apply it.
2. **Build in your own folders** so parallel Gradle runs don't collide:
   ```
   ./gradlew -PbuildSuffix=<session> --project-cache-dir .gradle-<session> -Pkotlin.project.persistent.dir=.kotlin-<session> :app:compileDebugKotlin :app:testDebugUnitTest
   ```
   If compilation fails in a file you don't own, it's another session mid-edit: wait a
   minute and retry; never "fix" it.
3. **Don't commit** (the main session commits each feature after integrating). Don't touch git.
4. Keep the stub **names and signatures** below (they're referenced from the manifest,
   `ui/App.kt`, `ui/HomeScreen.kt`, `ui/StatsScreen.kt`, `ui/AddEditSheet.kt`, `ExpenseViewModel.kt`).
   You may add parameters with defaults and add new files inside your package.
5. Unit tests: plain JVM (JUnit 4) under `app/src/test/java/com/expensetracker/app/<your package>/`.
   No Robolectric. `android.*` classes aren't available in tests, so keep logic in pure Kotlin.
6. Only libraries already in `app/build.gradle.kts` (Compose BOM, material3, icons-core,
   lifecycle, activity, core-ktx). No new dependencies. minSdk 26, compileSdk 36.

## File ownership
| Session | Owns |
|---|---|
| **ml** | `app/ml/**`, `app/src/test/.../ml/**` |
| **capture** | `app/capture/**` (keep `Placeholder.kt` until all stubs are gone), `app/src/test/.../capture/**` |
| **goals-insights** | `app/goals/**`, `app/insights/**`, `app/src/test/.../goals/**`, `app/src/test/.../insights/**` |
| **backup** | `app/backup/**`, `app/categories/**`, `app/data/BackupIo.kt`, and in `ExpenseViewModel.kt` **only** the bodies of `exportJson()` and `restoreJson()`; `app/src/test/.../backup/**`, `.../categories/**` |
| main session | everything else (Models, DB, Categories registry, VM, App/Home/Stats/Settings/AddEdit UI, manifest, Gradle) |

## Shared foundation already in place
- **Categories are customisable.** `data/Categories.kt`: `Category` is a class (identity = `id`),
  source-compatible with the old enum (`Category.FOOD`, `.label/.emoji/.color/.name`,
  `Category.entries` = visible ones, `Category.valueOf`, `Category.fromId(id)` → OTHER if unknown).
  `CategoryRegistry`: `allInfo()`, `visible()`, `info(id)`, `save(ctx, infos)`, `load(ctx)`,
  `newCustomId()`, `encode/decode` (for backups), `version: StateFlow<Int>` (bumps on change),
  `setForTesting(...)`. `CategoryInfo(id, label, emoji, color, hidden)`.
- `Expense.goalId: String?` (DB v5 column `goal_id`, already in JSON backup as "goalId").
- `Settings` new fields (persisted by `SettingsStore`): `captureEnabled`, `autoBackupEnabled`,
  `autoBackupTreeUri`, `autoBackupEveryDays`, `autoBackupKeep`, `insightsNotification`.
  Change them with `vm.updateSettings { it.copy(...) }`.
- ViewModel helpers: `vm.expenses`, `vm.settings`, `vm.spendInfo` (one-off/fixed classifier),
  `vm.add/update/delete/addAll`, `vm.recategorize(from, to, onDone)`, `vm.clearGoal(goalId)`,
  `vm.suggestCategory(note, amountPaise?, place?)`, `vm.updateSettings`, `vm.reload()`.
- Data helpers: `Places`, `EntryOrder`, `SpendClassifier`, `frequentTemplates`, `noteKey` (internal),
  `QuickLog.rupees(paise)` (₹ with Indian grouping), UI `formatRupees`, `formatMonth`, `formatShortDate`,
  `MonthSelector`, `DatePickDialog`, `TimePickDialog`, `ExpenseRow`, `incomeColor()`.
- Screens open as overlays: `Overlay.{CATEGORIES, GOALS, INSIGHTS, CAPTURE, BACKUP}`; Settings has
  entries for each. Boot/app-update calls `AutoBackup.restore(ctx)` and `Insights.restore(ctx)`.
- Manifest already declares `.capture.PaymentListenerService` (notification listener),
  `.backup.AutoBackupReceiver`, `.insights.InsightsReceiver`. Ask the main session for any other
  manifest entry (permission, receiver) via your report.

## APIs each session implements (stubs exist — keep signatures)
### ml — `ml/Predictor.kt`
```kotlin
object Predictor {
    data class Prediction(val note: String, val category: Category, val confidence: Double)
    fun train(all: List<Expense>)                       // full history, newest first; after every change
    fun learn(saved: Expense)                           // a manual add/edit was saved
    fun predictCategory(note: String, amountPaise: Long? = null, at: LocalDateTime? = null, place: String? = null): Category
    fun predictPayment(payee: String, amountPaise: Long, at: LocalDateTime, place: String? = null): Prediction
    fun suggestions(now: LocalDateTime, place: String? = null, limit: Int = 8): List<QuickTemplate>
}
```
### capture — `capture/Capture.kt` (split into more files as you like)
`CapturedPayment`, `CaptureStore.pending: StateFlow<List<CapturedPayment>>`, `CaptureStore.refresh(ctx)`,
`PaymentListenerService`, `@Composable CaptureBanner(onOpen)`, `@Composable CaptureInboxScreen(vm, onBack)`.
### goals-insights — `goals/Goals.kt`, `insights/Insights.kt`
`GoalsScreen(vm, onBack)`, `GoalsCard(vm, onOpen)`, `GoalPickerRow(vm, goalId, onChange)`;
`Insights.restore(ctx)`, `InsightsReceiver`, `InsightsCard(vm, onOpen)`, `InsightsScreen(vm, onBack)`.
Goals must be exportable: provide `GoalStore.exportText(ctx): String` and `GoalStore.importText(ctx, text)`.
### backup — `backup/AutoBackup.kt`, `categories/ManageCategories.kt`, `data/BackupIo.kt`
`AutoBackup.restore(ctx)`, `AutoBackupReceiver`, `BackupSettingsScreen(vm, onBack)`,
`ManageCategoriesScreen(vm, onBack)`. JSON backups must also carry custom categories
(`CategoryRegistry.encode/decode`) and goals (`GoalStore.exportText/importText`, implemented by
goals-insights — call them by that exact name).
