package com.expensetracker.app.trips

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.expensetracker.app.ExpenseViewModel
import com.expensetracker.app.data.ActiveTrip
import com.expensetracker.app.data.TripSummary
import com.expensetracker.app.data.Trips
import com.expensetracker.app.ui.formatRupees
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dayFmt = DateTimeFormatter.ofPattern("d MMM")

private fun range(from: LocalDate, to: LocalDate): String =
    if (from == to) from.format(dayFmt) else "${from.format(dayFmt)} – ${to.format(dayFmt)}"

/** Add/edit sheet: which trip this entry belongs to (recent ones, a new one, or none). */
@Composable
fun TripPickerRow(vm: ExpenseViewModel, trip: String?, onChange: (String?) -> Unit) {
    val all by vm.expenses.collectAsStateWithLifecycle()
    val recent = remember(all) { Trips.recent(all).take(8) }
    var menu by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        Text("Trip", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
        Column {
            AssistChip(
                onClick = { if (recent.isEmpty() && trip == null) naming = true else menu = true },
                label = {
                    Text(if (trip != null) "✈️ $trip" else "None · part of a trip?", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("None") }, onClick = { onChange(null); menu = false })
                (listOfNotNull(trip) + recent).distinct().forEach { t ->
                    DropdownMenuItem(text = { Text("✈️ $t") }, onClick = { onChange(t); menu = false })
                }
                DropdownMenuItem(text = { Text("New trip…") }, onClick = { menu = false; naming = true })
            }
        }
    }
    if (naming) NameDialog("New trip", "", onDismiss = { naming = false }) { onChange(it); naming = false }
}

/** Slim Home banner while trip mode is on. */
@Composable
fun TripBanner(vm: ExpenseViewModel) {
    val ctx = LocalContext.current
    val active by TripStore.active(ctx).collectAsStateWithLifecycle()
    val all by vm.expenses.collectAsStateWithLifecycle()
    val a = active ?: return
    val total = remember(all, a) { Trips.totalFor(all, a.name) }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "✈️ On trip: ${a.name} · ${formatRupees(total)} so far",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { TripStore.end(ctx) }) { Text("End") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsScreen(vm: ExpenseViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val all by vm.expenses.collectAsStateWithLifecycle()
    val active by TripStore.active(ctx).collectAsStateWithLifecycle()
    val trips = remember(all) { Trips.summaries(all) }
    var starting by remember { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trips & events") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp)) {
            item {
                Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val a = active
                        if (a != null) {
                            Text("Trip mode is on", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("✈️ ${a.name}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(
                                "Every new spend is tagged with it" + (a.end?.let { " until ${it.format(dayFmt)}" } ?: " until you end it") + ".",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { TripStore.end(ctx) }) { Text("End trip") }
                        } else {
                            Text("Going somewhere?", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Start a trip and every new spend — typed, quick-logged or captured — is tagged with it. Trip spending stays out of your everyday averages.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = { starting = true }) { Text("Start a trip") }
                        }
                    }
                }
            }
            if (trips.isEmpty()) item {
                Text(
                    "No trips yet. You can also tag any entry with a trip from its edit sheet.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(trips, key = { it.name }) { t ->
                TripRow(
                    t, expanded = open == t.name,
                    onClick = { open = if (open == t.name) null else t.name },
                    onRename = { renaming = t.name },
                    onDelete = { deleting = t.name },
                )
            }
        }
    }

    if (starting) StartTripDialog(onDismiss = { starting = false }) { TripStore.start(ctx, it); starting = false }
    renaming?.let { from ->
        NameDialog("Rename trip", from, hint = "Use an existing trip's name to merge them", onDismiss = { renaming = null }) { to ->
            if (to != from) vm.setTrip(from, to)
            if (open == from) open = to
            renaming = null
        }
    }
    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove trip “$name”?") },
            text = { Text("The entries stay; only the trip tag is removed, so they count as normal spending again.") },
            confirmButton = { TextButton(onClick = { vm.setTrip(name, null); deleting = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TripRow(t: TripSummary, expanded: Boolean, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick)) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("✈️ ${t.name}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${range(t.from, t.to)} · ${t.count} entr${if (t.count == 1) "y" else "ies"}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(formatRupees(t.totalPaise), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Column {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename / merge") }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text("Remove trip") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            if (expanded) {
                Spacer(Modifier.padding(top = 4.dp))
                t.byCategory.forEach { (c, amt) ->
                    Row(Modifier.fillMaxWidth().padding(end = 12.dp, top = 2.dp)) {
                        Text("${c.emoji} ${c.label}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(formatRupees(amt), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, hint: String? = null, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val clean = Trips.cleanName(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(Trips.MAX_NAME) }, singleLine = true,
                placeholder = { Text("e.g. Goa 2026, Diwali, Wedding") },
                supportingText = hint?.let { { Text(it) } },
            )
        },
        confirmButton = { TextButton(enabled = clean != null, onClick = { clean?.let(onDone) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun StartTripDialog(onDismiss: () -> Unit, onStart: (ActiveTrip) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var days by rememberSaveable { mutableStateOf<Int?>(null) } // null = until I end it
    val clean = Trips.cleanName(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start a trip") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(Trips.MAX_NAME) }, singleLine = true,
                    placeholder = { Text("e.g. Goa 2026") },
                )
                Text("Ends", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(null to "When I end it", 3 to "3 days", 7 to "1 week").forEach { (d, label) ->
                        FilterChip(selected = days == d, onClick = { days = d }, label = { Text(label) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = clean != null, onClick = {
                val today = LocalDate.now()
                clean?.let { onStart(ActiveTrip(it, today, days?.let { d -> today.plusDays(d - 1L) })) }
            }) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
