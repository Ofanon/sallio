package fr.sallio.pronote

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** The parts of `FonctionParametres` the app needs. */
internal class InstanceParameters(
    val navigatorIdentifier: String?,
    /** "8h55", "9h50"... end time of each block of the day. */
    val endings: List<String>,
    val blocksPerDay: Int,
    val firstMonday: LocalDate?,
    /** Trimestres / semestres, as `{N, G, L}` entities plus their dates. */
    val periods: List<Period>,
    /** School holidays and days off, from `General.listeJoursFeries`. */
    val holidays: List<Holiday> = emptyList(),
)

/** "Vacances de la Toussaint", from [start] to [end], both days off. */
data class Holiday(val name: String, val start: LocalDate, val end: LocalDate) {
    fun toJson(): JSONObject = JSONObject().put("n", name).put("s", start.toString()).put("e", end.toString())

    companion object {
        fun fromJson(o: JSONObject) = Holiday(o.optString("n"), LocalDate.parse(o.getString("s")), LocalDate.parse(o.getString("e")))

        internal fun parse(general: JSONObject): List<Holiday> =
            (general.optJSONObject("listeJoursFeries")?.optJSONArray("V") ?: general.optJSONArray("listeJoursFeries"))
                ?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
                .mapNotNull { h ->
                    val start = h.optJSONObject("dateDebut")?.optString("V")?.let { PronoteDates.parse(it)?.toLocalDate() } ?: return@mapNotNull null
                    val end = h.optJSONObject("dateFin")?.optString("V")?.let { PronoteDates.parse(it)?.toLocalDate() } ?: start
                    Holiday(h.optString("L").trim(), start, end)
                }
                .sortedBy { it.start }
    }
}

internal class Period(val entity: JSONObject, val name: String, val start: LocalDate?, val end: LocalDate?)

/**
 * What survives between two logins. The token is single use: PRONOTE hands a
 * new one on every successful login, and the old one stops working.
 */
data class Credentials(
    val url: String,
    val username: String,
    val token: String,
    val deviceUuid: String,
    val accountKind: Int = AccountKinds.STUDENT,
    val navigatorIdentifier: String? = null,
)

/** A logged in session, ready for requests, plus the next credentials to store. */
class LoggedIn internal constructor(
    val session: Session,
    val credentials: Credentials,
    val studentName: String,
    val schoolName: String,
    val className: String?,
    internal val hasPhoto: Boolean = false,
    internal val resourceId: String? = null,
    private val photoBase64: String? = null,
) {
    /**
     * The student's PRONOTE photo, the same file the official app shows. The
     * URL is an entity encrypted with this session's key, so it only works
     * while the session is alive. Null when the school publishes no photo.
     */
    fun photo(): ByteArray? {
        // Some servers send the photo inline with the user parameters.
        photoBase64?.let { inline ->
            runCatching { java.util.Base64.getMimeDecoder().decode(inline.substringAfter("base64,")) }.getOrNull()
                ?.takeIf { it.size > 100 }?.let { return it }
        }
        if (!hasPhoto || resourceId == null) return null
        // Same URL as PRONOTE's own client (composeUrlImgPhotoIndividu): the
        // element {N, Actif} encrypted with the session key, and the person's
        // name with spaces turned into underscores as the file name.
        val entity = "{\"N\":${JSONObject.quote(resourceId)},\"Actif\":true}".toByteArray(Charsets.UTF_8)
        val encrypted = Crypto.hex(Crypto.aesEncrypt(entity, session.aesKey, session.aesIv))
        val names = listOf(studentName.replace(Regex("\\s"), "_") + ".jpg", "photo.jpg")
        for (name in names) {
            val file = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            val url = "${session.base}/FichiersExternes/$encrypted/$file?Session=${session.id}"
            session.http.bytes(url, 2_000_000)?.let { return it }
        }
        return null
    }
}

/**
 * What PRONOTE asks when the account has "double authentification" on.
 * The app can answer two of the cases itself: naming the device, and the PIN.
 */
class SecurityChallenge internal constructor(
    internal val session: Session,
    internal val authentication: JSONObject,
    internal val identity: JSONObject,
    internal val username: String,
    internal val deviceUuid: String,
    val needsPin: Boolean,
    val needsSourceOnly: Boolean,
    /** Choosing a password or a strategy: has to be done on the PRONOTE website. */
    val needsWebsiteSetup: Boolean,
)

object Pronote {

    /** Removes query, fragment and the trailing `*.html`, like Pawnote's `cleanURL`. */
    fun cleanUrl(url: String): String {
        val u = java.net.URI(url.trim())
        val segments = (u.path ?: "").split("/").toMutableList()
        if (segments.isNotEmpty() && segments.last().contains(".html")) segments.removeAt(segments.size - 1)
        val path = segments.joinToString("/").trimEnd('/')
        return "${u.scheme}://${u.authority}$path"
    }

    /** Login with the token PRONOTE gave after an ENT / web login, or after the previous refresh. */
    fun loginToken(http: Http, credentials: Credentials): LoggedIn {
        val base = cleanUrl(credentials.url)
        val session = Session.open(http, base, credentials.accountKind, credentialsMode = false)
        session.instance = instanceParameters(session, credentials.navigatorIdentifier)

        val identity = try {
            identify(
                session,
                username = credentials.username,
                deviceUuid = credentials.deviceUuid,
                loginTokenSav = credentials.token,
                requestFirstMobile = false,
                reuseMobile = true,
                fromQr = false,
            )
        } catch (e: PronoteException.Server) {
            throw PronoteException.TokenRefused()
        }

        var username = credentials.username
        var token = credentials.token
        if (identity.optInt("modeCompLog") == 1) username = username.lowercase()
        if (identity.optInt("modeCompMdp") == 1) token = token.lowercase()

        val key = middlewareKey(identity, username, token)
        val authentication = authenticate(session, solveChallenge(session, identity, key))
        switchToAuthKey(session, authentication, key)

        if (authentication.has("actionsDoubleAuth")) {
            throw PronoteException.SecurityRequired(
                securityChallenge(session, authentication, identity, credentials.username, credentials.deviceUuid)
            )
        }
        return finish(session, authentication, identity, credentials.username, credentials.deviceUuid)
    }

    /**
     * Login with a PRONOTE username and password (schools without ENT, and the
     * public demo used by the tests). PRONOTE answers with a mobile token, and
     * the next logins use [loginToken].
     */
    fun loginCredentials(
        http: Http,
        url: String,
        username: String,
        password: String,
        deviceUuid: String,
        accountKind: Int = AccountKinds.STUDENT,
    ): LoggedIn {
        val base = cleanUrl(url)
        val session = Session.open(http, base, accountKind, credentialsMode = true)
        session.instance = instanceParameters(session, null)

        val identity = identify(
            session,
            username = username,
            deviceUuid = deviceUuid,
            loginTokenSav = "",
            requestFirstMobile = true,
            reuseMobile = false,
            fromQr = false,
        )
        var user = username
        var pass = password
        if (identity.optInt("modeCompLog") == 1) user = user.lowercase()
        if (identity.optInt("modeCompMdp") == 1) pass = pass.lowercase()

        val key = middlewareKey(identity, user, pass)
        val authentication = authenticate(session, solveChallenge(session, identity, key))
        switchToAuthKey(session, authentication, key)

        if (authentication.has("actionsDoubleAuth")) {
            // PRONOTE handles the double authentication on the token login.
            return loginToken(
                http,
                Credentials(
                    url = base,
                    username = identity.optString("login").ifEmpty { username },
                    token = authentication.getString("jetonConnexionAppliMobile"),
                    deviceUuid = deviceUuid,
                    accountKind = session.accountKind,
                    navigatorIdentifier = session.instance?.navigatorIdentifier,
                )
            )
        }
        return finish(session, authentication, identity, username, deviceUuid)
    }

    /**
     * Answers a double authentication. [pin] is required when
     * [SecurityChallenge.needsPin]; [deviceName] is how the device will be
     * listed in the student's PRONOTE security settings.
     */
    fun solveSecurity(challenge: SecurityChallenge, deviceName: String, pin: String?): LoggedIn {
        if (challenge.needsWebsiteSetup) throw PronoteException.SecurityRequired(challenge)
        val session = challenge.session
        val p = session.properties
        val source = deviceName.take(30)

        // Same sequence as the official app: ask whether the name is known, then save.
        session.call(
            "SecurisationCompteDoubleAuth",
            JSONObject().put(p.data, JSONObject().put("action", 2).put("libelle", source)),
        )

        val save = JSONObject().put("action", 3)
        if (challenge.needsPin) {
            requireNotNull(pin) { "PIN requis" }
            save.put("codePin", Crypto.hex(Crypto.aesEncrypt(pin.toByteArray(), session.aesKey, session.aesIv)))
        } else {
            save.put("mode", 2) // MGDA_NotificationSeulement
        }
        save.put("avecIdentification", true).put("strIdentification", source)

        val saved = session.call("SecurisationCompteDoubleAuth", JSONObject().put(p.data, save))
        val newToken = saved.optJSONObject(p.data)?.optString("jetonConnexionAppliMobile").orEmpty()
        if (newToken.isNotEmpty()) challenge.authentication.put("jetonConnexionAppliMobile", newToken)

        return finish(session, challenge.authentication, challenge.identity, challenge.username, challenge.deviceUuid)
    }

    private fun instanceParameters(session: Session, navigatorIdentifier: String?): InstanceParameters {
        val data = JSONObject()
            .put("identifiantNav", navigatorIdentifier ?: JSONObject.NULL)
            .put("Uuid", session.uuidForParameters())
        // The version-dependent key is not known for sure yet: send both, like Pawnote.
        val response = session.call("FonctionParametres", JSONObject().put("data", data).put("donnees", data))
        val params = response.optJSONObject("data") ?: response.optJSONObject("donnees")
            ?: throw PronoteException.Protocol("FonctionParametres vide")
        val general = params.getJSONObject("General")

        val endings = mutableListOf<String>()
        general.optJSONObject("ListeHeuresFin")?.optJSONArray("V")?.let { list ->
            for (i in 0 until list.length()) endings += list.getJSONObject(i).optString("L")
        }
        val firstMonday = general.optJSONObject("PremierLundi")?.optString("V")?.let { PronoteDates.parse(it)?.toLocalDate() }
        val periods = general.optJSONArray("ListePeriodes")?.objects().orEmpty().map { p ->
            Period(
                entity = JSONObject().put("N", p.opt("N")).put("G", p.opt("G")).put("L", p.opt("L")),
                name = p.optString("L"),
                start = p.optJSONObject("dateDebut")?.optString("V")?.let { PronoteDates.parse(it)?.toLocalDate() },
                end = p.optJSONObject("dateFin")?.optString("V")?.let { PronoteDates.parse(it)?.toLocalDate() },
            )
        }

        return InstanceParameters(
            navigatorIdentifier = params.optString("identifiantNav").ifEmpty { null },
            endings = endings,
            blocksPerDay = general.optInt("PlacesParJour", endings.size),
            firstMonday = firstMonday,
            periods = periods,
            holidays = Holiday.parse(general),
        )
    }

    private fun identify(
        session: Session,
        username: String,
        deviceUuid: String,
        loginTokenSav: String,
        requestFirstMobile: Boolean,
        reuseMobile: Boolean,
        fromQr: Boolean,
    ): JSONObject {
        val p = session.properties
        val data = JSONObject()
            .put("genreConnexion", 0)
            .put("genreEspace", session.accountKind)
            .put("identifiant", username)
            .put("pourENT", false)
            .put("enConnexionAuto", false)
            .put("demandeConnexionAuto", false)
            .put("enConnexionAppliMobile", reuseMobile)
            .put("demandeConnexionAppliMobile", requestFirstMobile)
            .put("demandeConnexionAppliMobileJeton", fromQr)
            .put("uuidAppliMobile", deviceUuid)
            .put("loginTokenSAV", loginTokenSav)
            // Must stay coherent with the iOS user agent of UrlConnectionHttp.
            .put("informationsAppareil", JSONObject().put("modele", "iPhone18,3").put("platforme", "iOS"))
        val response = session.call("Identification", JSONObject().put(p.data, data))
        return response.optJSONObject(p.data) ?: throw PronoteException.Protocol("Identification vide")
    }

    private fun middlewareKey(identity: JSONObject, username: String, secret: String): ByteArray {
        val alea = identity.optString("alea", "")
        val hash = Crypto.hex(Crypto.sha256((alea + secret).toByteArray(Charsets.UTF_8))).uppercase()
        return (username.lowercase() + hash).toByteArray(Charsets.UTF_8)
    }

    /** Since PRONOTE 2026.2.5 the challenge is encrypted as is, no more "alea" stripping. */
    private fun solveChallenge(session: Session, identity: JSONObject, key: ByteArray): String {
        val challenge = identity.optString("challenge").ifEmpty { throw PronoteException.BadCredentials() }
        return Crypto.hex(Crypto.aesEncrypt(challenge.toByteArray(Charsets.UTF_8), key, session.aesIv))
    }

    private fun authenticate(session: Session, challenge: String): JSONObject {
        val p = session.properties
        val response = session.call(
            "Authentification",
            JSONObject().put(p.data, JSONObject().put("connexion", 0).put("challenge", challenge).put("espace", session.accountKind)),
        )
        val data = response.optJSONObject(p.data) ?: throw PronoteException.Protocol("Authentification vide")
        when (data.optInt("Acces", 0)) {
            0 -> Unit
            1 -> throw PronoteException.BadCredentials()
            2, 3, 4, 5, 7, 8 -> throw PronoteException.AccessDenied()
            6, 10 -> throw PronoteException.AccountDisabled()
            else -> {
                val message = data.optJSONObject("AccesMessage")
                throw PronoteException.Server(message?.optString("message")?.ifEmpty { null } ?: "Accès refusé")
            }
        }
        return data
    }

    private fun switchToAuthKey(session: Session, authentication: JSONObject, key: ByteArray) {
        val cle = authentication.optString("cle").ifEmpty { throw PronoteException.BadCredentials() }
        val decrypted = try {
            Crypto.aesDecrypt(Crypto.unhex(cle), key, session.aesIv)
        } catch (e: Exception) {
            // A wrong token makes the key undecipherable.
            throw PronoteException.BadCredentials()
        }
        session.aesKey = decrypted.toString(Charsets.ISO_8859_1)
            .split(",").map { it.trim().toInt().toByte() }.toByteArray()
    }

    private fun securityChallenge(
        session: Session,
        authentication: JSONObject,
        identity: JSONObject,
        username: String,
        deviceUuid: String,
    ): SecurityChallenge {
        val actions = PronoteDates.domain(authentication.optJSONObject("actionsDoubleAuth")?.optString("V") ?: "[]")
        return SecurityChallenge(
            session = session,
            authentication = authentication,
            identity = identity,
            username = username,
            deviceUuid = deviceUuid,
            needsPin = 3 in actions,
            needsSourceOnly = 5 in actions && 3 !in actions,
            needsWebsiteSetup = 0 in actions || 1 in actions,
        )
    }

    private fun finish(
        session: Session,
        authentication: JSONObject,
        identity: JSONObject,
        username: String,
        deviceUuid: String,
    ): LoggedIn {
        val p = session.properties
        val response = session.call("ParametresUtilisateur", JSONObject())
        val params = response.optJSONObject(p.data) ?: throw PronoteException.Protocol("ParametresUtilisateur vide")
        val user = params.getJSONObject("ressource")
        // A parent account lists its children; the app follows the first one.
        val resource = user.optJSONArray("listeRessources")?.optJSONObject(0) ?: user
        session.userResource = JSONObject()
            .put("G", resource.opt("G"))
            .put("L", resource.opt("L"))
            .put("N", resource.opt("N"))
        session.defaultPeriods = defaultPeriods(resource)

        val token = authentication.optString("jetonConnexionAppliMobile")
        if (token.isEmpty()) throw PronoteException.Protocol("Pas de jeton d'appareil")

        return LoggedIn(
            session = session,
            credentials = Credentials(
                url = session.base,
                username = identity.optString("login").ifEmpty { username },
                token = token,
                deviceUuid = deviceUuid,
                accountKind = session.accountKind,
                navigatorIdentifier = session.instance?.navigatorIdentifier,
            ),
            studentName = resource.optString("L"),
            schoolName = resource.optJSONObject("Etablissement")?.optJSONObject("V")?.optString("L").orEmpty(),
            className = resource.optJSONObject("classeDEleve")?.optString("L")?.ifEmpty { null },
            hasPhoto = resource.optBoolean("avecPhoto"),
            resourceId = resource.optString("N").ifEmpty { null },
            // {"_T": 25, "V": "<base64>"}, with V = 0 when the school shares no photo.
            photoBase64 = resource.opt("photoBase64").let { v ->
                (if (v is JSONObject) v.opt("V") else v)?.toString()?.takeIf { it.length > 100 }
            },
        )
    }

    /** Tab number -> id of the period PRONOTE opens that tab on ("Trimestre 1"...). */
    private fun defaultPeriods(resource: JSONObject): Map<Int, String> =
        resource.optJSONObject("listeOngletsPourPeriodes")?.optJSONArray("V")?.objects().orEmpty()
            .mapNotNull { tab ->
                val id = tab.optJSONObject("periodeParDefaut")?.optJSONObject("V")?.optString("N") ?: return@mapNotNull null
                tab.optInt("G") to id
            }.toMap()

    internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
}
