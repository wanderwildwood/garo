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
import com.wanderwildwood.garo.media.FolderOrder
import com.wanderwildwood.garo.media.Immich
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Whether the app may read the pictures. */
enum class Access { GRANTED, ASK, REFUSED }

/** How the Immich server last answered. */
enum class Server {
    /** No server set. */
    NONE,
    /** Asked, no answer yet. */
    ASKING,
    OK,
    /** No answer: off the tailnet, or the server down. The albums last seen are still shown. */
    UNREACHABLE,
    /** It answered and would not take the key. */
    REFUSED,
}

data class GalleryState(
    val access: Access = Access.ASK,
    /** True until the index has been read once; the screens say nothing rather than "empty". */
    val reading: Boolean = true,
    val folders: List<Folder> = emptyList(),
    val choices: Choices = Choices(),
    /** Immich's albums, as folders; their pictures are read when one is opened. */
    val albums: List<Folder> = emptyList(),
    val server: Server = Server.NONE,
    val serverAddress: String? = null,
    val hasKey: Boolean = false,
    /** Albums whose pictures are being read just now. */
    val opening: Set<String> = emptySet(),
)

class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = Settings(app)
    private val index = MediaIndex(
        app.contentResolver,
        rootPhone = app.getString(R.string.folder_root_phone),
        rootCard = app.getString(R.string.folder_root_card),
    )
    val decoder = Decoder(app.contentResolver, app.cacheDir)

    private val _state = MutableStateFlow(
        GalleryState(
            choices = settings.read(),
            serverAddress = settings.immichServer(),
            hasKey = settings.hasImmichKey(),
        ),
    )
    val state: StateFlow<GalleryState> = _state

    private var everything: List<Picture> = emptyList()
    private var reading: Job? = null
    private var asking: Job? = null

    /** The albums as last read, and each album's pictures once read. */
    private var albums: List<Immich.Album> = emptyList()
    private val albumPictures = HashMap<String, List<Picture>>()

    /** The album list from the last answer, kept so the albums still show when the server cannot be reached. */
    private val albumsFile = File(app.filesDir, "immich-albums.json")

    /** Each album's pictures as last read, one file an album, for the same reason. */
    private val picturesDir = File(app.filesDir, "immich-albums")

    /** Whether Android has been asked once already, so a second "no" can be told from a first. */
    private var asked = false

    /** Whether the phone's index has been read, or found unreadable; until then the screens wait. */
    private var indexRead = false

    init {
        albums = readAlbums()
        connect()
    }

    /**
     * Read the index again. Called every time the app comes to the front, because the camera
     * may have added a picture, or another app taken one away, while it was behind.
     */
    fun refresh() {
        askServer()
        val granted = getApplication<Application>().checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            indexRead = true
            _state.update { it.copy(access = if (asked) Access.REFUSED else Access.ASK, reading = false) }
            return
        }
        _state.update { it.copy(access = Access.GRANTED) }
        reading?.cancel()
        reading = viewModelScope.launch {
            val pictures = withContext(Dispatchers.IO) { runCatching { index.pictures() }.getOrDefault(emptyList()) }
            everything = pictures
            indexRead = true
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

    // Immich ------------------------------------------------------------------------------------

    fun setServer(address: String) {
        val changed = address.trim() != settings.immichServer()
        settings.writeImmichServer(address.takeIf { it.isNotBlank() })
        if (changed) forgetAlbums()
        _state.update { it.copy(serverAddress = settings.immichServer()) }
        connect()
        askServer()
    }

    fun setKey(key: String) {
        settings.writeImmichKey(key.takeIf { it.isNotBlank() })
        _state.update { it.copy(hasKey = settings.hasImmichKey()) }
        connect()
        askServer()
    }

    /** Server, key and everything fetched from it, gone. */
    fun forgetServer() {
        settings.writeImmichServer(null)
        settings.writeImmichKey(null)
        forgetAlbums()
        _state.update { it.copy(serverAddress = null, hasKey = false) }
        connect()
    }

    /**
     * Read an album's pictures, when it is opened. Shown at once from the last reading if there
     * was one, and read again behind it, so an album changed on the server catches up.
     */
    fun openAlbum(key: String) {
        val album = albums.firstOrNull { Immich.folderKey(it.id) == key } ?: return
        val server = decoder.immich ?: return
        if (key in _state.value.opening) return
        _state.update { it.copy(opening = it.opening + key) }
        viewModelScope.launch {
            // What was read last time, at once, so an album opened off the network still opens.
            if (albumPictures[key] == null) {
                withContext(Dispatchers.IO) { readPictures(album) }?.let {
                    albumPictures[key] = it
                    arrange()
                }
            }
            val read = withContext(Dispatchers.IO) { runCatching { server.pictures(album) } }
            read.onSuccess { list ->
                albumPictures[key] = list
                withContext(Dispatchers.IO) { writePictures(album, list) }
            }
            read.onFailure { problem ->
                _state.update { it.copy(server = if (problem is Immich.Trouble.Refused) Server.REFUSED else Server.UNREACHABLE) }
            }
            _state.update { it.copy(opening = it.opening - key) }
            arrange()
        }
    }

    private fun connect() {
        val login = settings.immich()
        decoder.immich = login?.let { Immich(it.server, it.key) }
        if (login == null) _state.update { it.copy(server = Server.NONE) }
        arrange()
    }

    private fun askServer() {
        val server = decoder.immich ?: return
        if (asking?.isActive == true) return
        _state.update { it.copy(server = if (it.server == Server.OK) Server.OK else Server.ASKING) }
        asking = viewModelScope.launch {
            val answer = withContext(Dispatchers.IO) { runCatching { server.albums() } }
            answer.onSuccess { list ->
                albums = list
                withContext(Dispatchers.IO) { writeAlbums(list) }
                _state.update { it.copy(server = Server.OK) }
            }
            answer.onFailure { problem ->
                _state.update { it.copy(server = if (problem is Immich.Trouble.Refused) Server.REFUSED else Server.UNREACHABLE) }
            }
            arrange()
        }
    }

    private fun forgetAlbums() {
        albums = emptyList()
        albumPictures.clear()
        albumsFile.delete()
        picturesDir.deleteRecursively()
        decoder.forgetRemote()
    }

    private fun arrange() {
        val choices = _state.value.choices
        val folders = Arrange.folders(
            everything,
            choices.folderOrder,
            choices.pictureOrder,
            cardMark = getApplication<Application>().getString(R.string.folder_on_card),
        )
        val shown = if (decoder.immich == null) emptyList() else albums.map { album ->
            val key = Immich.folderKey(album.id)
            val pictures = albumPictures[key]?.let { Arrange.sort(it, choices.pictureOrder) } ?: emptyList()
            Folder(
                key = key,
                label = album.name,
                pictures = pictures,
                count = albumPictures[key]?.size ?: album.count,
                albumCover = album.cover?.let { Immich.remote(it, album.name, album) },
                remote = true,
                albumNewest = album.newest,
            )
        }.filter { it.count > 0 }.let { list ->
            when (choices.folderOrder) {
                FolderOrder.NEWEST -> list.sortedByDescending { it.newest }
                FolderOrder.NAME -> list.sortedWith { a, b -> Arrange.natural(a.label, b.label) }
            }
        }
        _state.update { it.copy(folders = folders, albums = shown, reading = !indexRead) }
    }

    private fun writeAlbums(list: List<Immich.Album>) {
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("count", it.count)
                    .put("cover", it.cover ?: JSONObject.NULL)
                    .put("newest", it.newest),
            )
        }
        runCatching { albumsFile.writeText(array.toString()) }
    }

    private fun readAlbums(): List<Immich.Album> = runCatching {
        val array = JSONArray(albumsFile.readText())
        (0 until array.length()).map { i ->
            val a = array.getJSONObject(i)
            Immich.Album(
                id = a.getString("id"),
                name = a.getString("name"),
                count = a.optInt("count"),
                cover = if (a.isNull("cover")) null else a.getString("cover"),
                newest = a.optLong("newest"),
            )
        }
    }.getOrDefault(emptyList())

    private fun picturesFile(album: Immich.Album) = File(picturesDir, album.id.filter { it.isLetterOrDigit() || it == '-' } + ".json")

    private fun writePictures(album: Immich.Album, list: List<Picture>) {
        val array = JSONArray()
        list.forEach { p ->
            array.put(
                JSONObject()
                    .put("asset", p.remote)
                    .put("name", p.name)
                    .put("taken", p.taken ?: JSONObject.NULL)
                    .put("modified", p.modified)
                    .put("size", p.size)
                    .put("width", p.width)
                    .put("height", p.height)
                    .put("mime", p.mime ?: JSONObject.NULL)
                    .put("camera", p.camera ?: JSONObject.NULL),
            )
        }
        runCatching {
            picturesDir.mkdirs()
            picturesFile(album).writeText(array.toString())
        }
    }

    private fun readPictures(album: Immich.Album): List<Picture>? = runCatching {
        val array = JSONArray(picturesFile(album).readText())
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Immich.remote(
                assetId = o.getString("asset"),
                name = o.optString("name"),
                album = album,
                taken = if (o.isNull("taken")) null else o.getLong("taken"),
                modified = o.optLong("modified"),
                size = o.optLong("size"),
                width = o.optInt("width"),
                height = o.optInt("height"),
                mime = if (o.isNull("mime")) null else o.getString("mime"),
                camera = if (o.isNull("camera")) null else o.getString("camera"),
            )
        }
    }.getOrNull()
}
