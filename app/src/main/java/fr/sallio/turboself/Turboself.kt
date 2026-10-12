package fr.sallio.turboself

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * TurboSelf, the canteen service: its REST API, as the Papillon app reads it
 * through `turboself-api` (raphckrman, GPL-3.0), rewritten in plain Kotlin.
 *
 * Unlike PRONOTE there is no device token: a login with the account's e-mail
 * and password gives a bearer token for about 55 minutes, and nothing to
 * renew it with. Keeping the session alive therefore means logging in again.
 */
object Turboself {

    const val BASE = "https://api-rest-prod.incb.fr/api"

    /** A login: the bearer token, the "host" (the diner) and the user account. */
    data class Session(val token: String, val hostId: Long, val userId: Long, val createdAt: Long = System.currentTimeMillis()) {
        /** The token lasts about 55 minutes; renewed a little before. */
        val fresh: Boolean get() = System.currentTimeMillis() - createdAt < 50 * 60_000L
    }

    /** The diner, as TurboSelf knows them. */
    data class Host(
        val firstName: String,
        val lastName: String,
        /** "Argent" (a balance in money) or "Forfait" (a meal plan). */
        val mode: String,
        val className: String?,
        /** Price of one lunch, in the unit of the balances. */
        val lunchPrice: Double?,
        /** The canteen card number: the QR code shown at the terminal. */
        val cardNumber: Long?,
        val canBook: Boolean,
        val canBookWithNegativeBalance: Boolean,
    )

    data class Balance(
        val label: String,
        val amount: Double,
        /** After future bookings and pending payments. */
        val estimatedAmount: Double,
        /** TurboSelf's own sentence about the estimate ("... au 24/10/2026"). */
        val estimatedMessage: String?,
    )

    /** One day of a booking week at one terminal (lunch, usually). */
    data class BookingDay(
        /** The booking week's id, needed to book. */
        val weekId: String,
        val terminal: String,
        /** As TurboSelf numbers it; sent back unchanged to book. */
        val dayOfWeek: Int,
        val label: String,
        val booked: Int,
        val allowed: Boolean,
        val message: String?,
        val year: Int,
        val week: Int,
    )

    data class HistoryLine(val date: String, val detail: String, val amount: Double)

    sealed class Failure(message: String) : IOException(message) {
        class BadCredentials : Failure("Identifiants TurboSelf refusés")
        class Expired : Failure("Session TurboSelf expirée")
        class Unreachable(cause: Throwable) : Failure("TurboSelf injoignable") {
            init { initCause(cause) }
        }
        class Server(val code: Int, body: String) : Failure("TurboSelf $code : ${body.take(200)}")
    }

    // ---- HTTP ------------------------------------------------------------------------

    /** What goes over the network, so tests can swap it. */
    fun interface Transport {
        /** Returns (status, body). */
        fun send(method: String, url: String, body: String?, token: String?): Pair<Int, String>
    }

    /** HTTPS only, no redirects, JSON in and out. */
    val https = Transport { method, url, body, token ->
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw Failure.Unreachable(e)
        }
        if (connection.url.protocol != "https") throw Failure.Unreachable(IOException("connexion non chiffrée refusée"))
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 25_000
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            code to (stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "")
        } catch (e: IOException) {
            throw Failure.Unreachable(e)
        } finally {
            connection.disconnect()
        }
    }

    private fun call(t: Transport, method: String, path: String, body: String? = null, token: String? = null): String {
        val (code, text) = t.send(method, "$BASE/$path", body, token)
        return when {
            code in 200..299 -> text
            code == 401 && token != null -> throw Failure.Expired()
            code == 401 || code == 403 -> throw Failure.BadCredentials()
            else -> throw Failure.Server(code, text)
        }
    }

    // ---- API -------------------------------------------------------------------------

    /** `v1/auth/login` with the account's e-mail (or username) and password. */
    fun login(t: Transport, username: String, password: String, hostId: Long? = null): Session {
        val body = JSONObject().put("username", username).put("password", password)
        hostId?.let { body.put("hoteId", it) }
        val o = JSONObject(call(t, "POST", "v1/auth/login", body.toString()))
        val token = o.optString("access_token").ifEmpty { throw Failure.BadCredentials() }
        return Session(token, o.getLong("hoteId"), o.optLong("userId"))
    }

    fun host(t: Transport, s: Session): Host = parseHost(JSONObject(call(t, "GET", "v1/hotes/${s.hostId}", token = s.token)))

    fun balances(t: Transport, s: Session): List<Balance> = parseBalances(JSONArray(call(t, "GET", "v1/comptes/hotes/${s.hostId}/3", token = s.token)))

    /** The booking days of a week (TurboSelf's own week number), the current one when null. */
    fun bookings(t: Transport, s: Session, week: Int? = null): List<BookingDay> {
        val query = week?.let { "?num=" + URLEncoder.encode(it.toString(), "UTF-8") } ?: ""
        return parseBookings(JSONObject(call(t, "GET", "v1/reservations/hotes/${s.hostId}/semaines$query", token = s.token)))
    }

    /** Books ([count] 1) or cancels ([count] 0) a day; returns how many are booked now. */
    fun book(t: Transport, s: Session, day: BookingDay, count: Int): Int {
        val body = JSONObject()
            .put("dayOfWeek", day.dayOfWeek)
            .put("dayReserv", count)
            .put("web", JSONObject().put("id", day.weekId))
            .put("hasHoteResaSoirActive", false)
        return JSONObject(call(t, "POST", "v2/hotes/${s.hostId}/reservations-jours", body.toString(), s.token)).optInt("dayReserv", count)
    }

    fun history(t: Transport, s: Session): List<HistoryLine> = parseHistory(JSONArray(call(t, "GET", "v1/historiques/hotes/${s.hostId}", token = s.token)))

    // ---- Parsing (pure, unit tested) -------------------------------------------------

    private fun JSONObject.str(name: String): String? = if (isNull(name)) null else optString(name).trim().ifEmpty { null }
    private fun JSONObject.num(name: String): Double? = if (isNull(name) || !has(name)) null else optDouble(name).takeIf { !it.isNaN() }
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    internal fun parseHost(o: JSONObject) = Host(
        // turboself-api documents "nom" as the first name; to check on a real account.
        firstName = o.optString("prenom"),
        lastName = o.optString("nom"),
        mode = o.optString("mode"),
        className = o.str("division"),
        lunchPrice = o.num("prixDej"),
        cardNumber = if (o.has("carteCodee") && !o.isNull("carteCodee")) o.optLong("carteCodee").takeIf { it > 0 } else null,
        canBook = o.optBoolean("droitReservation"),
        canBookWithNegativeBalance = o.optBoolean("autoriseReservSoldeIns"),
    )

    internal fun parseBalances(a: JSONArray) = a.objects().map { b ->
        Balance(
            label = b.optJSONObject("appli")?.str("lib") ?: "Compte",
            amount = b.num("montant") ?: 0.0,
            estimatedAmount = b.num("montantEstime") ?: b.num("montant") ?: 0.0,
            estimatedMessage = b.str("montantEstimeMsg"),
        )
    }

    internal fun parseBookings(o: JSONObject) = o.optJSONArray("rsvWebDto")?.objects().orEmpty().flatMap { w ->
        val terminal = w.optJSONObject("borne")?.str("lib") ?: "Self"
        w.optJSONArray("jours")?.objects().orEmpty().map { d ->
            BookingDay(
                weekId = w.optString("id"),
                terminal = terminal,
                dayOfWeek = d.optInt("dayOfWeek"),
                label = d.optString("dayLabel"),
                booked = d.optInt("dayReserv"),
                allowed = d.optBoolean("autorise"),
                message = d.str("msg"),
                year = w.optInt("annee"),
                week = w.optInt("semaine"),
            )
        }
    }

    internal fun parseHistory(a: JSONArray) = a.objects().map { h ->
        HistoryLine(h.optString("date"), h.optString("detail"), (h.num("credit") ?: 0.0) - (h.num("debit") ?: 0.0))
    }
}
