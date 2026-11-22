package com.metrolist.innertubex.cipher

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import platform.Foundation.NSCondition
import platform.Foundation.NSThread
import kotlin.coroutines.CoroutineContext

private const val CIPHER_STACK_BYTES = 16_777_216uL

// Dispatchers.Default runs on GCD workers with 512 KB stacks. An overflow there ends the process.
// Reuse one dedicated large-stack NSThread instead of spawning a new 16 MiB-stack OS thread per dispatch.
private object NativeCipherWorker {
    private val condition = NSCondition()
    private val queue = ArrayDeque<Runnable>()
    private var started = false

    fun enqueue(block: Runnable) {
        condition.lock()
        try {
            queue.addLast(block)
            if (!started) {
                started = true
                NSThread { runLoop() }.apply { stackSize = CIPHER_STACK_BYTES }.start()
            }
            condition.signal()
        } finally {
            condition.unlock()
        }
    }

    private fun runLoop() {
        while (true) {
            val task =
                run {
                    condition.lock()
                    try {
                        while (queue.isEmpty()) {
                            condition.wait()
                        }
                        queue.removeFirst()
                    } finally {
                        condition.unlock()
                    }
                }
            try {
                task.run()
            } catch (_: Throwable) {
                // Keep the dedicated worker alive if an arbitrary Runnable throws outside DispatchedTask.
            }
        }
    }
}

internal actual val cipherDispatcher: CoroutineDispatcher =
    object : CoroutineDispatcher() {
        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            NativeCipherWorker.enqueue(block)
        }
    }
