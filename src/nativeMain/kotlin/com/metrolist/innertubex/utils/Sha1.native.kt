package com.metrolist.innertubex.utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA1
import platform.CoreCrypto.CC_SHA1_DIGEST_LENGTH

@OptIn(ExperimentalForeignApi::class)
public actual fun sha1(input: String): String {
    val inputBytes = input.encodeToByteArray()
    val digest = ByteArray(CC_SHA1_DIGEST_LENGTH)
    if (inputBytes.isNotEmpty()) {
        inputBytes.usePinned { pinnedInput ->
            digest.usePinned { pinnedDigest ->
                CC_SHA1(
                    pinnedInput.addressOf(0),
                    inputBytes.size.toUInt(),
                    pinnedDigest.addressOf(0).reinterpret(),
                )
            }
        }
    } else {
        digest.usePinned { pinnedDigest ->
            CC_SHA1(
                null,
                0u,
                pinnedDigest.addressOf(0).reinterpret(),
            )
        }
    }
    return digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
