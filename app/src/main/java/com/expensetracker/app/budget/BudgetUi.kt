package com.expensetracker.app.budget

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.expensetracker.app.data.BudgetLevel
import com.expensetracker.app.data.BudgetProgress
import com.expensetracker.app.data.Category
import com.expensetracker.app.data.CategoryRegistry
import com.expensetracker.app.ui.formatRupeesWhole
import com.expensetracker.app.ui.kit.LinearProgressIndicator
import com.expensetracker.app.ui.paiseToInput
import com.expensetracker.app.ui.parseAmountInput

/** Amber for "near the limit"; readable on light and dark cards alike. */
private val NearColor = Color(0xFFF9A825)

@Composable
fun budgetLevelColor(p: BudgetProgress): Color = when (p.level) {
    BudgetLevel.OK -> Color(p.category.color)
    BudgetLevel.NEAR -> NearColor
    BudgetLevel.OVER -> MaterialTheme.colorScheme.error
}

/** One row per budgeted category: name, "₹spent / ₹limit", a bar and what's left (or over). */
@Composable
fun CategoryBudgetList(progress: List<BudgetProgress>, isCurrentMonth: Boolean) {
    Column {
        progress.forEach { p ->
            Column(Modifier.padding(vertical = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${p.category.emoji} ${p.category.label}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${formatRupeesWhole(p.spentPaise)} / ${formatRupeesWhole(p.limitPaise)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                LinearProgressIndicator(
                    progress = { p.fraction.coerceIn(0f, 1f) },
                    color = budgetLevelColor(p),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().height(8.dp).padding(top = 3.dp),
                    drawStopIndicator = {},
                )
                val pct = (p.fraction * 100).toInt()
                Text(
                    when (p.level) {
                        BudgetLevel.OVER -> "$pct% · ${formatRupeesWhole(-p.leftPaise)} over"
                        else -> "$pct% · ${formatRupeesWhole(p.leftPaise)} ${if (isCurrentMonth) "left" else "under"}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (p.level == BudgetLevel.OVER) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** Edit every category's monthly limit at once. Empty = no limit. */
@Composable
fun CategoryBudgetsDialog(current: Map<String, Long>, onDismiss: () -> Unit, onSave: (Map<String, Long>) -> Unit) {
    val cats = remember {
        val visible = Category.entries
        visible + current.keys.filter { id -> visible.none { it.id == id } && CategoryRegistry.exists(id) }.map { Category.fromId(it) }
    }
    val texts = remember {
        mutableStateMapOf<String, String>().apply { current.forEach { (id, v) -> put(id, paiseToInput(v)) } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Category budgets") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Monthly limit per category. Leave empty for no limit. You get a nudge at 80% and 100%.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                cats.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Text(
                            "${c.emoji} ${c.label}",
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedTextField(
                            value = texts[c.id] ?: "",
                            onValueChange = { v -> texts[c.id] = v.filter { it.isDigit() || it == '.' || it == ',' } },
                            prefix = { Text("₹") },
                            placeholder = { Text("—") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.width(130.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val out = texts.mapNotNull { (id, t) -> parseAmountInput(t)?.takeIf { it > 0 }?.let { id to it } }.toMap()
                onSave(out)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
