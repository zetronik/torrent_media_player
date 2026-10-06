package com.zetronik.torrentplayer.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.zetronik.torrentplayer.R
import com.zetronik.torrentplayer.ui.theme.AppTheme
import com.zetronik.torrentplayer.ui.theme.LocalIsTv

data class DialogOption(
    val label: String,
    val selected: Boolean = false,
    @param:DrawableRes val icon: Int? = null,
    val onSelect: () -> Unit,
)

/** A titled list of choices (track selection, item actions). Focus starts on the selected option. */
@Composable
fun OptionsDialog(title: String, options: List<DialogOption>, onDismiss: () -> Unit) {
    val listState = rememberLazyListState()
    val selectedIndex = options.indexOfFirst { it.selected }.coerceAtLeast(0)
    val selectedFocus = remember { FocusRequester() }
    AppDialog(onDismissRequest = onDismiss, modifier = Modifier.ignoreHeldConfirmKey(), maxWidth = 560.dp) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 12.dp),
        )
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (LocalIsTv.current) 4.dp else 0.dp),
        ) {
            itemsIndexed(options) { index, option ->
                AppListItem(
                    selected = option.selected,
                    onClick = {
                        option.onSelect()
                        onDismiss()
                    },
                    modifier = if (index == selectedIndex) Modifier.focusRequester(selectedFocus) else Modifier,
                    position = ItemPosition.of(index, options.size),
                    // Rows lie flat on the dialog, divided by hairlines (iOS action sheet).
                    containerColor = Color.Transparent,
                    headlineContent = { Text(option.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    leadingContent = option.icon?.let { icon ->
                        { Icon(painterResource(icon), null, Modifier.size(22.dp), tint = AppTheme.colors.accent) }
                    },
                    trailingContent = if (option.selected) {
                        { Icon(painterResource(R.drawable.ic_check), null, Modifier.size(22.dp), tint = AppTheme.colors.accent) }
                    } else {
                        null
                    },
                )
            }
        }
        val keyboardNavigation = isKeyboardNavigation()
        LaunchedEffect(Unit) {
            listState.scrollToItem(selectedIndex)
            withFrameNanos { }
            if (keyboardNavigation) selectedFocus.requestFocusSafely()
        }
    }
}
