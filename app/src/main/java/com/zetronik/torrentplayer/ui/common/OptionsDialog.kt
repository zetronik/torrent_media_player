package com.zetronik.torrentplayer.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.zetronik.torrentplayer.R

data class DialogOption(
    val label: String,
    val selected: Boolean = false,
    @param:DrawableRes val icon: Int? = null,
    val onSelect: () -> Unit,
)

/** A titled list of choices (track selection, item actions). Focus starts on the selected option. */
@Composable
fun OptionsDialog(title: String, options: List<DialogOption>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        val listState = rememberLazyListState()
        val selectedIndex = options.indexOfFirst { it.selected }.coerceAtLeast(0)
        val selectedFocus = remember { FocusRequester() }
        Surface(
            modifier = Modifier
                .ignoreHeldConfirmKey()
                .widthIn(min = 320.dp, max = 560.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(options) { index, option ->
                        AppListItem(
                            selected = option.selected,
                            onClick = {
                                option.onSelect()
                                onDismiss()
                            },
                            modifier = if (index == selectedIndex) Modifier.focusRequester(selectedFocus) else Modifier,
                            headlineContent = { Text(option.label) },
                            leadingContent = option.icon?.let { icon ->
                                { Icon(painterResource(icon), null, Modifier.size(20.dp)) }
                            },
                            trailingContent = if (option.selected) {
                                { Icon(painterResource(R.drawable.ic_check), null, Modifier.size(20.dp)) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
        LaunchedEffect(Unit) {
            listState.scrollToItem(selectedIndex)
            withFrameNanos { }
            selectedFocus.requestFocusSafely()
        }
    }
}
