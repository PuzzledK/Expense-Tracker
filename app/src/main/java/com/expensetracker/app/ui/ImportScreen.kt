@file:OptIn(ExperimentalMaterial3Api::class)

package com.expensetracker.app.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.expensetracker.app.ui.kit.Button
import com.expensetracker.app.ui.kit.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.expensetracker.app.ui.kit.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.expensetracker.app.ui.kit.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.expensetracker.app.ExpenseViewModel
import com.expensetracker.app.data.ParseResult
import kotlinx.coroutines.launch
import java.time.YearMonth

/**
 * Paste (or pick a .txt of) the old notes-app history, preview what was understood, then import.
 * [onImported] is called with the number of inserted entries; the caller shows the result.
 */
@Composable
fun ImportScreen(
    vm: ExpenseViewModel,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onImported: (Int) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var text by rememberSaveable { mutableStateOf("") }
    var yearText by rememberSaveable { mutableStateOf("") }
    var yearEdited by rememberSaveable { mutableStateOf(false) }
    var skipDuplicates by rememberSaveable { mutableStateOf(true) }
    var preview by remember { mutableStateOf<ParseResult?>(null) }
    var importing by remember { mutableStateOf(false) }
    // Entries before any date/month header: import them into this month (empty = suggested).
    var includeUndated by rememberSaveable { mutableStateOf(true) }
    var undatedMonthText by rememberSaveable { mutableStateOf("") }

    fun runPreview(y: Int) {
        val first = vm.previewImport(text, y)
        if (first.undatedEntries == 0 || !includeUndated) {
            preview = first
            return
        }
        val month = undatedMonthText.takeIf { it.isNotEmpty() }?.let { java.time.YearMonth.parse(it) }
            ?: first.suggestedUndatedMonth ?: java.time.YearMonth.now()
        undatedMonthText = month.toString()
        preview = vm.previewImport(text, y, month)
    }

    fun setText(t: String) {
        text = t
        preview = null
        undatedMonthText = ""
        if (!yearEdited && t.isNotBlank()) yearText = vm.guessFirstYear(t).toString()
    }

    fun toast(msg: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg)
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }
                .onSuccess { setText(it) }
                .onFailure { toast("Couldn't read file: ${it.message}") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import from notes", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                "Paste your whole notes history. Date lines like “31 MARCH” start a day; month lines like " +
                    "“OCTOBER 2026” put that month's entries on the 1st. Lines like “80 - Lunch” or “80-- lunch” " +
                    "become entries; “+5000 - Mammi” is money received.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                    if (clip.isNullOrBlank()) toast("Clipboard is empty") else setText(clip)
                }) { Text("Paste clipboard") }
                OutlinedButton(onClick = { fileLauncher.launch(arrayOf("text/plain", "text/*")) }) { Text("Pick .txt file") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { setText(it) },
                label = { Text("Notes text") },
                placeholder = { Text("EXPENSES\n\n31 MARCH\n5000 - Mammi\n80 - Lunch") },
                minLines = 8,
                maxLines = 14,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = yearText,
                    onValueChange = { v ->
                        yearText = v.filter { it.isDigit() }.take(4)
                        yearEdited = true
                        preview = null
                    },
                    label = { Text("Year of first entry") },
                    supportingText = { Text("Later months roll into next year automatically") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Skip duplicates", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Same date, amount and note already in the app",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = skipDuplicates, onCheckedChange = { skipDuplicates = it })
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val y = yearText.toIntOrNull()
                    if (y == null || y < 2000 || y > 2100) toast("Enter the year, e.g. 2026")
                    else runPreview(y)
                },
                enabled = text.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Preview") }

            preview?.let { p ->
                if (p.undatedEntries > 0) {
                    Spacer(Modifier.height(16.dp))
                    UndatedCard(
                        count = p.undatedEntries,
                        include = includeUndated,
                        month = undatedMonthText.takeIf { it.isNotEmpty() }?.let { java.time.YearMonth.parse(it) }
                            ?: p.suggestedUndatedMonth ?: java.time.YearMonth.now(),
                        onInclude = { on ->
                            includeUndated = on
                            yearText.toIntOrNull()?.let { runPreview(it) }
                        },
                        onMonth = { m ->
                            undatedMonthText = m.toString()
                            yearText.toIntOrNull()?.let { runPreview(it) }
                        },
                    )
                }
                Spacer(Modifier.height(16.dp))
                PreviewCard(p)
                if (p.skippedLines.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    SkippedCard(p)
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        importing = true
                        vm.importEntries(p.entries, skipDuplicates) { n ->
                            scope.launch {
                                importing = false
                                onImported(n)
                            }
                        }
                    },
                    enabled = p.entries.isNotEmpty() && !importing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (importing) {
                        CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Import ${p.entries.size} entries")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Lines that came before any date/month header (e.g. the current month's block with no title). */
@Composable
private fun UndatedCard(
    count: Int,
    include: Boolean,
    month: java.time.YearMonth,
    onInclude: (Boolean) -> Unit,
    onMonth: (java.time.YearMonth) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("$count entries have no month", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "They come before the first date or month line.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = include, onCheckedChange = onInclude)
            }
            if (include) {
                Spacer(Modifier.height(8.dp))
                Text("Put them on 1 ${formatMonth(month)}:", style = MaterialTheme.typography.bodyMedium)
                MonthSelector(month, onMonth)
            } else {
                Text("They'll be left out.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PreviewCard(p: ParseResult) {
    val spend = p.entries.filter { !it.isIncome }.sumOf { it.amountPaise }
    val income = p.entries.filter { it.isIncome }.sumOf { it.amountPaise }
    val first = p.entries.minOfOrNull { it.date }
    val last = p.entries.maxOfOrNull { it.date }
    val perMonth = p.entries.filter { !it.isIncome }
        .groupBy { YearMonth.from(it.date) }
        .mapValues { (_, v) -> v.sumOf { it.amountPaise } }
        .toSortedMap()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("${p.entries.size} entries found", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (first != null && last != null) {
                Text("${formatShortDate(first)} → ${formatShortDate(last)}", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(4.dp))
            Text("Spent ${formatRupees(spend)}", style = MaterialTheme.typography.bodyMedium)
            if (income > 0) Text("Received +${formatRupees(income)}", style = MaterialTheme.typography.bodyMedium, color = incomeColor())
            if (perMonth.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                perMonth.forEach { (ym, v) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(formatMonth(ym), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(formatRupees(v), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (p.entries.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("First few:", style = MaterialTheme.typography.labelMedium)
                p.entries.take(5).forEach { e ->
                    Text(
                        "${formatShortDate(e.date)}  ${if (e.isIncome) "+" else ""}${formatRupees(e.amountPaise)}  ${e.category.emoji} ${e.note}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun SkippedCard(p: ParseResult) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "⚠️ ${p.skippedLines.size} lines not understood (won't be imported)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            p.skippedLines.take(30).forEach { (n, line) ->
                Text(
                    "Line $n: $line",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            if (p.skippedLines.size > 30) {
                Text("…and ${p.skippedLines.size - 30} more", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Fix them in the text above and tap Preview again, or add them by hand later.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
