package fr.sallio.pronote

import org.json.JSONException
import org.json.JSONObject
import java.security.SecureRandom

/**
 * One PRONOTE session: the `h` id the page gave us, the AES material, and the
 * order counter both sides increment on every message. Port of Pawnote-LTS
 * (GPL-3.0) `RequestFN` / `ResponseFN`, checked against pronotepy 2.15.7.
 */
class Session internal constructor(
    val base: String,
    val accountKind: Int,
    internal val http: Http,
    internal val id: Long,
    internal val version: IntArray,
    private val rsaModulus: String,
    private val rsaExponent: String,
    private val rsaFromConstants: Boolean,
    private val plainHttp: Boolean,
    private val skipEncryption: Boolean,
    private val skipCompression: Boolean,
) {
    internal val aesIv: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }
    internal var aesKey: ByteArray = ByteArray(0)
    private var order = 0

    /** Filled by `FonctionParametres`. */
    internal var instance: InstanceParameters? = null

    /** The `{G, L, N}` resource of the logged in student. */
    internal var userResource: JSONObject? = null

    internal val properties: ApiProperties get() = ApiProperties.of(version)

    /** `Uuid` of `FonctionParametres`: the IV, RSA encrypted unless over HTTPS with the built-in key. */
    internal fun uuidForParameters(): String {
        val raw = if (rsaFromConstants && !plainHttp) aesIv else Crypto.rsaEncrypt(aesIv, rsaModulus, rsaExponent)
        return java.util.Base64.getEncoder().encodeToString(raw)
    }

    @Synchronized
    internal fun call(name: String, data: JSONObject): JSONObject {
        val p = properties
        order++
        // The very first message of a session uses a zero IV, every other one md5(iv).
        val requestIv = if (order == 1) ByteArray(0) else aesIv
        val orderHex = Crypto.hex(Crypto.aesEncrypt(order.toString().toByteArray(), aesKey, requestIv))

        val json = data.toString()
        var compressedHex: String? = null
        if (!skipCompression) {
            // Quirk of PRONOTE: what gets deflated is the hex text of the JSON, not the JSON.
            val hexOfJson = Crypto.hex(json.toByteArray(Charsets.UTF_8))
            compressedHex = Crypto.hex(Crypto.deflateRaw(hexOfJson.toByteArray(Charsets.US_ASCII)))
        }
        val payload: Any = when {
            !skipEncryption -> {
                val clear = compressedHex?.let { Crypto.unhex(it) } ?: json.toByteArray(Charsets.UTF_8)
                Crypto.hex(Crypto.aesEncrypt(clear, aesKey, requestIv))
            }
            compressedHex != null -> compressedHex
            else -> data
        }

        val body = JSONObject()
            .put(p.session, id)
            .put(p.orderNumber, orderHex)
            .put(p.requestId, name)
            .put(p.secureData, payload)

        val url = "$base/appelfonction/$accountKind/$id/$orderHex"
        val content = http.post(url, body.toString())
        return decode(content)
    }

    private fun decode(content: String): JSONObject {
        val p = properties
        order++
        try {
            val response = JSONObject(content)
            response.optJSONObject("Erreur")?.let {
                throw PronoteException.Server(it.optString("Titre", "Erreur serveur"))
            }

            var payload: Any = response.get(p.secureData)
            if (!skipEncryption || !skipCompression) {
                var bytes: ByteArray = if (!skipEncryption) {
                    Crypto.aesDecrypt(Crypto.unhex(payload as String), aesKey, aesIv)
                } else {
                    Crypto.unhex(payload as String)
                }
                if (!skipCompression) bytes = Crypto.inflateRaw(bytes)
                payload = bytes.toString(Charsets.UTF_8)
            }
            val result = payload as? JSONObject ?: JSONObject(payload as String)

            result.optJSONObject(p.signature)?.let { signature ->
                if (signature.has("Erreur")) {
                    throw PronoteException.Server(signature.optString("MessageErreur", "Erreur serveur"))
                }
            }
            return result
        } catch (e: PronoteException) {
            throw e
        } catch (e: Exception) {
            throw classifyErrorPage(content) ?: PronoteException.Protocol(
                "Réponse illisible (${e.javaClass.simpleName})"
            )
        }
    }

    companion object {
        /** Fallback key PRONOTE uses when the page does not ship MR / ER. */
        internal const val RSA_MODULUS_1024 =
            "B99B77A3D72D3A29B4271FC7B7300E2F791EB8948174BE7B8024667E915446D4EEA0C2424B8D1EBF7E2DDFF94691C6E994E8" +
                "39225C627D140A8F1146D1B0B5F18A09BBD3D8F421CA1E3E4796B301EEBCCF80D81A32A1580121B8294433C38377083C" +
                "5517D5921E8A078CDC019B15775292EFDA2C30251B1CCABE812386C893E5"
        internal const val RSA_EXPONENT = "010001"

        private val VERSION = Regex(">PRONOTE (\\d+)\\.(\\d+)\\.(\\d+)")

        /**
         * Opens a session by reading the `Start({...})` call of the mobile page.
         * The `appliMobile=1` cookie and `fd=1` are what the official app sends.
         */
        fun open(http: Http, base: String, accountKind: Int, credentialsMode: Boolean): Session {
            val page = "$base/mobile.${AccountKinds.path(accountKind)}.html?fd=1&login=true" +
                if (credentialsMode) "&bydlg=A6ABB224-12DD-4E31-AD3E-8A39A1C2C335" else ""
            val html = http.get(page, if (credentialsMode) emptyList() else listOf("appliMobile=1"))

            val match = VERSION.find(html)
                ?: throw classifyErrorPage(html) ?: PronoteException.Unavailable()
            val version = intArrayOf(
                match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt()
            )

            val start: JSONObject = try {
                parseStart(html)
            } catch (e: JSONException) {
                throw classifyErrorPage(html) ?: PronoteException.Unavailable()
            } catch (e: IllegalStateException) {
                throw classifyErrorPage(html) ?: PronoteException.Unavailable()
            }

            val fromConstants = !start.has("MR") && !start.has("ER")
            val skipEncryption: Boolean
            val skipCompression: Boolean
            if (ApiProperties.gte(version, 2025, 1, 3)) {
                skipEncryption = !truthy(start.opt("CrA"))
                skipCompression = !truthy(start.opt("CoA"))
            } else {
                skipEncryption = truthy(start.opt("sCrA"))
                skipCompression = truthy(start.opt("sCoA"))
            }

            return Session(
                base = base,
                accountKind = start.optInt("a", accountKind),
                http = http,
                id = start.get("h").toString().toLong(),
                version = version,
                rsaModulus = if (fromConstants) RSA_MODULUS_1024 else start.getString("MR"),
                rsaExponent = if (fromConstants) RSA_EXPONENT else start.getString("ER"),
                rsaFromConstants = fromConstants,
                plainHttp = truthy(start.opt("http")),
                skipEncryption = skipEncryption,
                skipCompression = skipCompression,
            )
        }

        private fun truthy(value: Any?): Boolean = when (value) {
            null, JSONObject.NULL -> false
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.isNotEmpty() && value != "0" && value != "false"
            else -> true
        }

        /** Extracts the relaxed-JSON argument of `Start(...)`, walking braces like Pawnote does. */
        internal fun parseStart(html: String): JSONObject {
            val cleaned = html.replace(" ", "").replace("\n", "").replace("\r", "")
            val marker = "Start({"
            val startIndex = cleaned.indexOf(marker)
            check(startIndex >= 0) { "no Start({" }
            val opening = startIndex + marker.length - 1
            var depth = 0
            var inString = false
            var quote = ' '
            var end = -1
            var i = opening
            while (i < cleaned.length) {
                val c = cleaned[i]
                if (inString) {
                    if (c == '\\') i++ else if (c == quote) inString = false
                } else if (c == '"' || c == '\'') {
                    inString = true
                    quote = c
                } else if (c == '{') {
                    depth++
                } else if (c == '}') {
                    depth--
                    if (depth == 0) {
                        end = i + 1
                        break
                    }
                }
                i++
            }
            check(end > 0) { "unterminated Start" }
            val relaxed = cleaned.substring(opening, end)
            val strict = relaxed
                .replace(Regex("(['\"])?([a-z0-9A-Z_]+)(['\"])?:"), "\"$2\": ")
                .replace("'", "\"")
            return JSONObject(strict)
        }

        /** PRONOTE answers errors with HTML pages; their wording is the only signal. */
        internal fun classifyErrorPage(content: String): PronoteException? = when {
            content.contains("La page a expir") -> PronoteException.SessionExpired()
            content.contains("Votre adresse IP ") -> PronoteException.SuspendedIp()
            content.contains("momentanément indisponible") || content.contains("momentan&eacute;ment") ->
                PronoteException.Busy()
            content.contains("La page dem") || content.contains("Impossible d'a") ||
                content.contains("Le site n'est pas disponible") -> PronoteException.Unavailable()
            content.contains("Vous avez d") -> PronoteException.RateLimited()
            content.contains("s refus") -> PronoteException.AccessDenied()
            else -> null
        }
    }
}

/** Field names changed twice (2024.3.9 and 2025.1.3). */
internal data class ApiProperties(
    val data: String,
    val signature: String,
    val orderNumber: String,
    val secureData: String,
    val requestId: String,
    val session: String,
) {
    companion object {
        fun gte(v: IntArray, major: Int, minor: Int, patch: Int): Boolean = when {
            v[0] != major -> v[0] > major
            v[1] != minor -> v[1] > minor
            else -> v[2] >= patch
        }

        fun of(version: IntArray): ApiProperties = when {
            gte(version, 2025, 1, 3) -> ApiProperties("data", "Signature", "no", "dataSec", "id", "session")
            gte(version, 2024, 3, 9) -> ApiProperties("data", "Signature", "numeroOrdre", "donneesSec", "nom", "session")
            else -> ApiProperties("donnees", "_Signature_", "numeroOrdre", "donneesSec", "nom", "session")
        }
    }
}

object AccountKinds {
    const val STUDENT = 6
    const val PARENT = 7

    fun path(kind: Int): String = when (kind) {
        PARENT -> "parent"
        8 -> "professeur"
        else -> "eleve"
    }
}
