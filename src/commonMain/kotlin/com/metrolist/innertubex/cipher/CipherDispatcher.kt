package com.metrolist.innertubex.cipher

import kotlinx.coroutines.CoroutineDispatcher

/** Runs QuackJS, whose recursion over player code needs more stack than some platform worker threads provide. */
internal expect val cipherDispatcher: CoroutineDispatcher
