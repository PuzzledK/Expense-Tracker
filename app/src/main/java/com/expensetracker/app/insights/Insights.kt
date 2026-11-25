@file:OptIn(ExperimentalMaterial3Api::class)

package com.expensetracker.app.insights

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.expensetracker.app.ui.kit.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.expensetracker.app.ui.kit.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.expensetracker.app.ExpenseViewModel
import com.expensetracker.app.MainActivity
import com.expensetracker.app.R
import com.expensetracker.app.data.CategoryRegistry
import com.expensetracker.app.data.ExpenseDb
import com.expensetracker.app.data.Places
import com.expensetracker.app.data.SpendClassifier
import com.expensetracker.app.data.SpendInfo
import com.expensetracker.app.ui.formatRupeesShort
import com.expensetracker.app.ui.incomeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Weekly "your week in spending" notification (Sunday ~7 pm, inexact). */
object Insights {
    private const val CHANNEL_ID = "weekly_insights"
    private const val REQUEST_CODE = 1301
    private const val NOTIFICATION_ID = 1301
    private const val PREFS = "settings"

    /** Arm/re-arm the weekly summary notification from settings. Call after toggling it. */
    fun restore(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(alarmIntent(ctx))
        if (!ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("insightsNotification", false)) return
        val now = LocalDateTime.now()
        var next = now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).atTime(LocalTime.of(19, 0))
        if (!next.isAfter(now)) next = next.plusWeeks(1)
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, at, AlarmManager.INTERVAL_DAY * 7, alarmIntent(ctx))
    }

    private fun alarmIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQUEST_CODE, Intent(ctx, InsightsReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    internal fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Builds and posts the summary (runs off the main thread). */
    internal fun postWeekly(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("insightsNotification", false) || !canNotify(ctx)) return
        CategoryRegistry.ensureLoaded(ctx)
        val db = ExpenseDb(ctx)
        val all = try { db.all() } finally { db.close() }
        val verdicts = SpendClassifier.classify(all)
        val info = HashMap<Long, SpendInfo>()
        all.forEachIndexed { i, e -> verdicts[i]?.let { info[e.id] = it } }
        // An inexact alarm can slip past midnight; Monday morning still reports the week just ended.
        val now = LocalDateTime.now()
        val day = if (now.dayOfWeek == DayOfWeek.MONDAY && now.hour < 12) now.toLocalDate().minusDays(1) else now.toLocalDate()
        val summary = InsightEngine.weekly(all, info, day) ?: return
        val locked = prefs.getBoolean("appLock", false)
        val text = if (locked) "Your weekly spending summary is ready — tap to see it." else summary.text()

        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Weekly summary", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Your week in spending, every Sunday evening"
                },
            )
        }
        val open = PendingIntent.getActivity(
            ctx, REQUEST_CODE,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setColor(com.expensetracker.app.ui.notificationAccent(ctx))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Your week in spending")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(if (locked) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }
}

/** Weekly summary alarm. Declared in the manifest. */
class InsightsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                Insights.postWeekly(app)
            } catch (_: Throwable) {
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/** Insights for the current data, computed off the main thread. */
@Composable
private fun rememberInsights(vm: ExpenseViewModel): List<Insight>? {
    val expenses by vm.expenses.collectAsStateWithLifecycle()
    val info by vm.spendInfo.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val catVersion by CategoryRegistry.version.collectAsStateWithLifecycle()
    val result by produceState<List<Insight>?>(null, expenses, info, settings.homeCity, catVersion) {
        value = withContext(Dispatchers.Default) {
            InsightEngine.build(expenses, info, LocalDate.now(), Places.homeCity(expenses, settings))
        }
    }
    return result
}

/** 1–2 headline insights on Home (tap → full screen). Renders nothing when there's nothing useful. */
@Composable
fun InsightsCard(vm: ExpenseViewModel, onOpen: () -> Unit) {
    val top = InsightEngine.headlines(rememberInsights(vm) ?: return)
    if (top.isEmpty()) return
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable(onClick = onOpen),
        colors = if (com.expensetracker.app.ui.LocalAppStyle.current.isBrutal) null else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                Text("💡 Insights", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("More ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            top.forEach { i ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(i.emoji, modifier = Modifier.width(28.dp))
                    Text(i.title, style = MaterialTheme.typography.bodyMedium, color = toneColor(i.tone), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun toneColor(t: InsightTone): Color = when (t) {
    InsightTone.GOOD -> incomeColor()
    InsightTone.BAD -> MaterialTheme.colorScheme.error
    InsightTone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
}

@Composable
fun InsightsScreen(vm: ExpenseViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val insights = rememberInsights(vm)
    val expenses by vm.expenses.collectAsStateWithLifecycle()
    val info by vm.spendInfo.collectAsStateWithLifecycle()
    val weekly = remember(expenses, info) { InsightEngine.weekly(expenses, info, LocalDate.now()) }

    fun setNotify(on: Boolean) {
        vm.updateSettings { it.copy(insightsNotification = on) }
        Insights.restore(ctx)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> setNotify(granted) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Insights") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp)) {
            item {
                Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Weekly summary", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "A notification every Sunday evening with your week's everyday spend and top category." +
                                    if (settings.appLock) " Amounts are hidden while app lock is on." else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.insightsNotification,
                            onCheckedChange = { on ->
                                if (on && !Insights.canNotify(ctx) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else setNotify(on)
                            },
                        )
                    }
                    weekly?.takeIf { settings.insightsNotification }?.let {
                        Text(
                            "This week so far: " + it.text().removePrefix("Your week: "),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        )
                    }
                }
            }
            when {
                insights == null -> item { Text("Crunching your numbers…", modifier = Modifier.padding(16.dp)) }
                insights.isEmpty() -> item {
                    Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🔍", style = MaterialTheme.typography.displaySmall)
                        Text("Nothing stands out yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Insights show up once you have two weeks of entries and something worth telling you — " +
                                "a category moving, a habit, a streak.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                else -> {
                    listOf(InsightPeriod.WEEK to "This week", InsightPeriod.MONTH to "This month").forEach { (period, label) ->
                        val list = insights.filter { it.period == period }
                        if (list.isNotEmpty()) {
                            item(key = "h_$period") {
                                Text(
                                    label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                                )
                            }
                            items(list, key = { it.id }) { InsightItem(it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InsightItem(i: Insight) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(i.emoji, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(36.dp))
                Column(Modifier.weight(1f)) {
                    Text(i.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = toneColor(i.tone))
                    Spacer(Modifier.height(2.dp))
                    Text(i.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (i.bars.size >= 2) {
                Spacer(Modifier.height(10.dp))
                Bars(i.bars)
            } else if (i.spark.size >= 3) {
                Spacer(Modifier.height(10.dp))
                Sparkline(i.spark, Modifier.fillMaxWidth().height(36.dp).padding(start = 36.dp))
            }
        }
    }
}

@Composable
private fun Bars(bars: List<InsightBar>) {
    val max = bars.maxOf { it.valuePaise }.coerceAtLeast(1)
    val base = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().height(84.dp).padding(start = 36.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        bars.forEach { b ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Text(formatRupeesShort(b.valuePaise), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                Box(
                    Modifier.fillMaxWidth()
                        .fillMaxHeight((b.valuePaise.toFloat() / max * 0.6f).coerceAtLeast(0.02f))
                        .background(if (b.highlight) base else base.copy(alpha = 0.35f), RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                )
                Text(b.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Sparkline(values: List<Long>, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val max = values.max().toFloat()
        val min = values.min().toFloat()
        val range = (max - min).takeIf { it > 0 } ?: 1f
        val step = size.width / (values.size - 1)
        val pts = values.mapIndexed { i, v -> Offset(i * step, size.height - (v - min) / range * size.height * 0.9f - size.height * 0.05f) }
        pts.zipWithNext().forEach { (a, b) -> drawLine(color, a, b, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round) }
        drawCircle(color, 3.dp.toPx(), pts.last())
    }
}
