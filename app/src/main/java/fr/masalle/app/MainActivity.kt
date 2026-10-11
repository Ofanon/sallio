package fr.masalle.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import fr.masalle.pronote.Lesson
import fr.masalle.pronote.PronoteException
import fr.masalle.pronote.Schools
import fr.masalle.pronote.UrlConnectionHttp
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var content: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()
    private val repo by lazy { Repo.get(applicationContext) }
    private var syncing = false

    // Setup screen state
    private var schoolMessage: TextView? = null
    private var results: LinearLayout? = null
    /** Last "autour de moi" answer, so the field can filter it by name. */
    private var nearby: List<Schools.Nearby>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        scroll.addView(content)
        scroll.fitsSystemWindowsCompat()
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        render()
        if (repo.account() != null) refresh(manual = false)
    }

    private fun render() {
        content.removeAllViews()
        if (repo.account() == null) renderSetup() else renderTimetable()
    }

    // ---- Setup ----------------------------------------------------------------

    private fun renderSetup() {
        content.addView(UI.text(this, "Ma Salle", 32f, bold = true))
        content.addView(UI.gap(this, 6))
        content.addView(
            UI.text(
                this,
                "Ta salle et tes profs absents directement sur ton écran d'accueil, même quand Pronote ne charge plus.",
                16f, R.color.text2,
            )
        )
        content.addView(UI.gap(this, 22))

        val card = UI.card(this)
        card.addView(UI.text(this, "Ton établissement", 17f, bold = true))
        card.addView(UI.gap(this, 12))
        card.addView(UI.button(this, "Chercher autour de moi") { findNearby() }, UI.matchWidth())
        card.addView(UI.gap(this, 14))
        card.addView(UI.text(this, "ou tape l'adresse de ton Pronote, le code de ton lycée (ex. 0750655E), ou son nom après une recherche :", 13f, R.color.text2))
        card.addView(UI.gap(this, 8))
        val field = EditText(this).apply {
            hint = "https://…index-education.net/pronote"
            setSingleLine()
            inputType = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_GO
            setBackgroundResource(R.drawable.field)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            textSize = 15f
            setTextColor(getColor(R.color.text))
            setHintTextColor(getColor(R.color.text3))
        }
        val go = { checkSchool(field.text.toString(), null) }
        field.setOnEditorActionListener { _, _, _ -> go(); true }
        card.addView(field, UI.matchWidth())
        card.addView(UI.gap(this, 10))
        card.addView(UI.ghostButton(this, "Continuer") { go() }, UI.matchWidth())
        schoolMessage = UI.text(this, "", 13f, R.color.alert).apply { visibility = View.GONE; setPadding(0, dp(10), 0, 0) }
        card.addView(schoolMessage)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(results)
        content.addView(card, UI.matchWidth())

        content.addView(UI.gap(this, 16))
        content.addView(privacyCard(), UI.matchWidth())
    }

    private fun privacyCard(): View {
        val card = UI.card(this)
        card.addView(UI.text(this, "Tes données restent chez toi", 17f, bold = true))
        for (line in listOf(
            "Ma Salle n'a aucun serveur : ton téléphone parle directement à Pronote.",
            "Tu te connectes sur la vraie page de ton ENT. L'app ne voit jamais ton mot de passe, seulement le jeton que Pronote donne à ce téléphone.",
            "Le jeton et ton emploi du temps sont chiffrés sur le téléphone et exclus des sauvegardes.",
            "Aucune pub, aucun traqueur, aucune statistique.",
        )) {
            card.addView(UI.gap(this, 8))
            card.addView(UI.text(this, "•  $line", 14f, R.color.text2))
        }
        return card
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
                found == null -> showSchoolMessage("Ce n'est pas une adresse Pronote. Elle ressemble à https://0750655e.index-education.net/pronote, ou touche « Chercher autour de moi » puis tape le nom.")
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
     * cannot keep device tokens, so it is good for the widgets, not for refreshes.
     */
    private fun loadDemo() {
        showLoading("Chargement de la démo…")
        worker.execute {
            val result = runCatching {
                val logged = fr.masalle.pronote.Pronote.loginCredentials(
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
        showSchoolMessage("Position introuvable. Active la localisation, ou colle l'adresse de ton Pronote.")
    }

    private fun searchAround(location: Location) {
        worker.execute {
            val result = runCatching { Schools.nearby(UrlConnectionHttp(), location.latitude, location.longitude) }
            runOnUiThread {
                hideLoading()
                val list = results ?: return@runOnUiThread
                list.removeAllViews()
                val schools = result.getOrNull()
                if (schools == null) {
                    showSchoolMessage("La recherche n'a pas répondu. Réessaie, ou colle l'adresse de ton Pronote.")
                    return@runOnUiThread
                }
                if (schools.isEmpty()) {
                    showSchoolMessage("Aucun établissement trouvé autour de toi.")
                    return@runOnUiThread
                }
                nearby = schools
                showSchoolMessage(null)
                showResults(schools)
            }
        }
    }

    private fun showResults(schools: List<Schools.Nearby>) {
        val list = results ?: return
        list.removeAllViews()
        list.addView(UI.gap(this, 12))
        list.addView(UI.text(this, "Pas dans la liste ? Tape son nom dans le champ ci-dessus.", 12f, R.color.text3))
        for (school in schools.take(15)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                isClickable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { checkSchool(school.url, school.name) }
            }
            row.addView(UI.text(this, school.name, 15f))
            row.addView(UI.text(this, "${school.postalCode} · ${"%.1f".format(school.distanceKm)} km", 12f, R.color.text3))
            list.addView(row, UI.matchWidth())
        }
    }

    @Deprecated("Activity API")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_LOCATION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) findNearby()
            else showSchoolMessage("Sans la localisation, colle l'adresse de ton Pronote ci-dessus.")
        }
    }

    // ---- Timetable ------------------------------------------------------------

    private fun renderTimetable() {
        val account = repo.account() ?: return
        val status = repo.status()
        val now = LocalDateTime.now()
        val lessons = repo.lessons()

        content.addView(UI.text(this, prettyStudent(account.studentName), 26f, bold = true))
        val sub = listOfNotNull(account.className, Schools.prettySchool(account.schoolName).ifEmpty { null }).joinToString(" · ")
        if (sub.isNotEmpty()) content.addView(UI.text(this, sub, 14f, R.color.text2))
        content.addView(UI.gap(this, 16))

        if (status.needsLogin) {
            val card = UI.card(this)
            card.addView(UI.text(this, "Ta connexion Pronote a expiré", 16f, R.color.alert, bold = true))
            card.addView(UI.gap(this, 4))
            card.addView(UI.text(this, status.error ?: "Reconnecte-toi pour que les widgets se mettent à jour.", 14f, R.color.text2))
            card.addView(UI.gap(this, 12))
            card.addView(UI.button(this, "Me reconnecter") {
                startActivity(
                    Intent(this, LoginActivity::class.java)
                        .putExtra(LoginActivity.EXTRA_URL, account.credentials.url)
                        .putExtra(LoginActivity.EXTRA_SCHOOL, Schools.prettySchool(account.schoolName))
                )
            }, UI.matchWidth())
            content.addView(card, UI.matchWidth())
            content.addView(UI.gap(this, 14))
        }

        // Status line + refresh
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val statusText = when {
            syncing -> "Mise à jour…"
            status.error != null && !status.needsLogin -> "⚠ ${status.error}\nDonnées ${Agenda.ago(status.lastSuccess, now)}"
            else -> "Mis à jour ${Agenda.ago(status.lastSuccess, now)}"
        }
        statusRow.addView(
            UI.text(this, statusText, 13f, if (status.error != null) R.color.warn else R.color.text3),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (syncing) {
            statusRow.addView(ProgressBar(this), LinearLayout.LayoutParams(dp(28), dp(28)))
        } else {
            statusRow.addView(UI.ghostButton(this, "Actualiser") { refresh(manual = true) })
        }
        content.addView(statusRow, UI.matchWidth())
        content.addView(UI.gap(this, 14))

        val view = Agenda.view(lessons, now)
        view.focus?.let { content.addView(focusCard(it, view.then, now), UI.matchWidth()) }
        if (view.focus == null) {
            val card = UI.card(this)
            card.addView(
                UI.text(
                    this,
                    if (status.lastSuccess == 0L) "Emploi du temps pas encore chargé." else "Aucun cours dans les deux prochaines semaines.",
                    15f, R.color.text2,
                )
            )
            content.addView(card, UI.matchWidth())
        }

        // Next days
        val today = now.toLocalDate()
        val days = lessons.filter { it.end > now }.groupBy { it.start.toLocalDate() }.toSortedMap().entries.take(6)
        for ((date, dayLessons) in days) {
            content.addView(UI.gap(this, 20))
            content.addView(UI.text(this, Agenda.dayTitle(date, today), 15f, bold = true))
            content.addView(UI.gap(this, 6))
            val card = UI.card(this).apply { setPadding(dp(14), dp(6), dp(14), dp(6)) }
            for (lesson in lessons.filter { it.start.toLocalDate() == date }) card.addView(lessonRow(lesson, now), UI.matchWidth())
            content.addView(card, UI.matchWidth())
        }

        content.addView(UI.gap(this, 24))
        content.addView(widgetsCard(), UI.matchWidth())
        content.addView(UI.gap(this, 16))
        content.addView(privacyCard(), UI.matchWidth())
        content.addView(UI.gap(this, 16))
        content.addView(UI.ghostButton(this, "Se déconnecter et tout effacer") { confirmLogout() }, UI.matchWidth())
    }

    private fun focusCard(lesson: Lesson, then: Lesson?, now: LocalDateTime): View {
        val card = UI.card(this)
        card.addView(UI.text(this, Agenda.when_(lesson, now).uppercase(), 12f, R.color.accent, bold = true))
        card.addView(UI.text(this, Agenda.room(lesson), 40f, bold = true))
        val teacher = lesson.teachers.firstOrNull()?.let { " · ${Agenda.prettyName(it)}" } ?: ""
        card.addView(UI.text(this, Agenda.pretty(lesson.subject) + teacher, 15f, R.color.text2))
        Agenda.alert(lesson)?.let { alert ->
            card.addView(UI.gap(this, 8))
            card.addView(pill(alert, lesson.canceled), LinearLayout.LayoutParams(-2, -2))
        }
        then?.let {
            card.addView(UI.gap(this, 10))
            val alert = Agenda.alert(it)?.let { a -> " · $a" } ?: ""
            card.addView(UI.text(this, "Puis ${Agenda.time(it.start)} · ${Agenda.room(it)} · ${Agenda.pretty(it.subject)}$alert", 13f, R.color.text2))
        }
        return card
    }

    private fun pill(text: String, severe: Boolean) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(getColor(if (severe) R.color.alert else R.color.warn))
        setBackgroundResource(if (severe) R.drawable.pill_alert else R.drawable.pill_warn)
        setPadding(dp(8), dp(3), dp(8), dp(3))
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun lessonRow(lesson: Lesson, now: LocalDateTime): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            alpha = if (lesson.end <= now) 0.45f else 1f
        }
        val stripe = View(this).apply {
            setBackgroundColor(lesson.color?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: getColor(R.color.accent))
        }
        row.addView(stripe, LinearLayout.LayoutParams(dp(4), dp(36)))
        val times = UI.text(this, "${Agenda.time(lesson.start)}\n${Agenda.time(lesson.end)}", 12f, R.color.text2)
        row.addView(times, LinearLayout.LayoutParams(dp(54), -2).apply { marginStart = dp(10) })
        val middle = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val room = UI.text(this, Agenda.room(lesson), 17f, bold = true)
        if (lesson.canceled) room.paintFlags = room.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        middle.addView(room)
        val teacher = lesson.teachers.firstOrNull()?.let { " · ${Agenda.prettyName(it)}" } ?: ""
        middle.addView(UI.text(this, Agenda.pretty(lesson.subject) + teacher, 13f, R.color.text2).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        row.addView(middle, LinearLayout.LayoutParams(0, -2, 1f))
        Agenda.alert(lesson)?.let { row.addView(pill(it, lesson.canceled), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) }) }
        return row
    }

    private fun widgetsCard(): View {
        val card = UI.card(this)
        card.addView(UI.text(this, "Widgets", 17f, bold = true))
        card.addView(UI.gap(this, 4))
        card.addView(UI.text(this, "Ils affichent l'emploi du temps enregistré sur le téléphone : ils marchent même quand Pronote est saturé.", 14f, R.color.text2))
        val manager = getSystemService(AppWidgetManager::class.java)
        if (manager.isRequestPinAppWidgetSupported) {
            card.addView(UI.gap(this, 12))
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(UI.ghostButton(this, "+ Prochaine salle") {
                manager.requestPinAppWidget(ComponentName(this, NextWidget::class.java), null, null)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            buttons.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
            buttons.addView(UI.ghostButton(this, "+ Ma journée") {
                manager.requestPinAppWidget(ComponentName(this, DayWidget::class.java), null, null)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(buttons, UI.matchWidth())
        }
        return card
    }

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
            .setMessage("Le jeton Pronote et l'emploi du temps enregistrés seront effacés de ce téléphone.")
            .setPositiveButton("Tout effacer") { _, _ ->
                SyncJob.cancelAll(this)
                Widgets.cancelTick(this)
                worker.execute {
                    repo.logout()
                    runOnUiThread { render() }
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ---- Loading overlay ------------------------------------------------------

    private var loading: AlertDialog? = null

    private fun showLoading(text: String) {
        hideLoading()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
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

        /** PRONOTE writes "NOM Prénom": show "Prénom Nom". */
        fun prettyStudent(name: String): String {
            val parts = name.trim().split(" ").filter { it.isNotEmpty() }
            if (parts.size < 2) return name
            val last = parts.takeWhile { it == it.uppercase() && it.any(Char::isLetter) }
            val first = parts.drop(last.size)
            if (last.isEmpty() || first.isEmpty()) return name
            return (first + last.map { Agenda.prettyName(it) }).joinToString(" ")
        }
    }
}
