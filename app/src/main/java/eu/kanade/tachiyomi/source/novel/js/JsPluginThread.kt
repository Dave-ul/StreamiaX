package eu.kanade.tachiyomi.source.novel.js

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The thread one plugin's JS runs on, so a slow or stuck plugin only holds up itself.
 *
 * Calls run one at a time, and each gets [callTimeoutMs] from the moment it starts: time spent
 * queued behind other calls does not count. A QuickJS instance must stay on the thread that
 * created it, and the bundled quickjs-android (0.9.2) has no interrupt handler, so a call that
 * runs past its time cannot be stopped: its runtime is asked to [Runtime.cancel] (pending HTTP
 * calls fail), the thread is given up, and the calls queued behind it move to a new one.
 *
 * Cancelling a caller only cancels its own call: a queued call is dropped, a running one is
 * cancelled in its runtime. A runtime left idle for [idleMs] is closed to free its memory and is
 * created again on the next call; the idle thread ends soon after. After [close], calls fail.
 */
class JsPluginThread<R : JsPluginThread.Runtime>(
    private val name: String,
    private val callTimeoutMs: Long,
    private val idleMs: Long,
    private val create: () -> R,
) : Closeable {

    /** A runtime whose call in progress can be made to give up from another thread. */
    interface Runtime : Closeable {
        /** Called on the plugin thread before each call; clears the previous call's [cancel]. */
        fun begin()

        /** Makes the call in progress fail fast. Called from other threads. */
        fun cancel()
    }

    private var worker: Worker? = null
    private var closed = false

    /** Runs [block] on the plugin thread, failing with [JsTimeoutException] after [callTimeoutMs]. */
    suspend fun <T> run(block: (R) -> T): T {
        while (true) {
            val worker = worker()
            val call = Call(block)
            worker.submit(call)
            try {
                call.started.await()
            } catch (_: Retired) {
                // The thread was given up while this call waited: queue it again on the new one
                continue
            } catch (e: CancellationException) {
                worker.cancel(call)
                throw e
            }
            return try {
                withTimeout(callTimeoutMs) { call.result.await() }
            } catch (e: TimeoutCancellationException) {
                retire(worker, call)
                throw JsTimeoutException("$name did not answer within ${callTimeoutMs / 1000} s", e)
            } catch (e: CancellationException) {
                worker.cancel(call)
                throw e
            }
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        worker?.retire()
        worker = null
    }

    @Synchronized
    private fun worker(): Worker {
        if (closed) throw IOException("$name was closed")
        return worker ?: Worker().also { worker = it }
    }

    /** Gives up [stuck]'s thread if [call] is still the one running on it. */
    @Synchronized
    private fun retire(stuck: Worker, call: Call<*>) {
        if (stuck.retireIfRunning(call) && worker === stuck) worker = null
    }

    /** Thrown to a queued call whose thread was given up. */
    private class Retired : Exception()

    private inner class Call<T>(val block: (R) -> T) {
        val started = CompletableDeferred<Unit>()
        val result = CompletableDeferred<T>()

        @Volatile
        var cancelled = false
    }

    private inner class Worker {
        private val executor = ScheduledThreadPoolExecutor(1) { Thread(it, "lnreader-$name") }.apply {
            removeOnCancelPolicy = true
            // A retired thread must not wait for the idle close to end
            executeExistingDelayedTasksAfterShutdownPolicy = false
            // Once the idle close has run, the thread itself ends too
            setKeepAliveTime(THREAD_KEEP_ALIVE_MS, TimeUnit.MILLISECONDS)
            allowCoreThreadTimeOut(true)
        }

        private val lock = Any()

        // Guarded by lock
        private val queued = mutableSetOf<Call<*>>()
        private var current: Call<*>? = null
        private var retired = false

        // Written on the executor thread only; read from others to cancel
        @Volatile
        private var runtime: R? = null
        private var idleClose: ScheduledFuture<*>? = null

        fun submit(call: Call<*>) {
            synchronized(lock) {
                if (retired) {
                    call.started.completeExceptionally(Retired())
                    return
                }
                queued += call
            }
            try {
                executor.execute { runCall(call) }
            } catch (_: RejectedExecutionException) {
                synchronized(lock) { queued -= call }
                call.started.completeExceptionally(Retired())
            }
        }

        fun cancel(call: Call<*>) {
            synchronized(lock) {
                call.cancelled = true
                if (current === call) runtime?.cancel()
            }
        }

        fun retireIfRunning(call: Call<*>): Boolean = synchronized(lock) {
            if (current !== call) return false
            retire()
            true
        }

        /** Hands the queued calls back to their callers, then closes the runtime once the running call ends. */
        fun retire() {
            val handedBack = synchronized(lock) {
                retired = true
                queued.toList().also { queued.clear() }
            }
            handedBack.forEach { it.started.completeExceptionally(Retired()) }
            runtime?.cancel()
            try {
                executor.execute(::closeRuntime)
            } catch (_: RejectedExecutionException) {
                // Already shut down
            }
            executor.shutdown()
        }

        private fun <T> runCall(call: Call<T>) {
            idleClose?.cancel(false)
            if (start(call)) {
                runCatching {
                    val runtime = runtime ?: create().also { runtime = it }
                    runtime.begin()
                    // A cancel that came while the runtime was created found nothing to cancel
                    if (call.cancelled) throw CancellationException("$name: call cancelled")
                    call.block(runtime)
                }.fold(call.result::complete, call.result::completeExceptionally)
                synchronized(lock) { current = null }
            }
            scheduleIdleClose()
        }

        /** Marks [call] as running, unless its caller gave up or it was handed back. */
        private fun start(call: Call<*>): Boolean = synchronized(lock) {
            if (!queued.remove(call) || call.cancelled) return false
            current = call
            call.started.complete(Unit)
            true
        }

        private fun scheduleIdleClose() {
            idleClose = try {
                executor.schedule(Runnable { closeRuntime() }, idleMs, TimeUnit.MILLISECONDS)
            } catch (_: RejectedExecutionException) {
                // Shut down while the call ran: the close queued by retire() follows
                null
            }
        }

        private fun closeRuntime() {
            runtime?.close()
            runtime = null
        }
    }

    private companion object {
        const val THREAD_KEEP_ALIVE_MS = 10_000L
    }
}

class JsTimeoutException(message: String, cause: Throwable? = null) : Exception(message, cause)
