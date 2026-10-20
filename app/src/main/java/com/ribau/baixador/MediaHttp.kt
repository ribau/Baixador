package com.ribau.baixador

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

object MediaHttp {
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    fun open(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(),
             body: ByteArray? = null, allowed: (String) -> Boolean = { URI(it).scheme == "https" },
             timeout: Int = 15000): HttpURLConnection {
        var current = url
        repeat(5) {
            require(allowed(current)) { "Endereço de mídia inválido." }
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = timeout; connection.readTimeout = timeout
            connection.requestMethod = method
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept-Encoding", "identity")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            try {
                if (body != null) {
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }
                if (connection.responseCode in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: error("Redirecionamento sem destino.")
                    val next = URI(current).resolve(location).toString()
                    // Session headers must never follow a redirect to another host.
                    if (headers.keys.any { it.equals("Cookie", true) }) {
                        require(URI(next).host == URI(current).host) { "O Instagram solicitou uma nova autenticação." }
                    }
                    connection.disconnect(); current = next
                } else return connection
            } catch (e: Exception) { connection.disconnect(); throw e }
        }
        error("Muitos redirecionamentos. Consulte o link novamente.")
    }
    fun bytes(url: String, limit: Int, headers: Map<String, String> = emptyMap(), body: ByteArray? = null,
              allowed: (String) -> Boolean = { URI(it).scheme == "https" },
              onResponse: ((HttpURLConnection) -> Unit)? = null): ByteArray {
        val connection = open(url, if (body == null) "GET" else "POST", headers, body, allowed)
        try {
            onResponse?.invoke(connection)
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            return connection.inputStream.use {
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= limit) { "Resposta maior que o limite permitido." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally { connection.disconnect() }
    }
    fun size(url: String): Long = runCatching {
        val c = open(url, "HEAD", mapOf("Referer" to "https://www.instagram.com/"),
            allowed = InstagramUrls::isCdn, timeout = 4000)
        try { if (c.responseCode in 200..299) c.contentLengthLong.coerceAtLeast(0) else 0L }
        finally { c.disconnect() }
    }.getOrDefault(0L)
}
