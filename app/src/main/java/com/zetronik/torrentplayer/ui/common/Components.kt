package com.zetronik.torrentplayer.ui.common

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.zetronik.torrentplayer.R

/**
 * Rotating arc. The rotation runs in the graphics layer, so each frame is a cheap transform
 * without recomposition or re-drawing the path.
 */
@Composable
fun LoadingIndicator(modifier: Modifier = Modifier, size: Dp = 48.dp, color: Color = MaterialTheme.colorScheme.primary) {
    val transition = rememberInfiniteTransition(label = "loading")
    val rotation = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_000, easing = LinearEasing), RepeatMode.Restart),
        label = "rotation",
    )
    Canvas(
        modifier
            .size(size)
            .graphicsLayer { rotationZ = rotation.value }
    ) {
        val stroke = this.size.minDimension / 10
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        val cancelFocus = remember { FocusRequester() }
        Surface(
            modifier = Modifier
                .ignoreHeldConfirmKey()
                .widthIn(max = 480.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Row(
                    modifier = Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AppOutlinedButton(onClick = onDismiss, modifier = Modifier.focusRequester(cancelFocus)) {
                        Text(stringResource(R.string.cancel))
                    }
                    AppButton(onClick = onConfirm) { Text(confirmText) }
                }
            }
        }
        // The safe choice is focused, so a stray OK press does not delete anything.
        LaunchedEffect(Unit) { cancelFocus.requestFocusSafely() }
    }
}

/**
 * For dialogs opened by a long OK press: the OK key is still held when the dialog appears, and its release
 * would click the freshly focused first option (tv-material clicks on key up). A release only counts if
 * this dialog also saw the press; auto-repeat presses while the key is held do not count.
 */
@Composable
fun Modifier.ignoreHeldConfirmKey(): Modifier {
    var pressedHere by remember { mutableStateOf(false) }
    return onPreviewKeyEvent { event ->
        if (event.key != Key.DirectionCenter && event.key != Key.Enter && event.key != Key.NumPadEnter) {
            return@onPreviewKeyEvent false
        }
        when (event.type) {
            KeyEventType.KeyDown -> {
                if (event.nativeKeyEvent.repeatCount == 0) pressedHere = true
                !pressedHere
            }
            KeyEventType.KeyUp -> {
                val stray = !pressedHere
                pressedHere = false
                stray
            }
            else -> false
        }
    }
}

/**
 * A focus requester for a lazy list item, registered under [key] while the item is composed,
 * so the screen can move focus to a specific item (e.g. the one the user came back from).
 */
@Composable
fun <K> rememberItemFocusRequester(registry: MutableMap<K, FocusRequester>, key: K): FocusRequester {
    val requester = remember { FocusRequester() }
    DisposableEffect(registry, key) {
        registry[key] = requester
        onDispose { if (registry[key] === requester) registry.remove(key) }
    }
    return requester
}

/** requestFocus() throws if the node is not attached yet (e.g. scrolled out); focus is best-effort here. */
fun FocusRequester.requestFocusSafely() {
    runCatching { requestFocus() }
}

fun Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
