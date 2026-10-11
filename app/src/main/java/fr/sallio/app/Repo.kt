package fr.sallio.app

import android.content.Context
import fr.sallio.pronote.Credentials
import fr.sallio.pronote.Lesson
import fr.sallio.pronote.LoggedIn
import fr.sallio.pronote.Pronote
import fr.sallio.pronote.PronoteException
import fr.sallio.pronote.Timetable
import fr.sallio.pronote.UrlConnectionHttp
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.locks.ReentrantLock

/**
 * The whole state of the app: the student's account, the cached timetable
 * and how the last sync went. The timetable is fetched ahead of time, so at
 * the end of a break the widget answers from the phone, whatever state the
 * PRONOTE servers are in.
 */
class Repo private constructor(private val context: Context) {

    private val vault = Vault(context)
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val lock = ReentrantLock()

    class Account(
        val credentials: Credentials,
        val studentName: String,
        val schoolName: String,
        val className: String?,
    )

    class Status(
        val lastSuccess: Long,
        val lastAttempt: Long,
        /** Message shown to the student, null when the last attempt worked. */
        val error: String?,
        val needsLogin: Boolean,
    )

    fun account(): Account? {
        val o = vault.read(ACCOUNT)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        return Account(
            credentials = Credentials(
                url = o.getString("url"),
                username = o.getString("username"),
                token = o.getString("token"),
                deviceUuid = o.getString("device"),
                accountKind = o.optInt("kind", 6),
                navigatorIdentifier = o.optString("nav").ifEmpty { null },
            ),
            studentName = o.optString("student"),
            schoolName = o.optString("school"),
            className = o.optString("class").ifEmpty { null },
        )
    }

    private fun saveAccount(logged: LoggedIn) {
        val c = logged.credentials
        vault.write(
            ACCOUNT,
            JSONObject()
                .put("url", c.url)
                .put("username", c.username)
                .put("token", c.token)
                .put("device", c.deviceUuid)
                .put("kind", c.accountKind)
                .put("nav", c.navigatorIdentifier ?: "")
                .put("student", logged.studentName)
                .put("school", logged.schoolName)
                .put("class", logged.className ?: "")
                .toString(),
        )
    }

    fun lessons(): List<Lesson> {
        val text = vault.read(CACHE) ?: return emptyList()
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map { Lesson.fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun status() = Status(
        lastSuccess = prefs.getLong("success", 0),
        lastAttempt = prefs.getLong("attempt", 0),
        error = prefs.getString("error", null),
        needsLogin = prefs.getBoolean("needsLogin", false),
    )

    /** Called once the login screen got a working session. */
    fun onLoggedIn(logged: LoggedIn) {
        lock.lock()
        try {
            saveAccount(logged)
            prefs.edit().putBoolean("needsLogin", false).remove("error")
                .putLong("attempt", System.currentTimeMillis()).apply()
            try {
                fetchAndStore(logged)
            } catch (e: PronoteException) {
                prefs.edit().putString("error", explain(e)).apply()
            }
        } finally {
            lock.unlock()
        }
        Widgets.refreshAll(context)
    }

    enum class SyncResult { DONE, SKIPPED, FAILED }

    /**
     * Logs in with the stored token and downloads two weeks of timetable.
     * Never throws: failures are recorded in [status] and the old timetable
     * stays on screen.
     */
    fun sync(manual: Boolean): SyncResult {
        val account = account() ?: return SyncResult.SKIPPED
        if (!lock.tryLock()) return SyncResult.SKIPPED
        try {
            val now = System.currentTimeMillis()
            val status = status()
            if (status.needsLogin) return SyncResult.SKIPPED
            if (now < prefs.getLong("suspendedUntil", 0)) return SyncResult.SKIPPED
            // Background syncs stay polite: PRONOTE suspends IPs that hammer it.
            val minGap = if (manual) MANUAL_GAP_MS else BACKGROUND_GAP_MS
            if (now - status.lastAttempt < minGap) return SyncResult.SKIPPED

            prefs.edit().putLong("attempt", now).apply()
            return try {
                val logged = Pronote.loginToken(UrlConnectionHttp(), account.credentials)
                // The old token is already dead: store the new one before anything else.
                saveAccount(logged)
                fetchAndStore(logged)
                SyncResult.DONE
            } catch (e: PronoteException) {
                val edit = prefs.edit().putString("error", explain(e))
                if (e.needsNewLogin) edit.putBoolean("needsLogin", true)
                if (e is PronoteException.SuspendedIp || e is PronoteException.RateLimited) {
                    edit.putLong("suspendedUntil", now + SUSPENSION_COOLDOWN_MS)
                }
                edit.apply()
                SyncResult.FAILED
            } catch (e: Exception) {
                prefs.edit().putString("error", "Erreur inattendue (${e.javaClass.simpleName})").apply()
                SyncResult.FAILED
            }
        } finally {
            lock.unlock()
            Widgets.refreshAll(context)
        }
    }

    private fun fetchAndStore(logged: LoggedIn) {
        val today = LocalDate.now()
        val lessons = Timetable.fetch(logged, today, today.plusDays(FETCH_DAYS))
        vault.write(CACHE, JSONArray(lessons.map { it.toJson() }).toString())
        prefs.edit()
            .putLong("success", System.currentTimeMillis())
            .remove("error")
            .apply()
    }

    /** Forgets everything: token, timetable, sync state, Keystore key. */
    fun logout() {
        lock.lock()
        try {
            vault.wipe()
            prefs.edit().clear().apply()
        } finally {
            lock.unlock()
        }
        Widgets.refreshAll(context)
    }

    companion object {
        private const val ACCOUNT = "account"
        private const val CACHE = "timetable"
        private const val FETCH_DAYS = 13L
        private const val MANUAL_GAP_MS = 30_000L
        private const val BACKGROUND_GAP_MS = 20 * 60_000L
        private const val SUSPENSION_COOLDOWN_MS = 30 * 60_000L

        @Volatile private var instance: Repo? = null

        fun get(context: Context): Repo =
            instance ?: synchronized(this) {
                instance ?: Repo(context.applicationContext).also { instance = it }
            }

        fun explain(e: PronoteException): String = when (e) {
            is PronoteException.Busy -> "Pronote est surchargé, nouvel essai plus tard"
            is PronoteException.Unreachable -> "Pas de connexion à Pronote"
            is PronoteException.Unavailable -> "Pronote est indisponible"
            is PronoteException.SuspendedIp, is PronoteException.RateLimited ->
                "Pronote limite les connexions, pause de 30 min"
            is PronoteException.SessionExpired -> "Session expirée, nouvel essai plus tard"
            is PronoteException.BadCredentials -> "Connexion expirée : reconnecte-toi"
            is PronoteException.AccessDenied -> "Accès refusé par Pronote : reconnecte-toi"
            is PronoteException.AccountDisabled -> "Compte Pronote désactivé"
            is PronoteException.SecurityRequired -> "Pronote demande une vérification : reconnecte-toi"
            is PronoteException.Server -> "Pronote : ${e.message}"
            is PronoteException.Protocol -> "Réponse de Pronote inattendue"
        }
    }
}
