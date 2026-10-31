package eu.kanade.tachiyomi.source.novel.js

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Real time on purpose: runTest's virtual clock would fire withTimeout while the plugin thread works.
class JsPluginThreadTest {

    private class FakeRuntime : JsPluginThread.Runtime {
        val cancelled = CountDownLatch(1)
        val closed = CountDownLatch(1)
        override fun cancel() = cancelled.countDown()
        override fun close() = closed.countDown()
    }

    private val created = mutableListOf<FakeRuntime>()

    private fun thread(callTimeoutMs: Long = 5_000, idleMs: Long = 60_000) =
        JsPluginThread("test", callTimeoutMs, idleMs) { FakeRuntime().also { synchronized(created) { created += it } } }

    @Test
    fun `calls share one runtime on one plugin thread`() = runBlocking {
        val thread = thread()
        val first = thread.run { it to Thread.currentThread().name }
        val second = thread.run { it to Thread.currentThread().name }

        assertSame(first.first, second.first)
        assertEquals("lnreader-test", first.second)
        assertEquals(1, created.size)
        thread.close()
    }

    @Test
    fun `errors reach the caller`() = runBlocking {
        val thread = thread()
        val error = assertThrows<IllegalStateException> { thread.run { error("broken plugin") } }
        assertEquals("broken plugin", error.message)
        thread.close()
    }

    @Test
    fun `a stuck call times out and the next call gets a fresh thread and runtime`() = runBlocking {
        val thread = thread(callTimeoutMs = 200)
        val release = CountDownLatch(1)

        assertThrows<JsTimeoutException> { thread.run { release.await() } }
        val stuck = created.single()
        assertTrue(stuck.cancelled.await(1, TimeUnit.SECONDS))

        val next = thread.run { it to Thread.currentThread() }
        assertNotSame(stuck, next.first)
        assertTrue(next.second.isAlive)

        // The stuck runtime is closed once its call finally returns
        release.countDown()
        assertTrue(stuck.closed.await(1, TimeUnit.SECONDS))
        thread.close()
    }

    @Test
    fun `cancelling the caller cancels the runtime call`() = runBlocking {
        val thread = thread()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val call = async {
            thread.run {
                started.countDown()
                release.await()
            }
        }
        assertTrue(started.await(1, TimeUnit.SECONDS))

        call.cancel()
        assertTrue(created.single().cancelled.await(1, TimeUnit.SECONDS))
        release.countDown()
        thread.close()
    }

    @Test
    fun `an idle runtime is closed and created again on the next call`() = runBlocking {
        val thread = thread(idleMs = 50)
        val first = thread.run { it }
        assertTrue(created.single().closed.await(1, TimeUnit.SECONDS))

        val second = thread.run { it }
        assertNotEquals(first, second)
        assertEquals(2, created.size)
        thread.close()
    }

    @Test
    fun `close frees the runtime`() = runBlocking {
        val thread = thread()
        thread.run { }
        thread.close()
        assertTrue(created.single().closed.await(1, TimeUnit.SECONDS))
    }
}
