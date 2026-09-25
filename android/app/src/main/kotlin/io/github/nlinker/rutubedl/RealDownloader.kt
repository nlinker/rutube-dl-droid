package io.github.nlinker.rutubedl

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.net.toUri
import io.github.nlinker.rutubedl.bindings.ProgressListener
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

// The real thing behind `Downloader`: probe, create the file, hand its descriptor to Rust,
// publish it. Everything Android-specific about a download lives here, which is what lets
// `DownloadQueue` stay a plain class.
class RealDownloader(context: Context) : Downloader {
    private val app = context.applicationContext as App
    private val resolver = app.contentResolver

    override suspend fun download(
        task: Task, report: (title: String, done: Int, total: Int) -> Unit
    ): Finished {
        var target: Target? = null
        try {
            val download = app.client.probe(task.url.value, task.quality)
            val info = download.info()
            report(info.title, 0, info.segments.toInt())

            val file = withContext(Dispatchers.IO) { create(task.folder, info.fileName) }
            target = file
            // Rust owns the descriptor from here on, so it must be detached, not closed:
            // detachFd() strips it from the ParcelFileDescriptor, and `download.save` takes the ownership.
            @Suppress("Recycle") val fd = withContext(Dispatchers.IO) {
                resolver.openFileDescriptor(file.uri, "rw")!!.detachFd()
            }
            download.save(fd, app.cacheDir.absolutePath, object : ProgressListener {
                // Called from a tokio worker thread, so it only touches thread-safe state.
                override fun onProgress(done: ULong, total: ULong) =
                    report(info.title, done.toInt(), total.toInt())
            })
            withContext(Dispatchers.IO) { file.commit() }

            return Finished(info.fileName, FileUri(file.uri.toString()))
        } catch (_: FileNotFoundException) {
            // The chosen folder is gone; forget it and fall back to Download next time.
            app.settings.clearFolder()
            target.discard()
            throw UserMessageException(app.getString(R.string.folder_missing))
        } catch (e: Throwable) {
            // Canceled or failed, the half-written file has to be deleted anyways.
            target.discard()
            throw e
        }
    }

    // Blocking, and reached from a canceled coroutine too, hence NonCancellable.
    private suspend fun Target?.discard() = withContext(NonCancellable + Dispatchers.IO) {
        this@discard?.delete()
    }

    private fun create(folder: FolderUri?, name: String): Target =
        if (folder == null) createInDownloads(name)
        else createDocument(folder.value.toUri(), name)

    // The default: the system Download collection, no folder picker and no permission.
    private fun createInDownloads(name: String): Target {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("could not create $name in Download")
        return Target(uri, inMediaStore = true)
    }

    // A folder the user picked through SAF. The tree Uri names a grant, not a
    // directory; the document Uri of its root is what createDocument wants.
    private fun createDocument(folder: Uri, name: String): Target {
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            folder, DocumentsContract.getTreeDocumentId(folder)
        )
        val uri = DocumentsContract.createDocument(resolver, parent, "video/mp4", name)
            ?: error("could not create $name in the chosen folder")
        return Target(uri, inMediaStore = false)
    }

    // The wrapper around the file being written: a MediaStore row or a SAF document.
    private inner class Target(val uri: Uri, private val inMediaStore: Boolean) {
        // A MediaStore row stays hidden from other apps until IS_PENDING is cleared, so this is what
        // makes the file to appear in the gallery. A SAF document is visible from the start.
        fun commit() {
            if (inMediaStore) {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, values, null, null)
            }
        }

        fun delete() = runCatching {
            if (inMediaStore) {
                resolver.delete(uri, null, null)
            } else {
                DocumentsContract.deleteDocument(resolver, uri)
            }
        }
    }
}
