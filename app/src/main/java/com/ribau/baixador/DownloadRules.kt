package com.ribau.baixador

import java.net.URI

object DownloadRules {
    fun extractUrl(text: String): String? =
        Regex("https?://[^\\s<>\"\\u0000-\\u001f]+", RegexOption.IGNORE_CASE)
            .findAll(text).map { it.value.trimEnd('.', ',', ';', '!', '?', ')', ']', '}') }
            .firstOrNull { value ->
                runCatching {
                    val uri = URI(value)
                    uri.scheme.lowercase() in setOf("http", "https") &&
                        !uri.host.isNullOrBlank() && uri.userInfo == null
                }.getOrDefault(false)
            }

    fun formatSelector(height: Int): String {
        require(height >= 0)
        val limit = if (height == 0) "" else "[height<=$height]"
        // Prefer MP4/H.264 when available; retain a fallback for other networks/codecs.
        return "bv*$limit[ext=mp4]+ba[ext=m4a]/b$limit[ext=mp4]/bv*$limit+ba/b$limit"
    }
}
