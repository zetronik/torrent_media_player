package com.zetronik.torrentplayer.ui.files

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.net.toUri
import androidx.tv.material3.Text
import com.zetronik.torrentplayer.R
import com.zetronik.torrentplayer.appContainer
import com.zetronik.torrentplayer.data.PlaybackPosition
import com.zetronik.torrentplayer.media.FolderItem
import com.zetronik.torrentplayer.media.LocalKind
import com.zetronik.torrentplayer.ui.common.AppListItem
import com.zetronik.torrentplayer.ui.common.DisclosureIndicator
import com.zetronik.torrentplayer.ui.common.IconBadge
import com.zetronik.torrentplayer.ui.common.ItemPosition
import com.zetronik.torrentplayer.ui.common.formatDuration
import com.zetronik.torrentplayer.ui.common.formatSize
import com.zetronik.torrentplayer.ui.common.toast

/**
 * Asks for the media permission. After a second refusal the system no longer shows the request,
 * so the row opens the app settings instead. [onResult] runs after the request (granted or not).
 */
@Composable
fun PermissionRow(onResult: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val activity = LocalActivity.current
    val files = context.appContainer.localFiles
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onResult() }
    val permanentlyDenied = files.permissionRequested &&
        activity?.shouldShowRequestPermissionRationale(files.permissions.first()) == false
    AppListItem(
        selected = false,
        onClick = {
            if (permanentlyDenied) {
                val settings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
                try {
                    context.startActivity(settings)
                } catch (_: ActivityNotFoundException) {
                    context.toast(resources.getString(R.string.files_grant_access_denied))
                }
            } else {
                files.permissionRequested = true
                request.launch(files.permissions)
            }
        },
        modifier = modifier,
        headlineContent = { Text(stringResource(R.string.files_grant_access)) },
        supportingContent = {
            Text(
                stringResource(if (permanentlyDenied) R.string.files_grant_access_denied else R.string.files_grant_access_hint),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { IconBadge(R.drawable.ic_movie) },
        trailingContent = { DisclosureIndicator() },
    )
}

/** A folder, video or playlist in a folder listing. [resume] is the saved position of a video. */
@Composable
fun FolderItemRow(
    item: FolderItem,
    position: ItemPosition,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    resume: PlaybackPosition? = null,
) {
    val resources = LocalResources.current
    when (item) {
        is FolderItem.Folder -> AppListItem(
            selected = false,
            position = position,
            onClick = onClick,
            modifier = modifier,
            headlineContent = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            leadingContent = { IconBadge(R.drawable.ic_folder) },
            trailingContent = { DisclosureIndicator() },
        )
        is FolderItem.File -> AppListItem(
            selected = false,
            position = position,
            onClick = onClick,
            modifier = modifier,
            headlineContent = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                val details = buildList {
                    if (item.kind == LocalKind.PLAYLIST) add(stringResource(R.string.files_playlist))
                    if (item.size > 0) add(resources.formatSize(item.size))
                    if (resume != null) {
                        add(
                            stringResource(
                                R.string.torrent_resume_at,
                                formatDuration(resume.positionMs),
                                formatDuration(resume.durationMs),
                            )
                        )
                    } else if (item.durationMs > 0) {
                        add(formatDuration(item.durationMs))
                    }
                }
                Text(details.joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            leadingContent = { IconBadge(kindIcon(item.kind, resume != null)) },
        )
    }
}

fun kindIcon(kind: LocalKind?, resumable: Boolean = false): Int = when {
    kind == null -> R.drawable.ic_folder
    kind == LocalKind.PLAYLIST -> R.drawable.ic_playlist
    resumable -> R.drawable.ic_play
    else -> R.drawable.ic_movie
}
