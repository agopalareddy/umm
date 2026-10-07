package io.github.agopalareddy.umm.linux

import java.net.URI
import java.net.URLDecoder

/** The `umm://` links the sign-in page opens. */
object UmmLink {
    /** The code from `umm://oauth?code=…`, or null for any other link. */
    fun authCode(url: String): String? {
        val uri = try {
            URI(url)
        } catch (e: java.net.URISyntaxException) {
            return null
        }
        if (uri.scheme != "umm" || uri.host != "oauth") return null
        return uri.rawQuery.orEmpty().split('&')
            .map { it.substringBefore('=') to it.substringAfter('=', "") }
            .firstOrNull { it.first == "code" }
            ?.let { URLDecoder.decode(it.second, Charsets.UTF_8) }
            ?.takeIf { it.isNotEmpty() }
    }
}
