package com.metrolist.innertubex.cipher

import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext

internal actual val QUICKJS_MAX_STACK_SIZE_BYTES: Long = 256L * 1024L

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal actual fun newQuickJsThread(): CloseableCoroutineDispatcher = newSingleThreadContext("InnerTubeX-QuickJS")
