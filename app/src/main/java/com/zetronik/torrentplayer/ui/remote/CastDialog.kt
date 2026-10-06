package com.zetronik.torrentplayer.ui.remote

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.zetronik.torrentplayer.R
import com.zetronik.torrentplayer.appContainer
import com.zetronik.torrentplayer.remote.DeviceDiscovery
import com.zetronik.torrentplayer.remote.PlayRequest
import com.zetronik.torrentplayer.remote.RemoteClient
import com.zetronik.torrentplayer.remote.RemoteDevice
import com.zetronik.torrentplayer.remote.RemoteError
import com.zetronik.torrentplayer.remote.RemoteException
import com.zetronik.torrentplayer.remote.deviceName
import com.zetronik.torrentplayer.ui.common.AppButton
import com.zetronik.torrentplayer.ui.common.AppDialog
import com.zetronik.torrentplayer.ui.common.AppListItem
import com.zetronik.torrentplayer.ui.common.AppSecondaryButton
import com.zetronik.torrentplayer.ui.common.IconBadge
import com.zetronik.torrentplayer.ui.common.ItemPosition
import com.zetronik.torrentplayer.ui.common.LoadingIndicator
import com.zetronik.torrentplayer.ui.common.listItemSpacing
import com.zetronik.torrentplayer.ui.common.requestFocusSafely
import com.zetronik.torrentplayer.ui.navigation.RemoteRoute
import com.zetronik.torrentplayer.ui.theme.AppTheme
import java.io.IOException
import kotlinx.coroutines.launch

private sealed interface CastState {
    data object Searching : CastState
    data class Pairing(val device: RemoteDevice, val wrongCode: Boolean = false) : CastState
    data class Busy(val device: RemoteDevice) : CastState
    data class Failed(@param:StringRes val message: Int) : CastState
}

private const val CODE_LENGTH = 4

/**
 * Phone: picks a TV on the network, pairs with it if needed (code shown on the TV) and sends it [request].
 * [onCasted] gets the route of the remote control screen once the TV has accepted the video.
 */
@Composable
fun CastDialog(
    request: suspend () -> PlayRequest?,
    onCasted: (RemoteRoute) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val pairings = context.appContainer.remotePairings
    val clientName = remember { deviceName(context) }
    val scope = rememberCoroutineScope()
    val discovery = remember { DeviceDiscovery(context.applicationContext) }
    val devices by discovery.devices.collectAsStateWithLifecycle()
    var state by remember { mutableStateOf<CastState>(CastState.Searching) }

    DisposableEffect(discovery) {
        discovery.start(scope)
        onDispose { discovery.stop() }
    }

    suspend fun startPairing(device: RemoteDevice) {
        RemoteClient(device, token = null).startPairing(clientName)
        state = CastState.Pairing(device)
    }

    fun send(device: RemoteDevice) {
        scope.launch {
            state = CastState.Busy(device)
            try {
                val token = pairings.token(device.id) ?: return@launch startPairing(device)
                val play = request() ?: return@launch run { state = CastState.Failed(R.string.remote_error_failed) }
                RemoteClient(device, token).play(play)
                onCasted(RemoteRoute(device.id, device.name, device.host, device.port))
            } catch (e: RemoteException) {
                if (e.error == RemoteError.UNAUTHORIZED) {
                    // The TV forgot us (app data cleared): pair again.
                    pairings.remove(device.id)
                    runCatching { startPairing(device) }
                        .onFailure { state = CastState.Failed(R.string.remote_error_unreachable) }
                } else {
                    state = CastState.Failed(R.string.remote_error_failed)
                }
            } catch (_: IOException) {
                state = CastState.Failed(R.string.remote_error_unreachable)
            }
        }
    }

    fun confirm(device: RemoteDevice, code: String) {
        scope.launch {
            state = CastState.Busy(device)
            try {
                pairings.save(device.id, RemoteClient(device, token = null).confirmPairing(clientName, code))
                send(device)
            } catch (e: RemoteException) {
                state = if (e.error == RemoteError.WRONG_CODE) {
                    CastState.Pairing(device, wrongCode = true)
                } else {
                    CastState.Failed(R.string.remote_error_pairing)
                }
            } catch (_: IOException) {
                state = CastState.Failed(R.string.remote_error_unreachable)
            }
        }
    }

    AppDialog(onDismissRequest = onDismiss, maxWidth = 520.dp) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.remote_cast), style = MaterialTheme.typography.titleLarge)
            when (val current = state) {
                CastState.Searching -> DeviceList(devices, isPaired = { pairings.token(it.id) != null }, onSelect = ::send)
                is CastState.Pairing -> CodeEntry(current) { code -> confirm(current.device, code) }
                is CastState.Busy -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    LoadingIndicator(size = 32.dp)
                    Text(stringResource(R.string.remote_sending, current.device.name), Modifier.weight(1f))
                }
                is CastState.Failed -> {
                    Text(stringResource(current.message), style = MaterialTheme.typography.bodyLarge)
                    AppButton(onClick = { state = CastState.Searching }) { Text(stringResource(R.string.retry)) }
                }
            }
            AppSecondaryButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

@Composable
private fun DeviceList(devices: List<RemoteDevice>, isPaired: (RemoteDevice) -> Boolean, onSelect: (RemoteDevice) -> Unit) {
    LazyColumn(Modifier.heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(listItemSpacing())) {
        itemsIndexed(devices, key = { _, device -> device.id }) { index, device ->
            AppListItem(
                selected = false,
                position = ItemPosition.of(index, devices.size),
                onClick = { onSelect(device) },
                headlineContent = { Text(device.name) },
                supportingContent = {
                    Text(stringResource(if (isPaired(device)) R.string.remote_paired else R.string.remote_not_paired))
                },
                leadingContent = { IconBadge(R.drawable.ic_tv) },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LoadingIndicator(size = 20.dp)
        Text(stringResource(R.string.remote_searching), style = MaterialTheme.typography.bodyMedium)
    }
    if (devices.isEmpty()) {
        Text(
            stringResource(R.string.remote_search_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.secondaryLabel,
        )
    }
}

@Composable
private fun CodeEntry(state: CastState.Pairing, onConfirm: (String) -> Unit) {
    var code by remember(state) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Text(stringResource(R.string.remote_pairing_enter, state.device.name), style = MaterialTheme.typography.bodyLarge)
    Box(
        Modifier
            .fillMaxWidth()
            .background(AppTheme.colors.fill, MaterialTheme.shapes.medium)
            .border(2.dp, AppTheme.colors.accent, MaterialTheme.shapes.medium)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicTextField(
            value = code,
            onValueChange = { value ->
                code = value.filter(Char::isDigit).take(CODE_LENGTH)
                if (code.length == CODE_LENGTH) onConfirm(code)
            },
            singleLine = true,
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 32.sp,
                letterSpacing = 12.sp,
                textAlign = TextAlign.Center,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus),
        )
    }
    if (state.wrongCode) {
        Text(stringResource(R.string.remote_pairing_wrong), color = AppTheme.colors.destructive)
    }
    AppButton(onClick = { if (code.length == CODE_LENGTH) onConfirm(code) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.remote_connect)) }
    LaunchedEffect(state) {
        focus.requestFocusSafely()
        keyboard?.show()
    }
}
