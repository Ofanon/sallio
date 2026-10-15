package fr.sallio.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import fr.sallio.pronote.Credentials
import fr.sallio.pronote.LoggedIn
import fr.sallio.pronote.Pronote
import fr.sallio.pronote.PronoteException
import fr.sallio.pronote.SecurityChallenge
import fr.sallio.pronote.UrlConnectionHttp
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Logs in the way the official PRONOTE app does: the student signs in on the
 * real pages (their ENT, monlycee.net, EduConnect, or PRONOTE itself) inside a
 * WebView, and PRONOTE then hands this device a token in `window.loginState`.
 *
 * Since it is a real browser, no portal can tell it apart from Chrome, and the
 * app never sees the password: only PRONOTE's device token.
 */
class LoginActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var overlay: View
    private lateinit var overlayText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var base: String
    private lateinit var pronoteHost: String
    private val deviceUuid = UUID.randomUUID().toString()
    private var received = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        base = Pronote.cleanUrl(intent.getStringExtra(EXTRA_URL) ?: return finish())
        pronoteHost = URI(base).host.lowercase()
        val school = intent.getStringExtra(EXTRA_SCHOOL).orEmpty()

        forgetBrowsing()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.fitsSystemWindowsCompat()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(8), dp(10))
        }
        bar.addView(
            TextView(this).apply {
                text = if (school.isNotEmpty()) "Connexion · ${school}" else "Connexion à Pronote"
                textSize = 16f
                setTextColor(getColor(R.color.text))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        bar.addView(UI.ghostButton(this, "Annuler") { finish() })
        root.addView(bar)

        val frame = FrameLayout(this)
        web = WebView(this)
        frame.addView(web, FrameLayout.LayoutParams(-1, -1))
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(getColor(R.color.bg))
            setPadding(dp(32), dp(32), dp(32), dp(32))
            addView(ProgressBar(context))
            overlayText = TextView(context).apply {
                text = "Ouverture de la page de connexion…"
                gravity = Gravity.CENTER
                textSize = 15f
                setTextColor(getColor(R.color.text2))
                setPadding(0, dp(16), 0, 0)
            }
            addView(overlayText)
        }
        frame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Without the "wv" marker some identity providers refuse to show their form.
            userAgentString = userAgentString.replace("; wv", "")
        }
        web.webViewClient = Client()
        web.loadUrl("$base/InfoMobileApp.json?id=$INFO_MOBILE_ID")
        handler.postDelayed(poll, 1000)
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Stay on the web, over HTTPS: no intent://, no file://, no plain http.
            return request.url.scheme?.lowercase() != "https"
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            pageReady = false
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (url.contains("InfoMobileApp.json", ignoreCase = true)) {
                // Tells PRONOTE we are its mobile app, then goes to the mobile login page,
                // which bounces to the school's ENT when there is one.
                view.evaluateJavascript(infoMobileScript(), null)
                return
            }
            overlay.visibility = View.GONE
            pageReady = true
            calledHook = false
        }
    }

    @Volatile private var pageReady = false
    private var calledHook = false

    private fun infoMobileScript() = """
        (function () {
          try {
            var json = JSON.parse(document.body.innerText);
            var soon = new Date(Date.now() + 5 * 60 * 1000).toUTCString();
            var year = new Date(Date.now() + 365 * 24 * 3600 * 1000).toUTCString();
            var cas = json && json.CAS && json.CAS.jetonCAS;
            if (cas) {
              document.cookie = "appliMobile=; expires=" + new Date(0).toUTCString();
              document.cookie = "validationAppliMobile=" + cas + "; expires=" + soon;
              document.cookie = "uuidAppliMobile=$deviceUuid; expires=" + soon;
            } else {
              document.cookie = "appliMobile=1; expires=" + soon;
            }
            document.cookie = "ielang=1036; expires=" + year;
          } catch (e) {}
          window.location.assign("$base/mobile.${fr.sallio.pronote.AccountKinds.path(6)}.html?fd=1");
        })();
    """.trimIndent()

    private fun stateScript(callHook: Boolean) = """
        (function () {
          try {
            window.hookAccesDepuisAppli = function () {
              this.passerEnModeValidationAppliMobile('', '$deviceUuid');
            };
            if ($callHook && window.GInterface) {
              try {
                window.GInterface.passerEnModeValidationAppliMobile('', '$deviceUuid', '', '', '{"model":"random","platform":"android"}');
              } catch (e) {}
            }
            var s = window.loginState;
            return s ? JSON.stringify(s) : null;
          } catch (e) { return null; }
        })();
    """.trimIndent()

    /** Reads `window.loginState` every second, only on the school's PRONOTE host. */
    private val poll = object : Runnable {
        override fun run() {
            if (received || isFinishing) return
            val host = web.url?.let { runCatching { URI(it).host?.lowercase() }.getOrNull() }
            if (pageReady && host == pronoteHost) {
                val callHook = !calledHook
                calledHook = true
                web.evaluateJavascript(stateScript(callHook)) { result -> onState(result) }
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun onState(raw: String?) {
        if (received || raw == null || raw == "null") return
        val text = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: return
        val state = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (state.optInt("status", -1) != 0) return
        val login = state.optString("login")
        val token = state.optString("mdp")
        if (login.isEmpty() || token.isEmpty()) return

        received = true
        showOverlay("Connexion réussie, récupération de ton emploi du temps…")
        val credentials = Credentials(url = base, username = login, token = token, deviceUuid = deviceUuid)
        worker.execute {
            try {
                complete(Pronote.loginToken(UrlConnectionHttp(), credentials))
            } catch (e: PronoteException.SecurityRequired) {
                runOnUiThread { askSecurity(e.challenge) }
            } catch (e: Exception) {
                runOnUiThread { fail(e) }
            }
        }
    }

    private fun askSecurity(challenge: SecurityChallenge) {
        when {
            challenge.needsWebsiteSetup -> AlertDialog.Builder(this)
                .setTitle("Pronote demande une configuration")
                .setMessage(
                    "Ton compte doit d'abord choisir un mot de passe ou une méthode de sécurité. " +
                        "Fais-le une fois sur le site Pronote, puis reviens ici."
                )
                .setPositiveButton("OK") { _, _ -> finish() }
                .setCancelable(false)
                .show()

            challenge.needsPin -> {
                val input = EditText(this).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    hint = "Code PIN"
                }
                val box = FrameLayout(this).apply {
                    setPadding(dp(24), dp(8), dp(24), 0)
                    addView(input)
                }
                AlertDialog.Builder(this)
                    .setTitle("Code PIN Pronote")
                    .setMessage("Ton compte est protégé par un code PIN. Entre-le pour autoriser ce téléphone.")
                    .setView(box)
                    .setPositiveButton("Valider") { _, _ -> solve(challenge, input.text.toString()) }
                    .setNegativeButton("Annuler") { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            }

            else -> solve(challenge, null)
        }
    }

    private fun solve(challenge: SecurityChallenge, pin: String?) {
        showOverlay("Enregistrement de ce téléphone auprès de Pronote…")
        val name = ("Sallio " + Build.MODEL).take(30)
        worker.execute {
            try {
                complete(Pronote.solveSecurity(challenge, name, pin))
            } catch (e: Exception) {
                runOnUiThread { fail(e) }
            }
        }
    }

    /** Worker thread. */
    private fun complete(logged: LoggedIn) {
        // The account is saved first; if PRONOTE fails on the timetable right after,
        // the background sync fetches it later, so the login still counts.
        val first = Repo.get(applicationContext).account() == null
        runCatching { Repo.get(applicationContext).onLoggedIn(logged) }
        SyncJob.schedulePeriodic(applicationContext)
        runOnUiThread {
            // A first login gets the welcome, which vibrates once: not twice in a row.
            if (!first) Motion.success(window.decorView)
            forgetBrowsing()
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun fail(e: Exception) {
        Motion.failure(window.decorView)
        val message = (e as? PronoteException)?.let { Repo.explain(it) } ?: "Erreur inattendue"
        AlertDialog.Builder(this)
            .setTitle("Connexion impossible")
            .setMessage(message)
            .setPositiveButton("Réessayer") { _, _ -> recreate() }
            .setNegativeButton("Fermer") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun showOverlay(text: String) {
        overlayText.text = text
        overlay.visibility = View.VISIBLE
    }

    /** No ENT cookie, cache or form data outlives the login. */
    private fun forgetBrowsing() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        if (::web.isInitialized) {
            web.clearCache(true)
            web.clearHistory()
            web.clearFormData()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::web.isInitialized) {
            forgetBrowsing()
            web.destroy()
        }
        worker.shutdown()
        super.onDestroy()
    }

    @Deprecated("Activity API")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_SCHOOL = "school"
        /** Fixed id the official app sends to `InfoMobileApp.json`. */
        const val INFO_MOBILE_ID = "0D264427-EEFC-4810-A9E9-346942A862A4"
    }
}
