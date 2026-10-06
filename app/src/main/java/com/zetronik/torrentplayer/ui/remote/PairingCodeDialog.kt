package com.zetronik.torrentplayer.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.zetronik.torrentplayer.R
import com.zetronik.torrentplayer.remote.RemoteReceiver
import com.zetronik.torrentplayer.ui.common.AppButton
import com.zetronik.torrentplayer.ui.common.AppDialog
import com.zetronik.torrentplayer.ui.common.requestFocusSafely
import com.zetronik.torrentplayer.ui.theme.AppTheme

/** TV: shows the code a phone has to type to pair, over whatever screen is open. */
@Composable
fun PairingCodeDialog(receiver: RemoteReceiver) {
    val prompt by receiver.prompt.collectAsStateWithLifecycle()
    val current = prompt ?: return
    val focus = remember { FocusRequester() }
    AppDialog(onDismissRequest = receiver::cancelPairing, maxWidth = 560.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                stringResource(R.string.remote_pairing_title, current.client),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                current.code,
                style = MaterialTheme.typography.displayLarge,
                letterSpacing = 16.sp,
                color = AppTheme.colors.accent,
            )
            Text(
                stringResource(R.string.remote_pairing_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = AppTheme.colors.secondaryLabel,
                textAlign = TextAlign.Center,
            )
            AppButton(onClick = receiver::cancelPairing, modifier = Modifier.focusRequester(focus)) {
                Text(stringResource(R.string.cancel))
            }
        }
        LaunchedEffect(current) { focus.requestFocusSafely() }
    }
}
