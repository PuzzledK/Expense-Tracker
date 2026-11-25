@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.expensetracker.app.ui

import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.rememberLazyListState

import com.expensetracker.app.ui.kit.themedSurface
import com.expensetracker.app.ui.kit.defaultCardColor

import androidx.compose.ui.unit.sp

import androidx.compose.ui.graphics.lerp

import androidx.compose.ui.draw.rotate

import androidx.compose.ui.draw.clip

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.layout.offset

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Search
import com.expensetracker.app.ui.kit.AssistChip
import com.expensetracker.app.ui.kit.FilterChip
import com.expensetracker.app.data.CategoryRegistry
import com.expensetracker.app.ui.kit.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import com.expensetracker.app.ui.kit.FloatingActionButton
import com.expensetracker.app.ui.kit.SmallFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.expensetracker.app.ui.kit.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.expensetracker.app.ui.kit.SuggestionChip
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import com.expensetracker.app.ui.kit.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.expensetracker.app.ExpenseViewModel
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.Settings
import com.expensetracker.app.data.SpendInfo
import com.expensetracker.app.data.SpendType
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun HomeScreen(
    vm: ExpenseViewModel,
    month: YearMonth,
    onMonthChange: (YearMonth) -> Unit,
    snackbar: SnackbarHostState,
    bottomBar: @Composable () -> Unit,
    onAdd: () -> Unit,
    onOpen: (Overlay) -> Unit = {},
    onBulkAdd: (LocalDate) -> Unit,
    onEdit: (Expense) -> Unit,
    onOpenImport: () -> Unit,
) {
    val expenses by vm.expenses.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val templates by vm.templates.collectAsStateWithLifecycle()
    val spendInfo by vm.spendInfo.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }

    var filterState by rememberSaveable { mutableStateOf("") }
    val filter = remember(filterState) { ListFilter.decode(filterState) }
    fun setFilter(f: ListFilter) { filterState = f.encode() }

    val today = LocalDate.now()
    val q = query.trim()
    val visible = remember(expenses, month, q, searching, filter, spendInfo) {
        val base = if (searching && q.isNotEmpty()) {
            expenses.filter {
                it.note.contains(q, ignoreCase = true) || it.category.label.contains(q, ignoreCase = true) ||
                    it.place?.contains(q, ignoreCase = true) == true
            }
        } else {
            expenses.filter { month.contains(it.date) }
        }
        if (filter.active) base.filter { filter.matches(it, spendInfo) } else base
    }
    val grouped = remember(visible) {
        visible.groupBy { it.date }.toSortedMap(compareByDescending { it })
    }

    fun deleteWithUndo(e: Expense) {
        vm.delete(e)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(
                "Deleted ${formatRupees(e.amountPaise)} · ${e.note}",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (r == SnackbarResult.ActionPerformed) vm.restore(e)
        }
    }

    fun announceAdded(e: Expense) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(
                "Added ${formatRupees(e.amountPaise)} · ${e.note}",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (r == SnackbarResult.ActionPerformed) undoAdd(vm, e)
        }
    }

    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 2 } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search all expenses") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                        )
                        LaunchedEffect(Unit) { searchFocus.requestFocus() }
                    } else {
                        Text("Expenses", fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    if (searching) {
                        IconButton(onClick = { searching = false; query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close search")
                        }
                    } else {
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search")
                        }
                    }
                },
            )
        },
        bottomBar = bottomBar,
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                SmallFloatingActionButton(
                    onClick = { onBulkAdd(if (month == YearMonth.from(today)) today else month.atEndOfMonth()) },
                ) {
                    Row(Modifier.padding(horizontal = 12.dp).animateContentSize(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Add multiple", Modifier.size(18.dp))
                        if (!scrolled) {
                            Spacer(Modifier.width(6.dp))
                            Text("Add multiple", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "Add expense") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 160.dp),
        ) {
            if (searching && q.isNotEmpty()) {
                item("filters") { ListFilterBar(filter, ::setFilter, expenses) }
                item("search-summary") {
                    val total = visible.filter { !it.isIncome }.sumOf { it.amountPaise }
                    Text(
                        "${visible.size} matches · ${formatRupees(total)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else if (!searching) {
                item("month") {
                    MonthSelector(month, onMonthChange, Modifier.padding(horizontal = 4.dp))
                }
                item("summary") {
                    SummaryCard(expenses, month, settings, today, spendInfo)
                }
                item("capture") {
                    com.expensetracker.app.capture.CaptureBanner(onOpen = { onOpen(Overlay.CAPTURE) })
                }
                item("insights") {
                    com.expensetracker.app.insights.InsightsCard(vm, onOpen = { onOpen(Overlay.INSIGHTS) })
                }
                item("quick") {
                    QuickAddBar(
                        defaultDate = if (month == YearMonth.from(today)) today else month.atEndOfMonth(),
                        onSubmit = { text, date ->
                            val e = vm.quickAdd(text, date)
                            if (e == null) {
                                scope.launch {
                                    snackbar.currentSnackbarData?.dismiss()
                                    snackbar.showSnackbar("No amount found — try “120 - Lunch”")
                                }
                                false
                            } else {
                                announceAdded(e)
                                true
                            }
                        },
                        templates = templates.map { t ->
                            "${t.category.emoji} ${t.note} · ${formatRupees(t.amountPaise)}" to { date: LocalDate ->
                                val e = Expense(
                                    amountPaise = t.amountPaise,
                                    note = t.note,
                                    category = t.category,
                                    date = date,
                                    time = if (date == LocalDate.now()) java.time.LocalTime.now().withSecond(0).withNano(0) else null,
                                )
                                vm.add(e)
                                announceAdded(e)
                            }
                        },
                    )
                }
                if (expenses.isNotEmpty()) {
                    item("filters") { ListFilterBar(filter, ::setFilter, expenses) }
                    if (filter.active && visible.isNotEmpty()) item("filter-summary") { FilterSummary(visible, filter) }
                }
                if (expenses.isEmpty()) {
                    item("empty") { EmptyState(onOpenImport) }
                } else if (visible.isEmpty()) {
                    item("empty-month") {
                        Text(
                            if (filter.active) "Nothing in ${formatMonth(month)} matches these filters." else "Nothing logged in ${formatMonth(month)}.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }

            grouped.forEach { (date, dayItems) ->
                stickyHeader(key = "h-$date") { DayHeader(date, dayItems, today, onAddMore = { onBulkAdd(date) }) }
                items(dayItems, key = { "e-${it.id}-${it.createdAt}" }) { e ->
                    SwipeableExpenseRow(
                        expense = e,
                        spend = spendInfo[e.id],
                        onClick = { onEdit(e) },
                        onDelete = { deleteWithUndo(e) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

/** Chips over the list: spend kind (Everyday, One-off…) and a category multi-select. */
@Composable
private fun ListFilterBar(filter: ListFilter, onChange: (ListFilter) -> Unit, all: List<Expense>) {
    var menu by remember { mutableStateOf(false) }
    // Categories you've actually used (plus any still selected), in your category order.
    val used = remember(all) { all.mapTo(HashSet()) { it.category.id } }
    val cats = CategoryRegistry.allInfo().filter { it.id in used || it.id in filter.categories }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item("cat") {
            Box {
                val sel = filter.categories
                FilterChip(
                    selected = sel.isNotEmpty(),
                    onClick = { menu = true },
                    label = {
                        Text(
                            when (sel.size) {
                                0 -> "Category"
                                1 -> CategoryRegistry.info(sel.first()).let { "${it.emoji} ${it.label}" }
                                else -> "${sel.size} categories"
                            },
                            maxLines = 1,
                        )
                    },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, Modifier.size(18.dp)) },
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (sel.isNotEmpty()) {
                        DropdownMenuItem(text = { Text("All categories") }, onClick = { onChange(filter.copy(categories = emptySet())); menu = false })
                        HorizontalDivider()
                    }
                    cats.forEach { c ->
                        val on = c.id in sel
                        DropdownMenuItem(
                            text = { Text("${c.emoji} ${c.label}") },
                            trailingIcon = { if (on) Icon(Icons.Filled.Check, contentDescription = "Selected") },
                            // Stays open so you can pick several.
                            onClick = { onChange(filter.copy(categories = if (on) sel - c.id else sel + c.id)) },
                        )
                    }
                }
            }
        }
        items(KindFilter.entries, key = { it.name }) { k ->
            FilterChip(
                selected = filter.kind == k,
                onClick = { onChange(filter.copy(kind = if (filter.kind == k) KindFilter.ALL else k)) },
                label = { Text(k.label) },
            )
        }
        if (filter.active) item("clear") {
            TextButton(onClick = { onChange(ListFilter()) }) { Text("Clear") }
        }
    }
}

/** "14 entries · ₹3,450 · ₹115/day" for the filtered list. */
@Composable
private fun FilterSummary(list: List<Expense>, filter: ListFilter) {
    val income = filter.kind == KindFilter.INCOME
    val total = list.filter { it.isIncome == income }.sumOf { it.amountPaise }
    val days = list.mapTo(HashSet()) { it.date }.size
    val perDay = if (!income && days > 0) " · ${formatRupeesWhole(total / days)}/day on days with spend" else ""
    Text(
        "${list.size} ${if (list.size == 1) "entry" else "entries"} · ${if (income) "+" else ""}${formatRupees(total)}$perDay",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** Removes an entry that was just added. The returned Expense may not carry the DB id yet. */
fun undoAdd(vm: ExpenseViewModel, e: Expense) {
    val target = if (e.id != 0L) e else vm.expenses.value.firstOrNull {
        it.createdAt == e.createdAt && it.amountPaise == e.amountPaise && it.note == e.note
    }
    target?.let { vm.delete(it) }
}

@Composable
private fun SummaryCard(all: List<Expense>, month: YearMonth, settings: Settings, today: LocalDate, info: Map<Long, SpendInfo>) {
    val style = LocalAppStyle.current
    val inMonth = all.filter { month.contains(it.date) }
    val split = splitSpend(inMonth, info)
    val spent = inMonth.filter { it.countsAsSpend(settings.excludeSavingsFromSpend) }.sumOf { it.amountPaise }
    val income = inMonth.filter { it.isIncome }.sumOf { it.amountPaise }
    val saved = if (settings.excludeSavingsFromSpend) {
        inMonth.filter { !it.isIncome && it.category == com.expensetracker.app.data.Category.SAVINGS }.sumOf { it.amountPaise }
    } else 0L
    val isCurrent = month == YearMonth.from(today)
    val todaySpent = all.filter { it.date == today && it.countsAsSpend(settings.excludeSavingsFromSpend) }.sumOf { it.amountPaise }
    val daysElapsed = if (isCurrent) today.dayOfMonth else month.lengthOfMonth()
    val onHero = style.onHero
    val shape = MaterialTheme.shapes.extraLarge
    val inset = if (style.hardShadow > 0.dp) style.hardShadow else 0.dp

    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp + inset, top = 4.dp, bottom = 8.dp + inset)
            .themedSurface(shape, style.hero, style),
    ) {
        // Big faint rupee mark for the soft themes; a slanted month sticker for neo-brutalism.
        if (!style.isBrutal) {
            Text(
                "₹",
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 160.sp, fontWeight = FontWeight.Black),
                color = onHero.copy(alpha = 0.08f),
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-34).dp),
            )
        } else {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .rotate(6f)
                    .themedSurface(MaterialTheme.shapes.extraSmall, style.accent(1), style, shadow = false)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(formatMonthShort(month).uppercase(), style = MaterialTheme.typography.labelMedium, color = textOn(style.accent(1)))
            }
        }
        Column(Modifier.padding(20.dp).animateContentSize()) {
            Text(
                if (style.capsLabels) "SPENT IN ${formatMonth(month).uppercase()}" else "Spent in ${formatMonth(month)}",
                style = MaterialTheme.typography.labelMedium,
                color = onHero.copy(alpha = 0.85f),
            )
            Text(
                formatRupees(spent),
                style = style.money(MaterialTheme.typography.displayMedium),
                color = onHero,
                maxLines = 1,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (isCurrent) StatPill("Today", formatRupees(todaySpent), Modifier.weight(1f))
                StatPill("Everyday / day", formatRupeesWhole(if (daysElapsed > 0) split.everyday / daysElapsed else 0), Modifier.weight(1f))
                if (income > 0) StatPill("Received", "+" + formatRupees(income), Modifier.weight(1f))
                else if (saved > 0) StatPill("Saved", formatRupees(saved), Modifier.weight(1f))
            }
            if (split.oneOff > 0 || split.fixed > 0) {
                Text(
                    listOfNotNull(
                        split.oneOff.takeIf { it > 0 }?.let { "${formatRupees(it)} one-offs" },
                        split.fixed.takeIf { it > 0 }?.let { "${formatRupees(it)} fixed" },
                    ).joinToString(" · ", prefix = "Incl. "),
                    style = MaterialTheme.typography.bodySmall,
                    color = onHero.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            if (settings.monthlyBudgetPaise > 0) {
                val budget = settings.monthlyBudgetPaise
                val frac = (spent.toFloat() / budget).coerceIn(0f, 1f)
                val left = budget - spent
                val over = left < 0
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier.fillMaxWidth().height(10.dp),
                    color = if (over) MaterialTheme.colorScheme.error else if (style.isBrutal) style.accent(3) else onHero,
                    trackColor = onHero.copy(alpha = 0.2f),
                    drawStopIndicator = {},
                )
                Spacer(Modifier.height(6.dp))
                val text = if (over) {
                    "Over budget by ${formatRupees(-left)} (budget ${formatRupees(budget)})"
                } else if (isCurrent) {
                    val daysLeft = month.lengthOfMonth() - today.dayOfMonth + 1
                    "${formatRupees(left)} left · ${formatRupeesWhole(left / daysLeft)}/day for $daysLeft days"
                } else {
                    "${formatRupees(left)} under budget of ${formatRupees(budget)}"
                }
                Text(text, style = MaterialTheme.typography.bodyMedium, color = onHero, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun StatPill(label: String, value: String, modifier: Modifier = Modifier) {
    val style = LocalAppStyle.current
    val shape = MaterialTheme.shapes.medium
    val bg = if (style.isBrutal) Color.White else style.onHero.copy(alpha = 0.14f)
    val fg = if (style.isBrutal) Color(0xFF111111) else style.onHero
    Column(
        modifier
            .then(if (style.isBrutal) Modifier.themedSurface(shape, bg, style, shadow = false) else Modifier.clip(shape).background(bg))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.8f), maxLines = 1)
        Text(value, style = style.money(MaterialTheme.typography.titleSmall), color = fg, maxLines = 1)
    }
}

/**
 * Notes-style quick entry: type "120 - Lunch" and hit send.
 * [templates] are (label, add-on-date) pairs for one-tap chips.
 */
@Composable
private fun QuickAddBar(
    defaultDate: LocalDate,
    onSubmit: (String, LocalDate) -> Boolean,
    templates: List<Pair<String, (LocalDate) -> Unit>>,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var date by remember(defaultDate) { mutableStateOf(defaultDate) }
    var menu by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val today = LocalDate.now()

    fun submit() {
        if (text.isBlank()) return
        if (onSubmit(text, date)) {
            text = ""
            focus.clearFocus()
        }
    }

    val style = LocalAppStyle.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 8.dp)) {
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Quick add · 120 - Lunch") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                val sendBg = if (style.isBrutal) style.accent(0) else MaterialTheme.colorScheme.primary
                val sendFill = if (style.isBrutal || text.isNotBlank()) sendBg else sendBg.copy(alpha = 0.35f)
                val sendFg = textOn(sendFill, behind = defaultCardColor(), preferred = if (style.isBrutal) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onPrimary)
                Box(
                    Modifier
                        .size(42.dp)
                        .then(
                            if (style.isBrutal) Modifier.themedSurface(MaterialTheme.shapes.small, sendBg, style, shadow = false)
                            else Modifier.clip(CircleShape).background(sendFill),
                        )
                        .clickable(enabled = text.isNotBlank()) { submit() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Add", tint = sendFg, modifier = Modifier.size(20.dp))
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Box {
                AssistChip(
                    onClick = { menu = true },
                    label = { Text(formatDateChip(date, today)) },
                    leadingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null, Modifier.size(18.dp)) },
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Today") }, onClick = { date = today; menu = false })
                    DropdownMenuItem(text = { Text("Yesterday") }, onClick = { date = today.minusDays(1); menu = false })
                    DropdownMenuItem(text = { Text("Pick a date…") }, onClick = { menu = false; picker = true })
                }
            }
            Spacer(Modifier.width(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(templates, key = { it.first }) { (label, add) ->
                    SuggestionChip(onClick = { add(date) }, label = { Text(label, maxLines = 1) })
                }
            }
        }
    }
    if (picker) DatePickDialog(initial = date, onPick = { date = it }, onDismiss = { picker = false })
}

@Composable
private fun DayHeader(date: LocalDate, items: List<Expense>, today: LocalDate, onAddMore: () -> Unit) {
    val style = LocalAppStyle.current
    val spent = items.filter { !it.isIncome }.sumOf { it.amountPaise }
    val income = items.filter { it.isIncome }.sumOf { it.amountPaise }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val label = formatDayHeader(date, today)
        if (style.isBrutal) {
            Box(
                Modifier
                    .themedSurface(MaterialTheme.shapes.extraSmall, MaterialTheme.colorScheme.onSurface, style, shadow = false)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.surface)
            }
            Spacer(Modifier.weight(1f))
        } else {
            Text(
                if (style.capsLabels) label.uppercase() else label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
        }
        if (income > 0) {
            Text(
                "+" + formatRupees(income),
                style = style.money(MaterialTheme.typography.labelLarge),
                color = incomeColor(),
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        if (spent > 0) {
            Text(formatRupees(spent), style = style.money(MaterialTheme.typography.labelLarge), color = MaterialTheme.colorScheme.onSurface)
        }
        Box(
            Modifier
                .padding(start = 8.dp)
                .size(26.dp)
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onAddMore),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Add more to this day", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun SwipeableExpenseRow(
    expense: Expense,
    spend: SpendInfo?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        onDismiss = { onDelete() },
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text("Delete", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold)
            }
        },
        enableDismissFromStartToEnd = false,
    ) {
        ExpenseRow(expense, onClick, spend = spend)
    }
}

@Composable
fun ExpenseRow(
    expense: Expense,
    onClick: () -> Unit,
    showDate: Boolean = false,
    spend: SpendInfo? = null,
    /** Opaque on Home (covers the swipe-to-delete background); pass Transparent inside cards. */
    background: Color = MaterialTheme.colorScheme.background,
) {
    val style = LocalAppStyle.current
    Column(Modifier.background(background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryBadge(expense)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    expense.note.ifBlank { expense.category.label },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (style.isBrutal) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val home = LocalHomeCity.current
                    val away = expense.place?.takeIf { p ->
                        p.isNotBlank() && com.expensetracker.app.data.Places.key(p) != com.expensetracker.app.data.Places.key(home)
                    }
                    Text(
                        (if (showDate) "${expense.category.label} · ${formatShortDate(expense.date)}" else expense.category.label) +
                            (expense.time?.let { " · ${formatTime(it.hour, it.minute)}" } ?: "") +
                            (away?.let { " · 📍 $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    val t = spend?.type
                    if (!expense.isIncome && (t == SpendType.ONE_OFF || t == SpendType.FIXED)) {
                        Text(
                            "  ${t.emoji} ${if (t == SpendType.ONE_OFF) "One-off" else "Fixed"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                        )
                    }
                }
            }
            Text(
                (if (expense.isIncome) "+" else "") + formatRupees(expense.amountPaise),
                style = style.money(MaterialTheme.typography.titleMedium),
                color = if (expense.isIncome) incomeColor() else MaterialTheme.colorScheme.onSurface,
            )
        }
        HorizontalDivider(
            Modifier.padding(start = 72.dp, end = 16.dp),
            thickness = if (style.isBrutal) 1.5.dp else 0.5.dp,
            color = if (style.isBrutal) MaterialTheme.colorScheme.outline.copy(alpha = 0.25f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        )
    }
}

@Composable
fun CategoryBadge(expense: Expense) {
    // Re-read when categories are renamed/recoloured.
    com.expensetracker.app.data.CategoryRegistry.version.collectAsStateWithLifecycle().value
    val style = LocalAppStyle.current
    val color = if (expense.isIncome) incomeColor() else Color(expense.category.color)
    val shape = if (style.corner >= 20.dp) RoundedCornerShape(14.dp) else MaterialTheme.shapes.small
    Box(
        modifier = Modifier
            .size(44.dp)
            .then(
                if (style.isBrutal) Modifier.themedSurface(shape, lerp(color, Color.White, 0.35f), style, shadow = false)
                else Modifier.clip(shape).background(color.copy(alpha = if (style.dark) 0.28f else 0.16f)),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (expense.isIncome) "💰" else expense.category.emoji, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun EmptyState(onOpenImport: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("📒", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text("No expenses yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Type “120 - Lunch” above, or bring over your old notes in one go.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenImport) { Text("Import from notes") }
        }
    }
}
