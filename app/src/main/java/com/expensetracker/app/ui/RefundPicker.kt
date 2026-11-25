package com.expensetracker.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.Ledger
import java.time.LocalDate

/**
 * "This is a refund" for money received: pick the expense it pays back. Best matches (same
 * amount / same merchant) come first. [refundOf] null = not a refund.
 */
@Composable
fun RefundPicker(
    all: List<Expense>,
    amountPaise: Long?,
    note: String,
    date: LocalDate,
    refundOf: Long?,
    onChange: (Long?) -> Unit,
) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val candidates = remember(all, amountPaise, note, date) {
        Ledger.refundCandidates(all, amountPaise ?: 0, note, date)
    }
    val linked = refundOf?.let { id -> all.firstOrNull { it.id == id } }
    Column {
        FilterChip(
            selected = refundOf != null,
            onClick = { onChange(if (refundOf != null) null else candidates.firstOrNull()?.id ?: -1L) },
            label = { Text("↩ This is a refund") },
        )
        if (refundOf != null) {
            Text(
                if (linked != null) "Refund of ${linked.note.ifBlank { linked.category.label }} · ${formatRupees(linked.amountPaise)} on ${formatShortDate(linked.date)} — lowers that spend instead of counting as income"
                else "Pick the expense this pays back",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val shown = if (showAll) candidates else candidates.take(6)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                shown.forEach { e ->
                    FilterChip(
                        selected = e.id == refundOf,
                        onClick = { onChange(e.id) },
                        label = { Text("${e.note.ifBlank { e.category.label }} · ${formatRupees(e.amountPaise)} · ${formatDateChip(e.date)}", maxLines = 1) },
                    )
                }
            }
            if (candidates.size > 6) {
                TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Fewer" else "More expenses") }
            }
            if (candidates.isEmpty()) {
                Text("No expenses before this date to link to.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
