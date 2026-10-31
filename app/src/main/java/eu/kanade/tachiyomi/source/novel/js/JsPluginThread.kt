package eu.kanade.tachiyomi.source.novel.js

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The thread one plugin's JS runs on, so a slow or stuck plugin only holds up itself.
 *
 * A QuickJS instance must stay on the thread that created it, and the bundled quickjs-android
 * (0.9.2) has no interrupt handler, so a call that runs past [callTimeoutMs] cannot be stopped:
 * its runtime is asked to [Runtime.cancel] (pending HTTP calls fail), and the thread is given up
 * for a new one. A runtime left idle for [idleMs] is closed to free its memory and is created
 * again on the next call.
 */
class JsPluginThread<R : JsPluginThread.Runtime>(
    private val name: String,
    private val callTimeoutMs: Long,
    private val idleMs: Long,
    private val create: () -> R,
) : Closeable {

    /** A runtime that can make its call in progress give up from another thread. */
    interface Runtime : Closeable {
        fun cancel()
    }

    private var worker: Worker? = null

    /** Runs [block] on the plugin thread, failing after [callTimeoutMs]. */
    suspend fun <T> run(block: (R) -> T): T {
        val worker = worker()
        return try {
            withTimeout(callTimeoutMs) {
                suspendCancellableCoroutine { cont ->
                    worker.submit(block, cont)
                    cont.invokeOnCancellation { worker.cancel() }
                }
            }
        } catch (e: TimeoutCancellationException) {
            retire(worker)
            throw JsTimeoutException("$name did not answer within ${callTimeoutMs / 1000} s", e)
        }
    }

    @Synchronized
    override fun close() {
        worker?.shutdown()
        worker = null
    }

    @Synchronized
    private fun worker(): Worker = worker ?: Worker().also { worker = it }

    @Synchronized
    private fun retire(stuck: Worker) {
        if (worker === stuck) worker = null
        stuck.shutdown()
    }

    private inner class Worker {
        private val executor = ScheduledThreadPoolExecutor(1) { Thread(it, "lnreader-$name") }.apply {
            removeOnCancelPolicy = true
            // A retired thread must not wait for the idle close to end
            executeExistingDelayedTasksAfterShutdownPolicy = false
        }

        // Written on the executor thread only; read from others to cancel
        @Volatile
        private var runtime: R? = null
        private var idleClose: ScheduledFuture<*>? = null

        fun <T> submit(block: (R) -> T, cont: CancellableContinuation<T>) {
            try {
                executor.execute {
                    idleClose?.cancel(false)
                    // Skipped when the caller gave up while the call was queued
                    if (cont.isActive) {
                        cont.resumeWith(runCatching { block(runtime ?: create().also { runtime = it }) })
                    }
                    scheduleIdleClose()
                }
            } catch (e: RejectedExecutionException) {
                cont.resumeWith(Result.failure(e))
            }
        }

        fun cancel() {
            runtime?.cancel()
        }

        /** Closes the runtime once the call in progress, if any, ends. */
        fun shutdown() {
            cancel()
            try {
                executor.execute(::closeRuntime)
            } catch (_: RejectedExecutionException) {
                // Already shut down
            }
            executor.shutdown()
        }

        private fun scheduleIdleClose() {
            idleClose = try {
                executor.schedule(Runnable { closeRuntime() }, idleMs, TimeUnit.MILLISECONDS)
            } catch (_: RejectedExecutionException) {
                // Shut down while the call ran: the close queued by shutdown() follows
                null
            }
        }

        private fun closeRuntime() {
            runtime?.close()
            runtime = null
        }
    }
}

class JsTimeoutException(message: String, cause: Throwable? = null) : Exception(message, cause)
