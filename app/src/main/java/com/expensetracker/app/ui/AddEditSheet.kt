@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.expensetracker.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import com.expensetracker.app.ui.kit.AssistChip
import com.expensetracker.app.ui.kit.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import com.expensetracker.app.ui.kit.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import com.expensetracker.app.ui.kit.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import com.expensetracker.app.ui.kit.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.expensetracker.app.ExpenseViewModel
import com.expensetracker.app.data.Category
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.SpendClassifier
import com.expensetracker.app.data.SpendType
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/**
 * Full add/edit form in a bottom sheet.
 * [onDeleted] is called after deleting so the caller (which outlives the sheet) can offer Undo.
 */
@Composable
fun AddEditSheet(
    vm: ExpenseViewModel,
    existing: Expense?,
    defaultDate: LocalDate?,
    onDismiss: () -> Unit,
    onDeleted: (Expense) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val all by vm.expenses.collectAsStateWithLifecycle()
    val recentPlaces by vm.recentPlaces.collectAsStateWithLifecycle()
    val settingsNow by vm.settings.collectAsStateWithLifecycle()

    var amountText by rememberSaveable { mutableStateOf(existing?.let { paiseToInput(it.amountPaise) } ?: "") }
    var note by rememberSaveable { mutableStateOf(existing?.note ?: "") }
    var category by rememberSaveable { mutableStateOf(existing?.category ?: Category.FOOD) }
    var categoryPicked by rememberSaveable { mutableStateOf(existing != null) }
    var isIncome by rememberSaveable { mutableStateOf(existing?.isIncome ?: false) }
    var date by rememberSaveable { mutableStateOf(existing?.date ?: defaultDate ?: LocalDate.now()) }
    var place by rememberSaveable { mutableStateOf(existing?.place) }
    var goalId by rememberSaveable { mutableStateOf(existing?.goalId) }
    // Own-account transfer: neither spend nor income. Refund: income that pays back an expense.
    var isTransfer by rememberSaveable { mutableStateOf(existing?.isTransfer ?: false) }
    var refundOf by rememberSaveable { mutableStateOf(existing?.refundOf) }
    // null = let the classifier decide ("Auto").
    var spendOverride by rememberSaveable { mutableStateOf(existing?.spendOverride) }
    var showDatePicker by remember { mutableStateOf(false) }
    // New entries for today start at the current time; clear it with the ✕ on the chip.
    var time by rememberSaveable {
        mutableStateOf(
            if (existing != null) existing.time
            else if ((defaultDate ?: LocalDate.now()) == LocalDate.now()) LocalTime.now().withSecond(0).withNano(0)
            else null,
        )
    }
    var showTimePicker by remember { mutableStateOf(false) }
    // The current time is only a guess for a new entry; once you pick or clear it, it's yours.
    var timeTouched by rememberSaveable { mutableStateOf(existing != null) }

    fun changeDate(d: LocalDate) {
        date = d
        if (!timeTouched) time = if (d == LocalDate.now()) LocalTime.now().withSecond(0).withNano(0) else null
    }
    var amountError by remember { mutableStateOf(false) }
    val amountFocus = remember { FocusRequester() }

    val parsedAmount = parseAmountInput(amountText)

    // What the classifier would say for this draft if left on Auto.
    val autoInfo = remember(parsedAmount, note, category, date, isIncome, all) {
        val amt = parsedAmount
        if (isIncome || amt == null || amt <= 0) {
            null
        } else {
            val draftId = existing?.id ?: -1L
            val draft = Expense(
                id = draftId, amountPaise = amt, note = note.trim(), category = category,
                date = date, isIncome = false, spendOverride = null,
            )
            val list = if (existing != null) all.map { if (it.id == existing.id) draft else it } else all + draft
            val i = list.indexOfFirst { it.id == draftId }
            if (i < 0) null else SpendClassifier.classify(list)[i]
        }
    }

    // Past notes matching what's typed, most recent first, for one-tap autocomplete.
    val suggestions = remember(note, all) {
        val q = note.trim()
        if (q.length < 2) emptyList()
        else all.asSequence()
            .filter { it.note.contains(q, ignoreCase = true) && !it.note.equals(q, ignoreCase = true) }
            .distinctBy { it.note.lowercase() }
            .take(4)
            .toList()
    }

    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    fun save() {
        val amt = parsedAmount
        if (amt == null || amt <= 0) {
            amountError = true
            return
        }
        val cleanNote = note.trim()
        val refund = refundOf?.takeIf { isIncome && !isTransfer && it > 0 }
        if (existing != null) {
            vm.update(existing.copy(amountPaise = amt, note = cleanNote, category = category, date = date, isIncome = isIncome, spendOverride = if (isIncome) null else spendOverride,
                time = time, place = place, lat = if (place == existing.place) existing.lat else null, lon = if (place == existing.place) existing.lon else null,
                goalId = goalId, isTransfer = isTransfer, refundOf = refund))
        } else {
            vm.add(Expense(amountPaise = amt, note = cleanNote, category = category, date = date, isIncome = isIncome, spendOverride = if (isIncome) null else spendOverride, place = place, time = time, goalId = goalId, isTransfer = isTransfer, refundOf = refund))
        }
        close()
    }

    LaunchedEffect(Unit) { if (existing == null) amountFocus.requestFocus() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Text(
                if (existing == null) "Add entry" else "Edit entry",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !isIncome && !isTransfer,
                    onClick = { isIncome = false; isTransfer = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 3),
                ) { Text("Expense") }
                SegmentedButton(
                    selected = isIncome && !isTransfer,
                    onClick = { isIncome = true; isTransfer = false },
                    shape = SegmentedButtonDefaults.itemShape(1, 3),
                ) { Text("Received") }
                SegmentedButton(
                    selected = isTransfer,
                    onClick = { isTransfer = true; isIncome = false; refundOf = null },
                    shape = SegmentedButtonDefaults.itemShape(2, 3),
                ) { Text("Transfer") }
            }
            if (isTransfer) {
                Text(
                    "Money moved between your own accounts — not counted as spend or income.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (isIncome && !isTransfer) {
                Spacer(Modifier.height(6.dp))
                RefundPicker(all, parsedAmount, note, date, refundOf, onChange = { refundOf = it })
            }
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = amountText,
                onValueChange = { v ->
                    amountText = v.filter { it.isDigit() || it == '.' || it == '+' || it == ',' || it == ' ' }
                    amountError = false
                },
                label = { Text("Amount") },
                prefix = { Text("₹") },
                isError = amountError,
                supportingText = {
                    when {
                        amountError -> Text("Enter an amount, e.g. 120 or 115+120")
                        amountText.contains('+') && parsedAmount != null -> Text("= ${formatRupees(parsedAmount)}")
                    }
                },
                trailingIcon = {
                    IconButton(onClick = { if (amountText.isNotBlank() && !amountText.endsWith("+")) amountText += "+" }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add another amount")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(amountFocus),
            )

            OutlinedTextField(
                value = note,
                onValueChange = {
                    note = it
                    if (!categoryPicked) category = vm.suggestCategory(it)
                },
                label = { Text("Note") },
                placeholder = { Text("Lunch, Rajma rice") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (suggestions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { s ->
                        SuggestionChip(
                            onClick = {
                                note = s.note
                                category = s.category
                                categoryPicked = true
                                if (amountText.isBlank()) amountText = paiseToInput(s.amountPaise)
                            },
                            label = { Text("${s.note} · ${formatRupees(s.amountPaise)}", maxLines = 1) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Date", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
                AssistChip(
                    onClick = { showDatePicker = true },
                    label = { Text(formatShortDate(date) + "  (" + formatDateChip(date) + ")") },
                    leadingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null, Modifier.size(18.dp)) },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Time", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
                val t = time
                if (t == null) {
                    AssistChip(
                        onClick = { showTimePicker = true },
                        label = { Text("Add time") },
                        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp)) },
                    )
                } else {
                    AssistChip(
                        onClick = { showTimePicker = true },
                        label = { Text(formatTime(t.hour, t.minute)) },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Clear time",
                                Modifier.size(18.dp).clickable { time = null; timeTouched = true },
                            )
                        },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Place", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
                PlaceChip(
                    place = place,
                    recent = recentPlaces,
                    autoHint = existing == null && settingsNow.locationTagging && date == LocalDate.now(),
                    onChange = { place = it },
                )
            }
            Spacer(Modifier.height(8.dp))

            if (category == Category.SAVINGS || goalId != null) {
                com.expensetracker.app.goals.GoalPickerRow(vm, goalId, onChange = { goalId = it })
            }
            Text("Category", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Category.entries.forEach { c ->
                    FilterChip(
                        selected = c == category,
                        onClick = { category = c; categoryPicked = true },
                        label = { Text("${c.emoji} ${c.label}") },
                    )
                }
            }

            if (!isIncome && !isTransfer) {
                Spacer(Modifier.height(8.dp))
                Text("Counts as", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = spendOverride == null,
                        onClick = { spendOverride = null },
                        label = { Text("Auto" + (autoInfo?.let { " \u00b7 ${it.type.emoji} ${it.type.label}" } ?: "")) },
                    )
                    listOf(SpendType.EVERYDAY, SpendType.ONE_OFF, SpendType.FIXED).forEach { t ->
                        FilterChip(
                            selected = spendOverride == t,
                            onClick = { spendOverride = t },
                            label = { Text("${t.emoji} ${t.label}") },
                        )
                    }
                }
                Text(
                    when {
                        spendOverride != null -> "Your choice. Auto would say: " +
                            (autoInfo?.let { "${it.type.label} (${it.reason})" } ?: "\u2014")
                        autoInfo != null -> autoInfo.reason + when (autoInfo.type) {
                            SpendType.EVERYDAY -> " \u2014 counts in daily averages"
                            else -> " \u2014 left out of everyday averages"
                        }
                        else -> "Enter an amount to see how it counts"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                if (existing != null) {
                    OutlinedButton(
                        onClick = {
                            vm.delete(existing)
                            onDeleted(existing)
                            close()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                Button(onClick = { save() }, modifier = Modifier.weight(1f)) {
                    Text(if (existing == null) "Add" else "Save")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showDatePicker) {
        DatePickDialog(initial = date, onPick = { changeDate(it) }, onDismiss = { showDatePicker = false })
    }
    if (showTimePicker) {
        val init = time ?: LocalTime.now()
        TimePickDialog(
            hour = init.hour,
            minute = init.minute,
            onPick = { h, m -> time = LocalTime.of(h, m); timeTouched = true },
            onDismiss = { showTimePicker = false },
            title = "Time spent",
        )
    }
}
