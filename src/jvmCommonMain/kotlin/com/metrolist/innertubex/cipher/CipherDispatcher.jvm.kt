package com.metrolist.innertubex.cipher

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val cipherDispatcher: CoroutineDispatcher = Dispatchers.Default
