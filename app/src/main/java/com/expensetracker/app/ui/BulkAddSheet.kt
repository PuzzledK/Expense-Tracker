package com.expensetracker.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.expensetracker.app.ExpenseViewModel
import com.expensetracker.app.data.Category
import com.expensetracker.app.data.Expense
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Add many entries for one day, typed the way the notes app was used:
 * one "120 - Lunch" per line. Shows a live preview with auto categories
 * (tap an emoji to change it) and saves everything in one go.
 * [onAdded] gets the saved count and the new ids so the caller can offer Undo.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BulkAddSheet(
    vm: ExpenseViewModel,
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onAdded: (count: Int, totalPaise: Long, ids: List<Long>) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val templates by vm.templates.collectAsStateWithLifecycle()
    val all by vm.expenses.collectAsStateWithLifecycle()
    val recentPlaces by vm.recentPlaces.collectAsStateWithLifecycle()
    val settingsNow by vm.settings.collectAsStateWithLifecycle()
    var place by rememberSaveable { mutableStateOf<String?>(null) }

    var text by rememberSaveable { mutableStateOf("") }
    var date by remember { mutableStateOf(initialDate) }
    var showDatePicker by remember { mutableStateOf(false) }
    // Category overrides keyed by the line's text, so they survive edits to other lines.
    val overrides = remember { mutableStateMapOf<String, Category>() }
    val focus = remember { FocusRequester() }

    val preview = remember(text, date, all) { vm.previewBulk(text, date) }
    val valid = preview.mapNotNull { (line, e) -> e?.let { it.copy(category = overrides[line] ?: it.category, place = place) } }
    val invalidCount = preview.count { it.second == null }
    val spend = valid.filter { !it.isIncome }.sumOf { it.amountPaise }
    val income = valid.filter { it.isIncome }.sumOf { it.amountPaise }
    val alreadyThatDay = all.filter { it.date == date && !it.isIncome }.sumOf { it.amountPaise }

    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    fun appendLine(line: String) {
        text = if (text.isBlank()) line else text.trimEnd('\n') + "\n" + line
    }

    LaunchedEffect(Unit) { focus.requestFocus() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Add multiple",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                AssistChip(
                    onClick = { showDatePicker = true },
                    label = { Text(formatDateChip(date)) },
                    leadingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null, Modifier.size(18.dp)) },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(0L to "Today", 1L to "Yesterday", 2L to "2 days ago").forEach { (back, label) ->
                    val d = LocalDate.now().minusDays(back)
                    TextButton(onClick = { date = d }, enabled = date != d) { Text(label) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Place for all: ", style = MaterialTheme.typography.bodyMedium)
                PlaceChip(
                    place = place,
                    recent = recentPlaces,
                    autoHint = settingsNow.locationTagging && date == LocalDate.now(),
                    onChange = { place = it },
                )
            }
            if (alreadyThatDay > 0) {
                Text(
                    "Already logged that day: ${formatRupees(alreadyThatDay)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp).focusRequester(focus),
                label = { Text("One per line") },
                placeholder = { Text("110 - Lunch, Sandwich\n125 - Dinner, Biryani\n35 - Ice cream\n+500 - Got back from Richa") },
                minLines = 5,
            )

            if (templates.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Tap to add a line", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    templates.forEach { t ->
                        SuggestionChip(
                            onClick = { appendLine("${paiseToInput(t.amountPaise)} - ${t.note}") },
                            label = { Text("${t.category.emoji} ${t.note} · ${formatRupees(t.amountPaise)}", maxLines = 1) },
                        )
                    }
                }
            }

            if (preview.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Preview", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                preview.forEach { (line, e) ->
                    PreviewRow(
                        line = line,
                        expense = e?.let { it.copy(category = overrides[line] ?: it.category) },
                        onCategory = { overrides[line] = it },
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row {
                    Text("${valid.size} entries", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    if (income > 0) {
                        Text("+" + formatRupees(income), color = incomeColor(), style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(end = 8.dp))
                    }
                    Text(formatRupees(spend), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
                if (invalidCount > 0) {
                    Text(
                        "$invalidCount line(s) have no amount and will be skipped",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val toSave = valid
                    vm.addAll(toSave) { ids -> onAdded(toSave.size, spend, ids) }
                    close()
                },
                enabled = valid.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (valid.isEmpty()) "Add entries" else "Add ${valid.size} · ${formatRupees(spend)}")
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showDatePicker) {
        DatePickDialog(initial = date, onPick = { date = it }, onDismiss = { showDatePicker = false })
    }
}

@Composable
private fun PreviewRow(line: String, expense: Expense?, onCategory: (Category) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (expense == null) {
            Text("⚠️", modifier = Modifier.padding(end = 10.dp))
            Text(
                line,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            return@Row
        }
        Box {
            Text(
                expense.category.emoji,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.clickable { menu = true }.padding(end = 10.dp),
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Category.entries.forEach { c ->
                    DropdownMenuItem(
                        text = { Text("${c.emoji}  ${c.label}") },
                        onClick = { onCategory(c); menu = false },
                    )
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(expense.note.ifBlank { "(no note)" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(expense.category.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            (if (expense.isIncome) "+" else "") + formatRupees(expense.amountPaise),
            color = if (expense.isIncome) incomeColor() else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
    }
}
