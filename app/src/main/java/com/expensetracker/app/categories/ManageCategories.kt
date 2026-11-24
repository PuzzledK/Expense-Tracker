package com.expensetracker.app.categories

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.app.ExpenseViewModel
import com.expensetracker.app.data.Category
import com.expensetracker.app.data.CategoryInfo
import com.expensetracker.app.data.CategoryRegistry
import com.expensetracker.app.ui.formatRupees
import java.time.YearMonth

private val EMOJIS = listOf(
    "🍔", "🍕", "☕", "🍺", "🍰", "🥗", "🛒", "🥦", "🍎", "🚌", "🛺", "🚗",
    "⛽", "✈️", "🚆", "🏠", "💡", "📱", "💻", "📺", "🛍️", "👕", "👟", "💄",
    "🎁", "👪", "👶", "🐶", "💪", "💊", "🏥", "🧘", "🎉", "🎬", "🎮", "🎵",
    "📚", "🎓", "✏️", "🏦", "💰", "📈", "💳", "🧾", "🔧", "🧹", "🌱", "⚽",
    "🏖️", "⛪", "🙏", "❤️", "🚬", "💇", "🧺", "📦", "🏷️", "⭐",
)

private val PALETTE: List<Long> = listOf(
    0xFFE53935, 0xFFD81B60, 0xFF8E24AA, 0xFF5E35B1, 0xFF3949AB, 0xFF1E88E5,
    0xFF039BE5, 0xFF00ACC1, 0xFF00897B, 0xFF43A047, 0xFF7CB342, 0xFFC0CA33,
    0xFFFDD835, 0xFFFFB300, 0xFFEF6C00, 0xFFF4511E, 0xFF6D4C41, 0xFF757575, 0xFF546E7A,
)

/** Editing state for the add/edit dialog. [id] null = new category. */
private data class Draft(val id: String?, val label: String, val emoji: String, val color: Long)

/** List, add, edit, hide, reorder, delete and merge categories. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageCategoriesScreen(vm: ExpenseViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val version by CategoryRegistry.version.collectAsState()
    val expenses by vm.expenses.collectAsState()
    val all = remember(version) { CategoryRegistry.allInfo() }
    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
    fun save(list: List<CategoryInfo>) = CategoryRegistry.save(ctx, list)

    val thisMonth = YearMonth.now()
    val stats = remember(expenses) {
        val counts = HashMap<String, Int>()
        val spend = HashMap<String, Long>()
        for (e in expenses) {
            counts.merge(e.category.id, 1, Int::plus)
            if (!e.isIncome && YearMonth.from(e.date) == thisMonth) spend.merge(e.category.id, e.amountPaise, Long::plus)
        }
        counts to spend
    }

    var draft by remember { mutableStateOf<Draft?>(null) }
    var moving by remember { mutableStateOf<CategoryInfo?>(null) } // delete-with-move or merge
    var movingIsDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Categories") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { draft = Draft(null, "", "🏷️", PALETTE[5]) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("New category") },
            )
        },
    ) { p ->
        val builtIns = all.filter { CategoryRules.isBuiltIn(it.id) }
        val customs = all.filterNot { CategoryRules.isBuiltIn(it.id) }
        LazyColumn(Modifier.fillMaxSize().padding(p)) {
            item {
                Text(
                    "Tap a category to rename, recolour, hide or merge it. Hidden ones aren't offered when adding " +
                        "entries but old entries keep them.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (customs.isNotEmpty()) {
                item { SectionLabel("Your categories") }
                items(customs, key = { it.id }) { c ->
                    val i = customs.indexOf(c)
                    CategoryRow(
                        c, stats.first[c.id] ?: 0, stats.second[c.id] ?: 0,
                        onClick = { draft = Draft(c.id, c.label, c.emoji, c.color) },
                        trailing = {
                            Row {
                                IconButton(onClick = { save(CategoryRules.moveCustom(all, c.id, -1)) }, enabled = i > 0) {
                                    Icon(Icons.Filled.KeyboardArrowUp, "Move up")
                                }
                                IconButton(onClick = { save(CategoryRules.moveCustom(all, c.id, 1)) }, enabled = i < customs.size - 1) {
                                    Icon(Icons.Filled.KeyboardArrowDown, "Move down")
                                }
                            }
                        },
                    )
                }
                item { HorizontalDivider() }
            }
            item { SectionLabel("Built-in") }
            items(builtIns, key = { it.id }) { c ->
                CategoryRow(
                    c, stats.first[c.id] ?: 0, stats.second[c.id] ?: 0,
                    onClick = { draft = Draft(c.id, c.label, c.emoji, c.color) },
                    trailing = if (CategoryRules.isEdited(c.copy(hidden = false))) {
                        { Text("Edited", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                    } else null,
                )
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }

    draft?.let { d ->
        val current = d.id?.let { id -> all.firstOrNull { it.id == id } }
        EditDialog(
            draft = d,
            current = current,
            all = all,
            count = d.id?.let { stats.first[it] } ?: 0,
            onDismiss = { draft = null },
            onSave = { label, emoji, color ->
                save(
                    if (d.id == null) CategoryRules.add(all, CategoryRegistry.newCustomId(), label, emoji, color)
                    else CategoryRules.edit(all, d.id, label, emoji, color),
                )
                draft = null
            },
            onToggleHidden = { c ->
                if (!c.hidden) CategoryRules.hideBlockedReason(all, c.id)?.let { toast(it); return@EditDialog }
                save(CategoryRules.setHidden(all, c.id, !c.hidden))
                draft = null
            },
            onReset = { c ->
                save(CategoryRules.resetToDefault(all, c.id))
                draft = null
            },
            onDelete = { c ->
                draft = null
                if ((stats.first[c.id] ?: 0) == 0) {
                    save(CategoryRules.delete(all, c.id))
                    toast("Deleted “${c.label}”")
                } else {
                    movingIsDelete = true
                    moving = c
                }
            },
            onMerge = { c ->
                draft = null
                movingIsDelete = false
                moving = c
            },
        )
    }

    moving?.let { from ->
        val n = stats.first[from.id] ?: 0
        AlertDialog(
            onDismissRequest = { moving = null },
            title = { Text(if (movingIsDelete) "Delete “${from.label}”" else "Merge “${from.label}” into…") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        if (movingIsDelete) "Move its $n ${if (n == 1) "entry" else "entries"} to:"
                        else "All $n ${if (n == 1) "entry" else "entries"} move to the category you pick. " +
                            if (CategoryRules.canDelete(from.id)) "“${from.label}” is then deleted." else "“${from.label}” is then hidden.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    CategoryRules.moveTargets(all, from.id).forEach { to ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                moving = null
                                vm.recategorize(Category.fromId(from.id), Category.fromId(to.id)) { moved ->
                                    // Re-read: the list may have changed while entries were moving.
                                    save(CategoryRules.afterMerge(CategoryRegistry.allInfo(), from.id))
                                    toast("Moved $moved ${if (moved == 1) "entry" else "entries"} to “${to.label}”")
                                }
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            EmojiDot(to.emoji, to.color, 32)
                            Text(
                                to.label + if (to.hidden) "  (hidden)" else "",
                                Modifier.padding(start = 12.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moving = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun EmojiDot(emoji: String, color: Long, sizeDp: Int) {
    Box(
        Modifier.size(sizeDp.dp).background(Color(color).copy(alpha = 0.2f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(emoji, fontSize = (sizeDp * 0.5f).sp, maxLines = 1) }
}

@Composable
private fun CategoryRow(
    c: CategoryInfo,
    count: Int,
    monthSpend: Long,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)?,
) {
    ListItem(
        leadingContent = { EmojiDot(c.emoji, c.color, 40) },
        headlineContent = {
            Text(
                c.label + if (c.hidden) "  · hidden" else "",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (c.hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = {
            Text(
                "$count ${if (count == 1) "entry" else "entries"} · ${formatRupees(monthSpend)} this month",
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = trailing,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditDialog(
    draft: Draft,
    current: CategoryInfo?,
    all: List<CategoryInfo>,
    count: Int,
    onDismiss: () -> Unit,
    onSave: (String, String, Long) -> Unit,
    onToggleHidden: (CategoryInfo) -> Unit,
    onReset: (CategoryInfo) -> Unit,
    onDelete: (CategoryInfo) -> Unit,
    onMerge: (CategoryInfo) -> Unit,
) {
    var label by remember(draft) { mutableStateOf(draft.label) }
    var emoji by remember(draft) { mutableStateOf(draft.emoji) }
    var color by remember(draft) { mutableStateOf(draft.color) }
    val error = CategoryRules.labelError(all, label, draft.id)
    val isNew = draft.id == null
    val builtIn = draft.id != null && CategoryRules.isBuiltIn(draft.id)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New category" else "Edit category") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EmojiDot(CategoryRules.cleanEmoji(emoji), color, 48)
                    Spacer(Modifier.size(12.dp))
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it.replace('\n', ' ').replace('\t', ' ').take(CategoryRules.MAX_LABEL) },
                        label = { Text("Name") },
                        singleLine = true,
                        isError = label.isNotEmpty() && error != null,
                        supportingText = { if (label.isNotEmpty() && error != null) Text(error) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text("Emoji", Modifier.padding(top = 8.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    EMOJIS.forEach { e ->
                        Box(
                            Modifier.size(38.dp)
                                .background(
                                    if (e == emoji) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                    CircleShape,
                                )
                                .clickable { emoji = e },
                            contentAlignment = Alignment.Center,
                        ) { Text(e, fontSize = 20.sp) }
                    }
                }
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { emoji = it.replace('\n', ' ').replace('\t', ' ').take(CategoryRules.MAX_EMOJI) },
                    label = { Text("Or type any emoji") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                Text("Colour", Modifier.padding(top = 8.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    (PALETTE + listOfNotNull(draft.color.takeIf { it !in PALETTE })).forEach { col ->
                        Box(
                            Modifier.size(32.dp)
                                .background(Color(col), CircleShape)
                                .then(
                                    if (col == color) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    else Modifier,
                                )
                                .clickable { color = col },
                            contentAlignment = Alignment.Center,
                        ) { if (col == color) Icon(Icons.Filled.Check, "Selected", tint = Color.White, modifier = Modifier.size(18.dp)) }
                    }
                }

                if (current != null) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(4.dp))
                    if (current.id != CategoryRules.OTHER) {
                        TextButton(onClick = { onToggleHidden(current) }) {
                            Text(if (current.hidden) "Show in pickers again" else "Hide from pickers")
                        }
                    }
                    if (builtIn && CategoryRules.isEdited(current.copy(hidden = false))) {
                        val d = CategoryRegistry.DEFAULTS.getValue(current.id)
                        TextButton(onClick = { onReset(current) }) { Text("Reset to default (${d.emoji} ${d.label})") }
                    }
                    if (current.id != CategoryRules.OTHER) {
                        TextButton(onClick = { onMerge(current) }) { Text("Merge into another category…") }
                    }
                    if (!builtIn) {
                        TextButton(onClick = { onDelete(current) }) {
                            Text(
                                if (count > 0) "Delete… (move its $count ${if (count == 1) "entry" else "entries"})" else "Delete",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(label, emoji, color) }, enabled = error == null) {
                Text(if (isNew) "Add" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
