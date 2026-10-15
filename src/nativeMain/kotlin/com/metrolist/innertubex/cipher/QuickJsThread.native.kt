package com.metrolist.innertubex.cipher

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import platform.posix.PTHREAD_CREATE_DETACHED
import platform.posix.pthread_attr_destroy
import platform.posix.pthread_attr_init
import platform.posix.pthread_attr_setdetachstate
import platform.posix.pthread_attr_setstacksize
import platform.posix.pthread_attr_t
import platform.posix.pthread_create
import platform.posix.pthread_tVar
import kotlin.coroutines.CoroutineContext

// Native QuickJS frames need more than 512 KiB for the EJS bundle; leave headroom below the thread stack.
internal actual val QUICKJS_MAX_STACK_SIZE_BYTES: Long = 4L * 1024L * 1024L
private const val THREAD_STACK_SIZE_BYTES = 16L * 1024L * 1024L

/** Kotlin/Native workers use the 512 KiB pthread default on Apple platforms, which QuickJS overflows. */
internal actual fun newQuickJsThread(): CloseableCoroutineDispatcher = LargeStackDispatcher()

@OptIn(ExperimentalForeignApi::class)
private class LargeStackDispatcher : CloseableCoroutineDispatcher() {
    private val tasks = Channel<Runnable>(Channel.UNLIMITED)

    init {
        val ref = StableRef.create(tasks)
        val started =
            memScoped {
                val attr = alloc<pthread_attr_t>()
                pthread_attr_init(attr.ptr)
                try {
                    pthread_attr_setstacksize(attr.ptr, THREAD_STACK_SIZE_BYTES.toULong())
                    pthread_attr_setdetachstate(attr.ptr, PTHREAD_CREATE_DETACHED)
                    pthread_create(
                        alloc<pthread_tVar>().ptr,
                        attr.ptr,
                        staticCFunction { arg ->
                            val stable = arg!!.asStableRef<Channel<Runnable>>()
                            val queue = stable.get()
                            stable.dispose()
                            runBlocking { for (task in queue) task.run() }
                            null
                        },
                        ref.asCPointer(),
                    )
                } finally {
                    pthread_attr_destroy(attr.ptr)
                }
            }
        if (started != 0) {
            ref.dispose()
            error("Could not start QuickJS thread: $started")
        }
    }

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        if (tasks.trySend(block).isFailure) {
            // Mirrors newSingleThreadContext: work dispatched after close is cancelled elsewhere.
            context.cancel()
            Dispatchers.Default.dispatch(context, block)
        }
    }

    override fun close() {
        tasks.close()
    }
}
