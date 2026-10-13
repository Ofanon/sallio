package fr.sallio.app

import android.content.Context
import fr.sallio.turboself.Turboself
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.IsoFields
import java.util.concurrent.locks.ReentrantLock

/**
 * The canteen, through TurboSelf: the account (kept in the [Vault], password
 * included, at the student's choice), and a copy of what TurboSelf said last
 * time (card number for the QR code, balance, bookings), so the QR code and
 * the balance show without network.
 *
 * TurboSelf has no lasting token: a session is a login with the password, good
 * for about 55 minutes, kept in memory only.
 */
class Canteen private constructor(private val context: Context) {

    private val vault = Vault(context)
    private val prefs = context.getSharedPreferences("canteen", Context.MODE_PRIVATE)
    private val lock = ReentrantLock()
    @Volatile private var session: Turboself.Session? = null
    private val net: Turboself.Transport = Turboself.https

    class Day(val date: LocalDate, val booking: Turboself.BookingDay)

    class Data(
        val firstName: String,
        val mode: String,
        val lunchPrice: Double?,
        val cardNumber: Long?,
        val canBook: Boolean,
        val canBookWithNegativeBalance: Boolean,
        val balances: List<Turboself.Balance>,
        val days: List<Day>,
        val history: List<Turboself.HistoryLine>,
        val updatedAt: Long,
    ) {
        /** The balance that matters: the canteen one (the first), after future bookings. */
        val balance: Turboself.Balance? get() = balances.firstOrNull()

        /** Lunches the estimated balance still pays for, when TurboSelf gives a price. */
        val lunchesLeft: Int? get() = lunchPrice?.takeIf { it > 0 }?.let { p -> balance?.let { Math.floor(it.estimatedAmount / p).toInt() } }

        fun day(date: LocalDate): Day? = days.firstOrNull { it.date == date }
    }

    fun connected(): Boolean = vault.read(ACCOUNT) != null

    fun data(): Data? = vault.read(DATA)?.let { runCatching { decode(JSONObject(it)) }.getOrNull() }

    /** The last error to show, null when the last sync worked. */
    fun error(): String? = prefs.getString("error", null)

    /** First login, from the settings. Worker thread. Returns an error message, or null. */
    fun connect(username: String, password: String): String? {
        lock.lock()
        try {
            val s = Turboself.login(net, username.trim(), password)
            session = s
            vault.write(ACCOUNT, JSONObject().put("u", username.trim()).put("p", password).put("h", s.hostId).toString())
            prefs.edit().clear().apply()
            fetch(s, first = true)
            Analytics.event("cantine_connexion", "resultat" to "ok")
            return null
        } catch (e: Turboself.Failure.BadCredentials) {
            Analytics.event("cantine_connexion", "resultat" to "refusee")
            return "E-mail ou mot de passe refusé par Turboself."
        } catch (e: Turboself.Failure) {
            Analytics.error("turboself_connexion", e)
            return "Turboself ne répond pas, réessaie dans un moment."
        } catch (e: Exception) {
            Analytics.error("turboself_connexion", e)
            return "Réponse de Turboself inattendue."
        } finally {
            lock.unlock()
        }
    }

    fun disconnect() {
        lock.lock()
        try {
            session = null
            vault.delete(ACCOUNT)
            vault.delete(DATA)
            prefs.edit().clear().apply()
        } finally {
            lock.unlock()
        }
    }

    /** A session less than 50 minutes old, or a new login with the stored password. */
    private fun live(): Turboself.Session {
        session?.takeIf { it.fresh }?.let { return it }
        val a = JSONObject(vault.read(ACCOUNT) ?: throw Turboself.Failure.BadCredentials())
        return Turboself.login(net, a.getString("u"), a.getString("p"), a.optLong("h").takeIf { it > 0 }).also { session = it }
    }

    /** With a session, retried once with a new login when TurboSelf says it expired. */
    private fun <T> withSession(block: (Turboself.Session) -> T): T = try {
        block(live())
    } catch (e: Turboself.Failure.Expired) {
        session = null
        block(live())
    }

    /**
     * Background refresh, after the PRONOTE sync. At most every 15 minutes
     * unless [force]d (the refresh button). Never throws.
     */
    fun sync(force: Boolean = false) {
        if (!connected()) return
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("lastSync", 0) < 15 * 60_000L) return
        if (!lock.tryLock()) return
        try {
            prefs.edit().putLong("lastSync", now).apply()
            withSession { fetch(it, first = false) }
            prefs.edit().remove("error").apply()
        } catch (e: Turboself.Failure.BadCredentials) {
            Analytics.error("turboself", e)
            prefs.edit().putString("error", "Turboself refuse le mot de passe enregistré : reconnecte-toi dans Réglages.").apply()
        } catch (e: Exception) {
            Analytics.error("turboself", e)
            prefs.edit().putString("error", "Turboself injoignable, données de ${Agenda.ago(data()?.updatedAt ?: 0, LocalDateTime.now())}").apply()
        } finally {
            lock.unlock()
        }
    }

    /** Books (true) or cancels a lunch. Worker thread. Returns an error message, or null. */
    fun book(day: Day, booked: Boolean): String? {
        lock.lock()
        try {
            withSession { s ->
                Turboself.book(net, s, day.booking, if (booked) 1 else 0)
                fetch(s, first = false)
            }
            Analytics.event("cantine_reservation", "reserver" to booked, "resultat" to "ok")
            return null
        } catch (e: Turboself.Failure.Server) {
            DebugLog.i("Turboself refused ${if (booked) "a booking" else "a cancellation"}: ${e.code} ${e.body.take(300)}")
            val reason = e.reason.orEmpty()
            val poor = Regex("(?i)solde|insuffisant|cr[ée]dit").containsMatchIn(reason)
            Analytics.event("cantine_reservation", "reserver" to booked, "resultat" to if (poor) "solde_insuffisant" else "refus", "http" to e.code)
            return when {
                // Cancelling never costs money: TurboSelf's reason as it is, without "recharge".
                !booked -> "Turboself refuse d'annuler ce repas${reason.takeIf { it.isNotEmpty() }?.let { " : ${it.trimEnd('.')}" } ?: ""}."
                // TurboSelf's refusal when the balance does not cover the meal.
                Regex("(?i)solde|insuffisant|cr[ée]dit").containsMatchIn(reason) -> INSUFFICIENT
                reason.isNotEmpty() -> "Turboself n'a pas pu réserver : ${reason.trimEnd('.')}."
                else -> "Turboself n'a pas pu réserver ce repas."
            }
        } catch (e: Exception) {
            Analytics.error("turboself_reservation", e)
            return "Pas de connexion à Turboself, réessaie."
        } finally {
            lock.unlock()
        }
    }

    private fun fetch(s: Turboself.Session, first: Boolean) {
        val host = Turboself.host(net, s) { DebugLog.i(it) }
        val balances = Turboself.balances(net, s)
        // This week and the next, when TurboSelf opens it: a Friday evening looks at Monday.
        val days = runCatching { Turboself.bookings(net, s) }.getOrDefault(emptyList()).toMutableList()
        days.firstOrNull()?.let { d -> runCatching { days += Turboself.bookings(net, s, d.week + 1) } }
        val history = runCatching { Turboself.history(net, s) }.getOrDefault(emptyList())
        if (BuildConfig.DEBUG) {
            // Shapes still to confirm on a real account (units, day numbering), never the card number.
            DebugLog.i("Turboself mode=${host.mode} prixDej=${host.lunchPrice} carte=${if (host.cardNumber != null) "présente" else "absente"} reservation=${host.canBook} " +
                balances.joinToString { "solde ${it.label}=${it.amount}/${it.estimatedAmount} (${it.estimatedMessage})" } +
                " jours=" + days.joinToString { "${it.year}-S${it.week} j${it.dayOfWeek} '${it.label}' r=${it.booked} ok=${it.allowed}" })
        }
        val before = data()
        val data = Data(host.firstName, host.mode, host.lunchPrice, host.cardNumber, host.canBook, host.canBookWithNegativeBalance,
            balances, days.distinctBy { it.weekId to it.dayOfWeek }.map { Day(dateOf(it), it) }.sortedBy { it.date }, history.take(30),
            System.currentTimeMillis())
        vault.write(DATA, encode(data).toString())
        if (!first) runCatching { Alerts.post(context, alerts(before, data, LocalDateTime.now())) }
        // The canteen syncs after PRONOTE, which already drew the widgets: the balance widget follows.
        runCatching { Widgets.refreshAll(context) }
    }

    // ---- Alerts ----------------------------------------------------------------------

    /** Balance turning low, and a lunch tomorrow not booked yet (the evening before). */
    internal fun alerts(before: Data?, after: Data, now: LocalDateTime): List<Changes.Item> {
        val items = mutableListOf<Changes.Item>()
        val left = after.lunchesLeft
        val leftBefore = before?.lunchesLeft
        if (left != null && left <= LOW_LUNCHES && (leftBefore == null || leftBefore > LOW_LUNCHES)) {
            items += Changes.Item(Changes.Kind.CANTEEN, "canteen|low|${now.toLocalDate()}",
                if (left <= 0) "Solde de cantine épuisé" else "Plus que $left repas sur ta carte de cantine",
                "Pense à recharger ton compte Turboself.")
        }
        val tomorrow = now.toLocalDate().plusDays(1)
        val lunch = after.day(tomorrow)?.booking
        val reminded = prefs.getString("reminded", null)
        if (now.hour >= REMIND_FROM_HOUR && lunch != null && lunch.allowed && lunch.booked == 0 && after.canBook && reminded != tomorrow.toString()) {
            prefs.edit().putString("reminded", tomorrow.toString()).apply()
            items += Changes.Item(Changes.Kind.CANTEEN, "canteen|book|$tomorrow", "Pas encore réservé pour demain midi",
                "Réserve d'ici, ou touche pour choisir dans Sallio.")
        }
        return items
    }

    // ---- Storage ---------------------------------------------------------------------

    private fun encode(d: Data): JSONObject = JSONObject()
        .put("first", d.firstName).put("mode", d.mode).put("price", d.lunchPrice ?: JSONObject.NULL)
        .put("card", d.cardNumber ?: JSONObject.NULL).put("book", d.canBook).put("neg", d.canBookWithNegativeBalance)
        .put("balances", JSONArray(d.balances.map {
            JSONObject().put("l", it.label).put("a", it.amount).put("e", it.estimatedAmount).put("m", it.estimatedMessage ?: JSONObject.NULL)
        }))
        .put("days", JSONArray(d.days.map {
            val b = it.booking
            JSONObject().put("date", it.date.toString()).put("w", b.weekId).put("t", b.terminal).put("d", b.dayOfWeek).put("l", b.label)
                .put("r", b.booked).put("ok", b.allowed).put("m", b.message ?: JSONObject.NULL).put("y", b.year).put("n", b.week)
        }))
        .put("history", JSONArray(d.history.map { JSONObject().put("d", it.date).put("t", it.detail).put("a", it.amount) }))
        .put("at", d.updatedAt)
        .put("euros", true)

    private fun decode(o: JSONObject): Data {
        // Copies saved by 0.6.x builds kept TurboSelf's cents.
        val k = if (o.optBoolean("euros")) 1.0 else 0.01
        fun JSONArray?.list() = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
        fun JSONObject.str(n: String) = if (isNull(n)) null else optString(n)
        return Data(
            o.optString("first"), o.optString("mode"),
            if (o.isNull("price")) null else o.optDouble("price") * k,
            if (o.isNull("card")) null else o.optLong("card"),
            o.optBoolean("book"), o.optBoolean("neg"),
            o.optJSONArray("balances").list().map { Turboself.Balance(it.optString("l"), it.optDouble("a") * k, it.optDouble("e") * k, it.str("m")) },
            o.optJSONArray("days").list().map {
                Day(LocalDate.parse(it.getString("date")), Turboself.BookingDay(it.optString("w"), it.optString("t"), it.optInt("d"),
                    it.optString("l"), it.optInt("r"), it.optBoolean("ok"), it.str("m"), it.optInt("y"), it.optInt("n")))
            },
            o.optJSONArray("history").list().map { Turboself.HistoryLine(it.optString("d"), it.optString("t"), it.optDouble("a") * k) },
            o.optLong("at"),
        )
    }

    companion object {
        /** The booking error that comes with a "Recharger" button. */
        const val INSUFFICIENT = "Ton solde ne suffit pas pour ce repas. Recharge ton compte Turboself, puis réserve à nouveau."

        private const val ACCOUNT = "turboself"
        private const val DATA = "turboself_data"
        private const val LOW_LUNCHES = 3
        private const val REMIND_FROM_HOUR = 17

        @Volatile private var instance: Canteen? = null

        fun get(context: Context): Canteen =
            instance ?: synchronized(this) { instance ?: Canteen(context.applicationContext).also { instance = it } }

        /**
         * The date of a booking day: TurboSelf numbers weeks like ISO 8601 and
         * days from 1 (Monday). To confirm on a real account (Papillon shifts
         * its dates by one day).
         */
        internal fun dateOf(b: Turboself.BookingDay): LocalDate =
            LocalDate.of(b.year, 6, 1)
                .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, b.week.toLong())
                .with(DayOfWeek.MONDAY)
                .plusDays((b.dayOfWeek - 1).toLong().coerceIn(0, 6))

        /** "12,40 €". */
        fun money(v: Double): String = String.format(java.util.Locale.FRANCE, "%.2f €", v)
    }
}
