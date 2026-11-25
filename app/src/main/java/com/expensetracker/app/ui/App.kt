package com.expensetracker.app.ui

import androidx.compose.ui.graphics.Color

import androidx.compose.ui.draw.drawBehind

import androidx.compose.material3.NavigationBarItemDefaults

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.expensetracker.app.ExpenseViewModel
import com.expensetracker.app.data.Expense
import kotlinx.coroutines.launch
import java.time.YearMonth

enum class Tab { HOME, STATS, SETTINGS }

/** Full-screen feature pages opened over the tabs. */
enum class Overlay { CATEGORIES, GOALS, INSIGHTS, CAPTURE, BACKUP }

/** State of the add/edit sheet: closed, adding a new entry, or editing an existing one. */
sealed interface SheetState {
    data object Closed : SheetState
    data object Adding : SheetState
    data class Editing(val expense: Expense) : SheetState
    data class Bulk(val date: java.time.LocalDate) : SheetState
}

@Composable
fun ExpenseApp(
    vm: ExpenseViewModel,
    openAddRequests: Int,
    openBulkRequests: Int = 0,
    /** A notification asked to open this screen; call [onOverlayRequestHandled] once shown. */
    overlayRequest: Overlay? = null,
    onOverlayRequestHandled: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var showImport by rememberSaveable { mutableStateOf(false) }
    var overlay by rememberSaveable { mutableStateOf<Overlay?>(null) }
    // Month shared between Home and Stats so switching tabs keeps context.
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    val month = YearMonth.parse(monthText)
    var sheet by remember { mutableStateOf<SheetState>(SheetState.Closed) }
    // Each tab keeps its own scroll position etc. while another tab or a full-screen page is shown.
    val tabStates = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun showDeletedSnackbar(e: Expense) {
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

    // Requests are counters from MainActivity; remember which were handled so coming back from
    // the lock screen (which re-creates this screen) doesn't replay an old one.
    var handledAdd by rememberSaveable { mutableIntStateOf(0) }
    var handledBulk by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(openAddRequests) {
        if (openAddRequests > handledAdd) {
            handledAdd = openAddRequests
            tab = Tab.HOME
            showImport = false
            sheet = SheetState.Adding
        }
    }

    LaunchedEffect(overlayRequest) {
        if (overlayRequest != null) {
            showImport = false
            overlay = overlayRequest
            onOverlayRequestHandled()
        }
    }

    LaunchedEffect(openBulkRequests) {
        if (openBulkRequests > handledBulk) {
            handledBulk = openBulkRequests
            tab = Tab.HOME
            showImport = false
            sheet = SheetState.Bulk(java.time.LocalDate.now())
        }
    }

    BackHandler(enabled = showImport) { showImport = false }
    BackHandler(enabled = !showImport && tab != Tab.HOME) { tab = Tab.HOME }

    val bottomBar: @Composable () -> Unit = {
        val style = LocalAppStyle.current
        val navColors = NavigationBarItemDefaults.colors(
            indicatorColor = if (style.isBrutal) style.accent(0) else MaterialTheme.colorScheme.secondaryContainer,
            selectedIconColor = if (style.isBrutal) Color(0xFF111111) else MaterialTheme.colorScheme.onSecondaryContainer,
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
        )
        NavigationBar(
            containerColor = if (style.isBrutal) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 0.dp,
            modifier = if (style.isBrutal) Modifier.drawBehind {
                drawRect(style.borderColor, size = androidx.compose.ui.geometry.Size(size.width, style.borderWidth.toPx()))
            } else Modifier,
        ) {
            NavigationBarItem(
                selected = tab == Tab.HOME,
                onClick = { tab = Tab.HOME },
                icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                label = { Text("Home") },
                colors = navColors,
            )
            NavigationBarItem(
                selected = tab == Tab.STATS,
                onClick = { tab = Tab.STATS },
                icon = { BarsIcon() },
                label = { Text("Stats") },
                colors = navColors,
            )
            NavigationBarItem(
                selected = tab == Tab.SETTINGS,
                onClick = { tab = Tab.SETTINGS },
                icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                label = { Text("Settings") },
                colors = navColors,
            )
        }
    }

    BackHandler(enabled = overlay != null) { overlay = null }

    if (overlay != null) {
        val close = { overlay = null }
        when (overlay) {
            Overlay.CATEGORIES -> com.expensetracker.app.categories.ManageCategoriesScreen(vm, close)
            Overlay.GOALS -> com.expensetracker.app.goals.GoalsScreen(vm, close)
            Overlay.INSIGHTS -> com.expensetracker.app.insights.InsightsScreen(vm, close)
            Overlay.CAPTURE -> com.expensetracker.app.capture.CaptureInboxScreen(vm, close)
            Overlay.BACKUP -> com.expensetracker.app.backup.BackupSettingsScreen(vm, close)
            null -> Unit
        }
    } else if (showImport) {
        ImportScreen(
            vm = vm,
            snackbar = snackbar,
            onBack = { showImport = false },
            onImported = { n ->
                showImport = false
                tab = Tab.HOME
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(if (n == 0) "Nothing new to import" else "Imported $n entries")
                }
            },
        )
    } else tabStates.SaveableStateProvider(tab.name) {
        when (tab) {
            Tab.HOME -> HomeScreen(
                vm = vm,
                month = month,
                onMonthChange = { monthText = it.toString() },
                snackbar = snackbar,
                bottomBar = bottomBar,
                onAdd = { sheet = SheetState.Adding },
                onOpen = { overlay = it },
                onBulkAdd = { sheet = SheetState.Bulk(it) },
                onEdit = { sheet = SheetState.Editing(it) },
                onOpenImport = { showImport = true },
            )
            Tab.STATS -> StatsScreen(
                vm = vm,
                onOpen = { overlay = it },
                month = month,
                onMonthChange = { monthText = it.toString() },
                bottomBar = bottomBar,
                onEdit = { sheet = SheetState.Editing(it) },
            )
            Tab.SETTINGS -> SettingsScreen(
                vm = vm,
                snackbar = snackbar,
                bottomBar = bottomBar,
                onOpenImport = { showImport = true },
                onOpen = { overlay = it },
            )
        }
    }

    when (val s = sheet) {
        SheetState.Closed -> Unit
        SheetState.Adding -> AddEditSheet(
            vm = vm,
            existing = null,
            defaultDate = if (month == YearMonth.now()) null else month.atDay(1),
            onDismiss = { sheet = SheetState.Closed },
            onDeleted = { showDeletedSnackbar(it) },
        )
        is SheetState.Bulk -> BulkAddSheet(
            vm = vm,
            initialDate = s.date,
            onDismiss = { sheet = SheetState.Closed },
            onAdded = { n, total, ids ->
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    val r = snackbar.showSnackbar(
                        "Added $n entries · ${formatRupees(total)}",
                        actionLabel = "Undo",
                        duration = SnackbarDuration.Short,
                    )
                    if (r == SnackbarResult.ActionPerformed) vm.deleteIds(ids)
                }
            },
        )
        is SheetState.Editing -> AddEditSheet(
            vm = vm,
            existing = s.expense,
            defaultDate = null,
            onDismiss = { sheet = SheetState.Closed },
            onDeleted = { showDeletedSnackbar(it) },
        )
    }
}

/** ‹ Nov 2026 › with a "Today" shortcut when browsing another month. */
@Composable
fun MonthSelector(month: YearMonth, onMonthChange: (YearMonth) -> Unit, modifier: Modifier = Modifier) {
    val now = YearMonth.now()
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onMonthChange(month.minusMonths(1)) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
        }
        Text(
            formatMonth(month),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        IconButton(onClick = { onMonthChange(month.plusMonths(1)) }, enabled = month < now) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
        }
        if (month != now) {
            TextButton(onClick = { onMonthChange(now) }) { Text("This month") }
        }
    }
}

/** Small bar-chart glyph (icons-core has no chart icon). */
@Composable
fun BarsIcon(modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier = modifier.size(24.dp).padding(3.dp)) {
        val w = size.width / 5f
        val heights = floatArrayOf(0.45f, 0.95f, 0.65f)
        heights.forEachIndexed { i, h ->
            val x = i * w * 2f
            drawRect(color, topLeft = Offset(x, size.height * (1 - h)), size = Size(w, size.height * h))
        }
    }
}
