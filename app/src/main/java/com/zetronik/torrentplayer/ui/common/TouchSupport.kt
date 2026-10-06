package com.zetronik.torrentplayer.ui.common

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonColors
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import com.zetronik.torrentplayer.ui.theme.AppTheme
import com.zetronik.torrentplayer.ui.theme.ListStyle
import com.zetronik.torrentplayer.ui.theme.LocalIsTv
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/*
 * tv-material's clickable components only react to D-pad center / Enter: they have no touch handling.
 * The app also targets phones and tablets, so these wrappers add tap and long-press on top, and feed the
 * touch into the component's interaction source so it shows its pressed state like a native control.
 * They also apply the current template's colours and shapes (see AppTheme).
 */

/** A press shorter than this shows no highlight, so starting a scroll does not flash the row. */
private const val PRESS_HIGHLIGHT_DELAY_MS = 60L

@Composable
private fun Modifier.tapGestures(
    interactions: MutableInteractionSource,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
): Modifier {
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    return pointerInput(interactions) {
        detectTapGestures(
            onPress = { offset ->
                val press = PressInteraction.Press(offset)
                coroutineScope {
                    val shown = async {
                        delay(PRESS_HIGHLIGHT_DELAY_MS)
                        interactions.emit(press)
                    }
                    val released = tryAwaitRelease()
                    if (shown.isCompleted) {
                        interactions.emit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                    } else {
                        shown.cancel()
                    }
                }
            },
            onTap = { click() },
            onLongPress = { longClick?.invoke() },
        )
    }
}

/**
 * Pressed colour: on TV the OK key "presses" a focused control, so it keeps the focus colour;
 * on touch screens it is the resting colour dimmed, like a highlighted iOS control.
 */
@Composable
private fun pressedColor(resting: Color, focused: Color): Color =
    if (LocalIsTv.current) focused else AppTheme.colors.label.copy(alpha = 0.12f).compositeOver(resting)

/** Prominent action: accent fill (iOS "filled" button). */
@Composable
fun appButtonColors(): ButtonColors {
    val colors = AppTheme.colors
    return ButtonDefaults.colors(
        containerColor = colors.accent,
        contentColor = colors.onAccent,
        focusedContainerColor = colors.focused,
        focusedContentColor = colors.onFocused,
        pressedContainerColor = pressedColor(colors.accent, colors.focused),
        pressedContentColor = if (LocalIsTv.current) colors.onFocused else colors.onAccent,
        disabledContainerColor = colors.fill,
        disabledContentColor = colors.tertiaryLabel,
    )
}

/** Secondary action: accent text on a light tint of it (iOS "tinted" button). */
@Composable
fun appSecondaryButtonColors(): ButtonColors {
    val colors = AppTheme.colors
    val container = colors.accent.copy(alpha = 0.16f).compositeOver(colors.background)
    return ButtonDefaults.colors(
        containerColor = container,
        contentColor = colors.accent,
        focusedContainerColor = colors.focused,
        focusedContentColor = colors.onFocused,
        pressedContainerColor = pressedColor(container, colors.focused),
        pressedContentColor = if (LocalIsTv.current) colors.onFocused else colors.accent,
        disabledContainerColor = colors.fill,
        disabledContentColor = colors.tertiaryLabel,
    )
}

/** Irreversible action (delete, clear): destructive red fill. */
@Composable
fun appDestructiveButtonColors(): ButtonColors {
    val colors = AppTheme.colors
    return ButtonDefaults.colors(
        containerColor = colors.destructive,
        contentColor = Color.White,
        focusedContainerColor = colors.focused,
        focusedContentColor = colors.onFocused,
        pressedContainerColor = pressedColor(colors.destructive, colors.focused),
        pressedContentColor = if (LocalIsTv.current) colors.onFocused else Color.White,
    )
}

/** Round icon button on a neutral fill. */
@Composable
fun appIconButtonColors(): ButtonColors {
    val colors = AppTheme.colors
    val container = colors.fill.compositeOver(colors.background)
    return IconButtonDefaults.colors(
        containerColor = container,
        contentColor = colors.accent,
        focusedContainerColor = colors.focused,
        focusedContentColor = colors.onFocused,
        pressedContainerColor = pressedColor(container, colors.focused),
        pressedContentColor = if (LocalIsTv.current) colors.onFocused else colors.accent,
        disabledContainerColor = container,
        disabledContentColor = colors.tertiaryLabel,
    )
}

@Composable
fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    colors: ButtonColors = appButtonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = if (enabled) modifier.tapGestures(interactions, onClick, null) else modifier,
        enabled = enabled,
        shape = ButtonDefaults.shape(RoundedCornerShape(AppTheme.shapes.button)),
        colors = colors,
        scale = if (LocalIsTv.current) ButtonDefaults.scale() else ButtonDefaults.scale(focusedScale = 1f),
        contentPadding = contentPadding,
        interactionSource = interactions,
        content = content,
    )
}

/** Same shape as [AppButton], tinted instead of filled: for the less important of two choices. */
@Composable
fun AppSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    AppButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = contentPadding,
        colors = appSecondaryButtonColors(),
        content = content,
    )
}

/** Round icon-only button; its size comes from [modifier] (tv-material's default is 40 dp). */
@Composable
fun AppIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = appIconButtonColors(),
    content: @Composable BoxScope.() -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    IconButton(
        onClick = onClick,
        modifier = if (enabled) modifier.tapGestures(interactions, onClick, null) else modifier,
        enabled = enabled,
        shape = IconButtonDefaults.shape(CircleShape),
        colors = colors,
        interactionSource = interactions,
        content = content,
    )
}

/** Where a row sits in its list; in [ListStyle.InsetGrouped] that decides its corners and separator. */
enum class ItemPosition {
    Single, First, Middle, Last;

    companion object {
        fun of(index: Int, count: Int): ItemPosition = when {
            count <= 1 -> Single
            index == 0 -> First
            index == count - 1 -> Last
            else -> Middle
        }
    }
}

/** Gap between list rows: none for grouped rows (they are divided by hairlines), a small one for cards. */
@Composable
fun listItemSpacing(): Dp = if (AppTheme.listStyle == ListStyle.InsetGrouped) 0.dp else 8.dp

/** Separator start inset: under the text, past the leading icon (16 padding + 32 icon + 8 gap). */
private val SeparatorInsetWithLeading = 56.dp
private val SeparatorInset = 16.dp

@Composable
fun AppListItem(
    selected: Boolean,
    onClick: () -> Unit,
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    position: ItemPosition = ItemPosition.Single,
    onLongClick: (() -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable BoxScope.() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    containerColor: Color = AppTheme.colors.card,
) {
    val colors = AppTheme.colors
    val grouped = AppTheme.listStyle == ListStyle.InsetGrouped
    val shape = rowShape(if (grouped) position else ItemPosition.Single, AppTheme.shapes.card)
    val interactions = remember { MutableInteractionSource() }
    val separator = colors.separator
    val separatorInset = if (leadingContent != null) SeparatorInsetWithLeading else SeparatorInset
    val showSeparator = grouped && (position == ItemPosition.Middle || position == ItemPosition.Last)
    ListItem(
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
        headlineContent = headlineContent,
        modifier = modifier
            .tapGestures(interactions, onClick, onLongClick)
            .drawWithContent {
                drawContent()
                if (showSeparator) {
                    val y = 0.5.dp.toPx()
                    drawLine(separator, Offset(separatorInset.toPx(), y), Offset(size.width, y), strokeWidth = 1.dp.toPx() / 2)
                }
            },
        supportingContent = supportingContent,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        shape = ListItemDefaults.shape(shape),
        colors = ListItemDefaults.colors(
            containerColor = containerColor,
            contentColor = colors.label,
            focusedContainerColor = colors.focused,
            focusedContentColor = colors.onFocused,
            pressedContainerColor = if (LocalIsTv.current) colors.focused else colors.cardPressed,
            pressedContentColor = if (LocalIsTv.current) colors.onFocused else colors.label,
            // Selection is shown with a checkmark, as in iOS, not with a different row colour.
            selectedContainerColor = containerColor,
            selectedContentColor = colors.label,
        ),
        // Grouped rows stay in line with their group; separate cards pop out on focus.
        scale = if (grouped) ListItemDefaults.scale(focusedScale = 1f) else ListItemDefaults.scale(focusedScale = 1.03f),
        interactionSource = interactions,
    )
}

private fun rowShape(position: ItemPosition, radius: Dp): Shape = when (position) {
    ItemPosition.Single -> RoundedCornerShape(radius)
    ItemPosition.First -> RoundedCornerShape(topStart = radius, topEnd = radius)
    ItemPosition.Middle -> RoundedCornerShape(0.dp)
    ItemPosition.Last -> RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
}
