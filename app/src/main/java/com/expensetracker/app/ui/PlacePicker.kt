package com.expensetracker.app.ui

import android.Manifest
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.expensetracker.app.location.LocationTagger
import java.time.LocalDate

/** Your home city (set or detected), so rows can show "📍 City" only for spending elsewhere. */
val LocalHomeCity = compositionLocalOf<String?> { null }

/** "📍 Patiala" chip that opens [PlaceDialog]. [autoHint] = location tagging will fill it in if left empty. */
@Composable
fun PlaceChip(place: String?, recent: List<String>, autoHint: Boolean, onChange: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AssistChip(
        onClick = { open = true },
        label = {
            Text(
                when {
                    !place.isNullOrBlank() -> place
                    autoHint -> "Current location"
                    else -> "Add place"
                },
            )
        },
        leadingIcon = { Icon(Icons.Filled.LocationOn, contentDescription = null, Modifier.size(18.dp)) },
    )
    if (open) {
        PlaceDialog(initial = place, recent = recent, onDismiss = { open = false }, onPick = { onChange(it); open = false })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaceDialog(initial: String?, recent: List<String>, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(initial.orEmpty()) }
    var locating by remember { mutableStateOf(false) }
    val main = remember { Handler(Looper.getMainLooper()) }

    fun locate() {
        locating = true
        LocationTagger.currentCityAsync(ctx) { fix ->
            main.post {
                locating = false
                when {
                    fix?.city != null -> text = fix.city
                    fix != null -> Toast.makeText(ctx, "Got your location but couldn't name the city — type it in", Toast.LENGTH_LONG).show()
                    else -> Toast.makeText(ctx, "Couldn't get your location. Is location turned on?", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) locate() else Toast.makeText(ctx, "Location permission denied", Toast.LENGTH_SHORT).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Where was this?") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("City") },
                    placeholder = { Text("Gurugram") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        if (LocationTagger.hasPermission(ctx)) locate() else permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    },
                    enabled = !locating,
                ) {
                    if (locating) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.LocationOn, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (locating) "Finding you…" else "Use current location")
                }
                if (recent.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        recent.forEach { r -> SuggestionChip(onClick = { text = r }, label = { Text(r) }) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(text.trim().ifEmpty { null }) }) { Text("Done") } },
        dismissButton = {
            Row {
                if (!initial.isNullOrBlank()) TextButton(onClick = { onPick(null) }) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Tag every entry in a date range with a city — for trips logged before location tagging existed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagTripDialog(recent: List<String>, onDismiss: () -> Unit, onTag: (LocalDate, LocalDate, String, Boolean) -> Unit) {
    var city by remember { mutableStateOf("") }
    var from by remember { mutableStateOf(LocalDate.now().minusDays(3)) }
    var to by remember { mutableStateOf(LocalDate.now()) }
    var overwrite by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tag a past trip") },
        text = {
            Column {
                Text(
                    "Every entry between these dates gets this city.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = city,
                    onValueChange = { city = it },
                    label = { Text("City") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (recent.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        recent.forEach { r -> SuggestionChip(onClick = { city = r }, label = { Text(r) }) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { picking = "from" },
                        label = { Text("From ${formatShortDate(from)}") },
                        leadingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null, Modifier.size(18.dp)) },
                    )
                    AssistChip(onClick = { picking = "to" }, label = { Text("To ${formatShortDate(to)}") })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Replace places already set", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = overwrite, onCheckedChange = { overwrite = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onTag(minOf(from, to), maxOf(from, to), city.trim(), overwrite) },
                enabled = city.isNotBlank(),
            ) { Text("Tag") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    when (picking) {
        "from" -> DatePickDialog(initial = from, onPick = { from = it }, onDismiss = { picking = null })
        "to" -> DatePickDialog(initial = to, onPick = { to = it }, onDismiss = { picking = null })
    }
}

@Composable
fun HomeCityDialog(current: String, detected: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Home city") },
        text = {
            Column {
                Text(
                    "Spending anywhere else counts as travel. Leave empty to use the city you spend in most" +
                        (detected?.let { " (now: $it)" } ?: "") + ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("City") },
                    placeholder = { Text(detected ?: "Gurugram") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
