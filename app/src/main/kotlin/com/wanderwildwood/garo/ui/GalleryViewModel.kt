package com.wanderwildwood.garo.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wanderwildwood.garo.R
import com.wanderwildwood.garo.media.Arrange
import com.wanderwildwood.garo.media.Choices
import com.wanderwildwood.garo.media.Decoder
import com.wanderwildwood.garo.media.Folder
import com.wanderwildwood.garo.media.MediaIndex
import com.wanderwildwood.garo.media.Picture
import com.wanderwildwood.garo.media.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Whether the app may read the pictures. */
enum class Access { GRANTED, ASK, REFUSED }

data class GalleryState(
    val access: Access = Access.ASK,
    /** True until the index has been read once; the screens say nothing rather than "empty". */
    val reading: Boolean = true,
    val folders: List<Folder> = emptyList(),
    val choices: Choices = Choices(),
)

class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = Settings(app)
    private val index = MediaIndex(
        app.contentResolver,
        rootPhone = app.getString(R.string.folder_root_phone),
        rootCard = app.getString(R.string.folder_root_card),
    )
    val decoder = Decoder(app.contentResolver)

    private val _state = MutableStateFlow(GalleryState(choices = settings.read()))
    val state: StateFlow<GalleryState> = _state

    private var everything: List<Picture> = emptyList()
    private var reading: Job? = null

    /** Whether Android has been asked once already, so a second "no" can be told from a first. */
    private var asked = false

    /**
     * Read the index again. Called every time the app comes to the front, because the camera
     * may have added a picture, or another app taken one away, while it was behind.
     */
    fun refresh() {
        val granted = getApplication<Application>().checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            _state.update { it.copy(access = if (asked) Access.REFUSED else Access.ASK, reading = false) }
            return
        }
        _state.update { it.copy(access = Access.GRANTED) }
        reading?.cancel()
        reading = viewModelScope.launch {
            val pictures = withContext(Dispatchers.IO) { runCatching { index.pictures() }.getOrDefault(emptyList()) }
            everything = pictures
            arrange()
        }
    }

    /**
     * What Android answered. [mayAskAgain] is false once the phone has stopped showing the
     * question — then only the app's own settings page can change the answer, and the screen
     * should say so rather than offer a button that does nothing.
     */
    fun permissionAnswered(granted: Boolean, mayAskAgain: Boolean) {
        asked = !granted && !mayAskAgain
        refresh()
    }

    fun choose(choices: Choices) {
        settings.write(choices)
        _state.update { it.copy(choices = choices) }
        arrange()
    }

    /** A picture the phone has just deleted at our request. */
    fun deleted(uri: Uri) {
        decoder.forget(uri)
        everything = everything.filterNot { it.uri == uri }
        arrange()
    }

    fun outside(uri: Uri): Picture = index.outside(uri)

    private fun arrange() {
        val choices = _state.value.choices
        val folders = Arrange.folders(
            everything,
            choices.folderOrder,
            choices.pictureOrder,
            cardMark = getApplication<Application>().getString(R.string.folder_on_card),
        )
        _state.update { it.copy(folders = folders, reading = false) }
    }
}
