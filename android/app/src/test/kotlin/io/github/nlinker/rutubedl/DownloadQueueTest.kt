package io.github.nlinker.rutubedl

import io.github.nlinker.rutubedl.bindings.Quality
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlin.concurrent.thread
import org.junit.Test

// The queue test carries no android.* dependency, and the downloader
// behind `Downloader` is stubbed. `runTest` gives virtual time, so nothing here waits for real.
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DownloadQueueTest {

    @Test
    fun `runs one download at a time, in the order they arrive`() = runTest {
        val downloader = StubDownloader()
        val queue = makeQueue(downloader)

        val a = queue.enqueue(VideoUrl(URL_A), Quality.Height(360u), null)!!
        val b = queue.enqueue(VideoUrl(URL_B), Quality.Height(360u), null)!!
        queue.enqueue(VideoUrl(URL_C), Quality.Height(360u), null)
        advanceUntilIdle()

        assertEquals(listOf(a), downloader.started)
        assertEquals(listOf(URL_A, URL_B, URL_C), queue.entries.value.map { it.task.url.value })
        assertTrue(queue.entries.value[0].state is TaskState.Running)
        assertTrue(queue.entries.value[1].state is TaskState.Waiting)

        downloader.finish(a)
        advanceUntilIdle()

        assertEquals(listOf(a, b), downloader.started)
        assertTrue(queue.entries.value[0].state is TaskState.Done)
        assertTrue(queue.entries.value[1].state is TaskState.Running)
    }

    @Test
    fun `progress reports reach the entry`() = runTest {
        val downloader = StubDownloader()
        val queue = makeQueue(downloader)

        val a = queue.enqueue(VideoUrl(URL_A), Quality.Height(360u), null)!!
        advanceUntilIdle()
        downloader.report(a, "Lecture 1", done = 3, total = 10)
        advanceUntilIdle()

        assertEquals(TaskState.Running(3, 10), queue.entries.value.single().state)
        assertEquals("Lecture 1", queue.entries.value.single().title)

        // In the app the reports arrive from a tokio worker, simulate that.
        val rounds = 500
        val reporter =
            thread { repeat(rounds) { downloader.report(a, "Lecture 1", it + 1, rounds) } }
        repeat(rounds) { queue.enqueue(VideoUrl("$URL_B/$it"), Quality.Height(360u), null) }
        reporter.join()

        assertEquals(rounds + 1, queue.entries.value.size)
        assertEquals(TaskState.Running(rounds, rounds), queue.entries.value.first().state)
    }

    @Test
    fun `cancelling a waiting task leaves the running one alone`() = runTest {
        val downloader = StubDownloader()
        val queue = makeQueue(downloader)

        val a = queue.enqueue(VideoUrl(URL_A), Quality.Height(360u), null)!!
        val b = queue.enqueue(VideoUrl(URL_B), Quality.Height(360u), null)!!
        advanceUntilIdle()

        queue.cancel(b)
        advanceUntilIdle()

        assertEquals(listOf(URL_A), queue.entries.value.map { it.task.url.value })
        assertTrue(queue.entries.value.single().state is TaskState.Running)
        assertEquals(listOf(a), downloader.started)
    }

    @Test
    fun `cancelling the running task starts the next one`() = runTest {
        val downloader = StubDownloader()
        val queue = makeQueue(downloader)

        val a = queue.enqueue(VideoUrl(URL_A), Quality.Height(360u), null)!!
        val b = queue.enqueue(VideoUrl(URL_B), Quality.Height(360u), null)!!
        advanceUntilIdle()

        queue.cancel(a)
        advanceUntilIdle()

        // A canceled entry disappears: it is not a failure worth showing.
        assertEquals(listOf(URL_B), queue.entries.value.map { it.task.url.value })
        assertEquals(listOf(a, b), downloader.started)
    }

    @Test
    fun `a failed download does not stop the queue`() = runTest {
        val downloader = StubDownloader()
        val queue = makeQueue(downloader)

        val a = queue.enqueue(VideoUrl(URL_A), Quality.Height(360u), null)!!
        queue.enqueue(VideoUrl(URL_B), Quality.Height(360u), null)
        advanceUntilIdle()

        downloader.fail(a, "no network")
        advanceUntilIdle()

        assertEquals(TaskState.Failed("no network"), queue.entries.value[0].state)
        assertTrue(queue.entries.value[1].state is TaskState.Running)
    }

    @Test
    fun `the same video in the same quality is not queued twice`() = runTest {
        val downloader = StubDownloader()
        val queue = makeQueue(downloader)

        val a = queue.enqueue(VideoUrl(URL_A), Quality.Height(360u), null)
        assertNotNull(a)
        assertNull(queue.enqueue(VideoUrl(URL_A), Quality.Height(360u), null))
        // Another quality of the same video is a separate file, so it is a separate task.
        assertNotNull(queue.enqueue(VideoUrl(URL_A), Quality.Height(720u), null))
        advanceUntilIdle()

        assertEquals(2, queue.entries.value.size)

        // Once it has finished it is history, and asking for it again starts a new download.
        downloader.finish(a!!)
        advanceUntilIdle()
        assertNotNull(queue.enqueue(VideoUrl(URL_A), Quality.Height(360u), null))
    }

    @Test
    fun `the queue reports itself idle once it drains`() = runTest {
        val downloader = StubDownloader()
        val queue = makeQueue(downloader)

        val a = queue.enqueue(VideoUrl(URL_A), Quality.Height(360u), null)!!
        val b = queue.enqueue(VideoUrl(URL_B), Quality.Height(360u), null)!!
        advanceUntilIdle()
        assertTrue(queue.entries.value.isBusy)

        downloader.finish(a)
        advanceUntilIdle()
        downloader.finish(b)
        advanceUntilIdle()

        assertFalse(queue.entries.value.isBusy)
        assertEquals(2, queue.entries.value.size)
    }

    @Test
    fun `history is capped, pending entries are never dropped`() = runTest {
        val downloader = StubDownloader()
        val queue = makeQueue(downloader)

        // One more than the cap, plus a tail that never finishes.
        val ids = List(25) { queue.enqueue(VideoUrl("$URL_A/$it"), Quality.Height(360u), null)!! }
        advanceUntilIdle()
        ids.take(22).forEach { id ->
            downloader.finish(id)
            advanceUntilIdle()
        }

        val states = queue.entries.value.groupingBy { it.state::class.simpleName }.eachCount()
        assertEquals(20, states["Done"])
        assertEquals(1, states["Running"])
        assertEquals(2, states["Waiting"])
    }
}

private const val URL_A = "https://rutube.ru/video/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/"
private const val URL_B = "https://rutube.ru/video/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb/"
private const val URL_C = "https://rutube.ru/video/cccccccccccccccccccccccccccccccc/"

// A scope of its own on the test scheduler: built from scratch, so it carries neither the test's
// Job nor its dispatcher, and `runTest` does not wait for downloads a test leaves parked. The
// SupervisorJob mirrors production, where one failed download must not take the scope down.
// Not `backgroundScope`: `advanceUntilIdle` does not run work launched there.
private fun TestScope.makeQueue(downloader: Downloader) =
    DownloadQueue(
        downloader,
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
    )

// Stands in for `RealDownloader`. Every download parks until the test completes it, so the test
// decides when each one finishes and in what order.
private class StubDownloader : Downloader {
    // a journal of run events, the ids are never removed
    val started = mutableListOf<Long>()
    private val gates = mutableMapOf<Long, CompletableDeferred<Finished>>()
    private val reports = mutableMapOf<Long, (String, Int, Int) -> Unit>()

    override suspend fun download(
        task: Task,
        report: (title: String, done: Int, total: Int) -> Unit
    ): Finished {
        started += task.id
        reports[task.id] = report
        val gate = CompletableDeferred<Finished>()
        gates[task.id] = gate
        return gate.await()
    }

    fun report(id: Long, title: String, done: Int, total: Int) =
        reports.getValue(id)(title, done, total)

    fun finish(id: Long) =
        gates.getValue(id).complete(Finished("video $id.mp4", FileUri("content://media/$id")))

    fun fail(id: Long, message: String) =
        gates.getValue(id).completeExceptionally(UserMessageException(message))
}
