package fr.sallio.app

import android.content.Context
import fr.sallio.pronote.Credentials
import fr.sallio.pronote.Discussion
import fr.sallio.pronote.Inbox
import fr.sallio.pronote.News
import fr.sallio.pronote.Homework
import fr.sallio.pronote.Report
import fr.sallio.pronote.Presence
import fr.sallio.pronote.SchoolLife
import fr.sallio.pronote.Schoolwork
import fr.sallio.pronote.Lesson
import fr.sallio.pronote.LoggedIn
import fr.sallio.pronote.Pronote
import fr.sallio.pronote.PronoteException
import fr.sallio.pronote.Timetable
import fr.sallio.pronote.UrlConnectionHttp
import java.io.File
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

    init {
        DebugLog.init(context)
    }

    private val vault = Vault(context)
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val lock = ReentrantLock()

    init {
        runCatching { learnColors() }
    }

    /** The student's choice: subjects in PRONOTE's colours (on by default). */
    fun pronoteColors(): Boolean = context.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("pronoteColors", true)

    fun setPronoteColors(on: Boolean) {
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("pronoteColors", on).apply()
        learnColors()
        Widgets.refreshAll(context)
    }

    /** Hands [Subjects] the colour PRONOTE gives each subject, from what is stored. */
    fun learnColors() {
        val colors = LinkedHashMap<String, String?>()
        // Lessons last: when sources disagree, the timetable's colour wins, as on PRONOTE.
        report()?.subjects?.forEach { if (it.color != null) colors[it.subject] = it.color }
        homework().forEach { if (it.color != null) colors[it.subject] = it.color }
        lessons().forEach { if (it.color != null) colors[it.subject] = it.color }
        Subjects.learn(colors, pronoteColors())
    }

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

    @Volatile private var cachedPhoto: android.graphics.Bitmap? = null

    /** The photo the student chose for their avatar (JPEG), or null to remove it. */
    fun setPhoto(jpeg: ByteArray?) {
        if (jpeg == null) vault.delete(PHOTO)
        else vault.write(PHOTO, android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP))
        cachedPhoto = null
    }

    /** The avatar photo, decoded once. */
    fun photo(): android.graphics.Bitmap? {
        cachedPhoto?.let { return it }
        val text = vault.read(PHOTO) ?: return null
        return runCatching {
            val bytes = android.util.Base64.decode(text, android.util.Base64.NO_WRAP)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()?.also { cachedPhoto = it }
    }

    /** The stored list, or null when the phone has none yet. */
    private fun <T> storedList(name: String, decode: (JSONObject) -> T): List<T>? =
        vault.read(name)?.let { text ->
            runCatching { JSONArray(text).let { a -> (0 until a.length()).map { decode(a.getJSONObject(it)) } } }.getOrNull()
        }

    /** Lists already decoded, by vault entry, with the text they came from. */
    private val decoded = HashMap<String, Pair<String, List<*>>>()

    /**
     * The stored list, decoded once per version of its text: every redraw asks
     * for news and discussions (the tab badge too), and decoding forty
     * actualités each time made the Infos tab lag (55-70 ms, 2026-10-13).
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> readList(name: String, decode: (JSONObject) -> T): List<T> {
        val text = vault.read(name) ?: return emptyList()
        synchronized(decoded) {
            decoded[name]?.let { (raw, list) -> if (raw === text || raw == text) return list as List<T> }
        }
        val list = runCatching { JSONArray(text).let { a -> (0 until a.length()).map { decode(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
        synchronized(decoded) { decoded[name] = text to list }
        return list
    }

    fun news(): List<News> = readList(NEWS) { News.fromJson(it) }

    fun discussions(): List<Discussion> = readList(DISCUSSIONS) { Discussion.fromJson(it) }

    /** Unread actualités and messages, for the badge on the tab. */
    fun unreadInbox(): Int = news().count { !isRead(it) } + discussions().sumOf { it.unread }

    /**
     * "J'ai vu" waiting to reach PRONOTE, as "+key", like [pending]. One way,
     * as on PRONOTE itself: once an actualité is marked read, it stays read.
     */
    private fun pendingReads(): Set<String> = prefs.getStringSet("pendingRead", emptySet())!!

    fun isRead(n: News): Boolean {
        val p = pendingReads()
        return when {
            "+" + n.key in p -> true
            "-" + n.key in p -> false
            else -> n.read
        }
    }

    /** Records "J'ai vu" on the phone at once; [pushPending] then sends it to PRONOTE. */
    fun setRead(n: News) {
        if (n.read) return
        prefs.edit().putStringSet("pendingRead", pendingReads() + ("+" + n.key)).apply()
    }

    /**
     * Sends the waiting "J'ai vu" in [logged]'s session. When the request
     * fails they stay waiting.
     *
     * PRONOTE applies them, but within the same session it keeps answering
     * the list of actualités as it was at login (seen on a real school,
     * 2026-10-12: an "unread" read back as read, then came back unread at the
     * next login). So what was sent is remembered with its session, and
     * [withSentReads] lays it over the lists read in that session.
     */
    private fun pushReads(logged: LoggedIn) {
        // "-key" entries come from a build that let a tick be undone: PRONOTE does not undo it.
        val wanted = pendingReads().filter { it.startsWith("+") }.toSet()
        if (wanted.size != pendingReads().size) prefs.edit().putStringSet("pendingRead", wanted).apply()
        if (wanted.isEmpty()) return
        val asked = wanted.associate { it.substring(1) to (it[0] == '+') }
        val settled = runCatching { Inbox.setNewsRead(logged, asked) { DebugLog.i(it) } }
            .onFailure { DebugLog.i("SaisieActualites failed", it) }
            .getOrNull() ?: return
        DebugLog.i("J'ai vu sent to PRONOTE: ${asked.filterKeys { it in settled }}")
        val sent = if (prefs.getLong("readSentSession", 0) == liveSince) prefs.getStringSet("readSent", emptySet())!! else emptySet()
        prefs.edit()
            .putStringSet("pendingRead", pendingReads().filterNot { it.substring(1) in settled }.toSet())
            .putStringSet("readSent", sent.filterNot { it.substring(1) in settled }.toSet() + wanted.filter { it.substring(1) in settled })
            .putLong("readSentSession", liveSince)
            .apply()
    }

    /** [list] with what this session sent laid over it; a later session's list is trusted as is. */
    private fun withSentReads(list: List<News>): List<News> {
        if (prefs.getLong("readSentSession", 0) != liveSince) {
            // A new session: PRONOTE's list is current again, so it tells whether what was sent took.
            prefs.getStringSet("readSent", emptySet())!!.forEach { sent ->
                val now = list.firstOrNull { it.key == sent.substring(1) }?.read
                DebugLog.i("J'ai vu check after a new login: sent lue=${sent[0] == '+'}, PRONOTE now says lue=$now")
            }
            prefs.edit().remove("readSent").remove("readSentSession").apply()
            return list
        }
        val sent = prefs.getStringSet("readSent", emptySet())!!.associate { it.substring(1) to (it[0] == '+') }
        return list.map { n -> sent[n.key]?.let { n.copy(read = it) } ?: n }
    }

    /** Ticks and "J'ai vu" not yet on PRONOTE. */
    fun pendingTotal(): Int = pending().size + pendingReads().size

    fun schoolLife(): SchoolLife? = vault.read(SCHOOL_LIFE)?.let { runCatching { SchoolLife.fromJson(JSONObject(it)) }.getOrNull() }

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
        if (pendingTotal() == 0) return SyncResult.SKIPPED
        account() ?: return SyncResult.SKIPPED
        lock.lock()
        try {
            if (status().needsLogin || System.currentTimeMillis() < prefs.getLong("suspendedUntil", 0)) return SyncResult.SKIPPED
            // Ticks are safe on the phone: without a live session, they wait for the next
            // sync rather than costing a login of their own.
            val logged = liveSession() ?: if (canLogin(manual = true)) login() else return SyncResult.SKIPPED
            return try {
                fetchAndStore(logged)
                onSuccess()
                if (pendingTotal() == 0) SyncResult.DONE else SyncResult.FAILED
            } catch (e: PronoteException) {
                onFailure(e)
                SyncResult.FAILED
            } catch (e: Exception) {
                SyncResult.FAILED
            }
        } catch (e: PronoteException) {
            onFailure(e)
            return SyncResult.FAILED
        } finally {
            lock.unlock()
            Widgets.refreshAll(context)
        }
    }

    /**
     * A homework file, from the phone when it was already opened, else from
     * PRONOTE with the session of a recent sync or a new login. Worker thread.
     */
    fun attachment(key: String, name: String, resolve: (LoggedIn, String) -> String?): Attachments.Result {
        val file = Attachments.file(context, key, name)
        if (file.isFile && file.length() > 0) return Attachments.Result.Ready(file)
        account() ?: return Attachments.Result.Failed("Connecte-toi d'abord à Pronote.")
        lock.lock()
        try {
            if (status().needsLogin) return Attachments.Result.Failed("Pronote demande de te reconnecter : va dans Réglages.")
            val logged = liveSession() ?: run {
                val wait = waitBeforeLogin(manual = true)
                if (wait > 0) return Attachments.Result.Wait(wait)
                login()
            }
            val url = resolve(logged, name)
                ?: return Attachments.Result.Failed("Ce fichier n'est plus sur Pronote.")
            onSuccess()
            val (bytes, _) = UrlConnectionHttp(readTimeoutMs = 60_000).download(url, MAX_FILE_BYTES)
                ?: return Attachments.Result.Failed("Pronote n'a pas envoyé le fichier (trop gros ou indisponible).")
            file.parentFile?.mkdirs()
            val part = File(file.path + ".part")
            part.writeBytes(bytes)
            if (!part.renameTo(file)) return Attachments.Result.Failed("Impossible d'enregistrer le fichier.")
            return Attachments.Result.Ready(file)
        } catch (e: PronoteException) {
            onFailure(e)
            return Attachments.Result.Failed(explain(e))
        } catch (e: Exception) {
            return Attachments.Result.Failed("Le téléchargement n'a pas marché, réessaie.")
        } finally {
            lock.unlock()
        }
    }

    // ---- Being polite with PRONOTE ---------------------------------------------------
    //
    // PRONOTE suspends, for a while, IP addresses that log in too often. A whole
    // class on the same school or home network shares one address, so every
    // login counts: sessions are reused for a few minutes, logins are spaced out,
    // and failures back off exponentially.

    private var live: LoggedIn? = null
    private var liveSince = 0L

    /** The session of a login made less than [SESSION_REUSE_MS] ago, if any. */
    private fun liveSession(): LoggedIn? =
        live?.takeIf { System.currentTimeMillis() - liveSince < SESSION_REUSE_MS }

    private fun canLogin(manual: Boolean, openGap: Boolean = false): Boolean = waitBeforeLogin(manual, openGap) == 0L

    /** Milliseconds before a login is allowed (0 = now). */
    fun waitBeforeLogin(manual: Boolean, openGap: Boolean = false): Long {
        val now = System.currentTimeMillis()
        val suspended = prefs.getLong("suspendedUntil", 0)
        if (now < suspended) return suspended - now
        val fails = prefs.getInt("fails", 0)
        val base = when {
            manual -> MANUAL_GAP_MS
            openGap -> OPEN_GAP_MS
            else -> BACKGROUND_GAP_MS
        }
        val gap = (base shl fails.coerceAtMost(3)).coerceAtMost(MAX_BACKOFF_MS)
        return (prefs.getLong("lastLogin", 0) + gap - now).coerceAtLeast(0)
    }

    private fun login(): LoggedIn {
        val account = account() ?: throw PronoteException.BadCredentials()
        prefs.edit().putLong("lastLogin", System.currentTimeMillis()).putLong("attempt", System.currentTimeMillis()).apply()
        val logged = Pronote.loginToken(UrlConnectionHttp(), account.credentials)
        // The old token is already dead: store the new one before anything else.
        saveAccount(logged)
        live = logged
        liveSince = System.currentTimeMillis()
        return logged
    }

    private fun onSuccess() {
        prefs.edit().putInt("fails", 0).remove("suspendedUntil").apply()
    }

    private fun onFailure(e: PronoteException) {
        live = null
        val now = System.currentTimeMillis()
        val edit = prefs.edit().putString("error", explain(e)).putInt("fails", prefs.getInt("fails", 0) + 1)
        if (e.needsNewLogin || (e is PronoteException.TokenRefused && prefs.getInt("fails", 0) >= 1)) edit.putBoolean("needsLogin", true)
        if (e is PronoteException.SuspendedIp || e is PronoteException.RateLimited) {
            val until = now + SUSPENSION_COOLDOWN_MS
            edit.putLong("suspendedUntil", until)
            val t = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(until), java.time.ZoneId.systemDefault())
            edit.putString("error", "Pronote bloque ta connexion pour un moment, nouvel essai vers ${Agenda.time(t)}")
        }
        edit.apply()
    }

    /** When PRONOTE lets us try again, or 0. */
    fun suspendedUntil(): Long = prefs.getLong("suspendedUntil", 0).takeIf { it > System.currentTimeMillis() } ?: 0


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
            // A first login gets the welcome celebration; a reconnection does not.
            if (account() == null) context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("welcomePending", true).apply()
            saveAccount(logged)
            prefs.edit().putBoolean("needsLogin", false).remove("error").putInt("fails", 0).remove("suspendedUntil")
                .putLong("attempt", System.currentTimeMillis()).putLong("lastLogin", System.currentTimeMillis()).apply()
            live = logged
            liveSince = System.currentTimeMillis()
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
    /** Opening the app reads PRONOTE again sooner than the background job does. */
    fun syncOnOpen(): SyncResult = sync(manual = false, openGap = true)

    /** PRONOTE, then the canteen if one is connected (each failing on its own). */
    fun sync(manual: Boolean, openGap: Boolean = false): SyncResult =
        syncPronote(manual, openGap).also { runCatching { Canteen.get(context).sync(force = manual) } }

    private fun syncPronote(manual: Boolean, openGap: Boolean): SyncResult {
        account() ?: return SyncResult.SKIPPED
        if (!lock.tryLock()) return SyncResult.SKIPPED
        try {
            if (status().needsLogin) return SyncResult.SKIPPED
            val logged = liveSession() ?: if (canLogin(manual, openGap)) login() else return SyncResult.SKIPPED
            fetchAndStore(logged)
            onSuccess()
            return SyncResult.DONE
        } catch (e: PronoteException) {
            onFailure(e)
            return SyncResult.FAILED
        } catch (e: Exception) {
            live = null
            prefs.edit().putString("error", "Erreur inattendue (${e.javaClass.simpleName})").apply()
            return SyncResult.FAILED
        } finally {
            lock.unlock()
            Widgets.refreshAll(context)
        }
    }

    private fun fetchAndStore(logged: LoggedIn) {
        val today = LocalDate.now()
        val now = java.time.LocalDateTime.now()
        val changes = mutableListOf<Changes.Item>()
        val lessons = Timetable.fetch(logged, today, today.plusDays(FETCH_DAYS))
        runCatching { changes += Changes.lessons(lessons(), lessons, now) }
        vault.write(CACHE, JSONArray(lessons.map { it.toJson() }).toString())
        // Homework and grades are extras: when PRONOTE fails on them, the timetable still counts.
        runCatching { Schoolwork.homework(logged, today, today.plusDays(FETCH_DAYS)) }.onSuccess { list ->
            val synced = pushTicks(logged, list)
            vault.write(HOMEWORK, JSONArray(synced.map { it.toJson() }).toString())
        }
        runCatching { Attachments.prune(context) }
        // Actualités and Messagerie: read-only, contents cached so a sync only asks for new ones.
        pushReads(logged)
        val newsBefore = storedList(NEWS) { News.fromJson(it) }
        runCatching { Inbox.news(logged, newsBefore.orEmpty().associateBy { it.key }) }.map { withSentReads(it) }.onSuccess { list ->
            vault.write(NEWS, JSONArray(list.map { it.toJson() }).toString())
            runCatching { changes += Changes.news(newsBefore, list) }
        }
        val discussionsBefore = storedList(DISCUSSIONS) { Discussion.fromJson(it) }
        runCatching { Inbox.discussions(logged, discussionsBefore.orEmpty().associateBy { it.key }) }.onSuccess { list ->
            vault.write(DISCUSSIONS, JSONArray(list.map { it.toJson() }).toString())
            runCatching { changes += Changes.messages(discussionsBefore, list) }
        }
        runCatching { Schoolwork.report(logged) }.onSuccess { report ->
            val before = report()
            vault.write(GRADES, report.toJson().toString())
            // On the very first download nothing is "new": everything was already on PRONOTE.
            if (before == null) markGradesSeen()
            // A new trimestre starts empty: its grades are compared with nothing, not with the last one's.
            runCatching { changes += Changes.grades(before?.takeIf { it.periodName == report.periodName }?.grades, report.grades) }
        }
        runCatching { Presence.fetch(logged) }.onSuccess { life ->
            val before = schoolLife()
            vault.write(SCHOOL_LIFE, life.toJson().toString())
            runCatching { changes += Changes.schoolLife(before?.events, life.events) }
        }
        runCatching { learnColors() }
        runCatching { Alerts.post(context, changes, now) }
        prefs.edit()
            .putLong("success", System.currentTimeMillis())
            .remove("error")
            .apply()
    }

    /** Forgets everything: token, timetable, sync state, Keystore key. */
    fun logout() {
        lock.lock()
        try {
            runCatching { Canteen.get(context).disconnect() }
            vault.wipe()
            cachedPhoto = null
            runCatching { Attachments.clear(context) }
            runCatching { Reminders.cancel(context) }
            if (Wallpaper.isEnabled(context)) runCatching { Wallpaper.disable(context) }
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
        private const val PHOTO = "photo"
        private const val GRADES = "grades"
        private const val NEWS = "news"
        private const val DISCUSSIONS = "discussions"
        private const val SCHOOL_LIFE = "schoollife"
        private const val FETCH_DAYS = 13L
        private const val MANUAL_GAP_MS = 2 * 60_000L
        private const val MAX_FILE_BYTES = 40 * 1024 * 1024
        private const val SESSION_REUSE_MS = 4 * 60_000L
        private const val OPEN_GAP_MS = 10 * 60_000L
        private const val MAX_BACKOFF_MS = 3 * 3600_000L
        private const val BACKGROUND_GAP_MS = 45 * 60_000L
        private const val SUSPENSION_COOLDOWN_MS = 60 * 60_000L

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
                "Pronote bloque ta connexion pour un moment"
            is PronoteException.SessionExpired -> "Session expirée, nouvel essai plus tard"
            is PronoteException.BadCredentials -> "Connexion expirée : reconnecte-toi"
            is PronoteException.AccessDenied -> "Accès refusé par Pronote : reconnecte-toi"
            is PronoteException.AccountDisabled -> "Compte Pronote désactivé"
            is PronoteException.SecurityRequired -> "Pronote demande une vérification : reconnecte-toi"
            is PronoteException.TokenRefused -> "Pronote refuse la connexion enregistrée, nouvel essai plus tard"
            is PronoteException.Server -> "Pronote : ${e.message}"
            is PronoteException.Protocol -> "Réponse de Pronote inattendue"
        }
    }
}
