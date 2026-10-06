package com.zetronik.torrentplayer.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.zetronik.torrentplayer.R
import com.zetronik.torrentplayer.ui.theme.AppTheme
import com.zetronik.torrentplayer.ui.theme.LocalIsTv

/*
 * Adaptive building blocks. The rule that keeps buttons from being squeezed: anything with a label gets
 * `weight(fill = false)` in its row, so it ellipsizes when space runs out instead of crushing its
 * neighbours, and compact windows fall back to icon-only buttons or stacked rows.
 */

/** Size of touch targets on touch screens (Apple HIG minimum). */
val TouchTarget = 44.dp

/**
 * Screen title with optional back button and actions, in the iOS navigation bar pattern:
 *  - compact (phone portrait): actions in a bar of their own, a large title under them;
 *  - wide (landscape, tablet, TV): title and actions on one line, the title ellipsizing first.
 * The back button is only shown on touch screens; a remote has a Back key.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleMaxLines: Int = 2,
    largeTitle: Boolean = true,
    onBack: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val layout = AppTheme.layout
    val back = onBack?.takeIf { !layout.isTv }
    // The Large Title only where it has a line of its own (compact) or on TV, where the screen is far away;
    // next to the actions on a phone or tablet it is the smaller inline title, as in iOS.
    val titleStyle = when {
        layout.isShort -> MaterialTheme.typography.titleLarge
        largeTitle && (layout.isCompact || layout.isTv) -> MaterialTheme.typography.headlineMedium
        else -> MaterialTheme.typography.headlineSmall
    }

    @Composable
    fun TitleBlock(modifier: Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = titleStyle, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.secondaryLabel,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    if (layout.isCompact) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (back != null || actions != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = TouchTarget),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (back != null) BackButton(back)
                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { actions?.invoke(this) }
                }
            }
            TitleBlock(Modifier.fillMaxWidth())
        }
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (back != null) BackButton(back)
            TitleBlock(Modifier.weight(1f))
            if (actions != null) {
                Row(
                    // Wider on TV: a focused button grows by 10% and would touch its neighbour.
                    horizontalArrangement = Arrangement.spacedBy(if (layout.isTv) 20.dp else 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    AppIconButton(onClick = onClick, modifier = Modifier.size(TouchTarget)) {
        Icon(
            painterResource(R.drawable.ic_chevron_left),
            contentDescription = stringResource(R.string.back),
            modifier = Modifier
                .align(Alignment.Center)
                .size(26.dp),
        )
    }
}

/**
 * A toolbar button: icon with a label where there is room (TV, large tablets), icon only on phones in
 * either orientation, where labels would crowd out the title. [prominent] always keeps its label: it is
 * the screen's main action.
 */
@Composable
fun RowScope.ToolbarAction(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
    busy: Boolean = false,
) {
    val layout = AppTheme.layout
    val showLabel = prominent || layout.isTv || layout.isExpanded
    if (showLabel) {
        val content: @Composable RowScope.() -> Unit = {
            if (busy) {
                LoadingIndicator(size = 20.dp, color = LocalContentColor.current)
            } else {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val buttonModifier = modifier.weight(1f, fill = false)
        val padding = ButtonDefaults.ButtonWithIconContentPadding
        if (prominent) {
            AppButton(onClick = onClick, modifier = buttonModifier, contentPadding = padding, content = content)
        } else {
            AppSecondaryButton(onClick = onClick, modifier = buttonModifier, contentPadding = padding, content = content)
        }
    } else {
        AppIconButton(onClick = onClick, modifier = modifier.size(TouchTarget)) {
            if (busy) {
                LoadingIndicator(Modifier.align(Alignment.Center), size = 20.dp, color = LocalContentColor.current)
            } else {
                Icon(
                    painterResource(icon),
                    contentDescription = label,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(22.dp),
                )
            }
        }
    }
}

/** iOS Settings-style leading icon: a white glyph on a rounded coloured square. */
@Composable
fun IconBadge(@DrawableRes icon: Int, modifier: Modifier = Modifier, color: Color = AppTheme.colors.accent) {
    Box(
        modifier
            .size(32.dp)
            .background(color, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = Color.White)
    }
}

/** The ">" at the end of a row that opens another screen; touch screens only, as in iOS. */
@Composable
fun DisclosureIndicator() {
    if (LocalIsTv.current) return
    Icon(
        painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = AppTheme.colors.tertiaryLabel,
    )
}

/**
 * Caps content width on large tablets so rows do not stretch across the whole screen; phones and TVs use
 * the whole width. Generous enough that a landscape tablet is not left with wide empty margins.
 */
@Composable
fun Modifier.readableWidth(max: Dp = 1040.dp): Modifier =
    if (AppTheme.layout.isExpanded) widthIn(max = max) else this

/** Dialog card in the template's style; [content] is laid out in a column. */
@Composable
fun AppDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 480.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Column(
            modifier
                .widthIn(min = 280.dp, max = maxWidth)
                .clip(RoundedCornerShape(AppTheme.shapes.dialog))
                .background(AppTheme.colors.elevated),
            content = content,
        )
    }
}
