@file:OptIn(ExperimentalMaterial3Api::class)

package com.expensetracker.app.capture

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.text.input.KeyboardType
import com.expensetracker.app.ui.paiseToInput
import com.expensetracker.app.ui.parseAmountInput
import androidx.compose.material3.SwipeToDismissBoxValue

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Notifications
import com.expensetracker.app.ui.kit.AssistChip
import com.expensetracker.app.ui.kit.Button
import com.expensetracker.app.ui.kit.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import com.expensetracker.app.ui.kit.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.expensetracker.app.ui.kit.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.expensetracker.app.ui.kit.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.expensetracker.app.ExpenseViewModel
import com.expensetracker.app.data.Category
import com.expensetracker.app.data.Expense
import com.expensetracker.app.ml.Predictor
import com.expensetracker.app.ui.DatePickDialog
import com.expensetracker.app.ui.TimePickDialog
import com.expensetracker.app.ui.formatDateChip
import com.expensetracker.app.ui.formatRupees
import com.expensetracker.app.ui.formatTime
import com.expensetracker.app.ui.incomeColor
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Predictions at or above this can be confirmed in bulk. */
private const val HIGH_CONFIDENCE = 0.7

/** What you'd save for a draft: starts from the prediction, edited inline. */
private data class DraftEdit(
    val note: String, val category: Category, val date: LocalDate, val time: LocalTime,
    /** Amount as typed; starts as the parsed amount. */
    val amountText: String,
    val isIncome: Boolean,
)

private fun CapturedPayment.localDateTime(): LocalDateTime =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(atMillis), ZoneId.systemDefault())

private fun predictionFor(p: CapturedPayment): Predictor.Prediction = try {
    Predictor.predictPayment(p.payee.takeIf { it != "Unknown" } ?: "", p.amountPaise, p.localDateTime())
} catch (_: Exception) {
    Predictor.Prediction(p.payee, Category.OTHER, 0.0)
}

private fun defaultEdit(p: CapturedPayment, pred: Predictor.Prediction): DraftEdit {
    val at = p.localDateTime()
    val note = pred.note.ifBlank { if (p.payee == "Unknown") "" else p.payee }
    return DraftEdit(note, pred.category, at.toLocalDate(), at.toLocalTime().withSecond(0).withNano(0), paiseToInput(p.amountPaise), p.isIncome)
}

private fun isListenerGranted(ctx: Context): Boolean = CaptureListener.granted(ctx)

/** Banner on Home: "3 payments to review". Renders nothing when there are none. */
@Composable
fun CaptureBanner(onOpen: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { CaptureStore.refresh(ctx) }
    val pending by CaptureStore.pending.collectAsState()
    if (pending.isEmpty()) return
    val spent = pending.filter { !it.isIncome }.sumOf { it.amountPaise }
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (pending.size == 1) "1 payment to review" else "${pending.size} payments to review",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                val latest = pending.first()
                Text(
                    buildString {
                        append("Latest: ").append(formatRupees(latest.amountPaise))
                        append(if (latest.isIncome) " from " else " to ").append(latest.payee)
                        if (pending.size > 1 && spent > 0) append(" · ").append(formatRupees(spent)).append(" total")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text("Review", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Review inbox + capture settings (toggle, notification-access permission, which apps). */
@Composable
fun CaptureInboxScreen(vm: ExpenseViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val settings by vm.settings.collectAsState()
    val pending by CaptureStore.pending.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var granted by remember { mutableStateOf(isListenerGranted(ctx)) }
    LifecycleResumeEffect(Unit) {
        granted = isListenerGranted(ctx)
        CaptureStore.refresh(ctx)
        onPauseOrDispose { }
    }

    val predictions = remember(pending) { pending.associate { it.id to predictionFor(it) } }
    val edits = remember { mutableStateMapOf<Long, DraftEdit>() }
    fun editOf(p: CapturedPayment) = edits[p.id] ?: defaultEdit(p, predictions[p.id] ?: predictionFor(p))

    fun confirm(p: CapturedPayment, stamp: Long = System.currentTimeMillis()) {
        val e = editOf(p)
        val amount = parseAmountInput(e.amountText)?.takeIf { it > 0 }
        if (amount == null) {
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar("Enter the amount first")
            }
            return
        }
        val note = e.note.trim().ifBlank { p.payee }
        vm.add(
            Expense(
                amountPaise = amount,
                note = note,
                category = e.category,
                date = e.date,
                isIncome = e.isIncome,
                createdAt = stamp,
                time = e.time,
            ),
            payee = p.payee.takeIf { it != "Unknown" },
        )
        // What you saved vs what was read: the parser learns from the difference.
        CaptureStore.confirm(ctx, p, stamp, amountPaise = amount, isIncome = e.isIncome, note = e.note.trim())
        edits.remove(p.id)
    }

    fun dismiss(p: CapturedPayment, notPayment: Boolean) {
        CaptureStore.dismiss(ctx, p, notPayment)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val what = if (notPayment) "Not a payment — will learn from this" else "Dismissed"
            val r = snackbar.showSnackbar("$what · ${formatRupees(p.amountPaise)} · ${p.payee}", actionLabel = "Undo", withDismissAction = true)
            if (r == SnackbarResult.ActionPerformed) CaptureStore.undoDismiss(ctx, p, notPayment)
        }
    }

    val hidden by CaptureStore.hidden.collectAsState()
    var showHidden by remember { mutableStateOf(false) }
    // "Confirm all" never includes drafts the learning model only guessed at.
    val highConfidence = pending.filter { it.origin != ParserModel.Origin.UNSURE && (predictions[it.id]?.confidence ?: 0.0) >= HIGH_CONFIDENCE }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Auto-capture") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item("settings") {
                CaptureSettingsCard(
                    enabled = settings.captureEnabled,
                    granted = granted,
                    onEnabled = { on -> vm.updateSettings { it.copy(captureEnabled = on) } },
                    onOpenAccess = { openListenerSettings(ctx) },
                )
            }
            item("header") {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (pending.isEmpty()) "To review" else "To review (${pending.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (highConfidence.isNotEmpty()) {
                        TextButton(onClick = {
                            val now = System.currentTimeMillis()
                            val list = highConfidence.toList()
                            list.forEachIndexed { i, p -> confirm(p, now + i) }
                            scope.launch { snackbar.showSnackbar("Saved ${list.size} payment${if (list.size == 1) "" else "s"}") }
                        }) {
                            Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Confirm all (${highConfidence.size})")
                        }
                    }
                }
            }
            if (pending.isEmpty()) {
                item("empty") { EmptyState(settings.captureEnabled, granted) }
            } else {
                items(pending, key = { it.id }) { p ->
                    SwipeableDraft(
                        p = p,
                        edit = editOf(p),
                        confidence = predictions[p.id]?.confidence ?: 0.0,
                        onEdit = { edits[p.id] = it },
                        onConfirm = { confirm(p) },
                        onDismiss = { notPayment -> dismiss(p, notPayment) },
                    )
                }
            }
            if (hidden.isNotEmpty()) {
                item("hidden-header") {
                    Row(
                        Modifier.fillMaxWidth().clickable { showHidden = !showHidden }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Hidden as not payments (${hidden.size})", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Messages like ones you marked “Not a payment”. Kept 7 days.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(if (showHidden) "Hide" else "Show", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (showHidden) {
                    items(hidden, key = { "h-${it.id}" }) { p -> HiddenRow(p, onRestore = { CaptureStore.unhide(ctx, p) }) }
                }
            }
            item("bottom") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun openListenerSettings(ctx: Context) {
    try {
        ctx.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        ctx.startActivity(Intent(AndroidSettings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun CaptureSettingsCard(enabled: Boolean, granted: Boolean, onEnabled: (Boolean) -> Unit, onOpenAccess: () -> Unit) {
    val ctx = LocalContext.current
    var notify by remember { mutableStateOf(CapturePrefs.notifyOnCapture(ctx)) }
    var showApps by remember { mutableStateOf(false) }
    val appStates = remember { mutableStateMapOf<SourceGroup, Boolean>().apply { SourceGroup.entries.forEach { put(it, CapturePrefs.appEnabled(ctx, it)) } } }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            SwitchRow(
                title = "Capture payments from notifications",
                subtitle = "UPI and bank payments become drafts you confirm",
                checked = enabled,
                onChange = { on ->
                    onEnabled(on)
                    if (on && !granted) onOpenAccess()
                },
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val connected by CaptureListener.connected.collectAsState()
                // "Allowed" isn't enough: after an update some phones never reconnect the listener.
                val stalled = enabled && granted && !connected
                Column(Modifier.weight(1f)) {
                    Text("Notification access", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when {
                            !granted -> "Not allowed — needed to see payments"
                            stalled -> "Allowed, but not running — tap Restart"
                            enabled -> "Allowed · running"
                            else -> "Allowed"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (granted && !stalled) incomeColor() else if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (stalled) OutlinedButton(onClick = { CaptureListener.ensureRunning(ctx, force = true) }) { Text("Restart") }
                else OutlinedButton(onClick = onOpenAccess) { Text(if (granted) "Manage" else "Allow") }
            }
            // Bank SMS: Android 15+ hides their notification text from other apps, so read SMS directly.
            var smsGranted by remember { mutableStateOf(SmsAccess.granted(ctx)) }
            val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> smsGranted = ok }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Bank SMS access", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (smsGranted) "Allowed — bank SMS are read directly"
                        else "Needed on Android 15+, which hides bank SMS from notification readers. Only bank payment messages are kept.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (smsGranted) incomeColor() else if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!smsGranted) {
                    OutlinedButton(onClick = { smsLauncher.launch(Manifest.permission.RECEIVE_SMS) }) { Text("Allow") }
                }
            }
            SwitchRow(
                title = "Notify me on capture",
                subtitle = "A quiet “₹142 to Zomato captured — tap to review”",
                checked = notify,
                onChange = { on ->
                    notify = on
                    CapturePrefs.setNotifyOnCapture(ctx, on)
                    if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
            )
            Row(
                Modifier.fillMaxWidth().clickable { showApps = !showApps }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Apps to read", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        SourceGroup.entries.filter { appStates[it] == true }.joinToString(", ") { it.label }.ifEmpty { "None" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(if (showApps) "Hide" else "Edit", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
            if (showApps) {
                SourceGroup.entries.forEach { g ->
                    SwitchRow(
                        title = g.label,
                        subtitle = when (g) {
                            SourceGroup.BANK_APPS -> "SBI, HDFC, ICICI, Axis, Kotak and other bank apps"
                            SourceGroup.SMS -> "Only bank-style messages are read; personal texts are ignored"
                            else -> null
                        },
                        checked = appStates[g] == true,
                        onChange = { on -> appStates[g] = on; CapturePrefs.setAppEnabled(ctx, g, on) },
                        indent = true,
                    )
                }
            }
            // What the parser has learned from your reviews (see ParserModel).
            val learning by CaptureStore.learning.collectAsState()
            var confirmReset by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Learning from your reviews", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (learning.examples == 0) "Confirm, correct or mark drafts “Not a payment” and it adapts to your messages"
                        else "${learning.formats} message format${if (learning.formats == 1) "" else "s"} learned from ${learning.examples} review${if (learning.examples == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (learning.examples > 0) {
                    if (confirmReset) {
                        TextButton(onClick = { CaptureStore.resetLearning(ctx); confirmReset = false }) {
                            Text("Forget all", color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        TextButton(onClick = { confirmReset = true }) { Text("Reset") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, indent: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }
            .padding(start = if (indent) 32.dp else 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun EmptyState(enabled: Boolean, granted: Boolean) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🧾", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                !enabled -> "Auto-capture is off"
                !granted -> "Allow notification access to start"
                else -> "Nothing to review"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(12.dp))
        val body = MaterialTheme.typography.bodyMedium
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Text(
            "When you pay with Google Pay, PhonePe, Paytm, BHIM, Amazon Pay, CRED or get a bank SMS, " +
                "the payment shows up here as a draft with a guessed note and category. Confirm it, tweak it, or swipe it away.",
            style = body, color = muted,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "The same payment often triggers 2–3 notifications (app + SMS + bank app) — they're merged into one. " +
                "Payments you've already logged by hand are skipped.",
            style = body, color = muted,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "🔒 Private by design: everything stays on this phone. Only payment notifications from the apps you pick are read; " +
                "the rest are ignored and never stored. Notification text is deleted as soon as you confirm or dismiss.",
            style = body, color = muted,
        )
    }
}

@Composable
private fun SwipeableDraft(
    p: CapturedPayment,
    edit: DraftEdit,
    confidence: Double,
    onEdit: (DraftEdit) -> Unit,
    onConfirm: () -> Unit,
    /** true = "Not a payment" (teaches the parser); false = just dismiss (duplicate / already logged). */
    onDismiss: (Boolean) -> Unit,
) {
    val state = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = state,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        // A swipe doesn't say why, so it teaches nothing.
        onDismiss = { onDismiss(false) },
        backgroundContent = {
            if (state.dismissDirection == SwipeToDismissBoxValue.Settled) return@SwipeToDismissBox
            Box(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.large)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text("Dismiss", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold)
            }
        },
        enableDismissFromStartToEnd = false,
    ) {
        DraftCard(p, edit, confidence, onEdit, onConfirm, onDismiss)
    }
}

@Composable
private fun DraftCard(
    p: CapturedPayment,
    edit: DraftEdit,
    confidence: Double,
    onEdit: (DraftEdit) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: (Boolean) -> Unit,
) {
    var showDismissMenu by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (edit.isIncome) "From " else "To ") + p.payee,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append(p.sourceApp)
                            p.account?.let { append(" · ").append(it) }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val shownAmount = parseAmountInput(edit.amountText)
                Text(
                    if (shownAmount == null) "₹ ?" else (if (edit.isIncome) "+" else "") + formatRupees(shownAmount),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (edit.isIncome) incomeColor() else Color.Unspecified,
                )
            }
            when (p.origin) {
                ParserModel.Origin.UNSURE -> OriginNote("Unsure — found by learning from your reviews. Check the amount.", MaterialTheme.colorScheme.error)
                ParserModel.Origin.LEARNED -> OriginNote("Read the way you corrected this kind of message before.", MaterialTheme.colorScheme.primary)
                ParserModel.Origin.REGEX -> Unit
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = edit.amountText,
                    onValueChange = { onEdit(edit.copy(amountText = it)) },
                    label = { Text("₹") },
                    singleLine = true,
                    isError = parseAmountInput(edit.amountText) == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(0.35f),
                )
                OutlinedTextField(
                    value = edit.note,
                    onValueChange = { onEdit(edit.copy(note = it)) },
                    label = { Text(if (confidence >= HIGH_CONFIDENCE) "Note (suggested)" else "Note") },
                    singleLine = true,
                    modifier = Modifier.weight(0.65f),
                )
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = !edit.isIncome, onClick = { onEdit(edit.copy(isIncome = false)) }, label = { Text("Expense") })
                FilterChip(selected = edit.isIncome, onClick = { onEdit(edit.copy(isIncome = true)) }, label = { Text("Income") })
            }
            Spacer(Modifier.height(8.dp))
            val cats = remember { Category.entries }
            val ordered = remember(edit.category) { listOf(edit.category) + cats.filter { it != edit.category } }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ordered, key = { it.id }) { c ->
                    FilterChip(
                        selected = c == edit.category,
                        onClick = { onEdit(edit.copy(category = c)) },
                        label = { Text("${c.emoji} ${c.label}") },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = { showDate = true },
                    label = { Text(formatDateChip(edit.date)) },
                    leadingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null, Modifier.size(18.dp)) },
                )
                AssistChip(onClick = { showTime = true }, label = { Text(formatTime(edit.time.hour, edit.time.minute)) })
                Spacer(Modifier.weight(1f))
                if (p.rawText.isNotBlank()) {
                    TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "Hide text" else "Show text") }
                }
            }
            if (showRaw) {
                Text(
                    p.rawText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                )
                Spacer(Modifier.height(4.dp))
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box {
                    TextButton(onClick = { showDismissMenu = true }) { Text("Dismiss") }
                    DropdownMenu(expanded = showDismissMenu, onDismissRequest = { showDismissMenu = false }) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("Not a payment")
                                    Text("Teaches auto-capture to skip messages like this", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = { showDismissMenu = false; onDismiss(true) },
                        )
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("Already logged / duplicate")
                                    Text("A real payment, just not needed here", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = { showDismissMenu = false; onDismiss(false) },
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onConfirm) { Text(if (edit.isIncome) "Save income" else "Confirm") }
            }
        }
    }

    if (showDate) DatePickDialog(edit.date, onPick = { onEdit(edit.copy(date = it)) }, onDismiss = { showDate = false })
    if (showTime) {
        TimePickDialog(
            edit.time.hour, edit.time.minute,
            onPick = { h, m -> onEdit(edit.copy(time = LocalTime.of(h, m))) },
            onDismiss = { showTime = false },
            title = "Payment time",
        )
    }
}

@Composable
private fun OriginNote(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.padding(top = 6.dp))
}

/** A draft the learning model hid as not a payment, with a way to bring it back. */
@Composable
private fun HiddenRow(p: CapturedPayment, onRestore: () -> Unit) {
    var showRaw by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { showRaw = !showRaw }) {
                Text(
                    (if (p.isIncome) "+" else "") + formatRupees(p.amountPaise) + " · " + p.payee,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    p.sourceApp + " · " + formatTime(p.localDateTime().hour, p.localDateTime().minute),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRestore) { Text("It's a payment") }
        }
        if (showRaw && p.rawText.isNotBlank()) {
            Text(
                p.rawText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(8.dp),
            )
        }
    }
}
