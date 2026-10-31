package eu.kanade.tachiyomi.source.novel.js

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Real time on purpose: runTest's virtual clock would fire withTimeout while the plugin thread works.
// Calls that must wait are started undispatched: runBlocking's only thread then blocks on a latch.
class JsPluginThreadTest {

    private class FakeRuntime : JsPluginThread.Runtime {
        val cancelled = CountDownLatch(1)
        val closed = CountDownLatch(1)
        override fun begin() = Unit
        override fun cancel() = cancelled.countDown()
        override fun close() = closed.countDown()
    }

    private val created = mutableListOf<FakeRuntime>()
    private val threads = mutableListOf<JsPluginThread<FakeRuntime>>()

    /** Opened in the tests that block a call; let go after each test, so a failed one leaks no thread. */
    private val release = CountDownLatch(1)

    private fun thread(callTimeoutMs: Long = 5_000, idleMs: Long = 60_000) =
        JsPluginThread("test", callTimeoutMs, idleMs) {
            FakeRuntime().also { synchronized(created) { created += it } }
        }.also { threads += it }

    @AfterEach
    fun cleanUp() {
        release.countDown()
        threads.forEach { it.close() }
    }

    @Test
    fun `calls share one runtime on one plugin thread`() = runBlocking {
        val thread = thread()
        val first = thread.run { it to Thread.currentThread().name }
        val second = thread.run { it to Thread.currentThread().name }

        assertSame(first.first, second.first)
        assertEquals("lnreader-test", first.second)
        assertEquals(1, created.size)
    }

    @Test
    fun `errors reach the caller`() = runBlocking {
        val thread = thread()
        val error = assertThrows<IllegalStateException> { thread.run { error("broken plugin") } }
        assertEquals("broken plugin", error.message)
    }

    @Test
    fun `a stuck call times out and the next call gets a fresh thread and runtime`() = runBlocking {
        val thread = thread(callTimeoutMs = 1_000)

        assertThrows<JsTimeoutException> { thread.run { release.await() } }
        val stuck = created.single()
        assertTrue(stuck.cancelled.await(1, TimeUnit.SECONDS))

        val next = thread.run { it to Thread.currentThread() }
        assertNotSame(stuck, next.first)
        assertTrue(next.second.isAlive)

        // The stuck runtime is closed once its call finally returns
        release.countDown()
        assertTrue(stuck.closed.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun `time spent queued does not count against a call`() = runBlocking {
        val thread = thread(callTimeoutMs = 2_000)
        // Each call takes well under the timeout, both together well over it
        val first = async(start = CoroutineStart.UNDISPATCHED) { thread.run { Thread.sleep(1_200) } }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            thread.run {
                Thread.sleep(1_200)
                "second"
            }
        }

        first.await()
        assertEquals("second", second.await())
        assertEquals(1, created.size)
    }

    @Test
    fun `calls queued behind a stuck one move to the new thread`() = runBlocking {
        val thread = thread(callTimeoutMs = 1_000)
        val stuck = async(start = CoroutineStart.UNDISPATCHED) { runCatching { thread.run { release.await() } } }
        val queued = async(start = CoroutineStart.UNDISPATCHED) { thread.run { Thread.currentThread() } }

        assertTrue(stuck.await().exceptionOrNull() is JsTimeoutException)
        assertTrue(queued.await().isAlive)
        assertEquals(2, created.size)
    }

    @Test
    fun `cancelling the caller cancels the runtime call`() = runBlocking {
        val thread = thread()
        val started = CountDownLatch(1)
        val call = async(start = CoroutineStart.UNDISPATCHED) {
            thread.run {
                started.countDown()
                release.await()
            }
        }
        assertTrue(started.await(1, TimeUnit.SECONDS))

        call.cancel()
        assertTrue(created.single().cancelled.await(1, TimeUnit.SECONDS))
        release.countDown()
        call.join()
        assertTrue(call.isCancelled)
    }

    @Test
    fun `cancelling a queued call leaves the running one alone`() = runBlocking {
        val thread = thread()
        val started = CountDownLatch(1)
        val running = async(start = CoroutineStart.UNDISPATCHED) {
            thread.run {
                started.countDown()
                release.await()
                "done"
            }
        }
        assertTrue(started.await(1, TimeUnit.SECONDS))
        var queuedRan = false
        val queued = async(start = CoroutineStart.UNDISPATCHED) { thread.run { queuedRan = true } }

        queued.cancel()
        assertFalse(created.single().cancelled.await(200, TimeUnit.MILLISECONDS))
        release.countDown()
        assertEquals("done", running.await())
        // The dropped call never runs
        thread.run { }
        assertFalse(queuedRan)
    }

    @Test
    fun `an idle runtime is closed and created again on the next call`() = runBlocking {
        val thread = thread(idleMs = 50)
        val first = thread.run { it }
        assertTrue(created.single().closed.await(1, TimeUnit.SECONDS))

        val second = thread.run { it }
        assertNotEquals(first, second)
        assertEquals(2, created.size)
    }

    @Test
    fun `close frees the runtime`() = runBlocking {
        val thread = thread()
        thread.run { }
        thread.close()
        assertTrue(created.single().closed.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun `calls after close fail and start no thread`() = runBlocking {
        val thread = thread()
        thread.close()

        assertThrows<IOException> { thread.run { } }
        assertEquals(0, created.size)
    }
}
