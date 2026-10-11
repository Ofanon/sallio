package fr.sallio.pronote

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** What the protocol needs from the network, so tests can swap it. */
interface Http {
    fun get(url: String, cookies: List<String> = emptyList()): String
    fun post(url: String, body: String, contentType: String = "application/json"): String
}

/**
 * Plain HttpURLConnection. Redirects are not followed: a PRONOTE page that
 * redirects (to an ENT login, typically) is not a page we can use, and
 * following it would send the request somewhere the student never chose.
 */
class UrlConnectionHttp(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 25_000,
) : Http {

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        if (connection.url.protocol != "https") {
            connection.disconnect()
            throw PronoteException.Unreachable(IOException("refus d'une connexion non chiffrée"))
        }
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("User-Agent", USER_AGENT)
        return connection
    }

    private fun read(connection: HttpURLConnection): String {
        try {
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            return stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        } finally {
            connection.disconnect()
        }
    }

    override fun get(url: String, cookies: List<String>): String = wrap {
        val connection = open(url)
        if (cookies.isNotEmpty()) connection.setRequestProperty("Cookie", cookies.joinToString("; "))
        read(connection)
    }

    override fun post(url: String, body: String, contentType: String): String = wrap {
        val connection = open(url)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", contentType)
        val bytes = body.toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { it.write(bytes) }
        read(connection)
    }

    private inline fun wrap(block: () -> String): String =
        try {
            block()
        } catch (e: PronoteException) {
            throw e
        } catch (e: IOException) {
            throw PronoteException.Unreachable(e)
        }

    companion object {
        /**
         * PRONOTE serves its mobile pages only to its own app. This is the
         * user agent of the official iOS app, the one Pawnote and Papillon use,
         * and it matches the device we declare in `Identification`.
         */
        const val USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 19_0 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Mobile/15E148 PRONOTE Mobile APP Version/2.0.11"
    }
}
