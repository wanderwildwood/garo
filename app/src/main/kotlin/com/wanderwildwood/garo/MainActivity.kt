package com.wanderwildwood.garo

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.wanderwildwood.garo.media.Arrange
import com.wanderwildwood.garo.media.MediaIndex
import com.wanderwildwood.garo.media.Picture
import com.wanderwildwood.garo.ui.Access
import com.wanderwildwood.garo.ui.FoldersScreen
import com.wanderwildwood.garo.ui.GalleryViewModel
import com.wanderwildwood.garo.ui.GridScreen
import com.wanderwildwood.garo.ui.RefreshOnResume
import com.wanderwildwood.garo.ui.rememberPull
import com.wanderwildwood.garo.ui.Server
import com.wanderwildwood.garo.ui.SettingsScreen
import com.wanderwildwood.garo.ui.ViewerScreen
import com.wanderwildwood.garo.ui.monochrome
import com.wanderwildwood.garo.ui.openAppSettings
import com.wanderwildwood.garo.ui.rememberAsk

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Another app asking for one picture to be shown: a file from Files, an attachment from
        // Email, the camera's "last photo". Leaving the picture leaves the app, back to whoever
        // asked.
        val handed: Uri? = intent?.takeIf { it.action in SHOW }?.data
        // How to recognise the handed picture among the index's, when it can be recognised.
        val lookFor: ((Picture) -> Boolean)? = handed?.let { uri ->
            MediaIndex.indexId(uri)?.let { id -> { p: Picture -> p.id == id } }
                ?: MediaIndex.tanaPath(uri)?.let { path -> { p: Picture -> "${p.folderPath}/${p.name}" == path } }
        }

        setContent {
            ThemeMMD(colorScheme = monochrome) {
                when {
                    handed == null -> Gallery()
                    // One the phone's index knows — the camera's, or a file from Files — is opened
                    // in its own folder, so the pictures either side are a page-turn away.
                    lookFor != null -> Gallery(lookFor = lookFor, handed = handed, onLeave = ::finish)
                    // Anyone else's — an attachment, a file in a message — can only be shown alone.
                    else -> Outside(handed, onBack = ::finish)
                }
            }
        }
    }

    private companion object {
        val SHOW = setOf(
            Intent.ACTION_VIEW,
            // What a camera sends from its "last photo" thumbnail; the older spelling is what
            // Open Camera still uses first.
            "com.android.camera.action.REVIEW",
            MediaStore.ACTION_REVIEW,
        )
    }
}

@Composable
private fun Outside(uri: Uri, onBack: () -> Unit, viewModel: GalleryViewModel = viewModel()) {
    val picture: Picture = remember(uri) { viewModel.outside(uri) }
    ViewerScreen(
        pictures = listOf(picture),
        startIndex = 0,
        decoder = viewModel.decoder,
        canDelete = false,
        onIndex = {},
        onBack = onBack,
        onDelete = {},
    )
}

/**
 * The gallery: folders, a folder's pictures, one picture.
 *
 * With [lookFor] set it was opened on a particular picture by another app, and goes straight to
 * that picture inside its folder; [onLeave] then takes the place of going back up the folders.
 */
@Composable
private fun Gallery(
    lookFor: ((Picture) -> Boolean)? = null,
    handed: Uri? = null,
    onLeave: () -> Unit = {},
    viewModel: GalleryViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val ask = rememberAsk(viewModel)
    RefreshOnResume(viewModel)

    // Where the reader is. Kept as plain keys so a turn of the phone or a trip to the share
    // sheet brings them back to the same picture rather than the top of the app.
    var folderKey by rememberSaveable { mutableStateOf<String?>(null) }
    var viewing by rememberSaveable { mutableStateOf<Int?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var found by rememberSaveable { mutableStateOf(lookFor == null) }
    var notFound by rememberSaveable { mutableStateOf(false) }
    // Opened on one picture, then gone on from it to its folder: from here it is the whole
    // gallery, and back walks up through it rather than straight out to the app that asked.
    var browsing by rememberSaveable { mutableStateOf(false) }
    // Pictures marked in a folder to be deleted together. A hold starts it; it lasts while
    // the folder is open, and leaving the folder lets them go.
    var marked by rememberSaveable { mutableStateOf(LongArray(0)) }
    val markedSet = remember(marked) { marked.toSet() }

    val foldersList = rememberLazyListState()
    val gridList = rememberLazyListState()

    // Find the handed picture once the index has been read.
    LaunchedEffect(state.reading, state.folders, state.access) {
        if (found || state.reading || state.access != Access.GRANTED) return@LaunchedEffect
        // In its own folder, not the pile of everything, so "Show in Files" and the turns either
        // side are about the folder it came from.
        for (f in state.folders.filter { it.key != Arrange.ALL }) {
            val i = f.pictures.indexOfFirst { lookFor?.invoke(it) == true }
            if (i >= 0) {
                folderKey = f.key
                viewing = i
                gridList.requestScrollToItem(i / state.choices.perRow)
                found = true
                return@LaunchedEffect
            }
        }
        // In the index but not a picture this shows — a pending one, say. Show it alone.
        found = true
        notFound = true
    }
    // Without the permission, a handed picture is still shown, on its own, from the reading
    // grant the handing app gave with it — a request to see one photo is not the moment to
    // stop and ask for all of them.
    val noAccess = !state.reading && state.access != Access.GRANTED
    if (handed != null && (notFound || (lookFor != null && !found && noAccess))) {
        Outside(handed, onBack = onLeave)
        return
    }

    // The phone, not this app, asks whether to delete: on Android 11 and later a picture the
    // camera made belongs to the camera, and only the system's own question can give it up.
    // That makes it a dialog, which the house style otherwise avoids, but it is the phone's.
    var deleting by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val delete = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val gone = deleting
        deleting = emptyList()
        if (result.resultCode == Activity.RESULT_OK && gone.isNotEmpty()) {
            viewModel.deleted(gone)
            marked = LongArray(0)
        }
    }
    // One question from the phone for all of them, however many.
    val askToDelete: (List<Uri>) -> Unit = { uris ->
        deleting = uris
        val request = MediaStore.createDeleteRequest(context.contentResolver, uris)
        delete.launch(IntentSenderRequest.Builder(request.intentSender).build())
    }

    val folder = state.folders.firstOrNull { it.key == folderKey } ?: state.albums.firstOrNull { it.key == folderKey }

    // A swipe down at the top of a list fed from Immich asks it again, and says so in a line.
    // The phone's own folders are read again on every return to the app, so with no server set
    // the folders list has nothing a pull would add.
    val albumPull = rememberPull { folderKey?.let { viewModel.pullAlbum(it) } ?: state.server }
    val foldersPull = rememberPull { viewModel.pullFolders() }

    // An album's pictures are read from the server when it is opened, and again each time.
    LaunchedEffect(folderKey) {
        folderKey?.let { key -> if (state.albums.any { it.key == key }) viewModel.openAlbum(key) }
    }

    // A folder emptied from outside — every picture deleted elsewhere — has nothing left to show.
    // Asked of the state as it is when the effect runs, not of the folder this composition saw:
    // the search above sets a folder in the same frame, and the folder seen here is still the
    // one from before it, which would undo the search as soon as it succeeded.
    LaunchedEffect(folderKey, folder, state.reading) {
        val key = folderKey
        if (key != null && !state.reading && found && state.folders.none { it.key == key } && state.albums.none { it.key == key }) {
            folderKey = null
            viewing = null
        }
    }

    when {
        settingsOpen -> {
            BackHandler { settingsOpen = false }
            // The worker writes its record from outside; read it fresh whenever settings show.
            LaunchedEffect(Unit) { viewModel.readBackup() }
            SettingsScreen(
                choices = state.choices,
                onChoose = viewModel::choose,
                onClose = { settingsOpen = false },
                serverAddress = state.serverAddress,
                hasKey = state.hasKey,
                onServer = viewModel::setServer,
                onKey = viewModel::setKey,
                onForgetServer = viewModel::forgetServer,
                savedKey = viewModel::savedKey,
                backup = state.backup,
                onBackup = viewModel::setBackup,
                onBackupWhen = viewModel::setBackupWhen,
                onBackUpNow = viewModel::backUpNow,
                hidden = state.hidden,
                onShowFolder = viewModel::showFolder,
            )
        }

        folder != null && viewing != null -> {
            // Back in the grid with the row holding that picture in view.
            val toGrid: () -> Unit = {
                viewing?.let { i ->
                    val row = i / state.choices.perRow
                    val shown = gridList.layoutInfo.visibleItemsInfo.map { it.index }
                    if (row !in shown) gridList.requestScrollToItem(row)
                }
                viewing = null
            }
            val closeViewer: () -> Unit = { if (lookFor != null && !browsing) onLeave() else toGrid() }
            BackHandler(onBack = closeViewer)
            ViewerScreen(
                pictures = folder.pictures,
                startIndex = viewing ?: 0,
                decoder = viewModel.decoder,
                canDelete = !folder.remote,
                onIndex = { viewing = it },
                onBack = closeViewer,
                onDelete = { picture -> askToDelete(listOf(picture.uri)) },
                onTitle = {
                    browsing = true
                    toGrid()
                },
            )
        }

        folder != null -> {
            fun toggle(picture: Picture) {
                marked = if (picture.id in markedSet) marked.filter { it != picture.id }.toLongArray() else marked + picture.id
            }
            // Back lets go of the marks first, then leaves the folder.
            val up: () -> Unit = {
                if (markedSet.isNotEmpty()) marked = LongArray(0) else folderKey = null
            }
            BackHandler(onBack = up)
            GridScreen(
                folder = folder,
                perRow = state.choices.perRow,
                decoder = viewModel.decoder,
                listState = gridList,
                onBack = up,
                // While anything is marked a tap marks or unmarks; otherwise it opens the picture.
                onOpen = { i -> if (markedSet.isNotEmpty()) toggle(folder.pictures[i]) else viewing = i },
                // An album's pictures are on the server, not the phone's to delete.
                onHold = if (folder.remote) null else ({ i -> toggle(folder.pictures[i]) }),
                chosen = markedSet,
                // "Delete 3" in the bar once anything is marked: words, saying how many will go.
                actions = {
                    if (markedSet.isNotEmpty()) {
                        TextMMD(
                            text = stringResource(R.string.grid_delete, markedSet.size),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { askToDelete(folder.pictures.filter { it.id in markedSet }.map { it.uri }) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                },
                // Only an answer that went wrong says so; until then it is being read, including
                // the frame before the reading has started, which would otherwise flash an error.
                note = when {
                    !folder.remote -> null
                    folder.key !in state.opening && state.server == Server.REFUSED -> stringResource(R.string.immich_refused)
                    folder.key !in state.opening && state.server == Server.UNREACHABLE -> stringResource(R.string.immich_unreachable)
                    else -> stringResource(R.string.immich_reading_album)
                },
                onPullAtTop = if (folder.remote) albumPull::pull else null,
                pulled = albumPull.pulled.takeIf { folder.remote },
            )
        }

        // Opened on one picture and still looking for it: nothing yet, rather than the folders
        // flashing up for a frame before the picture replaces them.
        !found && state.access == Access.GRANTED -> Unit

        else -> FoldersScreen(
            state = state,
            decoder = viewModel.decoder,
            listState = foldersList,
            onOpen = {
                if (it.key != folderKey) gridList.requestScrollToItem(0)
                folderKey = it.key
                marked = LongArray(0)
            },
            onSettings = { settingsOpen = true },
            onAllow = ask,
            onAppSettings = { openAppSettings(context) },
            onHide = { viewModel.hideFolder(it.key, it.label) },
            onPullAtTop = if (state.server != Server.NONE) foldersPull::pull else null,
            pulled = foldersPull.pulled,
        )
    }
}
