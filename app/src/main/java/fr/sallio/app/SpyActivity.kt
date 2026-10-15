package fr.sallio.app

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import fr.sallio.pronote.Pronote

/**
 * Debug builds only: the student's PRONOTE in a WebView, to see what its own
 * page sends for the actualités (read, acknowledge, survey answers). PRONOTE
 * encrypts each request; JSON.stringify runs before that, so a wrapper logs
 * the plain text of those containing an actualité, in files/debug.log. The
 * student logs in themselves, as in a browser; cookies and cache are wiped on
 * the way out. Nothing else is logged.
 */
class SpyActivity : Activity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) return finish()
        val base = Pronote.cleanUrl(intent.getStringExtra(EXTRA_URL) ?: return finish())
        val root = UI.column(this).apply { setBackgroundColor(getColor(R.color.bg)) }
        root.addView(UI.row(this).apply {
            setPadding(dp(16), dp(40), dp(16), dp(10))
            addView(UI.text(context, "Espion Pronote (debug) : ouvre une actualité, puis flèche retour, ou réponds au sondage.", 13f, R.color.text2), UI.weight())
            addView(UI.ghostButton(context, "Fermer") { finish() })
        }, UI.matchWidth())
        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            userAgentString = userAgentString.replace("; wv", "")
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                val text = m.message()
                if (text.startsWith(TAG)) DebugLog.i("spy " + text.removePrefix(TAG).take(4000))
                return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.url.scheme?.lowercase() != "https"
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { view.evaluateJavascript(HOOK, null) }
            override fun onPageFinished(view: WebView, url: String) { view.evaluateJavascript(HOOK, null) }
        }
        DebugLog.i("spy: opened ${intent.getStringExtra(EXTRA_PAGE) ?: "eleve.html"}")
        web.loadUrl("$base/${intent.getStringExtra(EXTRA_PAGE) ?: "eleve.html"}")
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            web.clearCache(true)
            web.destroy()
        }
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_PAGE = "page"
        private const val TAG = "SALLIO_SPY "

        /** Wraps JSON.stringify once; logs what concerns the actualités, before PRONOTE encrypts it. */
        private val HOOK = """
            (function () {
              if (window.__sallioSpy) return;
              window.__sallioSpy = true;
              var original = JSON.stringify;
              JSON.stringify = function () {
                var out = original.apply(JSON, arguments);
                try {
                  if (typeof out === 'string' && /listeActualites|listeQuestions|SaisieActualites|actualite/i.test(out) && out.length < 20000)
                    console.log('$TAG' + out);
                } catch (e) {}
                return out;
              };
            })();
        """.trimIndent()
    }
}
