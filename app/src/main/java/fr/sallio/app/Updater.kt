package fr.sallio.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates from the project's GitHub releases, without any store.
 *
 * A release carries one asset named `sallio-<versionCode>.apk`. The app
 * only installs it if Android confirms it is the same app, newer, and signed
 * with the same key as the one installed: a tampered or foreign APK is
 * rejected before the student is even asked.
 */
object Updater {

    const val REPO = "Ofanon/sallio"
    /**
     * Checked each time the app opens. The minute only absorbs the resumes that
     * follow a system dialog (permission, share); GitHub allows 60 anonymous
     * calls an hour per address.
     */
    private const val CHECK_EVERY_MS = 60_000L
    private val ASSET = Regex("""^sallio-(\d+)\.apk$""")

    class Update(val versionName: String, val versionCode: Int, val notes: String, val url: String, val size: Long)

    /** Latest release if newer than this build; null otherwise or when GitHub cannot be reached. */
    fun check(context: Context, force: Boolean = false): Update? {
        val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("lastCheck", 0) < CHECK_EVERY_MS) {
            return cached(prefs)
        }
        val release = try {
            JSONObject(get("https://api.github.com/repos/$REPO/releases/latest"))
        } catch (e: Exception) {
            return cached(prefs)
        }
        prefs.edit().putLong("lastCheck", now).apply()

        val assets = release.optJSONArray("assets") ?: return store(prefs, null)
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val code = ASSET.matchEntire(asset.optString("name"))?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (code <= BuildConfig.VERSION_CODE) continue
            val url = asset.optString("browser_download_url")
            if (!url.startsWith("https://github.com/$REPO/releases/download/")) continue
            return store(
                prefs,
                Update(
                    versionName = release.optString("tag_name").removePrefix("v"),
                    versionCode = code,
                    notes = release.optString("body").trim(),
                    url = url,
                    size = asset.optLong("size"),
                ),
            )
        }
        return store(prefs, null)
    }

    private fun store(prefs: android.content.SharedPreferences, update: Update?): Update? {
        val edit = prefs.edit()
        if (update == null) edit.remove("update") else edit.putString(
            "update",
            JSONObject().put("name", update.versionName).put("code", update.versionCode)
                .put("notes", update.notes).put("url", update.url).put("size", update.size).toString(),
        )
        edit.apply()
        return update
    }

    private fun cached(prefs: android.content.SharedPreferences): Update? {
        val o = prefs.getString("update", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val code = o.optInt("code")
        if (code <= BuildConfig.VERSION_CODE) return null
        return Update(o.optString("name"), code, o.optString("notes"), o.optString("url"), o.optLong("size"))
    }

    /** Whether Android lets this app install packages (a one-time switch in the settings). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettings(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(android.net.Uri.parse("package:${context.packageName}"))

    /** Worker thread. Downloads, checks the APK, then hands it to Android's installer. */
    fun downloadAndInstall(context: Context, update: Update, progress: (Int) -> Unit) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val apk = File(dir, "sallio.apk")
        download(update.url, apk, update.size, progress)
        verify(context, apk, update.versionCode)
        install(context, apk)
    }

    private fun verify(context: Context, apk: File, expectedCode: Int) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: throw IOException("Fichier de mise à jour illisible")
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (archive.packageName != context.packageName) throw IOException("Ce n'est pas Sallio")
        @Suppress("DEPRECATION")
        val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        if (archiveCode != expectedCode.toLong()) throw IOException("Version inattendue")
        if (signers(archive) != signers(installed) || signers(installed).isEmpty()) {
            throw IOException("Signature différente : mise à jour refusée")
        }
    }

    private fun signers(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION") info.signatures
        } ?: return emptySet()
        return signatures.map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("sallio.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                context, 3,
                Intent(context, UpdateReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(callback.intentSender)
        }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "sallio")
            if (c.responseCode != 200) throw IOException("GitHub ${c.responseCode}")
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    /** GitHub redirects assets to its CDN; HttpURLConnection only follows https -> https. */
    private fun download(url: String, target: File, expectedSize: Long, progress: (Int) -> Unit) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = true
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "sallio")
            if (c.responseCode != 200 || c.url.protocol != "https") throw IOException("Téléchargement refusé (${c.responseCode})")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: expectedSize
            c.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) progress((done * 100 / total).toInt())
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }
}

/** Android's installer reports here; the first answer is "ask the user to confirm". */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> File(context.cacheDir, "updates").deleteRecursively()
            else -> {
                File(context.cacheDir, "updates").deleteRecursively()
                android.widget.Toast.makeText(
                    context,
                    "Mise à jour annulée : " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "erreur"),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}
