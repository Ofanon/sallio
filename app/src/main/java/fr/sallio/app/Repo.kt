package fr.sallio.app

import android.content.Context
import fr.sallio.pronote.Credentials
import fr.sallio.pronote.Homework
import fr.sallio.pronote.Report
import fr.sallio.pronote.Schoolwork
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

    fun homework(): List<Homework> {
        val text = vault.read(HOMEWORK) ?: return emptyList()
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map { Homework.fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun report(): Report? = vault.read(GRADES)?.let { runCatching { Report.fromJson(JSONObject(it)) }.getOrNull() }

    /**
     * Ticks waiting to reach PRONOTE, as "+key" (done) or "-key" (not done).
     * Keys, not ids: PRONOTE changes every id at each login.
     */
    private fun pending(): Set<String> = prefs.getStringSet("pendingDone", emptySet())!!

    fun isDone(h: Homework): Boolean {
        val p = pending()
        return when {
            "+" + h.key in p -> true
            "-" + h.key in p -> false
            else -> h.done
        }
    }

    /** Records the tick on the phone at once; [pushPending] then sends it to PRONOTE. */
    fun setDone(h: Homework, done: Boolean) {
        val set = pending().toMutableSet()
        set.remove("+" + h.key)
        set.remove("-" + h.key)
        if (done != h.done) set += (if (done) "+" else "-") + h.key
        prefs.edit().putStringSet("pendingDone", set).apply()
    }

    /** Number of ticks not yet on PRONOTE. */
    fun pendingCount(): Int = pending().size

    /** Grades the student has not seen in the app yet. */
    fun unseenGrades(): Set<String> {
        val seen = prefs.getStringSet("seenGradeKeys", null) ?: return emptySet()
        return report()?.grades?.map { it.key }?.filter { it !in seen }?.toSet().orEmpty()
    }

    fun markGradesSeen() {
        prefs.edit().putStringSet("seenGradeKeys", report()?.grades?.map { it.key }?.toSet().orEmpty()).apply()
    }

    /**
     * Sends pending ticks right away (log in, tick, re-read homework). Worker
     * thread. When PRONOTE cannot be reached the ticks stay pending and the
     * next sync sends them.
     */
    fun pushPending(): SyncResult {
        if (pending().isEmpty()) return SyncResult.SKIPPED
        val account = account() ?: return SyncResult.SKIPPED
        lock.lock()
        try {
            if (status().needsLogin || System.currentTimeMillis() < prefs.getLong("suspendedUntil", 0)) return SyncResult.SKIPPED
            return try {
                val logged = Pronote.loginToken(UrlConnectionHttp(), account.credentials)
                saveAccount(logged)
                prefs.edit().putLong("attempt", System.currentTimeMillis()).apply()
                fetchAndStore(logged)
                if (pending().isEmpty()) SyncResult.DONE else SyncResult.FAILED
            } catch (e: PronoteException) {
                val edit = prefs.edit().putString("error", explain(e))
                if (e.needsNewLogin) edit.putBoolean("needsLogin", true)
                edit.apply()
                SyncResult.FAILED
            } catch (e: Exception) {
                SyncResult.FAILED
            }
        } finally {
            lock.unlock()
            Widgets.refreshAll(context)
        }
    }

    /** Sends the pending ticks in [logged]'s session and returns the homework as PRONOTE now has it. */
    private fun pushTicks(logged: LoggedIn, fetched: List<Homework>): List<Homework> {
        val wanted = pending()
        if (wanted.isEmpty()) return fetched
        val byKey = fetched.associateBy { it.key }
        val changes = LinkedHashMap<String, Boolean>()
        val stale = mutableSetOf<String>()
        for (entry in wanted) {
            val h = byKey[entry.substring(1)]
            if (h == null) { stale += entry; continue }      // gone from the cahier de textes
            val done = entry[0] == '+'
            if (h.done == done) stale += entry else changes[h.id] = done
        }
        var result = fetched
        if (changes.isNotEmpty()) {
            runCatching { Schoolwork.setHomeworkDone(logged, changes) }.onSuccess {
                result = fetched.map { h -> changes[h.id]?.let { h.copy(done = it) } ?: h }
                stale += wanted.filter { e -> byKey[e.substring(1)]?.id in changes.keys }
            }
        }
        if (stale.isNotEmpty()) prefs.edit().putStringSet("pendingDone", wanted - stale).apply()
        return result
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
        // Homework and grades are extras: when PRONOTE fails on them, the timetable still counts.
        runCatching { Schoolwork.homework(logged, today, today.plusDays(FETCH_DAYS)) }.onSuccess { list ->
            val synced = pushTicks(logged, list)
            vault.write(HOMEWORK, JSONArray(synced.map { it.toJson() }).toString())
        }
        runCatching { Schoolwork.report(logged) }.onSuccess { report ->
            val first = vault.read(GRADES) == null
            vault.write(GRADES, report.toJson().toString())
            // On the very first download nothing is "new": everything was already on PRONOTE.
            if (first) markGradesSeen()
        }
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
        private const val HOMEWORK = "homework"
        private const val GRADES = "grades"
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
