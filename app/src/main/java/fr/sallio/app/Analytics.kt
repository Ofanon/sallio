package fr.sallio.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Anonymous usage statistics for the test phase, only with the student's
 * consent (asked once, changeable in the settings). Sent to PostHog's EU
 * cloud (Frankfurt) through its public HTTP API, no SDK.
 *
 * What leaves the phone: event names ("onglet", "devoir_coche"…), a few
 * fixed words or numbers describing them, the app and Android versions, and
 * a random id drawn at each launch and never stored. Never a name, class,
 * school, lesson, grade, homework, message, canteen balance, error message or
 * stack text that could hold any of those: errors travel as their type and
 * the code lines they went through.
 *
 * Builds without `posthog.key` in local.properties send nothing and never ask.
 */
object Analytics {

    private const val ENDPOINT = "https://eu.i.posthog.com/batch/"
    /** Events wait this long to leave together (one request, less battery). */
    private const val DELAY_S = 20L
    private const val BATCH = 25
    /** Offline, at most this many wait in memory. */
    private const val KEEP_MAX = 200

    /** Drawn at each launch, kept nowhere: two launches cannot be linked. */
    private val launch = UUID.randomUUID().toString()
    private val queue = ArrayList<JSONObject>()
    private var flushPlanned = false
    private val sender = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "analytics").apply { isDaemon = true } }
    @Volatile private var prefs: SharedPreferences? = null

    /** This build was given a key: the question can be asked. */
    fun available() = BuildConfig.POSTHOG_KEY.isNotEmpty()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("analytics", Context.MODE_PRIVATE)
        prefs = p
        installCrashHandler()
        // A crash ends the process before anything can be sent: it leaves with the next launch.
        p.getString("crash", null)?.let { saved ->
            p.edit().remove("crash").apply()
            runCatching { JSONObject(saved) }.getOrNull()?.let { send("plantage", it) }
        }
        val last = p.getInt("version", 0)
        if (last != BuildConfig.VERSION_CODE) {
            p.edit().putInt("version", BuildConfig.VERSION_CODE).apply()
            if (last != 0) event("mise_a_jour_installee", "depuis" to last)
        }
    }

    fun shouldAsk() = available() && prefs?.getBoolean("asked", false) == false
    fun enabled() = available() && prefs?.getBoolean("enabled", false) == true

    fun setEnabled(on: Boolean) {
        prefs?.edit()?.putBoolean("asked", true)?.putBoolean("enabled", on)?.apply()
        if (!on) synchronized(queue) { queue.clear() }
    }

    /** One event, with plain words or numbers only (see the class comment). */
    fun event(name: String, vararg props: Pair<String, Any?>) {
        if (!enabled()) return
        send(name, JSONObject().apply { for ((k, v) in props) put(k, v ?: JSONObject.NULL) })
    }

    /** Something went wrong in [where]: its type only, never its message. */
    fun error(where: String, e: Throwable) {
        remember("$where: ${e.javaClass.simpleName}")
        event("erreur", "ou" to where, "type" to e.javaClass.simpleName)
    }

    /** Leaving the app: what waits goes now. */
    fun flushSoon() {
        if (enabled()) sender.execute { flush() }
    }

    private fun send(name: String, props: JSONObject) {
        if (!enabled()) return
        props.put("distinct_id", launch)
            // No person profile, no country or city guessed from the address.
            .put("\$process_person_profile", false)
            .put("\$geoip_disable", true)
            .put("\$lib", "sallio")
            .put("version", BuildConfig.VERSION_NAME)
            .put("build", if (BuildConfig.DEBUG) "debug" else "release")
            .put("android", Build.VERSION.SDK_INT)
        val e = JSONObject().put("event", name).put("properties", props).put("timestamp", Instant.now().toString())
        DebugLog.i("analytics $name")
        synchronized(queue) {
            queue += e
            if (queue.size >= BATCH) sender.execute { flush() }
            else if (!flushPlanned) {
                flushPlanned = true
                sender.schedule(Runnable { flush() }, DELAY_S, TimeUnit.SECONDS)
            }
        }
    }

    private fun flush() {
        val batch = synchronized(queue) {
            flushPlanned = false
            queue.toList().also { queue.clear() }
        }
        if (batch.isEmpty() || !enabled()) return
        if (!post(batch)) synchronized(queue) {
            // Offline: they wait for the next event's send, the oldest dropped past the limit.
            queue.addAll(0, batch)
            while (queue.size > KEEP_MAX) queue.removeAt(0)
        }
    }

    private fun post(batch: List<JSONObject>): Boolean = try {
        val body = JSONObject().put("api_key", BuildConfig.POSTHOG_KEY).put("batch", JSONArray(batch)).toString().toByteArray()
        val c = URL(ENDPOINT).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body) }
        val code = c.responseCode
        c.disconnect()
        DebugLog.i("analytics: ${batch.size} sent, HTTP $code")
        code in 200..299
    } catch (e: Exception) {
        DebugLog.i("analytics: not sent", e)
        false
    }

    // ---- Crashes ------------------------------------------------------------------

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val crash = describe(e)
                // Kept for the test report in any case; sent only with consent.
                prefs?.edit()?.apply {
                    putString("lastCrash", "${Instant.now()} ${crash.getString("type")}\n${crash.getString("trace")}")
                    if (enabled()) putString("crash", crash.toString())
                }?.commit()
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** The exception's type, its root cause's type, and the app's own lines it went through. */
    internal fun describe(e: Throwable): JSONObject {
        var root = e
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val frames = (e.stackTrace.take(1) + root.stackTrace.filter { it.className.startsWith("fr.sallio") })
            .distinct().take(8)
            .joinToString("\n") { "${it.className.removePrefix("fr.sallio.")}.${it.methodName}:${it.lineNumber}" }
        return JSONObject()
            .put("type", e.javaClass.name)
            .put("cause", root.javaClass.name)
            .put("trace", frames)
    }

    // ---- Test report --------------------------------------------------------------

    /** The last errors, on the phone only, for the report the student chooses to send. */
    private fun remember(line: String) {
        val p = prefs ?: return
        val list = (p.getString("recent", "") ?: "").lines().filter { it.isNotBlank() }.takeLast(14) + "${Instant.now().toString().take(19)} $line"
        p.edit().putString("recent", list.joinToString("\n")).apply()
    }

    fun recentErrors(): String = prefs?.getString("recent", null).orEmpty()
    fun lastCrash(): String? = prefs?.getString("lastCrash", null)
}
