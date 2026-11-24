package com.expensetracker.app.quick

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import com.expensetracker.app.ui.kit.Button
import com.expensetracker.app.ui.kit.Card
import com.expensetracker.app.ui.kit.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.expensetracker.app.ui.kit.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.ExpenseDb
import com.expensetracker.app.data.NotesParser
import com.expensetracker.app.data.QuickTemplate
import com.expensetracker.app.data.frequentTemplates
import com.expensetracker.app.ui.ExpenseTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Small floating sheet over whatever is on screen: type "120 - Lunch" (one per line), Add, done. */
class QuickLogActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ExpenseTheme { QuickLogSheet(onClose = ::finish, onSave = ::save) } }
    }

    private fun save(text: String, date: LocalDate) {
        val app = applicationContext
        QuickLog.worker.execute {
            val saved = QuickLog.log(app, text, date)
            val msg = when {
                saved.isEmpty() -> "No amount found"
                saved.size == 1 -> "Logged ${QuickLog.rupees(saved[0].amountPaise)} · ${saved[0].note}"
                else -> "Logged ${saved.size} entries"
            }
            QuickLog.refreshAll(app, if (saved.isEmpty()) null else msg)
            runOnUiThread { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }
        }
        finish()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@androidx.compose.runtime.Composable
private fun QuickLogSheet(onClose: () -> Unit, onSave: (String, LocalDate) -> Unit) {
    var text by remember { mutableStateOf("") }
    var yesterday by remember { mutableStateOf(false) }
    var templates by remember { mutableStateOf<List<QuickTemplate>>(emptyList()) }
    var todayLine by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) {
        val all: List<Expense> = withContext(Dispatchers.IO) { ExpenseDb(ctx).use { it.all() } }
        // With the app lock on, logging still works but nothing about past spending is shown.
        val locked = com.expensetracker.app.security.AppLock.isEnabled(ctx)
        templates = if (locked) emptyList() else frequentTemplates(all, limit = 6)
        val s = QuickLog.summary(all)
        todayLine = when {
            locked -> "🔒 App is locked — you can still add"
            s.todayCount == 0 -> "Nothing logged today yet"
            else -> "Today so far: ${QuickLog.rupees(s.todayPaise)} · ${s.todayCount}"
        }
        focus.requestFocus()
    }

    val parsed = text.lines().mapNotNull { NotesParser.parseLine(it.trim()) }
    val total = parsed.filter { !it.isIncome }.sumOf { it.amountPaise }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .imePadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Card(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                // Swallow taps so they don't close the sheet.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Quick log", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    FilterChip(selected = !yesterday, onClick = { yesterday = false }, label = { Text("Today") })
                    Spacer(Modifier.padding(4.dp))
                    FilterChip(selected = yesterday, onClick = { yesterday = true }, label = { Text("Yesterday") })
                }
                if (todayLine.isNotEmpty()) {
                    Text(todayLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("120 - Lunch\n35 - Ice cream") },
                    supportingText = { Text("One per line · +500 for money received") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (templates.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        templates.forEach { t ->
                            SuggestionChip(
                                onClick = {
                                    val line = "${t.amountPaise / 100}${if (t.amountPaise % 100 != 0L) ".%02d".format(t.amountPaise % 100) else ""} - ${t.note}"
                                    text = if (text.isBlank()) line else text.trimEnd('\n') + "\n" + line
                                },
                                label = { Text("${t.category.emoji} ${t.note} · ${QuickLog.rupees(t.amountPaise)}", maxLines = 1) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose) { Text("Cancel") }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { onSave(text, if (yesterday) LocalDate.now().minusDays(1) else LocalDate.now()) },
                        enabled = parsed.isNotEmpty(),
                    ) {
                        Text(
                            when (parsed.size) {
                                0 -> "Add"
                                1 -> "Add ${QuickLog.rupees(parsed[0].amountPaise)}"
                                else -> "Add ${parsed.size} · ${QuickLog.rupees(total)}"
                            },
                        )
                    }
                }
            }
        }
    }
}
