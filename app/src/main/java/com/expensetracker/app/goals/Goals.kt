@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.expensetracker.app.goals

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.expensetracker.app.ExpenseViewModel
import com.expensetracker.app.data.Expense
import com.expensetracker.app.ui.ExpenseRow
import com.expensetracker.app.ui.formatRupees
import com.expensetracker.app.ui.formatShortDate
import com.expensetracker.app.ui.incomeColor
import com.expensetracker.app.ui.paiseToInput
import com.expensetracker.app.ui.parseAmountInput
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
private fun rememberGoals(): List<Goal> {
    val ctx = LocalContext.current
    remember { GoalStore.ensureLoaded(ctx) }
    return GoalStore.goals.collectAsStateWithLifecycle().value
}

/** Create/edit/delete goals, see progress. */
@Composable
fun GoalsScreen(vm: ExpenseViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val goals = rememberGoals()
    val expenses by vm.expenses.collectAsStateWithLifecycle()
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Goal?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    val today = LocalDate.now()

    val detail = goals.firstOrNull { it.id == detailId }
    if (detail != null) {
        BackHandler { detailId = null }
        GoalDetail(vm, detail, expenses, onBack = { detailId = null }, onEdit = { editing = detail })
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Savings goals") },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { creating = true },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("New goal") },
                )
            },
        ) { p ->
            val active = goals.filter { !it.archived }
            val archived = goals.filter { it.archived }
            LazyColumn(Modifier.fillMaxSize().padding(p), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp)) {
                if (goals.isEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🎯", style = MaterialTheme.typography.displaySmall)
                            Spacer(Modifier.height(8.dp))
                            Text("No goals yet", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Make one for a trip, an emergency fund or a gadget. Then link Savings entries " +
                                    "(FDs, RDs, transfers) to it in the add sheet and watch it fill up.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
                items(active, key = { it.id }) { g ->
                    val idx = active.indexOf(g)
                    GoalListItem(
                        g, GoalMath.progress(g, expenses, today),
                        onClick = { detailId = g.id },
                        onUp = if (idx > 0) ({ GoalStore.move(ctx, g.id, goals.indexOf(active[idx - 1]) - goals.indexOf(g)) }) else null,
                        onDown = if (idx < active.size - 1) ({ GoalStore.move(ctx, g.id, goals.indexOf(active[idx + 1]) - goals.indexOf(g)) }) else null,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                if (archived.isNotEmpty()) {
                    item {
                        TextButton(onClick = { showArchived = !showArchived }) {
                            Text((if (showArchived) "Hide" else "Show") + " archived (${archived.size})")
                        }
                    }
                    if (showArchived) items(archived, key = { it.id }) { g ->
                        GoalListItem(g, GoalMath.progress(g, expenses, today), onClick = { detailId = g.id }, onUp = null, onDown = null)
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        GoalEditDialog(
            initial = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { g -> GoalStore.upsert(ctx, g); creating = false; editing = null },
        )
    }
}

@Composable
private fun GoalListItem(goal: Goal, pr: GoalProgress, onClick: () -> Unit, onUp: (() -> Unit)?, onDown: (() -> Unit)?) {
    val color = Color(goal.color)
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GoalEmoji(goal)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(goal.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${formatRupees(pr.savedPaise)} of ${formatRupees(goal.targetPaise)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onUp != null || onDown != null) {
                    IconButton(onClick = { onUp?.invoke() }, enabled = onUp != null) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
                    IconButton(onClick = { onDown?.invoke() }, enabled = onDown != null) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
                }
            }
            Spacer(Modifier.height(10.dp))
            GoalBar(pr.fraction, color)
            Spacer(Modifier.height(6.dp))
            Row {
                Text(
                    "${(pr.fraction * 100).toInt()}%" + if (pr.remainingPaise > 0) " · ${formatRupees(pr.remainingPaise)} to go" else "",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                StatusText(goal, pr)
            }
            projectionLine(goal, pr)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun GoalEmoji(goal: Goal, size: Int = 40) {
    Box(
        Modifier.size(size.dp).background(Color(goal.color).copy(alpha = 0.22f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(goal.emoji, style = if (size >= 40) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun GoalBar(fraction: Float, color: Color, height: Int = 10) {
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier.fillMaxWidth().height(height.dp),
        color = color,
        trackColor = color.copy(alpha = 0.18f),
        drawStopIndicator = {},
        gapSize = 0.dp,
    )
}

@Composable
private fun StatusText(goal: Goal, pr: GoalProgress) {
    if (goal.archived) {
        Text("Archived", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val (text, color) = when (pr.status) {
        GoalStatus.DONE -> pr.status.label to incomeColor()
        GoalStatus.ON_TRACK -> "✓ On track" to incomeColor()
        GoalStatus.BEHIND -> "⚠ Behind" to MaterialTheme.colorScheme.error
        GoalStatus.OVERDUE -> "⚠ Past deadline" to MaterialTheme.colorScheme.error
        GoalStatus.NOT_STARTED -> "Not started" to MaterialTheme.colorScheme.onSurfaceVariant
        GoalStatus.NO_DEADLINE -> "" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.SemiBold)
}

private val monthYear = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

/** "Reach it by Mar 2027 at ₹5,000/month · need ₹6,200/month for Dec 2026". */
private fun projectionLine(goal: Goal, pr: GoalProgress): String? {
    if (pr.status == GoalStatus.DONE || goal.archived) return null
    val parts = mutableListOf<String>()
    if (pr.projectedDate != null && pr.monthlyRatePaise != null) {
        parts += "At ${formatRupees(pr.monthlyRatePaise)}/month${if (pr.rateFromPlan) " (plan)" else ""}: ${pr.projectedDate.format(monthYear)}"
    }
    if (pr.neededPerMonthPaise != null && goal.deadline != null && pr.status != GoalStatus.ON_TRACK) {
        parts += if (pr.status == GoalStatus.OVERDUE) "deadline was ${formatShortDate(goal.deadline)}"
        else "need ${formatRupees(pr.neededPerMonthPaise)}/month for ${goal.deadline.format(monthYear)}"
    } else if (goal.deadline != null && pr.projectedDate == null) {
        parts += "by ${goal.deadline.format(monthYear)}"
    }
    return parts.joinToString(" · ").ifEmpty { null }
}

@Composable
private fun GoalDetail(vm: ExpenseViewModel, goal: Goal, expenses: List<Expense>, onBack: () -> Unit, onEdit: () -> Unit) {
    val ctx = LocalContext.current
    val spendInfo by vm.spendInfo.collectAsStateWithLifecycle()
    val pr = GoalMath.progress(goal, expenses, LocalDate.now())
    val linked = remember(expenses, goal.id) { expenses.filter { it.goalId == goal.id }.sortedByDescending { it.date } }
    var adjust by remember { mutableStateOf<Int?>(null) } // +1 add, -1 withdraw
    var confirmDelete by remember { mutableStateOf(false) }
    val color = Color(goal.color)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(goal.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Edit") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
                },
            )
        },
    ) { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GoalEmoji(goal, 56)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(formatRupees(pr.savedPaise), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("of ${formatRupees(goal.targetPaise)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StatusText(goal, pr)
                    }
                    Spacer(Modifier.height(12.dp))
                    GoalBar(pr.fraction, color, 14)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${(pr.fraction * 100).toInt()}%" + if (pr.remainingPaise > 0) " · ${formatRupees(pr.remainingPaise)} to go" else " · done!",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    projectionLine(goal, pr)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(Modifier.height(8.dp))
                    val facts = buildList {
                        goal.deadline?.let { add("Deadline: ${formatShortDate(it)}") }
                        if (goal.monthlyPlanPaise > 0) add("Plan: ${formatRupees(goal.monthlyPlanPaise)}/month")
                        if (goal.startingPaise != 0L) add("Starting balance: ${formatRupees(goal.startingPaise)}")
                    }
                    if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { adjust = 1 }) { Text("+ Add money") }
                        OutlinedButton(onClick = { adjust = -1 }) { Text("− Withdraw") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { GoalStore.upsert(ctx, goal.copy(archived = !goal.archived)) }) {
                            Text(if (goal.archived) "Unarchive" else "Archive")
                        }
                    }
                    Text(
                        "How it counts: Savings entries you link to this goal in the add/edit sheet (“Counts towards”) add to it; " +
                            "a linked income entry counts as money taken out. Use Add money / Withdraw for anything you didn't log as an entry.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
            if (goal.adjustments.isNotEmpty()) {
                item { SectionLabel("Manual changes") }
                items(goal.adjustments.sortedByDescending { it.date }) { a ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.note.ifBlank { if (a.amountPaise >= 0) "Added" else "Withdrawn" }, style = MaterialTheme.typography.bodyLarge)
                            Text(formatShortDate(a.date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            (if (a.amountPaise >= 0) "+" else "") + formatRupees(a.amountPaise),
                            fontWeight = FontWeight.SemiBold,
                            color = if (a.amountPaise >= 0) incomeColor() else MaterialTheme.colorScheme.error,
                        )
                        IconButton(onClick = { GoalStore.upsert(ctx, goal.copy(adjustments = goal.adjustments - a)) }) {
                            Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item { SectionLabel(if (linked.isEmpty()) "No linked entries yet" else "Linked entries (${linked.size})") }
            items(linked, key = { it.id }) { e -> ExpenseRow(e, onClick = {}, showDate = true, spend = spendInfo[e.id]) }
        }
    }

    adjust?.let { sign ->
        AdjustDialog(
            withdraw = sign < 0,
            onDismiss = { adjust = null },
            onSave = { amount, note ->
                GoalStore.upsert(ctx, goal.copy(adjustments = goal.adjustments + GoalAdjustment(LocalDate.now(), sign * amount, note)))
                adjust = null
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${goal.name}”?") },
            text = { Text("Linked entries stay in your history; they just won't count towards a goal any more. Archive it instead to keep its progress.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    GoalStore.remove(ctx, goal.id)
                    vm.clearGoal(goal.id)
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun AdjustDialog(withdraw: Boolean, onDismiss: () -> Unit, onSave: (Long, String) -> Unit) {
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val paise = parseAmountInput(amount)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (withdraw) "Take money out" else "Add money") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    amount, { amount = it }, label = { Text("Amount (₹)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, singleLine = true)
                Text(
                    if (withdraw) "Reduces this goal's progress. It doesn't add an expense entry."
                    else "Counts towards this goal without adding an expense entry (e.g. interest, cash you set aside).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(enabled = paise != null && paise > 0, onClick = { onSave(paise!!, note.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val goalEmojis = listOf("🎯", "🏖️", "✈️", "🏠", "🚗", "💻", "📱", "🎓", "💍", "🛟", "🏍️", "🎁")

/** Create (initial == null) or edit a goal. */
@Composable
internal fun GoalEditDialog(initial: Goal?, onDismiss: () -> Unit, onSave: (Goal) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var emoji by remember { mutableStateOf(initial?.emoji ?: "🎯") }
    var target by remember { mutableStateOf(initial?.targetPaise?.let(::paiseToInput) ?: "") }
    var starting by remember { mutableStateOf(initial?.startingPaise?.takeIf { it != 0L }?.let(::paiseToInput) ?: "") }
    var plan by remember { mutableStateOf(initial?.monthlyPlanPaise?.takeIf { it > 0 }?.let(::paiseToInput) ?: "") }
    var deadline by remember { mutableStateOf(initial?.deadline) }
    var color by remember { mutableStateOf(initial?.color ?: GoalColors.first()) }
    var pickDate by remember { mutableStateOf(false) }

    val targetPaise = parseAmountInput(target)
    val startingPaise = if (starting.isBlank()) 0L else parseAmountInput(starting)
    val planPaise = if (plan.isBlank()) 0L else parseAmountInput(plan)
    val valid = name.isNotBlank() && targetPaise != null && targetPaise > 0 && startingPaise != null && planPaise != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New goal" else "Edit goal") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    goalEmojis.forEach { e ->
                        Box(
                            Modifier.size(36.dp)
                                .background(if (e == emoji) Color(color).copy(alpha = 0.3f) else Color.Transparent, CircleShape)
                                .clickable { emoji = e },
                            contentAlignment = Alignment.Center,
                        ) { Text(e) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(emoji, { emoji = it.take(8) }, label = { Text("Icon") }, singleLine = true, modifier = Modifier.width(72.dp))
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, placeholder = { Text("Goa trip") })
                }
                MoneyField(target, { target = it }, "Target (₹)")
                MoneyField(starting, { starting = it }, "Already saved (₹, optional)")
                MoneyField(plan, { plan = it }, "Plan per month (₹, optional)")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(onClick = { pickDate = true }, label = { Text(deadline?.let { "Deadline: ${formatShortDate(it)}" } ?: "Add deadline (optional)") })
                    if (deadline != null) TextButton(onClick = { deadline = null }) { Text("Clear") }
                }
                Text("Colour", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GoalColors.forEach { c ->
                        Box(
                            Modifier.size(32.dp)
                                .background(Color(c), CircleShape)
                                .then(if (c == color) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .clickable { color = c },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val base = initial ?: Goal(id = GoalStore.newId(), name = "", targetPaise = 0)
                onSave(
                    base.copy(
                        name = name.trim(), emoji = emoji.trim().ifEmpty { "🎯" }, targetPaise = targetPaise!!,
                        startingPaise = startingPaise!!, monthlyPlanPaise = planPaise!!, deadline = deadline, color = color,
                    ),
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (pickDate) FutureDateDialog(deadline ?: LocalDate.now().plusMonths(6), onPick = { deadline = it }, onDismiss = { pickDate = false })
}

@Composable
private fun MoneyField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true,
        isError = value.isNotBlank() && parseAmountInput(value) == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Date picker that only allows today or later (deadlines). */
@Composable
private fun FutureDateDialog(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val todayMillis = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayMillis
            override fun isSelectableYear(year: Int) = year >= LocalDate.now().year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state = state) }
}

/** Compact progress card at the top of Stats. Renders nothing when there are no goals. */
@Composable
fun GoalsCard(vm: ExpenseViewModel, onOpen: () -> Unit) {
    val goals = rememberGoals().filter { !it.archived }.take(3)
    if (goals.isEmpty()) return
    val expenses by vm.expenses.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row {
                Text("Savings goals", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("See all ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            goals.forEach { g ->
                val pr = GoalMath.progress(g, expenses, today)
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${g.emoji} ${g.name}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(
                            "${(pr.fraction * 100).toInt()}%" + if (pr.remainingPaise > 0) " · ${formatRupees(pr.remainingPaise)} to go" else " 🎉",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (pr.status == GoalStatus.BEHIND || pr.status == GoalStatus.OVERDUE) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    GoalBar(pr.fraction, Color(g.color), 6)
                }
            }
        }
    }
}

/**
 * Row in the add/edit sheet to link an entry to a goal ("Counts towards: Goa trip").
 * Shown by the sheet when the category is Savings or a goal is already set.
 */
@Composable
fun GoalPickerRow(vm: ExpenseViewModel, goalId: String?, onChange: (String?) -> Unit) {
    val ctx = LocalContext.current
    val goals = rememberGoals()
    var picking by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    val current = goals.firstOrNull { it.id == goalId }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        Text("Goal", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
        AssistChip(
            onClick = { if (goals.isEmpty()) creating = true else picking = true },
            label = {
                Text(
                    when {
                        current != null -> "Counts towards: ${current.emoji} ${current.name}"
                        goalId != null -> "Counts towards: (deleted goal)"
                        goals.isEmpty() -> "Counts towards a goal? Create one…"
                        else -> "Counts towards: none"
                    },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            },
        )
    }

    if (picking) {
        val expenses = vm.expenses.collectAsStateWithLifecycle().value
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Counts towards") },
            text = {
                LazyColumn {
                    item { PickRow("—", "None", null, goalId == null) { onChange(null); picking = false } }
                    items(goals.filter { !it.archived || it.id == goalId }, key = { it.id }) { g ->
                        val pr = GoalMath.progress(g, expenses)
                        PickRow(g.emoji, g.name, "${(pr.fraction * 100).toInt()}% · ${formatRupees(pr.savedPaise)} of ${formatRupees(g.targetPaise)}", g.id == goalId) {
                            onChange(g.id); picking = false
                        }
                    }
                    item { PickRow("➕", "New goal…", null, false) { picking = false; creating = true } }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Close") } },
        )
    }
    if (creating) {
        GoalEditDialog(
            initial = null,
            onDismiss = { creating = false },
            onSave = { g -> GoalStore.upsert(ctx, g); onChange(g.id); creating = false },
        )
    }
}

@Composable
private fun PickRow(emoji: String, title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, modifier = Modifier.width(32.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
