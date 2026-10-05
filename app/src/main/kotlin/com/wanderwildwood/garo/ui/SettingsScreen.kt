package com.wanderwildwood.garo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.garo.R
import com.wanderwildwood.garo.media.Choices
import com.wanderwildwood.garo.media.FolderOrder
import com.wanderwildwood.garo.media.PictureOrder

/**
 * Three rows, each cycling through its few values in place.
 *
 * Fossify has some sixty settings. What survives is what changes what a person sees on this
 * screen; the rest — themes, animations, swipe-to-dismiss, slideshow timing, the editor, video
 * playback — either cannot be honoured by the panel or belongs to a part that was left out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    choices: Choices,
    onChoose: (Choices) -> Unit,
    onClose: () -> Unit,
) {
    var aboutOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.settings_title)) },
                navigationIcon = { BarButton(Icons.Close, stringResource(R.string.settings_cd_close), onClose) },
                actions = { BarButton(Icons.Info, stringResource(R.string.settings_cd_about)) { aboutOpen = true } },
            )
        },
    ) { padding ->
        LazyColumnMMD(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            item { Spacer(Modifier.height(12.dp)) }
            item {
                Setting(
                    title = stringResource(R.string.settings_folder_order),
                    value = stringResource(
                        when (choices.folderOrder) {
                            FolderOrder.NEWEST -> R.string.order_newest_first
                            FolderOrder.NAME -> R.string.order_name
                        },
                    ),
                    onClick = { onChoose(choices.copy(folderOrder = choices.folderOrder.next())) },
                )
            }
            item {
                Setting(
                    title = stringResource(R.string.settings_picture_order),
                    value = stringResource(
                        when (choices.pictureOrder) {
                            PictureOrder.NEWEST -> R.string.order_newest_first
                            PictureOrder.OLDEST -> R.string.order_oldest_first
                            PictureOrder.NAME -> R.string.order_name
                        },
                    ),
                    onClick = { onChoose(choices.copy(pictureOrder = choices.pictureOrder.next())) },
                )
            }
            item {
                val sizes = Choices.PER_ROW
                Setting(
                    title = stringResource(R.string.settings_per_row),
                    value = choices.perRow.toString(),
                    onClick = {
                        onChoose(choices.copy(perRow = sizes[(sizes.indexOf(choices.perRow) + 1) % sizes.size]))
                    },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (aboutOpen) AboutDialog(onDismiss = { aboutOpen = false })
}

private inline fun <reified E : Enum<E>> E.next(): E {
    val all = enumValues<E>()
    return all[(ordinal + 1) % all.size]
}

@Composable
private fun Setting(title: String, value: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    ) {
        TextMMD(text = title, style = MaterialTheme.typography.bodyMedium)
        TextMMD(text = value, style = MaterialTheme.typography.labelSmall)
    }
}
