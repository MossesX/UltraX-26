package com.ultrax26.recorder.recording

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.ultrax26.recorder.settings.StorageLocation
import com.ultrax26.recorder.settings.StorageSettings
import com.ultrax26.recorder.util.UxLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** An open output with a way to finalize it (clear IS_PENDING / nothing). */
class OutputFile(val displayName: String, val pfd: ParcelFileDescriptor, val uri: Uri?, val file: File?, private val finalizer: () -> Unit) {
    var finalized = false
        private set
    fun finish() { if (finalized) return; finalized = true; try { pfd.close() } catch (_: Throwable) { }; try { finalizer() } catch (t: Throwable) { UxLog.w("Storage", "finalize: ${t.message}") } }
    fun abort() { try { pfd.close() } catch (_: Throwable) { } }
}

/** Creates clip / sidecar files in the gallery (MediaStore), a user-picked SAF folder, or app storage. */
class StorageTarget(private val context: Context, private val settings: StorageSettings) {
    private val resolver = context.contentResolver

    fun create(displayName: String, mime: String): OutputFile = when (settings.location) {
        StorageLocation.MEDIA_STORE -> createMediaStore(displayName, mime)
        StorageLocation.SAF_TREE -> settings.safTreeUri?.let { createSaf(Uri.parse(it), displayName, mime) } ?: createMediaStore(displayName, mime)
        StorageLocation.APP_PRIVATE -> createPrivate(displayName)
    }

    private fun createMediaStore(name: String, mime: String): OutputFile {
        val isVideo = mime.startsWith("video/")
        val collection = if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                         else MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relPath = (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_DOCUMENTS) + "/" + settings.subfolder.trim('/')
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("MediaStore insert failed")
        val pfd = resolver.openFileDescriptor(uri, "rw") ?: throw IllegalStateException("openFileDescriptor failed")
        return OutputFile(name, pfd, uri, null) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
    }

    private fun createSaf(tree: Uri, name: String, mime: String): OutputFile {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(resolver, parent, mime, name) ?: throw IllegalStateException("SAF createDocument failed")
        val pfd = resolver.openFileDescriptor(doc, "rw") ?: throw IllegalStateException("SAF open failed")
        return OutputFile(name, pfd, doc, null) { }
    }

    private fun createPrivate(name: String): OutputFile {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, settings.subfolder).apply { mkdirs() }
        val f = File(dir, name)
        val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
        return OutputFile(name, pfd, Uri.fromFile(f), f) { }
    }

    fun deleteIfEmpty(out: OutputFile) {
        try {
            if (out.file != null) { if (out.file.length() == 0L) out.file.delete() }
            else if (out.uri != null) {
                val size = resolver.openFileDescriptor(out.uri, "r")?.use { it.statSize } ?: -1L
                if (size == 0L) resolver.delete(out.uri, null, null)
            }
        } catch (_: Throwable) { }
    }

    fun freeBytes(): Long = try {
        val path = when (settings.location) {
            StorageLocation.APP_PRIVATE -> (context.getExternalFilesDir(null) ?: context.filesDir).path
            else -> Environment.getExternalStorageDirectory().path
        }
        StatFs(path).availableBytes
    } catch (_: Throwable) { -1L }

    companion object {
        /** Expand the file-name template. */
        fun fileName(template: String, width: Int, height: Int, fps: Int, codec: String, hdr: String, lens: String, segment: Int, ext: String, now: Date = Date()): String {
            val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(now)
            val time = SimpleDateFormat("HHmmss", Locale.US).format(now)
            val resName = when {
                width >= 7680 -> "8K"; width >= 3840 -> "4K"; width >= 2560 -> "1440p"; width >= 1920 -> "1080p"; width >= 1280 -> "720p"; else -> "${height}p"
            }
            var s = template
                .replace("{date}", date).replace("{time}", time)
                .replace("{res}", "${width}x${height}").replace("{resname}", resName)
                .replace("{fps}", fps.toString()).replace("{codec}", codec.lowercase())
                .replace("{hdr}", hdr).replace("{lens}", lens)
                .replace("{seg}", if (segment > 0) String.format(Locale.US, "_p%02d", segment + 1) else "")
            s = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            if (s.isEmpty()) s = "UX26_${date}_$time"
            return "$s.$ext"
        }
    }
}
