package com.wanderwildwood.garo

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mudita.mmd.ThemeMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.garo.media.Picture
import com.wanderwildwood.garo.media.Wanted
import com.wanderwildwood.garo.ui.FoldersScreen
import com.wanderwildwood.garo.ui.GalleryViewModel
import com.wanderwildwood.garo.ui.GridScreen
import com.wanderwildwood.garo.ui.RefreshOnResume
import com.wanderwildwood.garo.ui.Server
import com.wanderwildwood.garo.ui.monochrome
import com.wanderwildwood.garo.ui.openAppSettings
import com.wanderwildwood.garo.ui.rememberAsk

/**
 * Another app asking for a picture: Email attaching one, Messaging sending one.
 *
 * The same folders and the same grid as the gallery, so choosing a picture looks like looking
 * at one. Where the app takes one picture, a tap is the answer. Where it takes several, a tap
 * marks a picture with a bold edge — marks survive moving between folders — and the bar's
 * "Choose" sends them all.
 *
 * Its own activity in the standard launch mode, so the answer goes back to the app that asked
 * rather than into a gallery that happened to be open already.
 */
class PickActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wanted = Wanted(intent?.type, intent?.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList())
        val several = intent?.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) == true

        setContent {
            ThemeMMD(colorScheme = monochrome) {
                Pick(wanted, several, onAnswer = ::answer, onCancel = {
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                })
            }
        }
    }

    private fun answer(pictures: List<Picture>) {
        if (pictures.isEmpty()) return
        val clip = ClipData.newUri(contentResolver, null, pictures.first().uri)
        pictures.drop(1).forEach { clip.addItem(ClipData.Item(it.uri)) }
        // The address in both places: older apps read only the data, and anything taking
        // several reads the clip. The grant rides on both, so the asking app can open them
        // without a permission of its own.
        val result = Intent()
            .setData(pictures.first().uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        result.clipData = clip
        setResult(Activity.RESULT_OK, result)
        finish()
    }
}

@Composable
private fun Pick(
    wanted: Wanted,
    several: Boolean,
    onAnswer: (List<Picture>) -> Unit,
    onCancel: () -> Unit,
    viewModel: GalleryViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val ask = rememberAsk(viewModel)
    RefreshOnResume(viewModel)

    // Only what the asking app can take; a folder with none of it is not offered.
    val folders = remember(state.folders, wanted) {
        state.folders.mapNotNull { f ->
            val fits = f.pictures.filter { wanted.accepts(it.mime) }
            if (fits.isEmpty()) null else f.copy(pictures = fits)
        }
    }

    var folderKey by rememberSaveable { mutableStateOf<String?>(null) }
    var chosen by rememberSaveable { mutableStateOf(LongArray(0)) }
    val chosenSet = remember(chosen) { chosen.toSet() }
    val foldersList = rememberLazyListState()
    val gridList = rememberLazyListState()

    val title = stringResource(if (several) R.string.pick_title_several else R.string.pick_title_one)

    // "Choose 3" in the bar once anything is marked. Words, not a tick: it says how many will go.
    val send: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        if (chosenSet.isNotEmpty()) {
            TextMMD(
                text = stringResource(R.string.pick_send, chosenSet.size),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable {
                        val all = folders.flatMap { it.pictures }.distinctBy { it.id }
                        onAnswer(chosen.toList().mapNotNull { id -> all.firstOrNull { it.id == id } })
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }

    val folder = folders.firstOrNull { it.key == folderKey }
    if (folder != null) {
        BackHandler { folderKey = null }
        GridScreen(
            folder = folder,
            perRow = state.choices.perRow,
            decoder = viewModel.decoder,
            listState = gridList,
            onBack = { folderKey = null },
            onOpen = { i ->
                val picture = folder.pictures[i]
                if (!several) {
                    onAnswer(listOf(picture))
                } else {
                    chosen = if (picture.id in chosenSet) chosen.filter { it != picture.id }.toLongArray() else chosen + picture.id
                }
            },
            chosen = chosenSet,
            actions = send,
        )
    } else {
        BackHandler(onBack = onCancel)
        FoldersScreen(
            // The phone's own pictures only. An album on the server has no address on the phone
            // to hand the asking app; offering one would be offering something that cannot arrive.
            state = state.copy(folders = folders, albums = emptyList(), server = Server.NONE),
            decoder = viewModel.decoder,
            listState = foldersList,
            onOpen = {
                if (it.key != folderKey) gridList.requestScrollToItem(0)
                folderKey = it.key
            },
            onSettings = {},
            onAllow = ask,
            onAppSettings = { openAppSettings(context) },
            choosing = title,
            onCancel = onCancel,
            none = if (state.folders.isEmpty()) null else stringResource(R.string.pick_none_fit),
            actions = send,
        )
    }
}
