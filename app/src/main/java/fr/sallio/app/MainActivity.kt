package fr.sallio.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import fr.sallio.pronote.PronoteException
import fr.sallio.pronote.Schools
import fr.sallio.pronote.UrlConnectionHttp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    enum class Tab(val label: String, val icon: Int) {
        HOME("Accueil", R.drawable.ic_home),
        TIMETABLE("Cours", R.drawable.ic_calendar),
        HOMEWORK("Devoirs", R.drawable.ic_tasks),
        GRADES("Notes", R.drawable.ic_star),
    }

    private lateinit var root: FrameLayout
    private val worker = Executors.newSingleThreadExecutor()
    private val repo by lazy { Repo.get(applicationContext) }
    private val tabs by lazy { Tabs(this, repo) }

    // Screen state
    private var tab = Tab.HOME
    private var selectedDay: LocalDate? = null
    private var gradesBySubject = false
    internal val expanded = mutableSetOf<String>()
    private var scroll: ScrollView? = null
    private var syncing = false
    private var update: Updater.Update? = null
    private var topInset = 0
    private var bottomInset = 0
    private var updateStatus: String? = null

    // Setup screen state
    private var schoolMessage: android.widget.TextView? = null
    private var results: LinearLayout? = null
    private var nearby: List<Schools.Nearby>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.bg)) }
        // Insets are read once here and applied by render(), which rebuilds every view.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val (t, b) = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime()).let { it.top to it.bottom }
            } else {
                @Suppress("DEPRECATION") (insets.systemWindowInsetTop to insets.systemWindowInsetBottom)
            }
            if (t != topInset || b != bottomInset) {
                topInset = t
                bottomInset = b
                render()
            }
            insets
        }
        setContentView(root)
        savedInstanceState?.let { state ->
            tab = runCatching { Tab.valueOf(state.getString("tab") ?: "") }.getOrDefault(Tab.HOME)
            selectedDay = state.getString("day")?.let { LocalDate.parse(it) }
            gradesBySubject = state.getBoolean("bySubject")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("tab", tab.name)
        selectedDay?.let { outState.putString("day", it.toString()) }
        outState.putBoolean("bySubject", gradesBySubject)
    }

    override fun onResume() {
        super.onResume()
        render()
        if (repo.account() != null) refresh(manual = false)
        worker.execute {
            val found = Updater.check(applicationContext)
            runOnUiThread {
                if (found?.versionCode != update?.versionCode) {
                    update = found
                    render()
                }
            }
        }
    }

    override fun onPause() {
        if (tab == Tab.GRADES) repo.markGradesSeen()
        super.onPause()
    }

    fun render() {
        val keepScroll = scroll?.scrollY ?: 0
        root.removeAllViews()
        val account = repo.account()
        if (account == null) {
            renderSetup()
            return
        }
        val s = Snapshot(
            account = account,
            lessons = repo.lessons(),
            homework = repo.homework(),
            report = repo.report(),
            status = repo.status(),
            unseenGrades = repo.unseenGrades(),
            now = LocalDateTime.now(),
        )

        val column = UI.column(this)
        val content = UI.column(this).apply { setPadding(dp(18), topInset + dp(8), dp(18), dp(28)) }
        content.addView(header(s), UI.matchWidth())
        update?.let { content.addView(updateCard(it), UI.matchWidth().apply { topMargin = dp(14) }) }
        if (s.status.needsLogin) content.addView(reconnectCard(s), UI.matchWidth().apply { topMargin = dp(14) })
        content.addView(UI.gap(this, 14))
        content.addView(
            when (tab) {
                Tab.HOME -> tabs.home(s)
                Tab.TIMETABLE -> tabs.timetable(s, selectedDay) { selectedDay = it; render() }
                Tab.HOMEWORK -> tabs.homework(s)
                Tab.GRADES -> tabs.grades(s, gradesBySubject) { gradesBySubject = it; render() }
            },
            UI.matchWidth(),
        )

        val sv = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        scroll = sv
        column.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        column.addView(bottomNav(s), UI.matchWidth())
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        sv.post { sv.scrollTo(0, keepScroll) }
    }

    fun openTab(target: Tab) {
        if (tab == Tab.GRADES && target != Tab.GRADES) repo.markGradesSeen()
        if (tab != target) scroll?.scrollTo(0, 0)
        tab = target
        scroll = null
        render()
    }

    fun openPronote() {
        val url = repo.account()?.credentials?.url ?: return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$url/eleve.html"))) }
    }

    // ---- Shell -------------------------------------------------------------------

    private fun header(s: Snapshot): View {
        val row = UI.row(this).apply { setPadding(dp(4), dp(12), 0, 0) }
        val col = UI.column(this)
        val first = firstName(s.account.studentName)
        col.addView(UI.oneLine(UI.text(this, if (first.isNotEmpty()) "Salut $first" else "Salut", 28f, weight = UI.BOLD)))
        val today = s.now.toLocalDate()
        val date = "${Agenda.dayName(today)} ${today.dayOfMonth} ${today.month.getDisplayName(TextStyle.FULL, Locale.FRANCE)}"
        val (status, color) = when {
            syncing -> "Mise à jour…" to R.color.text2
            s.status.needsLogin -> "Connexion à refaire" to R.color.alert
            s.status.error != null -> "⚠ ${s.status.error}" to R.color.warn
            else -> "à jour ${Agenda.ago(s.status.lastSuccess, s.now)}" to R.color.text2
        }
        col.addView(UI.oneLine(UI.text(this, "$date · $status", 14f, color)))
        row.addView(col, UI.weight())
        if (syncing) {
            row.addView(ProgressBar(this), LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(8) })
        } else {
            row.addView(UI.roundIcon(this, R.drawable.ic_refresh) { refresh(manual = true) })
        }
        row.addView(UI.hgap(this, 8))
        val avatar = UI.text(this, first.take(1).uppercase().ifEmpty { "?" }, 17f, R.color.on_accent_container, UI.BOLD).apply {
            gravity = Gravity.CENTER
            background = UI.pressable(context, getColor(R.color.accent_container), 22)
            setOnClickListener { settingsSheet(s) }
        }
        row.addView(avatar, LinearLayout.LayoutParams(dp(44), dp(44)))
        return row
    }

    private fun bottomNav(s: Snapshot): View {
        val nav = UI.row(this).apply {
            setBackgroundColor(getColor(R.color.surface))
            setPadding(dp(6), dp(10), dp(6), dp(10) + bottomInset)
        }
        for (t in Tab.values()) {
            val selected = t == tab
            val item = UI.column(this).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setOnClickListener { openTab(t) }
            }
            val pill = FrameLayout(this).apply {
                if (selected) background = UI.rounded(getColor(R.color.accent_container), dp(16).toFloat())
            }
            pill.addView(UI.icon(this, t.icon, if (selected) R.color.on_accent_container else R.color.text2, 22).apply {
                layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
            })
            val showDot = t == Tab.GRADES && s.unseenGrades.isNotEmpty() && !selected
            if (showDot) pill.addView(View(this).apply {
                background = UI.rounded(getColor(R.color.alert), dp(4).toFloat())
            }, FrameLayout.LayoutParams(dp(8), dp(8), Gravity.TOP or Gravity.END).apply { topMargin = dp(4); marginEnd = dp(14) })
            item.addView(pill, LinearLayout.LayoutParams(dp(60), dp(32)))
            item.addView(UI.text(this, t.label, 12f, if (selected) R.color.text else R.color.text2, if (selected) UI.MEDIUM else null).apply {
                setPadding(0, dp(4), 0, 0)
                gravity = Gravity.CENTER
            }, UI.matchWidth())
            nav.addView(item, UI.weight())
        }
        return nav
    }

    private fun reconnectCard(s: Snapshot): View {
        val card = UI.card(this, R.color.alert_bg)
        card.addView(UI.text(this, "Ta connexion Pronote a expiré", 16f, R.color.alert, UI.MEDIUM))
        card.addView(UI.text(this, "Tes infos restent affichées, mais elles ne se mettent plus à jour.", 14f, R.color.text2).apply { setPadding(0, dp(4), 0, dp(12)) })
        card.addView(UI.button(this, "Me reconnecter") {
            startActivity(
                Intent(this, LoginActivity::class.java)
                    .putExtra(LoginActivity.EXTRA_URL, s.account.credentials.url)
                    .putExtra(LoginActivity.EXTRA_SCHOOL, Schools.prettySchool(s.account.schoolName))
            )
        }, UI.matchWidth())
        return card
    }

    private fun settingsSheet(s: Snapshot) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val sheet = UI.column(this).apply {
            background = UI.rounded(getColor(R.color.bg), dp(28).toFloat())
            setPadding(dp(20), dp(12), dp(20), dp(20))
        }
        sheet.addView(View(this).apply { background = UI.rounded(getColor(R.color.text3), dp(2).toFloat()) },
            LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(16) })

        val who = UI.row(this)
        who.addView(UI.text(this, firstName(s.account.studentName).take(1).uppercase(), 20f, R.color.on_accent_container, UI.BOLD).apply {
            gravity = Gravity.CENTER
            background = UI.rounded(getColor(R.color.accent_container), dp(26).toFloat())
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        who.addView(UI.hgap(this, 14))
        val info = UI.column(this)
        info.addView(UI.text(this, prettyStudent(s.account.studentName), 18f, weight = UI.BOLD))
        info.addView(UI.text(this, listOfNotNull(s.account.className, Schools.prettySchool(s.account.schoolName).ifEmpty { null }).joinToString(" · "), 13f, R.color.text2))
        who.addView(info, UI.weight())
        sheet.addView(who, UI.matchWidth())
        sheet.addView(UI.gap(this, 16))

        val list = UI.card(this, padding = 4)
        fun item(icon: Int, label: String, color: Int = R.color.text, action: () -> Unit) {
            list.addView(UI.row(this).apply {
                background = UI.pressable(context, getColor(R.color.surface), 18)
                setPadding(dp(14), dp(14), dp(14), dp(14))
                addView(UI.icon(context, icon, if (color == R.color.text) R.color.text2 else color, 22))
                addView(UI.hgap(context, 14))
                addView(UI.text(context, label, 15f, color), UI.weight())
                setOnClickListener { dialog.dismiss(); action() }
            }, UI.matchWidth())
        }
        item(R.drawable.ic_open, "Ouvrir Pronote") { openPronote() }
        val widgets = getSystemService(AppWidgetManager::class.java)
        if (widgets.isRequestPinAppWidgetSupported) {
            item(R.drawable.ic_widgets, "Ajouter le widget « Prochaine salle »") {
                widgets.requestPinAppWidget(ComponentName(this, NextWidget::class.java), null, null)
            }
            item(R.drawable.ic_widgets, "Ajouter le widget « Ma journée »") {
                widgets.requestPinAppWidget(ComponentName(this, DayWidget::class.java), null, null)
            }
        }
        item(R.drawable.ic_shield, "Tes données") { privacyDialog() }
        item(R.drawable.ic_logout, "Se déconnecter et tout effacer", R.color.alert) { confirmLogout() }
        sheet.addView(list, UI.matchWidth())

        sheet.addView(UI.text(this, "Sallio ${BuildConfig.VERSION_NAME} · code source sur GitHub", 12f, R.color.text3).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
            setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${Updater.REPO}"))) } }
        }, UI.matchWidth())

        dialog.setContentView(sheet)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            attributes = attributes.apply { y = dp(8) }
        }
        dialog.show()
    }

    private fun privacyDialog() {
        AlertDialog.Builder(this)
            .setTitle("Tes données restent chez toi")
            .setMessage(PRIVACY.joinToString("\n\n") { "• $it" })
            .setPositiveButton("OK", null)
            .show()
    }

    // ---- Setup -------------------------------------------------------------------

    private fun renderSetup() {
        val content = UI.column(this).apply { setPadding(dp(22), topInset + dp(36), dp(22), bottomInset + dp(28)) }

        val badge = FrameLayout(this).apply { background = UI.rounded(getColor(R.color.accent), dp(30).toFloat()) }
        badge.addView(android.widget.ImageView(this).apply { setImageResource(R.drawable.ic_launcher_fg) }, FrameLayout.LayoutParams(-1, -1))
        content.addView(badge, LinearLayout.LayoutParams(dp(84), dp(84)))
        content.addView(UI.gap(this, 20))
        content.addView(UI.text(this, "Sallio", 40f, weight = UI.BOLD))
        content.addView(UI.text(this, "Ta salle, tes profs absents, tes devoirs et tes notes. Même quand Pronote rame.", 18f, R.color.text2).apply {
            setPadding(0, dp(6), 0, 0)
            setLineSpacing(0f, 1.1f)
        })
        content.addView(UI.gap(this, 28))

        val card = UI.card(this, padding = 20)
        card.addView(UI.text(this, "Trouve ton lycée", 18f, weight = UI.BOLD))
        card.addView(UI.gap(this, 14))
        card.addView(UI.row(this).apply {
            gravity = Gravity.CENTER
            background = UI.pressable(context, getColor(R.color.accent), 16)
            setPadding(dp(18), dp(15), dp(18), dp(15))
            addView(UI.icon(context, R.drawable.ic_place, R.color.on_accent, 20))
            addView(UI.hgap(context, 8))
            addView(UI.text(context, "Autour de moi", 15f, R.color.on_accent, UI.MEDIUM))
            setOnClickListener { findNearby() }
        }, UI.matchWidth())
        card.addView(UI.text(this, "ou tape son nom après une recherche, son code (ex. 0782924E) ou l'adresse de son Pronote :", 13f, R.color.text2).apply { setPadding(0, dp(16), 0, dp(8)) })
        val field = EditText(this).apply {
            hint = "Nom, code ou adresse"
            setSingleLine()
            inputType = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_GO
            background = UI.rounded(getColor(R.color.surface2), dp(14).toFloat())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            textSize = 15f
            setTextColor(getColor(R.color.text))
            setHintTextColor(getColor(R.color.text3))
        }
        val go = { checkSchool(field.text.toString(), null) }
        field.setOnEditorActionListener { _, _, _ -> go(); true }
        card.addView(field, UI.matchWidth())
        card.addView(UI.gap(this, 10))
        card.addView(UI.ghostButton(this, "Continuer") { go() }, UI.matchWidth())
        schoolMessage = UI.text(this, "", 13f, R.color.alert).apply { visibility = View.GONE; setPadding(0, dp(12), 0, 0) }
        card.addView(schoolMessage)
        results = UI.column(this)
        card.addView(results, UI.matchWidth())
        nearby?.let { showResults(it) }
        content.addView(card, UI.matchWidth())

        content.addView(UI.gap(this, 22))
        for ((icon, line) in listOf(
            R.drawable.ic_shield to "Tes données restent sur ton téléphone. Aucun serveur, aucune pub.",
            R.drawable.ic_widgets to "Des widgets qui marchent même quand Pronote est saturé.",
            R.drawable.ic_open to "Tu te connectes sur la vraie page de ton ENT : l'app ne voit jamais ton mot de passe.",
        )) {
            content.addView(UI.row(this).apply {
                gravity = Gravity.TOP
                setPadding(dp(4), dp(8), dp(4), dp(8))
                addView(UI.icon(context, icon, R.color.accent, 20))
                addView(UI.hgap(context, 14))
                addView(UI.text(context, line, 14f, R.color.text2), UI.weight())
            }, UI.matchWidth())
        }

        val sv = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        scroll = null
        root.addView(sv, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showSchoolMessage(text: String?) {
        schoolMessage?.apply {
            this.text = text
            visibility = if (text == null) View.GONE else View.VISIBLE
        }
    }

    private fun checkSchool(input: String, knownName: String?) {
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable && input.trim() == "demo") return loadDemo()
        val url = Schools.urlFromInput(input)
        if (url == null) {
            val found = nearby?.let { Schools.filterByName(it, input) }
            when {
                found == null -> showSchoolMessage("Touche d'abord « Autour de moi » pour chercher par nom, ou tape le code ou l'adresse de ton Pronote.")
                found.isEmpty() -> showSchoolMessage("Aucun établissement de ce nom autour de toi.")
                else -> { showSchoolMessage(null); showResults(found) }
            }
            return
        }
        showSchoolMessage(null)
        showLoading("Vérification de l'établissement…")
        worker.execute {
            val result = runCatching { Schools.info(UrlConnectionHttp(), url) }
            runOnUiThread {
                hideLoading()
                result.onSuccess { info ->
                    startActivity(
                        Intent(this, LoginActivity::class.java)
                            .putExtra(LoginActivity.EXTRA_URL, url)
                            .putExtra(LoginActivity.EXTRA_SCHOOL, knownName ?: info.name)
                    )
                }.onFailure { e ->
                    showSchoolMessage(
                        if (e is PronoteException.Unreachable) "Impossible de joindre cette adresse. Vérifie ta connexion et l'adresse."
                        else "Pas de Pronote à cette adresse."
                    )
                }
            }
        }
    }

    /**
     * Debug builds only: Index Éducation's public demo (fake students). It
     * cannot keep device tokens, so it is good for the screens, not for refreshes.
     */
    private fun loadDemo() {
        showLoading("Chargement de la démo…")
        worker.execute {
            val result = runCatching {
                val logged = fr.sallio.pronote.Pronote.loginCredentials(
                    UrlConnectionHttp(), "https://demo.index-education.net/pronote",
                    "demonstration", "pronotevs", java.util.UUID.randomUUID().toString(),
                )
                repo.onLoggedIn(logged)
            }
            runOnUiThread {
                hideLoading()
                result.exceptionOrNull()?.let { showSchoolMessage("Démo : ${it.message}") } ?: render()
            }
        }
    }

    private fun findNearby() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
            return
        }
        showLoading("Recherche des établissements proches…")
        val manager = getSystemService(LocationManager::class.java)
        val last = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 6 * 3600_000L) {
            searchAround(last)
        } else if (Build.VERSION.SDK_INT >= 30 && manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            manager.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, mainExecutor) { location ->
                if (location != null) searchAround(location) else locationFailed()
            }
        } else if (last != null) {
            searchAround(last)
        } else {
            locationFailed()
        }
    }

    private fun locationFailed() {
        hideLoading()
        showSchoolMessage("Position introuvable. Active la localisation, ou tape le code de ton lycée.")
    }

    private fun searchAround(location: Location) {
        worker.execute {
            val result = runCatching { Schools.nearby(UrlConnectionHttp(), location.latitude, location.longitude) }
            runOnUiThread {
                hideLoading()
                val schools = result.getOrNull()
                when {
                    schools == null -> showSchoolMessage("La recherche n'a pas répondu. Réessaie, ou tape le code de ton lycée.")
                    schools.isEmpty() -> showSchoolMessage("Aucun établissement trouvé autour de toi.")
                    else -> {
                        nearby = schools
                        showSchoolMessage(null)
                        showResults(schools)
                    }
                }
            }
        }
    }

    private fun showResults(schools: List<Schools.Nearby>) {
        val list = results ?: return
        list.removeAllViews()
        list.addView(UI.gap(this, 14))
        for (school in schools.take(15)) {
            list.addView(UI.row(this).apply {
                background = UI.pressable(context, getColor(R.color.surface), 14)
                setPadding(dp(4), dp(11), dp(4), dp(11))
                addView(UI.icon(context, R.drawable.ic_place, R.color.text3, 18))
                addView(UI.hgap(context, 12))
                val col = UI.column(context)
                col.addView(UI.oneLine(UI.text(context, school.name, 15f)))
                col.addView(UI.text(context, "${school.postalCode} · ${"%.1f".format(school.distanceKm)} km", 12f, R.color.text3))
                addView(col, UI.weight())
                setOnClickListener { checkSchool(school.url, school.name) }
            }, UI.matchWidth())
        }
    }

    @Deprecated("Activity API")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_LOCATION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) findNearby()
            else showSchoolMessage("Sans la localisation, tape le code ou l'adresse de ton Pronote.")
        }
    }

    // ---- Updates -----------------------------------------------------------------

    private fun updateCard(u: Updater.Update): View {
        val card = UI.card(this, R.color.accent_container)
        card.addView(UI.text(this, "Nouvelle version ${u.versionName}", 17f, R.color.on_accent_container, UI.BOLD))
        if (u.notes.isNotEmpty()) card.addView(UI.text(this, u.notes.take(600), 14f, R.color.on_accent_container).apply {
            alpha = 0.85f
            setPadding(0, dp(6), 0, 0)
        })
        card.addView(UI.gap(this, 12))
        val status = updateStatus
        if (status != null) card.addView(UI.text(this, status, 14f, R.color.on_accent_container))
        else card.addView(UI.button(this, "Mettre à jour (${u.size / 1024} Ko)") { startUpdate(u) }, UI.matchWidth())
        return card
    }

    private fun startUpdate(u: Updater.Update) {
        if (!Updater.canInstall(this)) {
            AlertDialog.Builder(this)
                .setTitle("Une autorisation, une seule fois")
                .setMessage("Android doit autoriser Sallio à installer ses propres mises à jour. Active « Autoriser cette source », puis reviens ici.")
                .setPositiveButton("Ouvrir les réglages") { _, _ -> startActivity(Updater.unknownSourcesSettings(this)) }
                .setNegativeButton("Annuler", null)
                .show()
            return
        }
        updateStatus = "Téléchargement…"
        render()
        worker.execute {
            val error = runCatching {
                Updater.downloadAndInstall(applicationContext, u) { percent ->
                    runOnUiThread {
                        val text = "Téléchargement… $percent %"
                        if (updateStatus != text) { updateStatus = text; render() }
                    }
                }
            }.exceptionOrNull()
            runOnUiThread {
                updateStatus = null
                if (error != null) {
                    AlertDialog.Builder(this)
                        .setTitle("Mise à jour impossible")
                        .setMessage(error.message ?: "Erreur de téléchargement")
                        .setPositiveButton("OK", null)
                        .show()
                }
                render()
            }
        }
    }

    // ---- Sync & account ----------------------------------------------------------

    private fun refresh(manual: Boolean) {
        if (syncing) return
        syncing = true
        if (manual) render()
        worker.execute {
            repo.sync(manual)
            runOnUiThread {
                syncing = false
                render()
            }
        }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Se déconnecter ?")
            .setMessage("Le jeton Pronote, l'emploi du temps, les devoirs et les notes enregistrés seront effacés de ce téléphone.")
            .setPositiveButton("Tout effacer") { _, _ ->
                SyncJob.cancelAll(this)
                Widgets.cancelTick(this)
                worker.execute {
                    repo.logout()
                    runOnUiThread {
                        tab = Tab.HOME
                        render()
                    }
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ---- Loading -----------------------------------------------------------------

    private var loading: AlertDialog? = null

    private fun showLoading(text: String) {
        hideLoading()
        val box = UI.row(this).apply {
            setPadding(dp(24), dp(20), dp(24), dp(20))
            addView(ProgressBar(context), LinearLayout.LayoutParams(dp(32), dp(32)))
            addView(UI.text(context, text, 15f).apply { setPadding(dp(16), 0, 0, 0) })
        }
        loading = AlertDialog.Builder(this).setView(box).setCancelable(false).show()
    }

    private fun hideLoading() {
        loading?.dismiss()
        loading = null
    }

    override fun onDestroy() {
        hideLoading()
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val REQ_LOCATION = 1

        private val PRIVACY = listOf(
            "Sallio n'a aucun serveur : ton téléphone parle directement à Pronote.",
            "Tu te connectes sur la vraie page de ton ENT. L'app ne voit jamais ton mot de passe, seulement le jeton que Pronote donne à ce téléphone.",
            "Le jeton, ton emploi du temps, tes devoirs et tes notes sont chiffrés sur le téléphone et exclus des sauvegardes.",
            "Aucune pub, aucun traqueur, aucune statistique.",
            "Les devoirs que tu coches restent sur le téléphone : Pronote n'est jamais modifié.",
        )

        /** PRONOTE writes "NOM Prénom": show "Prénom Nom". */
        fun prettyStudent(name: String): String {
            val parts = name.trim().split(" ").filter { it.isNotEmpty() }
            if (parts.size < 2) return name
            val last = parts.takeWhile { it == it.uppercase() && it.any(Char::isLetter) }
            val first = parts.drop(last.size)
            if (last.isEmpty() || first.isEmpty()) return name
            return (first + last.map { Agenda.prettyName(it) }).joinToString(" ")
        }

        fun firstName(name: String): String {
            val parts = name.trim().split(" ").filter { it.isNotEmpty() }
            val last = parts.takeWhile { it == it.uppercase() && it.any(Char::isLetter) }
            return parts.drop(last.size).firstOrNull() ?: ""
        }
    }
}
