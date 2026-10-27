package com.wanderwildwood.garo.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wanderwildwood.garo.R
import com.wanderwildwood.garo.backup.BackupWorker
import com.wanderwildwood.garo.media.Arrange
import com.wanderwildwood.garo.media.Backup
import com.wanderwildwood.garo.media.BackupRecord
import com.wanderwildwood.garo.media.BackupStatus
import com.wanderwildwood.garo.media.BackupWhen
import com.wanderwildwood.garo.media.Conditions
import com.wanderwildwood.garo.media.DEFAULT_NEXTCLOUD_FOLDER
import com.wanderwildwood.garo.media.Destination
import com.wanderwildwood.garo.backup.Account
import com.wanderwildwood.garo.backup.LoginFlow
import com.wanderwildwood.garo.backup.PhotoFolder
import com.wanderwildwood.garo.backup.Refused
import com.wanderwildwood.garo.backup.Remote
import kotlinx.coroutines.delay
import com.wanderwildwood.garo.media.Reach
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wanderwildwood.garo.cloud.Cloud
import com.wanderwildwood.garo.cloud.CloudFiles
import com.wanderwildwood.garo.cloud.CloudItem
import com.wanderwildwood.garo.cloud.CloudListing
import kotlinx.coroutines.CancellationException
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
    val backup: BackupState = BackupState(),
    /** Folders hidden by hand: key to name. */
    val hidden: Map<String, String> = emptyMap(),
    val nextcloud: NextcloudState = NextcloudState(),
    val cloud: CloudState = CloudState(),
)

/**
 * Browsing the signed-in Nextcloud: the folders under the backup folder for the main screen, and
 * each folder's contents once opened. Kept in memory only; the pictures themselves are cached by
 * the decoder.
 */
data class CloudState(
    /** Signed in, so the section shows. */
    val on: Boolean = false,
    /** The backup folder, whose folders the main screen lists. */
    val backupFolder: String = "",
    /** The folders in it, by path; empty until read, or when it is not there yet. */
    val top: List<CloudItem> = emptyList(),
    /** How the server last answered for [top]. */
    val topAnswer: Server = Server.NONE,
    /** Each folder opened, by path: its folders and pictures as last read. */
    val listings: Map<String, CloudListing> = emptyMap(),
    /** How the server last answered for each folder opened. */
    val answers: Map<String, Server> = emptyMap(),
    /** Folders being read just now. */
    val opening: Set<String> = emptySet(),
)

/** The Nextcloud page: who is signed in, or how signing in is going. */
data class NextcloudState(
    /** Signed in as this login name, or null. */
    val user: String? = null,
    val server: String? = null,
    val folder: String = DEFAULT_NEXTCLOUD_FOLDER,
    /** The address as typed, before signing in. */
    val typed: String = "",
    val signIn: SignIn = SignIn.IDLE,
    val problem: NextcloudProblem? = null,
    /** No browser to open the sign-in page in: an app password is typed instead. */
    val byHand: Boolean = false,
)

enum class SignIn {
    IDLE,
    /** The browser has the sign-in page; the app is asking the server whether it is done. */
    WAITING,
    /** An app password in hand, being tried before it is kept. */
    CHECKING,
}

enum class NextcloudProblem { PLAIN_HTTP, UNREACHABLE, REFUSED, NO_BROWSER, NOT_KEPT, TIMED_OUT }

/** What the backup rows say. */
data class BackupState(
    val on: Boolean = false,
    val whenTo: BackupWhen = BackupWhen.WIFI_CHARGING,
    /** The line under the switch: how far it has got, or what it waits for. */
    val line: BackupStatus.Line = BackupStatus.Line.Off,
    /** The folders backed up, as paths ending in "/". */
    val folders: Set<String> = Backup.DEFAULT_FOLDERS,
    /** The folders there are to choose from, DCIM first. */
    val choosable: List<String> = listOf("DCIM/"),
    /** Where it goes, or null when neither server is set up. */
    val to: Destination? = null,
    /** Every place set up that it could go. */
    val places: List<Destination> = emptyList(),
)

class GalleryViewModel(app: Application) : AndroidViewModel(app), NextcloudActions {

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
            hidden = settings.hiddenFolders(),
            serverAddress = settings.immichServer(),
            hasKey = settings.hasImmichKey(),
            nextcloud = settings.nextcloud().let { a ->
                NextcloudState(user = a?.user, server = a?.server, folder = settings.nextcloudFolder())
            },
            cloud = CloudState(on = settings.nextcloud() != null, backupFolder = settings.nextcloudFolder()),
        ),
    )
    val state: StateFlow<GalleryState> = _state

    private var everything: List<Picture> = emptyList()

    /** Folders that hold sound, whose pictures are covers; read with the index. */
    private var soundFolders: Set<String> = emptySet()
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
        decoder.cloud = settings.nextcloud()?.let { CloudFiles(it) }
    }

    /**
     * Read the index again. Called every time the app comes to the front, because the camera
     * may have added a picture, or another app taken one away, while it was behind.
     */
    fun refresh() {
        askServer()
        askCloud()
        // Put the schedule back every time: a phone that force-stopped the app forgot it.
        BackupWorker.schedule(getApplication())
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
            soundFolders = withContext(Dispatchers.IO) { index.soundFolders() }
            everything = pictures
            indexRead = true
            arrange()
            readBackup()
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

    /** Takes a folder out of the list, until it is brought back in settings. */
    fun hideFolder(key: String, label: String) {
        val hidden = _state.value.hidden + (key to label)
        settings.writeHiddenFolders(hidden)
        _state.update { it.copy(hidden = hidden) }
        arrange()
    }

    fun showFolder(key: String) {
        val hidden = _state.value.hidden - key
        settings.writeHiddenFolders(hidden)
        _state.update { it.copy(hidden = hidden) }
        arrange()
    }

    /** A picture the phone has just deleted at our request. */
    fun deleted(uris: Collection<Uri>) {
        uris.forEach(decoder::forget)
        val gone = uris.toSet()
        everything = everything.filterNot { it.uri in gone }
        arrange()
    }

    fun outside(uri: Uri): Picture = index.outside(uri)

    // Immich ------------------------------------------------------------------------------------

    fun setServer(address: String) {
        val changed = address.trim() != settings.immichServer()
        settings.writeImmichServer(address.takeIf { it.isNotBlank() })
        if (changed) {
            forgetAlbums()
            // What went up went to the old server; the new one is asked from the start.
            BackupWorker.record(getApplication()).forget()
            readBackup()
        }
        _state.update { it.copy(serverAddress = settings.immichServer()) }
        connect()
        askServer()
    }

    fun setKey(key: String) {
        settings.writeImmichKey(key.takeIf { it.isNotBlank() })
        _state.update { it.copy(hasKey = settings.hasImmichKey()) }
        connect()
        askServer()
        BackupWorker.schedule(getApplication())
        BackupWorker.runSoon(getApplication(), replace = true)
    }

    fun setBackup(on: Boolean) {
        settings.writeBackupOn(on)
        BackupWorker.schedule(getApplication())
        BackupWorker.runSoon(getApplication(), replace = true)
        readBackup()
    }

    fun setBackupWhen(whenTo: BackupWhen) {
        settings.writeBackupWhen(whenTo)
        BackupWorker.schedule(getApplication())
        BackupWorker.runSoon(getApplication(), replace = true)
        readBackup()
    }

    fun backUpNow() {
        BackupWorker.backUpNow(getApplication())
        readBackup()
    }

    /** Folders to back up, chosen in settings; the next run goes by them. */
    fun setBackupFolders(folders: Set<String>) {
        if (folders.isEmpty()) return
        settings.writeBackupFolders(folders)
        BackupWorker.runSoon(getApplication(), replace = true)
        readBackup()
    }

    /** Where backing up goes, when both servers are set up. */
    fun setBackupTo(to: Destination) {
        settings.writeBackupTo(to)
        BackupWorker.schedule(getApplication())
        BackupWorker.runSoon(getApplication(), replace = true)
        readBackup()
    }

    /** Read the record again: the worker writes it from outside this screen. */
    fun readBackup() {
        val to = settings.destination()
        val record = BackupWorker.record(getApplication(), to ?: Destination.IMMICH)
        val folders = settings.backupFolders()
        val videos = to != null && Backup.videosTo(to)
        val chosen = everything.filter { Backup.isChosen(it, folders, videos) }
        val whenTo = settings.backupWhen()
        // Immich is asked whenever the app opens, so its answer is known; Nextcloud is asked
        // only by a backup, whose record says how that went.
        val reach = when {
            to != Destination.IMMICH -> Reach.UNKNOWN
            _state.value.server == Server.OK -> Reach.OK
            _state.value.server == Server.UNREACHABLE -> Reach.UNREACHABLE
            else -> Reach.UNKNOWN
        }
        val places = buildList {
            if (settings.immich() != null) add(Destination.IMMICH)
            if (settings.nextcloud() != null) add(Destination.NEXTCLOUD)
        }
        _state.update {
            it.copy(
                backup = BackupState(
                    on = settings.backupOn(),
                    whenTo = whenTo,
                    line = BackupStatus.of(
                        on = settings.backupOn(),
                        problem = record.status.problem,
                        total = chosen.size,
                        done = chosen.count(record::isDone),
                        lastRun = record.status.lastRun,
                        whenTo = whenTo,
                        now = conditions(),
                        reach = reach,
                    ),
                    folders = folders,
                    choosable = (Backup.choosable(everything) + folders).distinct(),
                    to = to,
                    places = places,
                ),
            )
        }
    }

    /** The network and the charger as they are this moment, for the line under the switch. */
    private fun conditions(): Conditions {
        val app = getApplication<Application>()
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val network = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val unmetered = network && caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return Conditions(network, unmetered, charging)
    }

    /** The key as saved, read only when the key dialog opens, so it can be checked by eye. */
    fun savedKey(): String = settings.immichKey().orEmpty()

    /** Server, key and everything fetched from it, gone. */
    fun forgetServer() {
        settings.writeImmichServer(null)
        settings.writeImmichKey(null)
        // Backing up stops only when there is nowhere left for it to go.
        if (settings.destination() == null) settings.writeBackupOn(false)
        BackupWorker.schedule(getApplication())
        BackupWorker.record(getApplication()).forget()
        readBackup()
        forgetAlbums()
        _state.update { it.copy(serverAddress = null, hasKey = false) }
        connect()
    }

    // Nextcloud ---------------------------------------------------------------------------------

    private var signingIn: Job? = null

    override fun setNextcloudAddress(typed: String) {
        _state.update { it.copy(nextcloud = it.nextcloud.copy(typed = typed.trim(), problem = null)) }
    }

    /**
     * Nextcloud's own sign-in: its login page opens in the browser, and this asks the server every
     * two seconds whether the reader has finished there, for the twenty minutes the server allows.
     * The app password it then hands over is checked against the files and kept, sealed.
     */
    override fun startNextcloudSignIn() {
        val typed = _state.value.nextcloud.typed
        if (typed.isBlank() || signingIn?.isActive == true) return
        if (LoginFlow.isPlain(typed)) {
            nextcloud { it.copy(problem = NextcloudProblem.PLAIN_HTTP) }
            return
        }
        nextcloud { it.copy(signIn = SignIn.WAITING, problem = null) }
        signingIn = viewModelScope.launch {
            val started = withContext(Dispatchers.IO) { runCatching { LoginFlow.start(typed) } }.getOrElse { e ->
                nextcloud { it.copy(signIn = SignIn.IDLE, problem = if (e is Refused) NextcloudProblem.REFUSED else NextcloudProblem.UNREACHABLE) }
                return@launch
            }
            val app = getApplication<Application>()
            val opened = runCatching {
                app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(started.loginUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (!opened) {
                nextcloud { it.copy(signIn = SignIn.IDLE, problem = NextcloudProblem.NO_BROWSER, byHand = true) }
                return@launch
            }
            repeat(600) {
                delay(2_000)
                val got = withContext(Dispatchers.IO) { runCatching { LoginFlow.poll(started) } }.getOrElse { e ->
                    if (e is Refused) {
                        nextcloud { it.copy(signIn = SignIn.IDLE, problem = NextcloudProblem.REFUSED) }
                        return@launch
                    }
                    null
                }
                if (got != null) {
                    keepNextcloud(got)
                    return@launch
                }
            }
            nextcloud { it.copy(signIn = SignIn.IDLE, problem = NextcloudProblem.TIMED_OUT) }
        }
    }

    /** Stop waiting for the browser. */
    override fun cancelNextcloudSignIn() {
        signingIn?.cancel()
        nextcloud { it.copy(signIn = SignIn.IDLE) }
    }

    /** For a phone with no browser: a login name and an app password made on the server by hand. */
    override fun signInNextcloudByHand(user: String, appPassword: String) {
        val typed = _state.value.nextcloud.typed
        if (typed.isBlank() || user.isBlank() || appPassword.isBlank()) return
        if (LoginFlow.isPlain(typed)) {
            nextcloud { it.copy(problem = NextcloudProblem.PLAIN_HTTP) }
            return
        }
        signingIn = viewModelScope.launch {
            keepNextcloud(Account(LoginFlow.normalise(typed), user.trim(), appPassword.trim()))
        }
    }

    override fun nextcloudByHand(on: Boolean) = nextcloud { it.copy(byHand = on, problem = null) }

    private suspend fun keepNextcloud(account: Account) {
        nextcloud { it.copy(signIn = SignIn.CHECKING, problem = null) }
        val checked = withContext(Dispatchers.IO) {
            runCatching { PhotoFolder(account, settings.nextcloudFolder()).probe() }
        }
        checked.exceptionOrNull()?.let { e ->
            nextcloud { it.copy(signIn = SignIn.IDLE, problem = if (e is Refused) NextcloudProblem.REFUSED else NextcloudProblem.UNREACHABLE) }
            return
        }
        if (!settings.writeNextcloud(account)) {
            nextcloud { it.copy(signIn = SignIn.IDLE, problem = NextcloudProblem.NOT_KEPT) }
            return
        }
        // A new account: nothing recorded as up belongs to it.
        BackupWorker.record(getApplication(), Destination.NEXTCLOUD).forget()
        // The first place set up is where backing up goes; with Immich already there, it stays
        // the choice until changed on the backup page.
        if (settings.immich() == null) settings.writeBackupTo(Destination.NEXTCLOUD)
        nextcloud { it.copy(user = account.user, server = account.server, signIn = SignIn.IDLE, byHand = false, typed = "") }
        decoder.forgetCloud()
        decoder.cloud = CloudFiles(account)
        _state.update { it.copy(cloud = CloudState(on = true, backupFolder = settings.nextcloudFolder())) }
        askCloud()
        BackupWorker.schedule(getApplication())
        BackupWorker.runSoon(getApplication(), replace = true)
        readBackup()
    }

    /**
     * Signed out here and on the server: the app password is deleted there first (best effort,
     * off the network it stays until revoked in Nextcloud's own settings), then forgotten here.
     * Nothing already backed up is touched.
     */
    override fun signOutNextcloud() {
        val account = settings.nextcloud()
        settings.forgetNextcloud()
        BackupWorker.record(getApplication(), Destination.NEXTCLOUD).forget()
        if (settings.destination() == null) settings.writeBackupOn(false)
        BackupWorker.schedule(getApplication())
        nextcloud { NextcloudState(folder = settings.nextcloudFolder()) }
        // Nothing read from it is kept: its folders, and every picture fetched from it.
        cloudAsking?.cancel()
        decoder.cloud = null
        decoder.forgetCloud()
        _state.update { it.copy(cloud = CloudState(backupFolder = settings.nextcloudFolder())) }
        readBackup()
        if (account != null) viewModelScope.launch(Dispatchers.IO) { LoginFlow.revoke(account) }
    }

    /** Another folder in the same account: the next run looks there, and sends what is missing. */
    override fun setNextcloudFolder(typed: String) {
        val clean = Remote.join(typed.trim(), "").ifEmpty { DEFAULT_NEXTCLOUD_FOLDER }
        if (clean == settings.nextcloudFolder()) return
        settings.writeNextcloudFolder(clean)
        BackupWorker.record(getApplication(), Destination.NEXTCLOUD).forget()
        nextcloud { it.copy(folder = clean) }
        _state.update { it.copy(cloud = it.cloud.copy(backupFolder = clean, top = emptyList(), topAnswer = Server.NONE)) }
        askCloud()
        BackupWorker.runSoon(getApplication(), replace = true)
        readBackup()
    }

    // Browsing Nextcloud ------------------------------------------------------------------------

    private var cloudAsking: Job? = null

    /** The backup folder's folders, for the main screen; asked whenever the app comes to the front. */
    private fun askCloud() {
        val files = decoder.cloud ?: return
        if (cloudAsking?.isActive == true) return
        val folder = settings.nextcloudFolder()
        cloud { it.copy(topAnswer = if (it.topAnswer == Server.OK) Server.OK else Server.ASKING) }
        cloudAsking = viewModelScope.launch {
            val answer = runCatching { files.list(folder) }
            answer.onSuccess { items -> cloud { it.copy(top = Cloud.listing(items, folder).folders, topAnswer = Server.OK) } }
            answer.onFailure { e ->
                when (e) {
                    is CancellationException -> throw e
                    // No backup folder yet: nothing backed up, and the rest of the files are still a row away.
                    is CloudFiles.Missing -> cloud { it.copy(top = emptyList(), topAnswer = Server.OK) }
                    else -> cloud { it.copy(topAnswer = answerFor(e)) }
                }
            }
        }
    }

    /** A Nextcloud folder opened: shown at once from the last reading, and read again behind it. */
    fun openCloud(path: String) {
        viewModelScope.launch { readCloud(path) }
    }

    /** A pull at the top of a Nextcloud folder: read again, returning how the server answered. */
    suspend fun pullCloud(path: String): Server = viewModelScope.async { readCloud(path) }.await()

    private suspend fun readCloud(path: String): Server {
        val files = decoder.cloud ?: return Server.NONE
        if (path in _state.value.cloud.opening) {
            _state.first { path !in it.cloud.opening }
            return _state.value.cloud.answers[path] ?: Server.NONE
        }
        cloud { it.copy(opening = it.opening + path) }
        val answer = try {
            val items = files.list(path)
            val listing = Cloud.listing(items, path)
            cloud { it.copy(listings = it.listings + (path to listing)) }
            Server.OK
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudFiles.Missing) {
            // Gone from the server since it was listed: nothing in it.
            cloud { it.copy(listings = it.listings + (path to CloudListing(emptyList(), emptyList()))) }
            Server.OK
        } catch (e: Exception) {
            answerFor(e)
        } finally {
            cloud { it.copy(opening = it.opening - path) }
        }
        cloud { it.copy(answers = it.answers + (path to answer)) }
        return answer
    }

    private fun answerFor(e: Throwable): Server = if (e is Refused) Server.REFUSED else Server.UNREACHABLE

    /**
     * A Nextcloud folder as the grid and the viewer take one: its pictures in the order chosen for
     * pictures (newest first unless changed), its own folders kept beside them in [CloudFolder].
     */
    fun cloudFolder(path: String, rootLabel: String): CloudFolder {
        val listing = _state.value.cloud.listings[path]
        val label = if (path.isEmpty()) rootLabel else path.substringAfterLast('/')
        val pictures = listing?.media.orEmpty().map { Cloud.picture(it, label) }
        return CloudFolder(
            folder = Folder(
                key = Cloud.folderKey(path),
                label = label,
                pictures = Arrange.sort(pictures, _state.value.choices.pictureOrder),
                remote = true,
            ),
            folders = listing?.folders.orEmpty(),
            read = listing != null,
        )
    }

    private fun cloud(change: (CloudState) -> CloudState) {
        _state.update { it.copy(cloud = change(it.cloud)) }
    }

    private fun nextcloud(change: (NextcloudState) -> NextcloudState) {
        _state.update { it.copy(nextcloud = change(it.nextcloud)) }
    }

    /**
     * Read an album's pictures, when it is opened. Shown at once from the last reading if there
     * was one, and read again behind it, so an album changed on the server catches up.
     */
    fun openAlbum(key: String) {
        viewModelScope.launch { readAlbum(key) }
    }

    /**
     * A pull at the top of an album's grid: its pictures read from the server again, returning
     * once they are, with how the server answered. Run in the model's own scope, so leaving the
     * grid halfway never leaves the album marked as being read.
     */
    suspend fun pullAlbum(key: String): Server = viewModelScope.async { readAlbum(key) }.await()

    /**
     * A pull at the top of the folders list: the phone's pictures and the server's albums read
     * again, as on coming back to the app, returning once both are done with how the server answered.
     */
    suspend fun pullFolders(): Server {
        refresh()
        asking?.join()
        reading?.join()
        cloudAsking?.join()
        // With both servers set, the worse of the two answers is the one worth saying.
        val immich = _state.value.server
        val cloud = _state.value.cloud.let { if (it.on) it.topAnswer else Server.NONE }
        return listOf(immich, cloud).filter { it != Server.NONE }.maxByOrNull { it.ordinal } ?: Server.NONE
    }

    /** Read one album's pictures; how the server answered, as [GalleryState.server] keeps it. */
    private suspend fun readAlbum(key: String): Server {
        val album = albums.firstOrNull { Immich.folderKey(it.id) == key } ?: return _state.value.server
        val server = decoder.immich ?: return _state.value.server
        if (key in _state.value.opening) {
            // Already being read: that reading is this one.
            _state.first { key !in it.opening }
            return _state.value.server
        }
        _state.update { it.copy(opening = it.opening + key) }
        try {
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
                // It answered: whatever it said last time, it can be reached now.
                _state.update { it.copy(server = Server.OK) }
            }
            read.onFailure { problem ->
                _state.update { it.copy(server = if (problem is Immich.Trouble.Refused) Server.REFUSED else Server.UNREACHABLE) }
            }
        } finally {
            _state.update { it.copy(opening = it.opening - key) }
            arrange()
        }
        return _state.value.server
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
            val timelineName = getApplication<Application>().getString(R.string.immich_timeline)
            val answer = withContext(Dispatchers.IO) {
                runCatching { listOfNotNull(server.timeline(timelineName)) + server.albums() }
            }
            answer.onSuccess { list ->
                albums = list
                withContext(Dispatchers.IO) { writeAlbums(list) }
                _state.update { it.copy(server = Server.OK) }
            }
            answer.onFailure { problem ->
                _state.update { it.copy(server = if (problem is Immich.Trouble.Refused) Server.REFUSED else Server.UNREACHABLE) }
            }
            arrange()
            // The backup line says whether the server answers; it has just been asked.
            readBackup()
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
        // Covers and hidden folders are left out of the list only: backing up looks at the
        // camera's folder whatever is shown. A picture another app opens from a hidden folder
        // is still shown, on its own, since its folder is not here to open it in.
        val hidden = _state.value.hidden.keys
        val folders = Arrange.folders(
            everything.filter { it.folderKey !in soundFolders && it.folderKey !in hidden },
            choices.folderOrder,
            choices.pictureOrder,
            cardMark = getApplication<Application>().getString(R.string.folder_on_card),
        ).let { folders ->
            // Hidden like any folder, from the same hold, and brought back from the same list.
            val all = if (Arrange.ALL in hidden) null else Arrange.all(folders, choices.pictureOrder, getApplication<Application>().getString(R.string.folders_all))
            listOfNotNull(all) + folders
        }
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
            // The whole timeline first, whatever the order: the albums are parts of it.
            val (timeline, rest) = list.partition { it.key == Immich.folderKey(Immich.TIMELINE) }
            timeline + when (choices.folderOrder) {
                FolderOrder.NEWEST -> rest.sortedByDescending { it.newest }
                FolderOrder.NAME -> rest.sortedWith { a, b -> Arrange.natural(a.label, b.label) }
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

/** A Nextcloud folder opened: its pictures as a [Folder], and the folders inside it. */
data class CloudFolder(val folder: Folder, val folders: List<CloudItem>, val read: Boolean)
