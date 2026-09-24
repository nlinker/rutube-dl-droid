package io.github.nlinker.rutubedl

import androidx.annotation.MainThread
import io.github.nlinker.rutubedl.bindings.Quality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Important note
// --------------
// Nothing here may depend on android.*: a plain JVM unit test drives this queue, and the stub
// android.jar on that classpath throws RuntimeException from every method - even from a value
// type like Uri.parse. Escaping that needs Robolectric or `returnDefaultValues = true`, so every
// address below is a String. The three of them mean different things, hence the three wrappers:
// on the JVM a value class is erased back to the String it holds, and the `init` turns what used
// to be a comment into a check at the point of construction.

// The page link the user shared or pasted, the one Client.probe takes.
@JvmInline
value class VideoUrl(val value: String) {
    init {
        require(value.startsWith("http://") || value.startsWith("https://")) { "not a video link: $value" }
    }
}

// A SAF tree, the folder the user picked. Names a grant, not a directory.
@JvmInline
value class FolderUri(val value: String) {
    init { require(value.startsWith("content://")) { "not a content Uri: $value" } }
}

// A single file: a MediaStore row or a SAF document, whichever the downloader created.
@JvmInline
value class FileUri(val value: String) {
    init { require(value.startsWith("content://")) { "not a content Uri: $value" } }
}

// One video to fetch, settled at enqueue and never changed. Null folder means the Download
// collection.
data class Task(val id: Long, val url: VideoUrl, val quality: Quality, val folder: FolderUri?)

sealed interface TaskState {
    data object Waiting : TaskState

    // `total` is 0 until the probe comes back with a segment count.
    data class Running(val done: Int, val total: Int) : TaskState
    data class Done(val name: String, val uri: FileUri) : TaskState
    data class Failed(val message: String) : TaskState
}

val TaskState.isPending: Boolean
    get() = this is TaskState.Waiting || this is TaskState.Running

val List<Entry>.isBusy: Boolean
    get() = any { it.state.isPending }

// The element of the queue. `title` is the video title, which only the probe can tell, so it
// stays null until the first progress report and then holds for every state that follows.
data class Entry(val task: Task, val state: TaskState, val title: String? = null)

// What the downloader produced. The queue turns it into `TaskState.Done`, which is its own
// business.
data class Finished(val name: String, val uri: FileUri)

// An exception whose message is already localized and fit to show, so the queue
// puts it into `Failed` as is.
class UserMessageException(message: String) : Exception(message)

// One download, start to finish. Everything platform-specific lives behind this interface.
// The queue knows nothing about MediaStore, SAF or Rust.
// Pause/Resume is not implemented for now. When it does, pausing cancels the call
// and resuming starts a new one from the segment already reached, so this gets a `from` parameter
// rather than a way to park inside.
fun interface Downloader {
    suspend fun download(
        task: Task,
        report: (title: String, done: Int, total: Int) -> Unit
    ): Finished
}

// Queue to download videos, in the order they arrive.
// For now - ordering, cancellation and reporting only, pause/resume is not implemented yet.
//
// `enqueue` and `cancel` must be called from main UI thread,
// because they touch the id counter and the `jobs` map. Progress reports may
// come from any thread: every change goes through `_entries.update`, which is a CAS and is safe.
class DownloadQueue(private val downloader: Downloader, private val scope: CoroutineScope) {
    // Shared between tokio worker thread (progress reports) and main UI thread.
    // The most recent entries are last in the list.
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private var nextId = 1L
    private val jobs = mutableMapOf<Long, Job>()

    // Returns the id of the queued task, or null when the same video in the same quality
    // is already queued or running.
    @MainThread
    fun enqueue(url: VideoUrl, quality: Quality, folder: FolderUri?): Long? {
        val duplicate =
            _entries.value.any { it.task.url == url && it.task.quality == quality && it.state.isPending }
        if (duplicate) return null
        val task = Task(nextId++, url, quality, folder)
        _entries.update { it + Entry(task, TaskState.Waiting) }
        ensureRunning()
        return task.id
    }

    // Drops a waiting entry, or stops a running one. Unknown ids are ignored.
    @MainThread
    fun cancel(id: Long) {
        jobs.remove(id)?.cancel()
        _entries.update { list -> list.filter { it.task.id != id || it.state !is TaskState.Waiting } }
    }

    // Idempotent: after the call something is running, as long as the queue is not empty.
    // Not a suspend function, because a completion handler cannot suspend.
    private fun ensureRunning() {
        if (_entries.value.count { it.state is TaskState.Running } >= RUNNING_LIMIT) return
        val task = _entries.value.firstOrNull { it.state is TaskState.Waiting }?.task ?: return

        setTaskState(task.id, TaskState.Running(done = 0, total = 0))
        // LAZY means do not start immediately, wait until explicitly called start()
        val job = scope.launch(start = CoroutineStart.LAZY) { runTask(task) }
        jobs[task.id] = job
        // Runs on completion of any kind, cancellation included, so this is where the slot is
        // freed and the next task picked up. Keeping it here rather than in a `finally` inside
        // `runTask` leaves that function to do the work and record the outcome, nothing else.
        job.invokeOnCompletion { cause ->
            jobs.remove(task.id)
            // A canceled task leaves no trace. It has to be dropped here and not in `runTask`:
            // a job canceled before its body starts never reaches the try block at all.
            if (cause is CancellationException) {
                _entries.update { list -> list.filter { it.task.id != task.id } }
            }
            trimTasks()
            ensureRunning()
        }
        job.start()
    }

    private suspend fun runTask(task: Task) {
        try {
            val finished = downloader.download(task) { title, done, total ->
                _entries.update { list ->
                    list.map {
                        if (it.task.id == task.id) it.copy(state = TaskState.Running(done, total), title = title) else it
                    }
                }
            }
            setTaskState(task.id, TaskState.Done(finished.name, finished.uri))
        } catch (e: CancellationException) {
            // Not a failure, and the completion handler above already drops the entry.
            throw e
        } catch (e: Exception) {
            setTaskState(task.id, TaskState.Failed(e.message ?: e.toString()))
        }
    }

    private fun setTaskState(id: Long, state: TaskState) {
        _entries.update { list -> list.map { if (it.task.id == id) it.copy(state = state) else it } }
    }

    // Keep the newest KEEP_FINISHED finished entries; waiting and running ones are never dropped.
    private fun trimTasks() {
        _entries.update { list ->
            val doomed = list.filter { !it.state.isPending }
                .dropLast(KEEP_FINISHED)
                .map { it.task.id }
                .toSet()
            // Remove from the list all doomed elements, if any.
            // Avoid rebuilding the list, if possible.
            if (doomed.isEmpty()) list else list.filter { it.task.id !in doomed }
        }
    }

    private companion object {
        // The number of simultaneous video downloads. Raising this needs a per-download speed
        // limit in the core and a notification that can show more than one task; the guard in
        // `ensureRunning` then has to become a loop.
        const val RUNNING_LIMIT = 1

        const val KEEP_FINISHED = 20
    }
}
