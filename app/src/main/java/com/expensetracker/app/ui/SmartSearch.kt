package com.expensetracker.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.SmartQuery

/** What the search understood, as chips; tapping one removes that part of the query. */
@Composable
fun SmartQueryChips(q: SmartQuery, onRemove: (SmartQuery.Part) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        q.parts.forEach { p ->
            InputChip(
                selected = !p.isText,
                onClick = { onRemove(p) },
                label = { Text(p.label) },
                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove ${p.label}", Modifier.size(16.dp)) },
            )
        }
    }
}

/**
 * "12 entries · ₹4,250 spent · ₹500 received". Totals go through the ledger ([counted]):
 * refunds net off their purchase, transfers count as nothing. A refunds/transfers-only result
 * (nothing left after netting) shows its plain sum instead.
 */
fun smartSummary(visible: List<Expense>, counted: List<Expense>): String {
    val (spent, received) = smartTotals(visible, counted)
    val n = "${visible.size} ${if (visible.size == 1) "entry" else "entries"}"
    val bits = buildList {
        if (spent > 0) add("${formatRupees(spent)} spent")
        if (received > 0) add("${formatRupees(received)} received")
        if (isEmpty() && visible.isNotEmpty()) add(formatRupees(visible.sumOf { it.amountPaise }))
    }
    return (listOf(n) + bits).joinToString(" · ")
}

/** Spent and received among [visible], as the ledger counts them. */
fun smartTotals(visible: List<Expense>, counted: List<Expense>): Pair<Long, Long> {
    val ids = visible.mapTo(HashSet()) { it.id }
    val mine = counted.filter { it.id in ids }
    return mine.filter { !it.isIncome }.sumOf { it.amountPaise } to mine.filter { it.isIncome }.sumOf { it.amountPaise }
}
