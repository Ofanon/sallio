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
        INBOX("Infos", R.drawable.ic_news),
    }

    private lateinit var root: FrameLayout
    /** Background work of the screens (network, files): one at a time. */
    val worker = Executors.newSingleThreadExecutor()
    val repo by lazy { Repo.get(applicationContext) }
    private val tabs by lazy { Tabs(this, repo) }

    // Screen state
    private var tab = Tab.HOME
    private var selectedDay: LocalDate? = null
    private var gradesBySubject = false
    private var inboxMode = InboxTab.Mode.NEWS
    private val inboxTab by lazy { InboxTab(this, repo) }
    internal val expanded = mutableSetOf<String>()
    private var scroll: ScrollView? = null
    private var syncing = false

    /** What animates on the next render: everything (screen opened), the tab body, or nothing. */
    enum class Enter { ALL, TAB, NONE }
    internal var entering = Enter.ALL
    private var switched = false
    private var spinner: android.animation.ObjectAnimator? = null
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
                insets.getInsets(android.view.WindowInsets.Type.systemBars()).let { it.top to it.bottom }
            } else {
                @Suppress("DEPRECATION") (insets.systemWindowInsetTop to insets.systemWindowInsetBottom)
            }
            // The keyboard only pads the screen: rebuilding the views would empty the field being typed in.
            val ime = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(android.view.WindowInsets.Type.ime()).bottom else 0
            root.setPadding(0, 0, 0, (ime - b).coerceAtLeast(0))
            if (ime > 0) root.post { root.findFocus()?.let { f -> f.requestRectangleOnScreen(android.graphics.Rect(0, 0, f.width, f.height + dp(120)), false) } }
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
        // A widget, shortcut or tile asking for a tab wins over the restored one.
        if (savedInstanceState == null) intent?.getStringExtra(EXTRA_TAB)?.let { name -> runCatching { tab = Tab.valueOf(name) } }
        if (savedInstanceState == null) intent?.let(::inboxModeFrom)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_TAB)?.let { name ->
            runCatching { Tab.valueOf(name) }.getOrNull()?.let { target ->
                if (target != tab) { tab = target; entering = Enter.ALL; scroll = null }
            }
        }
        if (inboxModeFrom(intent) && tab == Tab.INBOX) { entering = Enter.TAB; render() }
    }

    /** A notification can open Infos on its view (messages, vie scolaire). True when it did. */
    private fun inboxModeFrom(intent: Intent): Boolean {
        val mode = intent.getStringExtra(EXTRA_INBOX)?.let { runCatching { InboxTab.Mode.valueOf(it) }.getOrNull() } ?: return false
        inboxMode = mode
        return true
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
        if (repo.account() != null) refresh(manual = false, onOpen = true)
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
        // Leaving the app: send the ticks now rather than in 1.5 s.
        if (pushScheduled) { handler.removeCallbacks(pushNow); pushNow.run() }
        super.onPause()
    }

    /**
     * The first-login celebration, while it is on screen. A sync finishing right
     * after the login redraws the page: the celebration is put back on top
     * instead of being wiped with the rest.
     */
    private var celebration: View? = null

    /** Redraws the current tab, from a sheet that changed something behind it. */
    fun refreshScreen() = render()

    fun render() {
        val keepScroll = scroll?.scrollY ?: 0
        for (i in root.childCount - 1 downTo 0) if (root.getChildAt(i) !== celebration) root.removeViewAt(i)
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

        // Pages appear instantly; only the home tab rises in, the first time it opens.
        entering = if (entering != Enter.NONE && tab == Tab.HOME && !homeIntroPlayed) Enter.ALL else Enter.NONE
        if (entering == Enter.ALL) homeIntroPlayed = true

        val column = UI.column(this)
        val content = UI.column(this).apply { setPadding(dp(16), topInset + dp(8), dp(16), dp(28)) }
        content.addView(header(s), UI.matchWidth())
        update?.let { content.addView(updateCard(it), UI.matchWidth().apply { topMargin = dp(14) }) }
        if (s.status.needsLogin) content.addView(reconnectCard(s), UI.matchWidth().apply { topMargin = dp(14) })
        if (tab == Tab.HOME) widgetInvite()?.let { content.addView(it, UI.matchWidth().apply { topMargin = dp(14) }) }
        content.addView(UI.gap(this, 14))
        val body = when (tab) {
            Tab.HOME -> tabs.home(s)
            Tab.TIMETABLE -> tabs.timetable(s, selectedDay) { selectedDay = it; entering = Enter.TAB; render() }
            Tab.HOMEWORK -> tabs.homework(s)
            Tab.GRADES -> tabs.grades(s, gradesBySubject) { gradesBySubject = it; render() }
            Tab.INBOX -> inboxTab.build(s, inboxMode) { inboxMode = it; render() }
        }
        content.addView(body, UI.matchWidth())
        when (entering) {
            Enter.ALL -> { Motion.cascade(content); (body as? ViewGroup)?.let { Motion.cascade(it, 120) } }
            Enter.TAB -> (body as? ViewGroup)?.let { Motion.cascade(it) }
            Enter.NONE -> Unit
        }

        val sv = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            addView(content)
        }
        scroll = sv
        column.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        column.addView(bottomNav(s), UI.matchWidth())
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        // Opaque strip under the status bar, so scrolled content never runs beneath the clock.
        root.addView(View(this).apply { setBackgroundColor(getColor(R.color.bg)) }, FrameLayout.LayoutParams(-1, topInset))
        val ui = getSharedPreferences("ui", MODE_PRIVATE)
        celebration?.bringToFront()
        if (celebration == null && ui.getBoolean("welcomePending", false)) {
            ui.edit().putBoolean("welcomePending", false).apply()
            celebration = Welcome.celebrate(this, s, root, topInset, bottomInset, onGone = { celebration = null }) {
                // The home tab is built under the celebration, which then fades
                // away over it: the page rises in without a flash of its final state.
                tab = Tab.HOME
                entering = Enter.ALL
                homeIntroPlayed = false
                scroll = null
                render()
            }
        }
        if (entering == Enter.NONE && keepScroll > 0) {
            sv.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    sv.viewTreeObserver.removeOnPreDrawListener(this)
                    sv.scrollTo(0, keepScroll)
                    return true
                }
            })
        }
        entering = Enter.NONE
    }

    fun openTab(target: Tab) {
        if (tab == Tab.GRADES && target != Tab.GRADES) repo.markGradesSeen()
        if (tab != target) {
            scroll?.scrollTo(0, 0)
            entering = Enter.ALL
            switched = true
            root.let { Motion.tick(it) }
        }
        tab = target
        scroll = null
        render()
    }

    fun showLesson(lesson: fr.sallio.pronote.Lesson, s: Snapshot) {
        Motion.tap(root)
        LessonSheet.show(this, lesson, s, bottomInset)
    }

    fun showGrade(g: fr.sallio.pronote.Grade) {
        GradeSheet.show(this, g, repo.report(), bottomInset)
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pushScheduled = false
    private val pushNow = Runnable {
        pushScheduled = false
        worker.execute {
            val result = repo.pushPending()
            // Not allowed to log in yet: the ticks are safe, a job sends them as soon as it is.
            if (repo.pendingTotal() > 0) SyncJob.runLater(applicationContext, repo.waitBeforeLogin(manual = true))
            runOnUiThread {
                if (result == Repo.SyncResult.FAILED) Motion.failure(root)
                if (!isFinishing) render()
            }
        }
    }

    /** Ticks are sent a moment after the last one, so a few quick ticks go in one login. */
    fun schedulePush() {
        handler.removeCallbacks(pushNow)
        pushScheduled = true
        handler.postDelayed(pushNow, 1500)
    }

    val bottomInsetPx: Int get() = bottomInset

    fun isHomeworkDone(h: fr.sallio.pronote.Homework) = repo.isDone(h)

    /** "J'ai vu" from an actualité: saved at once, sent to PRONOTE shortly after with any ticks. */
    fun setNewsRead(n: fr.sallio.pronote.News) {
        repo.setRead(n)
        render()
        schedulePush()
    }

    /** Ticks from a sheet: saved at once, sent to PRONOTE shortly after, page redrawn behind. */
    fun setHomeworkDone(h: fr.sallio.pronote.Homework, done: Boolean) {
        repo.setDone(h, done)
        render()
        schedulePush()
    }

    fun pinWidget() {
        val manager = getSystemService(AppWidgetManager::class.java)
        if (manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(ComponentName(this, NextWidget::class.java), null, null)
    }

    /** The welcome screen animates the first time it shows, not when redrawn by a search. */
    private var setupAnimated = false

    fun openPronote() {
        val url = repo.account()?.credentials?.url ?: return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$url/eleve.html"))) }
    }

    // ---- Shell -------------------------------------------------------------------

    private fun header(s: Snapshot): View {
        val row = UI.row(this).apply { setPadding(dp(2), dp(10), 0, dp(2)) }
        val col = UI.column(this)
        val first = firstName(s.account.studentName)
        val today = s.now.toLocalDate()
        val (title, subtitle) = when (tab) {
            Tab.HOME -> (if (first.isNotEmpty()) "Salut $first 👋" else "Salut 👋") to
                "${Agenda.dayName(today)} ${today.dayOfMonth} ${today.month.getDisplayName(TextStyle.SHORT, Locale.FRANCE)}"
            Tab.TIMETABLE -> "Cours" to "2 semaines"
            Tab.HOMEWORK -> "Devoirs" to tabs.homeworkSummary(s)
            Tab.GRADES -> "Notes" to (s.report?.periodName ?: "")
            Tab.INBOX -> "Infos" to "Actualités, messages, vie scolaire"
        }
        col.addView(UI.oneLine(UI.title(this, title, 27f)))
        val (status, color) = when {
            syncing -> "mise à jour…" to R.color.text2
            s.status.needsLogin -> "connexion à refaire" to R.color.alert
            s.status.error != null -> "⚠ ${s.status.error}" to R.color.warn
            else -> "à jour ${Agenda.ago(s.status.lastSuccess, s.now)}" to R.color.text2
        }
        col.addView(UI.oneLine(UI.text(this, listOf(subtitle, status).filter { it.isNotEmpty() }.joinToString(" · "), 13f, color)).apply { setPadding(0, dp(4), 0, 0) })
        row.addView(col, UI.weight())
        val refresh = UI.icon(this, R.drawable.ic_refresh, if (syncing) R.color.accent_ink else R.color.text2, 22)
        spinner?.cancel()
        spinner = if (syncing) Motion.spin(refresh) else null
        row.addView(UI.squareButton(this, R.color.surface, R.color.edge, refresh) { refresh(manual = true) })
        row.addView(UI.hgap(this, 8))
        row.addView(Avatar.button(this, first, 40, 19f) { settingsSheet(s) })
        return row
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (Avatar.onResult(this, requestCode, resultCode, data)) {
            Motion.success(root)
            render()
        }
    }

    private fun bottomNav(s: Snapshot): View {
        val nav = UI.row(this).apply {
            background = topBordered(getColor(R.color.surface), getColor(R.color.edge), dp(2))
            setPadding(dp(6), dp(10), dp(6), dp(12) + bottomInset)
        }
        for (t in Tab.values()) {
            val selected = t == tab
            val item = UI.column(this).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setOnClickListener { openTab(t) }
            }
            val pill = FrameLayout(this).apply {
                // The selected tab inverted: the yellow becomes the pill, the icon takes its dark ink.
                if (selected) background = UI.sticker(context, getColor(R.color.accent), getColor(R.color.accent_edge), 13, 2)
            }
            if (selected && switched) { switched = false; pill.post { Motion.pop(pill) } }
            pill.addView(UI.icon(this, t.icon, if (selected) R.color.on_accent else R.color.text3, 22).apply {
                layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER).apply { bottomMargin = if (selected) dp(2) else 0 }
            })
            val dot = (t == Tab.GRADES && s.unseenGrades.isNotEmpty()) || (t == Tab.INBOX && repo.unreadInbox() > 0)
            if (dot && !selected) pill.addView(View(this).apply {
                background = UI.rounded(0xFFD9705A.toInt(), dp(5).toFloat())
            }, FrameLayout.LayoutParams(dp(10), dp(10), Gravity.TOP or Gravity.END).apply { topMargin = dp(2); marginEnd = dp(12) })
            item.addView(pill, LinearLayout.LayoutParams(dp(52), dp(34)))
            item.addView(UI.text(this, t.label, 12f, if (selected) R.color.text else R.color.text3, 800).apply {
                setPadding(0, dp(4), 0, 0)
                gravity = Gravity.CENTER
            }, UI.matchWidth())
            nav.addView(item, UI.weight())
        }
        return nav
    }

    /** Surface with a single top border, for the tab bar. */
    private fun topBordered(fill: Int, line: Int, width: Int): android.graphics.drawable.Drawable =
        android.graphics.drawable.LayerDrawable(arrayOf(ColorDrawable(line), ColorDrawable(fill))).apply { setLayerInset(1, 0, width, 0, 0) }

    /**
     * Until a widget is on the home screen, the home tab suggests one: it is the
     * fastest way to the room, without even opening the app.
     */
    private fun widgetInvite(): View? {
        val manager = getSystemService(AppWidgetManager::class.java)
        if (!manager.isRequestPinAppWidgetSupported) return null
        val placed = manager.getAppWidgetIds(ComponentName(this, NextWidget::class.java)).isNotEmpty() ||
            manager.getAppWidgetIds(ComponentName(this, DayWidget::class.java)).isNotEmpty()
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (placed || prefs.getBoolean("widgetInviteDismissed", false)) return null

        val card = UI.card(this, R.color.accent_container, R.color.accent_container_edge)
        card.addView(UI.row(this).apply {
            addView(UI.icon(context, R.drawable.ic_widgets, R.color.on_accent_container, 22))
            addView(UI.hgap(context, 8))
            addView(UI.title(context, "Ta salle sans ouvrir l'app", 17f, R.color.on_accent_container), UI.weight())
        })
        card.addView(UI.text(this, "Pose le widget sur ton écran d'accueil : il marche même quand Pronote est saturé.", 14f, R.color.on_accent_container).apply {
            alpha = 0.85f
            setPadding(0, dp(6), 0, dp(12))
        })
        val buttons = UI.row(this)
        buttons.addView(UI.button(this, "Ajouter le widget") {
            manager.requestPinAppWidget(ComponentName(this, NextWidget::class.java), null, null)
        }, UI.weight())
        buttons.addView(UI.hgap(this, 10))
        buttons.addView(UI.ghostButton(this, "Plus tard") {
            prefs.edit().putBoolean("widgetInviteDismissed", true).apply()
            render()
        })
        card.addView(buttons, UI.matchWidth())
        return card
    }

    private fun reconnectCard(s: Snapshot): View {
        val card = UI.card(this, R.color.alert_bg, R.color.alert_edge)
        card.addView(UI.title(this, "Ta connexion Pronote a expiré", 18f, R.color.alert))
        card.addView(UI.text(this, "Tes infos restent affichées, mais elles ne se mettent plus à jour.", 14f, R.color.text2).apply { setPadding(0, dp(6), 0, dp(12)) })
        card.addView(UI.button(this, "Me reconnecter") {
            startActivity(
                Intent(this, LoginActivity::class.java)
                    .putExtra(LoginActivity.EXTRA_URL, s.account.credentials.url)
                    .putExtra(LoginActivity.EXTRA_SCHOOL, Schools.prettySchool(s.account.schoolName))
            )
        }, UI.matchWidth())
        return card
    }

    /** Everything in one place: faster access, preferences, account. */
    private fun settingsSheet(s: Snapshot) {
        val first = firstName(s.account.studentName)
        val who = UI.row(this)
        // Tapping the avatar changes it: a photo, an emoji, a colour. This header follows.
        val slot = FrameLayout(this)
        fun editAvatar() {
            Avatar.edit(this, first) {
                render()
                slot.removeAllViews()
                slot.addView(Avatar.button(this, first, 50, 22f) { editAvatar() }.apply { contentDescription = "Changer mon avatar" }, FrameLayout.LayoutParams(-1, -1))
            }
        }
        slot.addView(Avatar.button(this, first, 50, 22f) { editAvatar() }.apply { contentDescription = "Changer mon avatar" }, FrameLayout.LayoutParams(-1, -1))
        who.addView(slot, LinearLayout.LayoutParams(dp(56), dp(59)))
        who.addView(UI.hgap(this, 14))
        val info = UI.column(this)
        info.addView(UI.title(this, prettyStudent(s.account.studentName), 20f))
        info.addView(UI.text(this, listOfNotNull(s.account.className, Schools.prettySchool(s.account.schoolName).ifEmpty { null }).joinToString(" · "), 13f, R.color.text2).apply { setPadding(0, dp(4), 0, 0) })
        who.addView(info, UI.weight())

        val widgets = getSystemService(AppWidgetManager::class.java)
        fun placed(cls: Class<*>) = widgets.getAppWidgetIds(ComponentName(this, cls)).isNotEmpty()

        val allWidgets = listOf(NextWidget::class.java, DayWidget::class.java, TomorrowWidget::class.java, AlertsWidget::class.java, HomeworkWidget::class.java)

        // Each page is built when it opens, so "Ajouté" and the switches show the state of that moment.
        val quick = SettingsSheet.Item.Page(R.drawable.ic_widgets, "Widgets et accès rapide",
            listOfNotNull(
                allWidgets.count { placed(it) }.let { n -> if (n > 0) "$n widget${if (n > 1) "s" else ""} posé${if (n > 1) "s" else ""}" else "Aucun widget posé" },
                if (Wallpaper.isEnabled(this)) "écran verrouillé" else null,
            ).joinToString(" · "),
            "Ta salle sans ouvrir l'app",
        ) {
            val homeScreen = buildList {
                if (widgets.isRequestPinAppWidgetSupported) {
                    for ((cls, icon, title, description) in listOf(
                        Quad(NextWidget::class.java, R.drawable.ic_school, "Prochaine salle", "Ta salle en grand, avec l'alerte s'il y en a une."),
                        Quad(DayWidget::class.java, R.drawable.ic_calendar, "Ma journée", "Tous les cours du jour avec leur salle."),
                        Quad(TomorrowWidget::class.java, R.drawable.ic_calendar, "Demain", "Les cours du prochain jour, pour préparer ton sac la veille."),
                        Quad(AlertsWidget::class.java, R.drawable.ic_warning, "À savoir", "Profs absents, cours annulés, salles changées : aujourd'hui et demain."),
                        Quad(HomeworkWidget::class.java, R.drawable.ic_tasks, "Devoirs", "Les devoirs du prochain jour de cours, à cocher depuis l'écran d'accueil."),
                    )) {
                        add(SettingsSheet.Item.Action(icon, "Widget « $title »", description, badge = if (placed(cls)) "Ajouté" else null) {
                            widgets.requestPinAppWidget(ComponentName(this@MainActivity, cls), null, null)
                        })
                    }
                }
            }
            val elsewhere = buildList {
                if (Build.VERSION.SDK_INT >= 33) {
                    add(SettingsSheet.Item.Action(R.drawable.ic_school, "Tuile des réglages rapides", "Glisse vers le bas, n'importe où : ta salle.") {
                        getSystemService(android.app.StatusBarManager::class.java).requestAddTileService(
                            ComponentName(this@MainActivity, RoomTile::class.java), "Ma salle",
                            android.graphics.drawable.Icon.createWithResource(this@MainActivity, R.drawable.ic_school), mainExecutor,
                        ) { }
                    })
                }
                add(SettingsSheet.Item.Toggle(R.drawable.ic_calendar, "Salles sur l'écran verrouillé",
                    "Ton fond de verrouillage affiche les cours du jour, mis à jour tout seul.", { Wallpaper.isEnabled(this@MainActivity) }) { on, done ->
                    if (on) previewWallpaper(done)
                    else {
                        worker.execute { Wallpaper.disable(applicationContext) }
                        done()
                    }
                })
            }
            listOf(SettingsSheet.Section("Écran d'accueil", homeScreen), SettingsSheet.Section("Ailleurs sur le téléphone", elsewhere))
        }

        val notifications = SettingsSheet.Item.Page(R.drawable.ic_clock, "Notifications",
            when {
                !notificationsAllowed() -> "Bloquées par Android"
                else -> listOfNotNull(
                    if (Alerts.isEnabled(this)) "alertes" else null,
                    if (Reminders.isEnabled(this)) "rappels" else null,
                ).joinToString(" et ").replaceFirstChar { it.uppercase() }.ifEmpty { "Aucune" }
            },
            "Ce que Sallio peut te dire",
        ) {
            listOf(
                SettingsSheet.Section("", listOf(
                    SettingsSheet.Item.Toggle(R.drawable.ic_warning, "Alertes de changement",
                        "Prof absent, cours annulé, salle changée, nouvelle note, nouveau message ou vie scolaire : prévenu dès la synchro.", { Alerts.isEnabled(this@MainActivity) && notificationsAllowed() }) { on, done ->
                        withNotifications(on, { Alerts.setEnabled(this@MainActivity, it) }, done)
                    },
                    SettingsSheet.Item.Toggle(R.drawable.ic_clock, "Rappel avant chaque cours",
                        "Une notification 5 min avant : la salle, le prof, ou s'il est annulé.", { Reminders.isEnabled(this@MainActivity) && notificationsAllowed() }) { on, done ->
                        withNotifications(on, { Reminders.setEnabled(this@MainActivity, it) }, done)
                    },
                )),
                SettingsSheet.Section("Dans Android", listOf(
                    SettingsSheet.Item.Action(R.drawable.ic_open, "Choisir les types d'alertes", "Couper les notes et garder l'emploi du temps, changer le son…") {
                        Alerts.createChannels(applicationContext)
                        runCatching {
                            startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
                        }
                    },
                )),
            )
        }

        val look = SettingsSheet.Item.Page(R.drawable.ic_palette, "Apparence",
            listOfNotNull(
                if (repo.pronoteColors()) "Couleurs de Pronote" else "Couleurs Sallio",
                if (Motion.hapticsOn(this)) "vibrations" else null,
            ).joinToString(" · "),
            "Ton avatar, les couleurs, les vibrations",
        ) {
            listOf(SettingsSheet.Section("", listOf(
                SettingsSheet.Item.Action(R.drawable.ic_palette, "Mon avatar", "Ta photo, un emoji ou ton initiale, et sa couleur.") {
                    Avatar.edit(this, first) { render() }
                },
                SettingsSheet.Item.Toggle(R.drawable.ic_sparkles, "Couleurs de Pronote",
                    "Chaque matière garde la couleur qu'elle a sur Pronote, adoucie pour rester lisible. Sinon, les couleurs de Sallio.", { repo.pronoteColors() }) { on, done ->
                    repo.setPronoteColors(on)
                    render()
                    done()
                },
                SettingsSheet.Item.Toggle(R.drawable.ic_sparkles, "Vibrations", "Un petit retour quand tu touches un bouton.", { Motion.hapticsOn(this) }) { on, done ->
                    getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("haptics", on).apply()
                    done()
                },
            )))
        }

        val canteen = Canteen.get(this)
        val canteenPage = SettingsSheet.Item.Page(R.drawable.logo_turboself, "Cantine",
            if (canteen.connected()) "Turboself connecté" else "Relier ton compte Turboself",
            "Turboself : ta carte, ton solde, tes réservations",
        ) {
            listOf(SettingsSheet.Section("", if (canteen.connected()) listOf(
                SettingsSheet.Item.Action(R.drawable.logo_turboself, "Ouvrir la cantine", "Ta carte en QR code, ton solde, tes réservations.") {
                    CanteenUI.showSheet(this)
                },
                SettingsSheet.Item.Action(R.drawable.ic_logout, "Déconnecter Turboself", "Efface le mot de passe et les données Turboself de ce téléphone.", danger = true) {
                    worker.execute { canteen.disconnect(); runOnUiThread { render() } }
                },
            ) else listOf(
                SettingsSheet.Item.Action(R.drawable.logo_turboself, "Connecter Turboself", "Avec ton e-mail et ton mot de passe Turboself.") {
                    CanteenUI.showLogin(this) { render() }
                },
            )))
        }

        val account = SettingsSheet.Item.Page(R.drawable.ic_shield, "Compte et données",
            "Version ${BuildConfig.VERSION_NAME} · tes données restent sur le téléphone",
            Schools.prettySchool(s.account.schoolName),
        ) {
            listOf(
                SettingsSheet.Section("", listOf(
                    SettingsSheet.Item.Action(R.drawable.logo_pronote, "Ouvrir Pronote", "Répondre aux messages, justifier une absence, bulletins…") { openPronote() },
                    SettingsSheet.Item.Action(R.drawable.ic_shield, "Tes données", "Ce que Sallio garde, et où.") { privacyDialog() },
                    SettingsSheet.Item.Action(R.drawable.ic_refresh, "Rechercher une mise à jour", "Version installée : ${BuildConfig.VERSION_NAME}") { checkUpdateNow() },
                ) + if (BuildConfig.DEBUG) listOf(
                    SettingsSheet.Item.Action(R.drawable.ic_warning, "Tester les alertes (debug)", "Invente des changements sur les données du téléphone, sans Pronote.") {
                        worker.execute { Alerts.demo(applicationContext) }
                    },
                ) else emptyList()),
                SettingsSheet.Section("", listOf(
                    SettingsSheet.Item.Action(R.drawable.ic_logout, "Se déconnecter et tout effacer", danger = true) { confirmLogout() },
                )),
            )
        }

        SettingsSheet.show(
            this, who,
            listOf(SettingsSheet.Section("Réglages", listOf(quick, notifications, look, canteenPage, account))),
            "Sallio ${BuildConfig.VERSION_NAME} · code source sur GitHub",
            { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${Updater.REPO}"))) } },
            bottomInset,
        )
    }

    /** Called with whether notifications may be shown, once Android answered. */
    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    private var afterNotificationPermission: ((Boolean) -> Unit)? = null

    /** Applies [on] through [set], asking Android for notifications first when needed. */
    private fun withNotifications(on: Boolean, set: (Boolean) -> Unit, done: () -> Unit) {
        if (on && !notificationsAllowed()) {
            afterNotificationPermission = { granted ->
                if (granted) set(true)
                done()
            }
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        } else {
            set(on)
            done()
        }
    }

    /** Shows the lock screen picture before replacing the student's own. */
    private fun previewWallpaper(done: () -> Unit) {
        val bitmap = Wallpaper.preview(this)
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = UI.column(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = UI.rounded(getColor(R.color.bg), dp(28).toFloat())
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        box.addView(UI.title(this, "Ton écran verrouillé", 20f))
        box.addView(UI.text(this, "Il se met à jour tout seul chaque jour. Ton fond d'écran d'accueil ne change pas.", 13f, R.color.text2).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(12))
        }, UI.matchWidth())
        val height = (resources.displayMetrics.heightPixels * 0.5f).toInt()
        val width = height * bitmap.width / bitmap.height
        box.addView(android.widget.ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = android.widget.ImageView.ScaleType.FIT_XY
            background = UI.rounded(Color.BLACK, dp(22).toFloat())
            clipToOutline = true
        }, LinearLayout.LayoutParams(width, height))
        val buttons = UI.row(this).apply { setPadding(0, dp(14), 0, 0) }
        buttons.addView(UI.ghostButton(this, "Annuler") { dialog.dismiss() }, UI.weight())
        buttons.addView(UI.hgap(this, 10))
        buttons.addView(UI.button(this, "Utiliser") {
            dialog.dismiss()
            Wallpaper.enable(applicationContext)
            done()
            worker.execute {
                Wallpaper.update(applicationContext)
                runOnUiThread { Motion.success(root) }
            }
        }, UI.weight())
        box.addView(buttons, UI.matchWidth())
        dialog.setContentView(box)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(resources.displayMetrics.widthPixels - dp(32), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setOnDismissListener { done() }
        dialog.show()
    }

    private fun notificationsAllowed() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun privacyDialog() {
        AlertDialog.Builder(this)
            .setTitle("Tes données restent chez toi")
            .setMessage(PRIVACY.joinToString("\n\n") { "• $it" })
            .setPositiveButton("OK", null)
            .show()
    }

    // ---- Setup -------------------------------------------------------------------

    private fun renderSetup() {
        val content = UI.column(this).apply { setPadding(dp(22), topInset + dp(12), dp(22), bottomInset + dp(28)) }

        val animate = !setupAnimated
        setupAnimated = true
        content.addView(Welcome.hero(this, animate))
        val appName = UI.title(this, "Sallio", 46f).apply { gravity = Gravity.CENTER }
        content.addView(appName, UI.matchWidth())
        val tagline = UI.text(this, "Ta salle, tes profs absents, tes devoirs et tes notes. Même quand Pronote rame.", 18f, R.color.text2).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), 0)
            setLineSpacing(0f, 1.1f)
        }
        content.addView(tagline, UI.matchWidth())
        content.addView(UI.gap(this, 26))

        val card = UI.card(this, padding = 18)
        // PRONOTE's own logo: the student sees at once which account Sallio asks for.
        card.addView(UI.row(this).apply {
            addView(UI.logo(context, R.drawable.logo_pronote, 34))
            addView(UI.hgap(context, 12))
            val col = UI.column(context)
            col.addView(UI.title(context, "Trouve ton lycée", 20f))
            col.addView(UI.text(context, "pour relier ton compte Pronote", 13f, R.color.text2))
            addView(col, UI.weight())
        }, UI.matchWidth())
        card.addView(UI.gap(this, 14))
        card.addView(UI.button(this, "Autour de moi", R.drawable.ic_place) { findNearby() }, UI.matchWidth())
        card.addView(UI.text(this, "Tu connais le code de ton lycée (ex. 0782924E) ou l'adresse de son Pronote ?", 13f, R.color.text2).apply { setPadding(0, dp(16), 0, dp(8)) })
        val field = EditText(this).apply {
            hint = "Code ou adresse"
            setSingleLine()
            inputType = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_GO
            background = UI.rounded(getColor(R.color.surface2), dp(14).toFloat(), dp(2), getColor(R.color.edge))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            textSize = 15f
            typeface = UI.body(context, 700)
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
        nearby?.let { list ->
            card.addView(UI.ghostButton(this, "Revoir les ${list.size} établissements trouvés") { openPicker(list) }, UI.matchWidth().apply { topMargin = dp(12) })
        }
        content.addView(card, UI.matchWidth())
        val rising = mutableListOf<View>(appName, tagline, card)

        content.addView(UI.gap(this, 22))
        for ((icon, line) in listOf(
            R.drawable.ic_shield to "Tes données restent sur ton téléphone. Aucun serveur, aucune pub.",
            R.drawable.ic_widgets to "Des widgets qui marchent même quand Pronote est saturé.",
            R.drawable.ic_open to "Tu te connectes sur la vraie page de ton ENT : l'app ne voit jamais ton mot de passe.",
        )) {
            content.addView(UI.row(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(7), dp(2), dp(7))
                val tile = FrameLayout(context).apply {
                    background = UI.rounded(getColor(R.color.accent_container), dp(12).toFloat())
                    addView(UI.icon(context, icon, R.color.on_accent_container, 20).apply { layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER) })
                }
                addView(tile, LinearLayout.LayoutParams(dp(38), dp(38)))
                addView(UI.hgap(context, 12))
                addView(UI.text(context, line, 14f, R.color.text2), UI.weight())
            }.also { rising += it }, UI.matchWidth())
        }
        if (animate) Welcome.riseIn(rising)

        val sv = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
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
                found.isEmpty() -> { Motion.failure(root); showSchoolMessage("Aucun établissement de ce nom autour de toi.") }
                else -> { Motion.tick(root); showSchoolMessage(null); openPicker(found) }
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
                    Motion.failure(root)
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
                    schools == null -> { Motion.failure(root); showSchoolMessage("La recherche n'a pas répondu. Réessaie, ou tape le code de ton lycée.") }
                    schools.isEmpty() -> showSchoolMessage("Aucun établissement trouvé autour de toi.")
                    else -> {
                        nearby = schools
                        Motion.success(root)
                        showSchoolMessage(null)
                        openPicker(schools)
                    }
                }
            }
        }
    }

    private fun openPicker(schools: List<Schools.Nearby>) {
        SchoolPicker.show(this, schools, bottomInset) { school -> checkSchool(school.url, SchoolPicker.titleCase(school.name)) }
    }

    private fun showResults(schools: List<Schools.Nearby>) {
        val list = results ?: return
        list.removeAllViews()
        list.addView(UI.gap(this, 10))
        schools.take(15).forEachIndexed { i, school ->
            if (i > 0) list.addView(UI.divider(this))
            list.addView(UI.row(this).apply {
                setPadding(dp(2), dp(11), dp(2), dp(11))
                addView(UI.icon(context, R.drawable.ic_school, R.color.accent_ink, 20))
                addView(UI.hgap(context, 12))
                val col = UI.column(context)
                col.addView(UI.oneLine(UI.text(context, Schools.prettySchool(school.name), 15f, weight = 800)))
                col.addView(UI.text(context, "${school.postalCode} · ${"%.1f".format(school.distanceKm)} km", 12f, R.color.text2))
                addView(col, UI.weight())
                foreground = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22000000), null, UI.rounded(Color.WHITE, dp(12).toFloat()))
                setOnClickListener { Motion.tap(this); checkSchool(school.url, school.name) }
            }, UI.matchWidth())
        }
    }

    @Deprecated("Activity API")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_NOTIFICATIONS) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            if (granted) Motion.success(root)
            afterNotificationPermission?.invoke(granted)
            afterNotificationPermission = null
            return
        }
        if (requestCode == REQ_LOCATION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) findNearby()
            else showSchoolMessage("Sans la localisation, tape le code ou l'adresse de ton Pronote.")
        }
    }

    // ---- Updates -----------------------------------------------------------------

    /** Asks GitHub right away, whatever the last check said. */
    private fun checkUpdateNow() {
        android.widget.Toast.makeText(this, "Recherche d'une mise à jour…", android.widget.Toast.LENGTH_SHORT).show()
        worker.execute {
            val found = Updater.check(applicationContext, force = true)
            runOnUiThread {
                update = found
                render()
                val text = if (found != null) "Sallio ${found.versionName} est disponible : regarde en haut de l'accueil." else "Tu as déjà la dernière version."
                android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateCard(u: Updater.Update): View {
        val card = UI.card(this, R.color.accent_container, R.color.accent_container_edge)
        card.addView(UI.row(this).apply {
            addView(UI.icon(context, R.drawable.ic_sparkles, R.color.on_accent_container, 22))
            addView(UI.hgap(context, 8))
            addView(UI.title(context, "Nouvelle version ${u.versionName}", 18f, R.color.on_accent_container))
        })
        if (u.notes.isNotEmpty()) card.addView(UI.text(this, u.notes.take(600), 14f, R.color.on_accent_container).apply {
            alpha = 0.85f
            setPadding(0, dp(6), 0, 0)
        })
        card.addView(UI.gap(this, 12))
        val status = updateStatus
        if (status != null) card.addView(UI.text(this, status, 14f, R.color.on_accent_container, 800))
        else card.addView(UI.button(this, "Mettre à jour") { startUpdate(u) }, UI.matchWidth())
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

    private fun refresh(manual: Boolean, onOpen: Boolean = false) {
        if (syncing) return
        syncing = true
        if (manual) render()
        worker.execute {
            val result = if (onOpen) repo.syncOnOpen() else repo.sync(manual)
            runOnUiThread {
                syncing = false
                if (manual) when (result) {
                    Repo.SyncResult.DONE -> Motion.success(root)
                    Repo.SyncResult.FAILED -> Motion.failure(root)
                    Repo.SyncResult.SKIPPED -> Unit
                }
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
            addView(UI.text(context, text, 15f, weight = 800).apply { setPadding(dp(16), 0, 0, 0) })
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

    /** Day strip of the Cours tab: kept across renders so picking a day does not jump. */
    internal var stripScroll = -1
    internal var stripOwner: android.view.View? = null

    companion object {
        /** The home cascade plays once per launch of the app, not at every visit. */
        private var homeIntroPlayed = false

        const val EXTRA_TAB = "tab"
        const val EXTRA_INBOX = "inbox"
        private const val REQ_LOCATION = 1
        private const val REQ_NOTIFICATIONS = 2

        private val PRIVACY = listOf(
            "Sallio n'a aucun serveur : ton téléphone parle directement à Pronote.",
            "Tu te connectes sur la vraie page de ton ENT. L'app ne voit jamais ton mot de passe Pronote, seulement le jeton que Pronote donne à ce téléphone.",
            "Si tu relies la cantine, ton mot de passe Turboself est gardé chiffré : Turboself ne donne pas de connexion durable. Il n'est envoyé qu'à Turboself.",
            "Le jeton, ton emploi du temps, tes devoirs, tes notes, tes messages et ta vie scolaire sont chiffrés sur le téléphone et exclus des sauvegardes.",
            "Aucune pub, aucun traqueur, aucune statistique.",
            "Sur Pronote, l'app ne modifie que deux choses, et seulement quand tu le demandes : cocher un devoir, et marquer une actualité « J'ai vu ».",
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
