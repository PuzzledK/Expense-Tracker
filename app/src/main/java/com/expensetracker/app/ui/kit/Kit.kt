package com.expensetracker.app.ui.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.expensetracker.app.ui.AppStyle
import com.expensetracker.app.ui.LocalAppStyle
import androidx.compose.material3.AssistChip as M3AssistChip
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.FilterChip as M3FilterChip
import androidx.compose.material3.FloatingActionButton as M3Fab
import androidx.compose.material3.LinearProgressIndicator as M3Linear
import androidx.compose.material3.OutlinedButton as M3OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton as M3SmallFab
import androidx.compose.material3.SuggestionChip as M3SuggestionChip
import androidx.compose.material3.TopAppBar as M3TopAppBar

/*
 * Theme-aware drop-ins for the Material components used across the app. Same names and the
 * parameters the screens use, so a screen switches by changing its import. In the
 * neo-brutalist theme they draw thick ink borders and solid offset shadows; elsewhere they
 * defer to Material with the theme's shapes and colours.
 */

// ------------------------------------------------------------------ surface primitive

/**
 * Background + optional border + optional hard (offset, unblurred) shadow.
 * Put it before size/padding modifiers of the content; the shadow draws outside the bounds.
 */
fun Modifier.themedSurface(shape: Shape, fill: Color, s: AppStyle, shadow: Boolean = true): Modifier {
    var m = this
    if (shadow && s.hardShadow > 0.dp) {
        m = m.drawBehind {
            val o = s.hardShadow.toPx()
            val outline = shape.createOutline(size, layoutDirection, this)
            translate(o, o) { drawOutline(outline, s.shadowColor) }
        }
    }
    m = m.clip(shape).background(fill)
    if (s.borderWidth > 0.dp) m = m.border(s.borderWidth, s.borderColor, shape)
    return m
}

/** Same as [themedSurface] but filled with a brush (gradient hero cards). */
fun Modifier.themedSurface(shape: Shape, fill: androidx.compose.ui.graphics.Brush, s: AppStyle): Modifier {
    var m = this
    if (s.hardShadow > 0.dp) {
        m = m.drawBehind {
            val o = s.hardShadow.toPx()
            val outline = shape.createOutline(size, layoutDirection, this)
            translate(o, o) { drawOutline(outline, s.shadowColor) }
        }
    }
    m = m.clip(shape).background(fill)
    if (s.borderWidth > 0.dp) m = m.border(s.borderWidth, s.borderColor, shape)
    return m
}

/** Default card colour per theme (white-ish paper cards on a tinted background). */
@Composable
fun defaultCardColor(): Color {
    val s = LocalAppStyle.current
    val c = MaterialTheme.colorScheme
    return when {
        s.isBrutal -> c.surfaceContainerLowest
        s.dark -> c.surfaceContainer
        else -> c.surfaceContainerLowest
    }
}

@Composable
private fun contentFor(bg: Color): Color {
    val c = MaterialTheme.colorScheme
    return when (bg) {
        c.primary -> c.onPrimary
        c.primaryContainer -> c.onPrimaryContainer
        c.secondaryContainer -> c.onSecondaryContainer
        c.tertiaryContainer -> c.onTertiaryContainer
        c.errorContainer -> c.onErrorContainer
        else -> if (LocalAppStyle.current.isBrutal && !LocalAppStyle.current.dark) Color(0xFF111111) else c.onSurface
    }
}

// ------------------------------------------------------------------ Card

@Composable
fun Card(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    colors: CardColors? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val s = LocalAppStyle.current
    val bg = colors?.containerColor ?: defaultCardColor()
    val fg = colors?.contentColor?.takeIf { it != Color.Unspecified } ?: contentFor(bg)
    // Brutal cards keep their shadow inside the slot so neighbours don't overlap it.
    val inset = if (s.hardShadow > 0.dp) Modifier.padding(end = s.hardShadow, bottom = s.hardShadow) else Modifier
    Box(
        modifier
            .then(inset)
            .themedSurface(shape, bg, s)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        CompositionLocalProvider(LocalContentColor provides fg) { Column(content = content) }
    }
}

// ------------------------------------------------------------------ Buttons

@Composable
private fun BrutalButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    fill: Color,
    content: @Composable RowScope.() -> Unit,
) {
    val s = LocalAppStyle.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press = if (pressed && enabled) s.hardShadow else 0.dp
    val shape = MaterialTheme.shapes.small
    val ink = if (s.dark) MaterialTheme.colorScheme.onSurface else Color(0xFF111111)
    Box(modifier.padding(end = s.hardShadow, bottom = s.hardShadow)) {
        Row(
            Modifier
                .offset(press, press)
                .themedSurface(shape, if (enabled) fill else fill.copy(alpha = 0.45f), s, shadow = !pressed)
                .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
                .defaultMinSize(minHeight = 48.dp)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides if (enabled) contentFor(fill).takeIf { fill != Color.White } ?: ink else ink.copy(alpha = 0.5f)) {
                ProvideTextStyle(MaterialTheme.typography.labelLarge) { content() }
            }
        }
    }
}

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val s = LocalAppStyle.current
    if (s.isBrutal) {
        BrutalButton(onClick, modifier, enabled, MaterialTheme.colorScheme.primaryContainer, content)
    } else {
        M3Button(
            onClick = onClick, modifier = modifier, enabled = enabled,
            shape = if (s.corner >= 20.dp) ButtonDefaults.shape else MaterialTheme.shapes.small,
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
            content = content,
        )
    }
}

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val s = LocalAppStyle.current
    if (s.isBrutal) {
        BrutalButton(onClick, modifier, enabled, MaterialTheme.colorScheme.surfaceContainerLowest, content)
    } else {
        M3OutlinedButton(
            onClick = onClick, modifier = modifier, enabled = enabled,
            shape = if (s.corner >= 20.dp) ButtonDefaults.outlinedShape else MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (s.dark) 0.8f else 0.6f)),
            content = content,
        )
    }
}

// ------------------------------------------------------------------ FABs

@Composable
private fun BrutalFab(onClick: () -> Unit, modifier: Modifier, size: Dp?, fill: Color, content: @Composable () -> Unit) {
    val s = LocalAppStyle.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press = if (pressed) s.hardShadow else 0.dp
    Box(modifier.padding(end = s.hardShadow, bottom = s.hardShadow)) {
        Box(
            Modifier
                .offset(press, press)
                .themedSurface(MaterialTheme.shapes.medium, fill, s, shadow = !pressed)
                .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
                .then(if (size != null) Modifier.size(size) else Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 40.dp)),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides Color(0xFF111111)) { content() }
        }
    }
}

@Composable
fun FloatingActionButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val s = LocalAppStyle.current
    if (s.isBrutal) BrutalFab(onClick, modifier, 60.dp, s.accent(1), content)
    else M3Fab(
        onClick = onClick, modifier = modifier,
        shape = if (s.corner >= 20.dp) FloatingActionButtonDefaults.shape else MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        content = content,
    )
}

@Composable
fun SmallFloatingActionButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val s = LocalAppStyle.current
    if (s.isBrutal) BrutalFab(onClick, modifier, null, s.accent(2), content)
    else M3SmallFab(
        onClick = onClick, modifier = modifier,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        content = content,
    )
}

// ------------------------------------------------------------------ Top bar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val s = LocalAppStyle.current
    val line = if (s.isBrutal) Modifier.drawBehind {
        val w = s.borderWidth.toPx()
        drawRect(s.borderColor, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - w), size = androidx.compose.ui.geometry.Size(size.width, w))
    } else Modifier
    M3TopAppBar(
        title = title,
        modifier = modifier.then(line),
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

// ------------------------------------------------------------------ Chips

@Composable
private fun chipBorder(selected: Boolean): BorderStroke? {
    val s = LocalAppStyle.current
    return when {
        s.isBrutal -> BorderStroke(2.dp, s.borderColor)
        else -> null
    }
}

@Composable
fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    val s = LocalAppStyle.current
    val c = MaterialTheme.colorScheme
    M3FilterChip(
        selected = selected, onClick = onClick, label = label, modifier = modifier, enabled = enabled,
        leadingIcon = leadingIcon, trailingIcon = trailingIcon,
        shape = MaterialTheme.shapes.small,
        colors = if (s.isBrutal) FilterChipDefaults.filterChipColors(
            containerColor = c.surfaceContainerLowest,
            selectedContainerColor = s.accent(0),
            selectedLabelColor = Color(0xFF111111),
            selectedLeadingIconColor = Color(0xFF111111),
        ) else FilterChipDefaults.filterChipColors(
            selectedContainerColor = c.primaryContainer,
            selectedLabelColor = c.onPrimaryContainer,
        ),
        border = chipBorder(selected) ?: FilterChipDefaults.filterChipBorder(
            enabled = enabled, selected = selected,
            borderColor = c.outline.copy(alpha = 0.5f),
        ),
    )
}

@Composable
fun AssistChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    val s = LocalAppStyle.current
    M3AssistChip(
        onClick = onClick, label = label, modifier = modifier, enabled = enabled,
        leadingIcon = leadingIcon, trailingIcon = trailingIcon,
        shape = MaterialTheme.shapes.small,
        colors = if (s.isBrutal) AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
        else AssistChipDefaults.assistChipColors(),
        border = chipBorder(false) ?: AssistChipDefaults.assistChipBorder(enabled, borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    )
}

@Composable
fun SuggestionChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val s = LocalAppStyle.current
    val c = MaterialTheme.colorScheme
    M3SuggestionChip(
        onClick = onClick, label = label, modifier = modifier, enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = if (s.isBrutal) s.accent(2).copy(alpha = 0.35f) else c.secondaryContainer.copy(alpha = 0.6f),
        ),
        border = chipBorder(false) ?: BorderStroke(0.dp, Color.Transparent),
    )
}

// ------------------------------------------------------------------ Progress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinearProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.linearColor,
    trackColor: Color = ProgressIndicatorDefaults.linearTrackColor,
    strokeCap: StrokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
    gapSize: Dp = ProgressIndicatorDefaults.LinearIndicatorTrackGapSize,
    drawStopIndicator: DrawScope.() -> Unit = {},
) {
    val s = LocalAppStyle.current
    if (s.isBrutal) {
        val shape = MaterialTheme.shapes.extraSmall
        Box(
            modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .border(1.5.dp, s.borderColor, shape),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress().coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(if (color == ProgressIndicatorDefaults.linearColor) s.accent(3) else color),
            )
        }
    } else {
        M3Linear(
            progress = progress, modifier = modifier, color = color, trackColor = trackColor,
            strokeCap = StrokeCap.Round, gapSize = gapSize, drawStopIndicator = drawStopIndicator,
        )
    }
}
