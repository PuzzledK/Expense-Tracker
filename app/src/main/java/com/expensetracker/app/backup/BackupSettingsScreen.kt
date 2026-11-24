package com.expensetracker.app.backup

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import com.expensetracker.app.ui.kit.Button
import com.expensetracker.app.ui.kit.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import com.expensetracker.app.ui.kit.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import com.expensetracker.app.ui.kit.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.expensetracker.app.ui.kit.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.expensetracker.app.ExpenseViewModel
import com.expensetracker.app.Reminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val whenFmt = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

private fun formatWhen(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(whenFmt)

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0)
}

/** Folder picker, frequency, how many to keep, last backup status, "Back up now", restore list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(vm: ExpenseViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsState()
    val status by AutoBackup.status.collectAsState()
    val running by AutoBackup.running.collectAsState()
    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

    var folderName by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<AutoBackup.BackupFile>>(emptyList()) }
    var filesLoaded by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<AutoBackup.BackupFile?>(null) }

    LaunchedEffect(Unit) { AutoBackup.refreshStatus(ctx) }
    LaunchedEffect(settings.autoBackupTreeUri, status.lastAttemptAt) {
        val uri = settings.autoBackupTreeUri
        withContext(Dispatchers.IO) {
            val name = AutoBackup.folderName(ctx, uri)
            val list = AutoBackup.listBackups(ctx, uri)
            withContext(Dispatchers.Main) {
                folderName = name
                files = list
                filesLoaded = true
            }
        }
    }

    fun backupNow() = AutoBackup.backupNow(ctx) { st ->
        toast(if (st.ok == true) "Backup saved" else "Backup failed: ${st.message}")
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val ok = runCatching { ctx.contentResolver.takePersistableUriPermission(uri, flags) }.isSuccess
        if (!ok) {
            toast("That folder can't be used for backups — pick another")
            return@rememberLauncherForActivityResult
        }
        val old = settings.autoBackupTreeUri
        if (old.isNotEmpty() && old != uri.toString()) {
            runCatching { ctx.contentResolver.releasePersistableUriPermission(android.net.Uri.parse(old), flags) }
        }
        vm.updateSettings { it.copy(autoBackupTreeUri = uri.toString(), autoBackupEnabled = true) }
        AutoBackup.clearFolderProblem(ctx)
        AutoBackup.restore(ctx)
        backupNow()
    }

    fun change(transform: (com.expensetracker.app.data.Settings) -> com.expensetracker.app.data.Settings) {
        vm.updateSettings(transform)
        AutoBackup.restore(ctx)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Automatic backups") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { p ->
        Column(
            Modifier.fillMaxSize().padding(p).verticalScroll(rememberScrollState()),
        ) {
            Card(
                Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Text(
                    "Saves a complete backup — entries, settings, categories and goals — as a JSON file " +
                        "in a folder you choose, on a schedule and when you leave the app if one is due.\n\n" +
                        "For a copy off your phone, choose a Google Drive folder: in the picker open the ☰ menu " +
                        "and pick Drive (the Drive app must be installed and signed in). A folder on an SD card works too.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            ListItem(
                headlineContent = { Text("Automatic backups") },
                supportingContent = {
                    Text(if (settings.autoBackupEnabled) BackupNames.frequencyLabel(settings.autoBackupEveryDays) else "Off")
                },
                trailingContent = {
                    Switch(
                        checked = settings.autoBackupEnabled,
                        onCheckedChange = { on ->
                            if (on && settings.autoBackupTreeUri.isEmpty()) {
                                folderPicker.launch(null)
                            } else {
                                change { it.copy(autoBackupEnabled = on) }
                                if (on && AutoBackup.isDue(ctx)) backupNow()
                            }
                        },
                    )
                },
            )

            ListItem(
                headlineContent = { Text("Backup folder") },
                supportingContent = {
                    Text(
                        when {
                            settings.autoBackupTreeUri.isEmpty() -> "Not chosen"
                            folderName != null -> folderName!!
                            else -> "Chosen folder (can't be read right now)"
                        },
                    )
                },
                trailingContent = {
                    OutlinedButton(onClick = { folderPicker.launch(null) }) {
                        Text(if (settings.autoBackupTreeUri.isEmpty()) "Choose folder" else "Change")
                    }
                },
            )

            Text("How often", Modifier.padding(start = 16.dp, top = 8.dp), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 3, 7).forEach { d ->
                    FilterChip(
                        selected = settings.autoBackupEveryDays == d,
                        onClick = { change { it.copy(autoBackupEveryDays = d) } },
                        label = { Text(BackupNames.frequencyLabel(d)) },
                    )
                }
            }
            Text("Keep the last", Modifier.padding(start = 16.dp, top = 8.dp), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 14, 30).forEach { n ->
                    FilterChip(
                        selected = settings.autoBackupKeep == n,
                        onClick = { change { it.copy(autoBackupKeep = n) } },
                        label = { Text("$n backups") },
                    )
                }
            }
            Text(
                "Older automatic backups in that folder are deleted. Other files are never touched.",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(Modifier.padding(top = 12.dp))
            Column(Modifier.padding(16.dp)) {
                Text("Last backup", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                if (status.lastSuccessAt > 0) {
                    Text(formatWhen(status.lastSuccessAt), style = MaterialTheme.typography.bodyMedium)
                    if (status.fileName.isNotEmpty()) {
                        Text(status.fileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text("Never", style = MaterialTheme.typography.bodyMedium)
                }
                if (status.ok == false) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Last try (${formatWhen(status.lastAttemptAt)}) failed: ${status.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (status.folderProblem) {
                        TextButton(onClick = { folderPicker.launch(null) }) { Text("Choose the folder again") }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { backupNow() },
                        enabled = !running && settings.autoBackupTreeUri.isNotEmpty(),
                    ) { Text("Back up now") }
                    if (running) {
                        Spacer(Modifier.width(12.dp))
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }

            if (settings.autoBackupTreeUri.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    "Restore from this folder",
                    Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (filesLoaded && files.isEmpty()) {
                    Text(
                        "No automatic backups in this folder yet.",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                files.forEach { f ->
                    val stamp = BackupNames.stampOf(f.name)
                    ListItem(
                        headlineContent = {
                            Text(stamp?.format(whenFmt) ?: f.name)
                        },
                        supportingContent = {
                            Text(listOf(f.name, formatSize(f.size)).filter { it.isNotEmpty() }.joinToString(" · "))
                        },
                        modifier = Modifier.clickable { pendingRestore = f },
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    pendingRestore?.let { f ->
        fun restore(replace: Boolean) {
            pendingRestore = null
            scope.launch {
                val text = withContext(Dispatchers.IO) { runCatching { AutoBackup.readText(ctx, f.uri) } }
                text.onFailure { toast("Couldn't read the backup: ${it.message}") }
                text.onSuccess { json ->
                    vm.restoreJson(json, replaceExisting = replace) { n ->
                        when {
                            n < 0 -> toast("That file isn't a valid backup")
                            replace -> {
                                Reminders.rescheduleFromPrefs(ctx)
                                toast("Restored $n entries")
                            }
                            else -> toast("Added $n entries")
                        }
                    }
                }
            }
        }
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "${f.name}\n\n" +
                        "Replace: wipe the current entries and load the backup (also its categories, goals and budget).\n" +
                        "Merge: add the backup's entries, skipping exact duplicates.",
                )
            },
            confirmButton = { TextButton(onClick = { restore(true) }) { Text("Replace") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingRestore = null }) { Text("Cancel") }
                    TextButton(onClick = { restore(false) }) { Text("Merge") }
                }
            },
        )
    }
}
