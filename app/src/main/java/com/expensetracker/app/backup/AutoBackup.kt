package com.expensetracker.app.backup

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.expensetracker.app.MainActivity
import com.expensetracker.app.R
import com.expensetracker.app.data.ExpenseDb
import com.expensetracker.app.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Scheduled backups into a folder picked with the system folder picker (local storage, SD card,
 * or Google Drive / any cloud provider that offers folders). No WorkManager: an inexact
 * repeating alarm wakes [AutoBackupReceiver] twice a day and it backs up when one is due; the
 * app also backs up when it goes to the background and a backup is overdue ([onAppBackground]).
 */
object AutoBackup {

    private const val PREFS = "auto_backup"
    private const val REQUEST_CODE = 1101
    private const val CHANNEL_ID = "backups"
    private const val NOTIFICATION_ID = 41
    private const val FIRST_RUN_DELAY_MS = 60L * 60 * 1000

    /** Extra on the "Backups paused" notification's intent; MainActivity may open the backup screen for it. */
    const val EXTRA_OPEN_BACKUP = "com.expensetracker.app.OPEN_BACKUP"

    data class Status(
        val lastAttemptAt: Long = 0,
        val lastSuccessAt: Long = 0,
        /** null = never ran. */
        val ok: Boolean? = null,
        val message: String = "",
        val fileName: String = "",
        /** The folder is gone or access was lost — the user must pick it again. */
        val folderProblem: Boolean = false,
    )

    data class BackupFile(val name: String, val uri: Uri, val modified: Long, val size: Long)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val lock = AtomicBoolean(false)

    // ---------------- scheduling ----------------

    /** Arm/re-arm the schedule from saved settings (called on boot, app update and settings change). */
    fun restore(ctx: Context) {
        val app = ctx.applicationContext
        refreshStatus(app)
        val s = SettingsStore(app).load()
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(alarmIntent(app))
        if (s.autoBackupEnabled && s.autoBackupTreeUri.isNotEmpty()) {
            // Checks twice a day; the receiver only writes a file when one is due, so a missed
            // or failed run is retried within ~12 hours instead of waiting a whole interval.
            am.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + FIRST_RUN_DELAY_MS,
                AlarmManager.INTERVAL_HALF_DAY,
                alarmIntent(app),
            )
        }
    }

    private fun alarmIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        REQUEST_CODE,
        Intent(ctx, AutoBackupReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Call from MainActivity.onStop: backs up in the background if automatic backups are on and
     * the last good one is older than the chosen interval. Returns immediately.
     */
    fun onAppBackground(ctx: Context) {
        val app = ctx.applicationContext
        if (!isDue(app)) return
        thread(name = "auto-backup") { runCatching { runBackup(app) } }
    }

    /** Enabled, folder chosen, and the last success is older than the interval. */
    internal fun isDue(ctx: Context): Boolean {
        val s = SettingsStore(ctx).load()
        if (!s.autoBackupEnabled || s.autoBackupTreeUri.isEmpty()) return false
        val last = prefs(ctx).getLong("lastSuccessAt", 0)
        return BackupNames.isDue(last, s.autoBackupEveryDays, System.currentTimeMillis())
    }

    /** "Back up now": runs off the main thread; [onDone] is called on the main thread. */
    fun backupNow(ctx: Context, onDone: (Status) -> Unit = {}) {
        val app = ctx.applicationContext
        thread(name = "auto-backup") {
            val st = runCatching { runBackup(app) }.getOrElse { _status.value }
            Handler(Looper.getMainLooper()).post { onDone(st) }
        }
    }

    // ---------------- the backup itself ----------------

    private class FolderProblem(msg: String) : Exception(msg)

    /** Blocking. Writes one backup file and prunes old ones. Safe to call from any background thread. */
    internal fun runBackup(ctx: Context): Status {
        if (!lock.compareAndSet(false, true)) return _status.value // one at a time
        _running.value = true
        val now = System.currentTimeMillis()
        try {
            refreshStatus(ctx)
            val settings = SettingsStore(ctx).load()
            if (settings.autoBackupTreeUri.isEmpty()) throw FolderProblem("Choose a backup folder first")
            val tree = Uri.parse(settings.autoBackupTreeUri)
            if (!hasAccess(ctx, tree)) throw FolderProblem("Access to the backup folder was lost — choose it again")

            // Load custom categories first: the DB maps unknown category ids to Other.
            com.expensetracker.app.data.CategoryRegistry.ensureLoaded(ctx)
            val expenses = ExpenseDb(ctx).use { it.all() }
            val bytes = BackupData.toJson(ctx, expenses, settings).toByteArray(Charsets.UTF_8)

            // Cloud providers cache the file on the phone first, so check internal storage too.
            val free = runCatching { Environment.getDataDirectory().usableSpace }.getOrDefault(Long.MAX_VALUE)
            if (free < bytes.size + 5L * 1024 * 1024) throw IllegalStateException("Not enough storage space on the phone")

            val children = listChildren(ctx, tree) ?: throw FolderProblem("The backup folder was deleted or moved — choose it again")
            val name = BackupNames.fileName(LocalDateTime.now())
            val existing = children.firstOrNull { it.name == name }
            val resolver = ctx.contentResolver
            val docUri = existing?.uri ?: run {
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                try {
                    DocumentsContract.createDocument(resolver, parent, "application/json", name)
                } catch (t: java.io.FileNotFoundException) {
                    null
                } ?: throw FolderProblem("Couldn't create a file in the backup folder — choose it again")
            }
            try {
                val out = runCatching { resolver.openOutputStream(docUri, "wt") }.getOrNull()
                    ?: resolver.openOutputStream(docUri, "w")
                    ?: throw IllegalStateException("Couldn't open the backup file for writing")
                out.use { it.write(bytes) }
            } catch (t: Throwable) {
                // Don't leave a half-written file that looks like a good backup.
                if (existing == null) runCatching { DocumentsContract.deleteDocument(resolver, docUri) }
                throw t
            }

            // Keep only the newest N of *our* files; anything else in the folder is left alone.
            val written = existing?.name ?: queryName(ctx, docUri) ?: name
            val names = (children.map { it.name } + written).distinct()
            val drop = BackupNames.filesToDelete(names, settings.autoBackupKeep).toSet()
            children.filter { it.name in drop && it.uri != docUri }.forEach { f ->
                runCatching { DocumentsContract.deleteDocument(resolver, f.uri) }
            }

            val st = Status(now, now, true, "Backed up ${expenses.size} entries", written, false)
            save(ctx, st)
            ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
            prefs(ctx).edit().putBoolean("pausedNotified", false).apply()
            return st
        } catch (t: Throwable) {
            val folder = t is FolderProblem || t is SecurityException || t is java.io.FileNotFoundException
            val msg = if (t is FolderProblem) t.message.orEmpty() else BackupNames.describeError(t)
            val st = _status.value.copy(lastAttemptAt = now, ok = false, message = msg, folderProblem = folder)
            save(ctx, st)
            if (folder) notifyPausedOnce(ctx)
            return st
        } finally {
            _running.value = false
            lock.set(false)
        }
    }

    private fun hasAccess(ctx: Context, tree: Uri): Boolean =
        ctx.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission && it.isReadPermission }

    /** Children of the tree root, or null when the folder can't be read (deleted, provider gone). */
    private fun listChildren(ctx: Context, tree: Uri): List<BackupFile>? = try {
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        ctx.contentResolver.query(childrenUri, cols, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val n = c.getString(1) ?: continue
                    add(
                        BackupFile(
                            name = n,
                            uri = DocumentsContract.buildDocumentUriUsingTree(tree, id),
                            modified = if (c.isNull(2)) 0 else c.getLong(2),
                            size = if (c.isNull(3)) 0 else c.getLong(3),
                        ),
                    )
                }
            }
        }
    } catch (t: Exception) {
        null
    }

    private fun queryName(ctx: Context, doc: Uri): String? = runCatching {
        ctx.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    // ---------------- for the screen ----------------

    /** Our backup files in the folder, newest first. Blocking; empty when the folder can't be read. */
    fun listBackups(ctx: Context, treeUri: String): List<BackupFile> {
        if (treeUri.isEmpty()) return emptyList()
        val all = listChildren(ctx, Uri.parse(treeUri)) ?: return emptyList()
        val byName = all.associateBy { it.name }
        return BackupNames.newestFirst(all.map { it.name }).mapNotNull { byName[it] }
    }

    /** Folder name as shown by its provider ("Expense backups"), or null. Blocking. */
    fun folderName(ctx: Context, treeUri: String): String? {
        if (treeUri.isEmpty()) return null
        return runCatching {
            val tree = Uri.parse(treeUri)
            queryName(ctx, DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)))
        }.getOrNull()
    }

    /** Blocking. */
    fun readText(ctx: Context, uri: Uri): String =
        ctx.contentResolver.openInputStream(uri)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** Re-reads the stored status into [status]. */
    fun refreshStatus(ctx: Context) {
        val p = prefs(ctx)
        _status.value = Status(
            lastAttemptAt = p.getLong("lastAttemptAt", 0),
            lastSuccessAt = p.getLong("lastSuccessAt", 0),
            ok = if (p.contains("ok")) p.getBoolean("ok", false) else null,
            message = p.getString("message", "") ?: "",
            fileName = p.getString("fileName", "") ?: "",
            folderProblem = p.getBoolean("folderProblem", false),
        )
    }

    /** A new folder was chosen: forget the old folder's failure state. */
    fun clearFolderProblem(ctx: Context) {
        val st = _status.value.copy(folderProblem = false, message = if (_status.value.folderProblem) "" else _status.value.message)
        save(ctx, st)
        ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        prefs(ctx).edit().putBoolean("pausedNotified", false).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun save(ctx: Context, st: Status) {
        _status.value = st
        prefs(ctx).edit()
            .putLong("lastAttemptAt", st.lastAttemptAt)
            .putLong("lastSuccessAt", st.lastSuccessAt)
            .apply { if (st.ok != null) putBoolean("ok", st.ok) else remove("ok") }
            .putString("message", st.message)
            .putString("fileName", st.fileName)
            .putBoolean("folderProblem", st.folderProblem)
            .apply()
    }

    // ---------------- notification ----------------

    private fun notifyPausedOnce(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean("pausedNotified", false)) return
        p.edit().putBoolean("pausedNotified", true).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Backups", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Problems with automatic backups"
                },
            )
        }
        val open = PendingIntent.getActivity(
            ctx,
            REQUEST_CODE,
            Intent(ctx, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_BACKUP, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Backups paused — choose the folder again")
                .setContentText("The app can't reach your backup folder any more.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }
}

/** Fired by the backup alarm. Declared in the manifest. */
class AutoBackupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val s = SettingsStore(app).load()
        if (!s.autoBackupEnabled || s.autoBackupTreeUri.isEmpty()) {
            AutoBackup.restore(app) // cancels the stale alarm
            return
        }
        if (!AutoBackup.isDue(app)) return
        val pending = goAsync()
        thread(name = "auto-backup") {
            try {
                AutoBackup.runBackup(app)
            } catch (_: Throwable) {
            } finally {
                pending.finish()
            }
        }
    }
}
