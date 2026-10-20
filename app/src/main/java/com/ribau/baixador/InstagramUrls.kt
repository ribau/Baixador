package com.ribau.baixador

import java.math.BigInteger
import java.net.URI

object InstagramUrls {
    fun shortcode(url: String): String? = runCatching {
        val uri = URI(url)
        if (uri.host?.lowercase() !in setOf("instagram.com", "www.instagram.com", "m.instagram.com")) return null
        Regex("^/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)/?$").matchEntire(uri.path)?.groupValues?.get(1)
    }.getOrNull()
    fun canonical(url: String): String = shortcode(url)?.let { "https://www.instagram.com/p/$it/" } ?: url
    fun mediaId(code: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        require(code.length in 1..28 && code.all { it in alphabet }) { "Código do post inválido." }
        return code.fold(BigInteger.ZERO) { n, c -> n * BigInteger.valueOf(64) + BigInteger.valueOf(alphabet.indexOf(c).toLong()) }.toString()
    }
    fun isInstagram(url: String) = runCatching {
        val uri = URI(url); val host = uri.host.orEmpty().lowercase()
        uri.scheme == "https" && uri.userInfo == null && (host == "instagram.com" || host.endsWith(".instagram.com"))
    }.getOrDefault(false)
    fun isCdn(url: String) = runCatching {
        val uri = URI(url); val host = uri.host.orEmpty().lowercase()
        uri.scheme == "https" && uri.userInfo == null &&
            (host.endsWith(".cdninstagram.com") || host.endsWith(".fbcdn.net"))
    }.getOrDefault(false)
}
