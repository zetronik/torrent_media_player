package com.zetronik.torrentplayer.ui.common

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.OutlinedButton

/*
 * tv-material's clickable components only react to D-pad center / Enter: they have no touch handling.
 * The app also targets phones and tablets, so these wrappers add tap and long-press on top.
 */

@Composable
private fun Modifier.tapGestures(onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier {
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    return pointerInput(Unit) {
        detectTapGestures(
            onTap = { click() },
            onLongPress = { longClick?.invoke() },
        )
    }
}

@Composable
fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.tapGestures(onClick, null),
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
fun AppOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = modifier.tapGestures(onClick, null), content = content)
}

@Composable
fun AppListItem(
    selected: Boolean,
    onClick: () -> Unit,
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable BoxScope.() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    ListItem(
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
        headlineContent = headlineContent,
        modifier = modifier.tapGestures(onClick, onLongClick),
        supportingContent = supportingContent,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
    )
}
