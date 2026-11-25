package com.expensetracker.app.rules

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.expensetracker.app.ExpenseViewModel
import com.expensetracker.app.data.AutoRule
import com.expensetracker.app.data.Category
import com.expensetracker.app.data.CategoryRegistry
import com.expensetracker.app.data.RuleKind
import com.expensetracker.app.data.SpendType
import com.expensetracker.app.ui.formatRupees
import com.expensetracker.app.ui.paiseToInput
import com.expensetracker.app.ui.parseAmountInput

/** Settings → Smart features → Auto-tagging rules. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoRulesScreen(vm: ExpenseViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val settings by vm.settings.collectAsState()
    val rules = settings.autoRules
    var editing by remember { mutableStateOf<AutoRule?>(null) }
    var confirmPast by remember { mutableStateOf<Int?>(null) }
    var deleting by remember { mutableStateOf<AutoRule?>(null) }

    fun save(list: List<AutoRule>) = vm.setAutoRules(list)
    fun move(i: Int, d: Int) {
        val j = i + d
        if (j !in rules.indices) return
        save(rules.toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Auto-tagging rules") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = AutoRule(id = System.currentTimeMillis(), keywords = emptyList()) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("New rule") },
            )
        },
    ) { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp)) {
            item {
                Text(
                    "New entries whose note contains a keyword get the rule's category, \"counts as\" and name. " +
                        "The first matching rule wins, and rules beat the app's own guess. In the add sheet you can still change anything.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            if (rules.isEmpty()) {
                item {
                    Text("No rules yet. Tap New rule — e.g. keyword \"rapido\" → Travel, counts as Everyday.",
                        style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 24.dp))
                }
            } else {
                item {
                    OutlinedButton(
                        onClick = { confirmPast = vm.ruleChangesForPast(rules).size },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    ) { Text("Apply to past entries…") }
                }
            }
            itemsIndexed(rules, key = { _, r -> r.id }) { i, r ->
                Card(onClick = { editing = r }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("${i + 1}. " + r.keywords.joinToString(" or ") { "“$it”" }, fontWeight = FontWeight.SemiBold)
                            describeWhen(r)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Text("→ " + describeThen(r), style = MaterialTheme.typography.bodyMedium)
                        }
                        Column {
                            IconButton(onClick = { move(i, -1) }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
                            IconButton(onClick = { move(i, 1) }, enabled = i < rules.lastIndex) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
                        }
                        IconButton(onClick = { deleting = r }) { Icon(Icons.Filled.Delete, "Delete") }
                    }
                }
            }
        }
    }

    editing?.let { r ->
        RuleDialog(
            initial = r,
            onDismiss = { editing = null },
            onSave = { n ->
                val i = rules.indexOfFirst { it.id == n.id }
                save(if (i >= 0) rules.toMutableList().also { it[i] = n } else rules + n)
                editing = null
            },
        )
    }
    deleting?.let { r ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete rule?") },
            text = { Text(r.keywords.joinToString(" or ") { "“$it”" } + " → " + describeThen(r)) },
            confirmButton = { TextButton(onClick = { save(rules.filter { it.id != r.id }); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    confirmPast?.let { n ->
        AlertDialog(
            onDismissRequest = { confirmPast = null },
            title = { Text("Apply to past entries") },
            text = {
                Text(if (n == 0) "No past entries would change." else
                    "$n past ${if (n == 1) "entry" else "entries"} would change (category, \"counts as\" or name). This can't be undone in one tap.")
            },
            confirmButton = {
                if (n > 0) TextButton(onClick = {
                    confirmPast = null
                    vm.applyRulesToPast(rules) { c -> Toast.makeText(ctx, "Updated $c ${if (c == 1) "entry" else "entries"}", Toast.LENGTH_SHORT).show() }
                }) { Text("Change $n") }
                else TextButton(onClick = { confirmPast = null }) { Text("OK") }
            },
            dismissButton = { if (n > 0) TextButton(onClick = { confirmPast = null }) { Text("Cancel") } },
        )
    }
}

private fun describeWhen(r: AutoRule): String? {
    val parts = buildList {
        when (r.kind) { RuleKind.EXPENSE -> add("expenses only"); RuleKind.INCOME -> add("income only"); RuleKind.ANY -> {} }
        if (r.minPaise != null && r.maxPaise != null) add("${formatRupees(r.minPaise)}–${formatRupees(r.maxPaise)}")
        else if (r.minPaise != null) add("at least ${formatRupees(r.minPaise)}")
        else if (r.maxPaise != null) add("up to ${formatRupees(r.maxPaise)}")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun describeThen(r: AutoRule): String = buildList {
    r.categoryId?.let { val c = Category.fromId(it); add("${c.emoji} ${c.label}") }
    r.countsAs?.let { add("counts as ${it.label}") }
    r.rename?.takeIf { it.isNotBlank() }?.let { add("rename to “$it”") }
}.ifEmpty { listOf("nothing yet") }.joinToString(" · ")

@Composable
private fun RuleDialog(initial: AutoRule, onDismiss: () -> Unit, onSave: (AutoRule) -> Unit) {
    var keywords by rememberSaveable { mutableStateOf(initial.keywords.joinToString(", ")) }
    var min by rememberSaveable { mutableStateOf(initial.minPaise?.let { paiseToInput(it) } ?: "") }
    var max by rememberSaveable { mutableStateOf(initial.maxPaise?.let { paiseToInput(it) } ?: "") }
    var kind by rememberSaveable { mutableStateOf(initial.kind) }
    var categoryId by rememberSaveable { mutableStateOf(initial.categoryId) }
    var countsAs by rememberSaveable { mutableStateOf(initial.countsAs) }
    var rename by rememberSaveable { mutableStateOf(initial.rename ?: "") }

    val kw = keywords.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val minP = min.takeIf { it.isNotBlank() }?.let { parseAmountInput(it) }
    val maxP = max.takeIf { it.isNotBlank() }?.let { parseAmountInput(it) }
    val badAmount = (min.isNotBlank() && minP == null) || (max.isNotBlank() && maxP == null) || (minP != null && maxP != null && minP > maxP)
    val rule = initial.copy(keywords = kw, minPaise = minP, maxPaise = maxP, kind = kind, categoryId = categoryId, countsAs = countsAs,
        rename = rename.trim().ifEmpty { null })
    val canSave = kw.isNotEmpty() && !badAmount && rule.hasAction

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.keywords.isEmpty()) "New rule" else "Edit rule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("When the note contains", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(keywords, { keywords = it }, label = { Text("Keywords, comma-separated") },
                    placeholder = { Text("swiggy, zomato") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(min, { min = it }, label = { Text("Min ₹") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(max, { max = it }, label = { Text("Max ₹") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = badAmount)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RuleKind.entries.forEach { k ->
                        FilterChip(kind == k, { kind = k }, label = { Text(when (k) { RuleKind.ANY -> "Any"; RuleKind.EXPENSE -> "Expenses"; RuleKind.INCOME -> "Income" }) })
                    }
                }
                Text("Then set category", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(categoryId == null, { categoryId = null }, label = { Text("Don't change") })
                    CategoryRegistry.visible().forEach { c ->
                        FilterChip(categoryId == c.id, { categoryId = c.id }, label = { Text("${c.emoji} ${c.label}") })
                    }
                }
                Text("Counts as", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(countsAs == null, { countsAs = null }, label = { Text("Auto") })
                    SpendType.entries.forEach { t ->
                        FilterChip(countsAs == t, { countsAs = t }, label = { Text("${t.emoji} ${t.label}") })
                    }
                }
                OutlinedTextField(rename, { rename = it }, label = { Text("Rename note to (optional)") },
                    placeholder = { Text("Swiggy") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (kw.isNotEmpty() && !rule.hasAction) Text("Pick at least one thing to set.", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(rule) }, enabled = canSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
