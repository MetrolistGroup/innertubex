package com.metrolist.innertubex.models

import platform.Foundation.NSLocale
import platform.Foundation.countryCode
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier
import platform.Foundation.preferredLanguages

internal actual fun defaultYouTubeLocale(): YouTubeLocale {
    val current = NSLocale.currentLocale
    val language =
        (NSLocale.preferredLanguages.firstOrNull() as? String)
            ?.takeIf(String::isNotBlank)
            ?: current.localeIdentifier
    val preferred = NSLocale(language)
    return YouTubeLocale(
        gl = (preferred.countryCode ?: current.countryCode).orEmpty().uppercase(),
        hl = language.substringBefore('@').replace('_', '-'),
    )
}
