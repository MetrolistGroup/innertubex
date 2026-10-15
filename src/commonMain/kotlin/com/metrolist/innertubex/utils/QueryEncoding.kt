package com.metrolist.innertubex.utils

internal fun encodeQueryComponent(value: String): String =
    buildString(value.length) {
        value.encodeToByteArray().forEach { byte ->
            val c = byte.toInt() and 0xff
            if (c in 0x30..0x39 || c in 0x41..0x5a || c in 0x61..0x7a || c == '-'.code || c == '.'.code || c == '_'.code ||
                c == '~'.code
            ) {
                append(c.toChar())
            } else {
                append('%')
                append("0123456789ABCDEF"[c ushr 4])
                append("0123456789ABCDEF"[c and 15])
            }
        }
    }
