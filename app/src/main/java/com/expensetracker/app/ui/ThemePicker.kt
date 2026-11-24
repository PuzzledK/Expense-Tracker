package com.expensetracker.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.app.data.Settings
import com.expensetracker.app.ui.kit.FilterChip
import com.expensetracker.app.ui.kit.themedSurface

/** Theme cards (each drawn in its own theme) + light/dark choice. */
@Composable
fun AppearanceSection(settings: Settings, onTheme: (String) -> Unit, onDarkMode: (Int) -> Unit) {
    val current = AppThemeId.of(settings.themeId)
    val dark = isDarkFor(current, settings.darkMode)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(AppThemeId.entries, key = { it.id }) { t ->
            ThemePreviewCard(t, dark, selected = t == current, onClick = { onTheme(t.id) })
        }
    }
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(DarkMode.SYSTEM to "System", DarkMode.LIGHT to "Light", DarkMode.DARK to "Dark").forEach { (mode, label) ->
            FilterChip(
                selected = settings.darkMode == mode && current != AppThemeId.MIDNIGHT,
                onClick = { onDarkMode(mode) },
                label = { Text(label) },
                enabled = current != AppThemeId.MIDNIGHT,
            )
        }
    }
    if (current == AppThemeId.MIDNIGHT) {
        Text(
            "Midnight is always dark.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun ThemePreviewCard(theme: AppThemeId, dark: Boolean, selected: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val (scheme, style) = previewColors(theme, dark, ctx)
    val shape = RoundedCornerShape(if (style.isBrutal) 6.dp else 18.dp)
    val ring = MaterialTheme.colorScheme.primary
    Column(Modifier.width(128.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .padding(end = style.hardShadow, bottom = style.hardShadow)
                .then(if (selected) Modifier.border(3.dp, ring, shape).padding(5.dp) else Modifier.padding(5.dp))
                .themedSurface(shape, scheme.background, style)
                .clickable(onClick = onClick)
                .width(112.dp)
                .height(150.dp),
        ) {
            Column(Modifier.padding(8.dp)) {
                // Mini hero
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .themedSurface(RoundedCornerShape(if (style.isBrutal) 4.dp else 12.dp), style.hero, style.copy(hardShadow = if (style.isBrutal) 2.dp else 0.dp, borderWidth = if (style.isBrutal) 1.5.dp else 0.dp)),
                ) {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Text("SPENT", fontSize = 7.sp, fontWeight = FontWeight.Bold, color = style.onHero.copy(alpha = 0.8f))
                        Text(
                            "₹12,340",
                            style = style.money(MaterialTheme.typography.titleMedium).copy(fontSize = 15.sp),
                            color = style.onHero,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Mini rows
                repeat(3) { i ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Box(
                            Modifier
                                .size(16.dp)
                                .clip(RoundedCornerShape(if (style.isBrutal) 3.dp else 6.dp))
                                .background(style.accent(i).copy(alpha = if (style.isBrutal) 1f else 0.35f))
                                .then(if (style.isBrutal) Modifier.border(1.dp, style.borderColor, RoundedCornerShape(3.dp)) else Modifier),
                        )
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier
                                .height(6.dp)
                                .width(if (i == 1) 34.dp else 44.dp)
                                .clip(CircleShape)
                                .background(scheme.onSurface.copy(alpha = 0.25f)),
                        )
                        Spacer(Modifier.weight(1f))
                        Box(
                            Modifier
                                .height(6.dp)
                                .width(16.dp)
                                .clip(CircleShape)
                                .background(scheme.onSurface.copy(alpha = 0.55f)),
                        )
                    }
                }
            }
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(ring),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(theme.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        Text(
            theme.blurb,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

