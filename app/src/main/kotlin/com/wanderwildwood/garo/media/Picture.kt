package com.wanderwildwood.garo.media

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

/**
 * One picture as the phone's media index knows it.
 *
 * [taken] is the moment the camera recorded, when it recorded one. Screenshots, downloads and
 * anything a messaging app saved usually carry no such thing, and then it is null rather than
 * quietly filled in from the file's date — [when] is the date to sort by, and the Info dialog
 * says which of the two it is showing.
 */
data class Picture(
    val id: Long,
    val name: String,
    val folderKey: String,
    val folderName: String,
    /** Where it sits on the volume, e.g. "DCIM/Camera/". Null for a picture opened from outside. */
    val path: String?,
    /** "external_primary" for the phone's own storage; anything else is a card. */
    val volume: String?,
    val taken: Long?,
    val modified: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    /** "image/jpeg" and so on, as the index or the handing app says; null when neither does. */
    val mime: String? = null,
    /** Set only for a picture another app handed over; everything else is found by its id. */
    val handed: Uri? = null,
    /** Immich's id for a picture that lives on the server rather than the phone. */
    val remote: String? = null,
    /** The camera, when it is already known; for the phone's own pictures it is read from the file. */
    val camera: String? = null,
    /** A video rather than a still; it plays in the viewer. */
    val video: Boolean = false,
    /** A video's length in milliseconds; 0 for a picture, or when the index does not know. */
    val duration: Long = 0L,
) {
    /**
     * Where to read it from. Worked out from the id rather than stored, so the ordering code
     * that never reads it can be tested without Android's own classes.
     */
    val uri: Uri by lazy {
        remote?.let { Uri.parse("immich://asset/$it") } ?: handed ?: ContentUris.withAppendedId(
            if (video) {
                MediaStore.Video.Media.getContentUri(volume ?: MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Images.Media.getContentUri(volume ?: MediaStore.VOLUME_EXTERNAL)
            },
            id,
        )
    }

    /**
     * Where it sits as a path, for handing to the file manager. Null for a picture opened from
     * outside, which has no folder here. The index names a card by its volume in lower case;
     * the path under /storage spells it in capitals.
     */
    val folderPath: String? get() {
        val below = path?.trimEnd('/') ?: return null
        val root = when (volume) {
            null -> return null
            "external_primary" -> "/storage/emulated/0"
            else -> "/storage/" + volume.uppercase()
        }
        return if (below.isEmpty()) root else "$root/$below"
    }

    /**
     * The folder as Android's storage documents name it — "primary:DCIM/Camera", or a card's
     * id in place of "primary" — which is how a file manager is asked to open a folder without
     * handing it a bare path, which Android refuses to let leave an app.
     */
    val folderDocumentId: String? get() {
        val below = path?.trimEnd('/') ?: return null
        val vol = when (volume) {
            null -> return null
            "external_primary" -> "primary"
            else -> volume.uppercase()
        }
        return "$vol:$below"
    }

    /** Milliseconds: when the camera said, or failing that when the file was last written. */
    val `when`: Long get() = taken ?: modified
}

/**
 * Pictures that share a folder.
 *
 * The key is MediaStore's bucket id — a hash of the folder's full path, so a "Camera" on the
 * phone and a "Camera" on a card are two folders, which is what they are. [label] is what the
 * row says; when two folders would otherwise read the same, it carries where the second one is.
 */
data class Folder(
    val key: String,
    val label: String,
    val pictures: List<Picture>,
    /** An Immich album: how many pictures the server says it holds, before they are read. */
    val count: Int = pictures.size,
    /** An Immich album's own cover, shown before its pictures are read. */
    val albumCover: Picture? = null,
    val remote: Boolean = false,
    /** For an album, its newest picture's date as the server gives it. */
    val albumNewest: Long = 0L,
) {
    val cover: Picture? get() = albumCover ?: pictures.firstOrNull()
    val newest: Long get() = if (remote) albumNewest else pictures.maxOfOrNull { it.`when` } ?: 0L
}
