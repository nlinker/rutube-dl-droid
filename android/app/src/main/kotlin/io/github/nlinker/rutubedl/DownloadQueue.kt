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
// type like Uri.parse. Escaping that needs Robolectric or `returnDefaultValues = true`, so paths
// and similar arguments have String type.

// Task corresponds to a video to fetch.
// - `folder` is a SAF tree Uri as a string, `null` for the Download collection.
// - `url` is the page link the user shared or pasted, the one Client.probe takes.
data class Task(val id: Long, val url: String, val quality: Quality, val folder: String?)

sealed interface TaskState {
    data object Waiting : TaskState

    // `total` is 0 until the probe comes back with a segment count.
    data class Running(val title: String, val done: Int, val total: Int) : TaskState
    data class Done(val name: String, val uri: String) : TaskState
    data class Failed(val message: String) : TaskState
}

val TaskState.isPending: Boolean
    get() = this is TaskState.Waiting || this is TaskState.Running

val List<Entry>.isBusy: Boolean
    get() = any { it.state.isPending }

// the element of the queue
data class Entry(val task: Task, val state: TaskState)

// What the downloader produced: where the finished file ended up, `uri` as a content Uri
// string. The queue turns it into `TaskState.Done`, which is its own business.
// content Uri example:
// `content://com.android.externalstorage.documents/tree/primary%3AMovies%2FRutube/document/primary%3AMovies%2FRutube%2Fvideo%20.mp4`
data class Finished(val name: String, val uri: String)

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

    // Returns false when the same video in the same quality is already queued or running.
    @MainThread
    fun enqueue(url: String, quality: Quality, folder: String?): Boolean {
        val duplicate =
            _entries.value.any { it.task.url == url && it.task.quality == quality && it.state.isPending }
        if (duplicate) return false
        val task = Task(nextId++, url, quality, folder)
        _entries.update { it + Entry(task, TaskState.Waiting) }
        ensureRunning()
        return true
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

        setTaskState(task.id, TaskState.Running(title = task.url, done = 0, total = 0))
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
                setTaskState(task.id, TaskState.Running(title, done, total))
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
