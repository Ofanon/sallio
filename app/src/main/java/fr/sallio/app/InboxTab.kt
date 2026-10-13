package fr.sallio.app

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import fr.sallio.pronote.Discussion
import fr.sallio.pronote.Inbox
import fr.sallio.pronote.News
import fr.sallio.pronote.SchoolEvent
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * The "Infos" tab: actualités, sondages, the messagerie and the vie scolaire.
 * Read-only, except "J'ai vu" on an actualité, which marks it read on PRONOTE.
 */
class InboxTab(private val activity: MainActivity, private val repo: Repo) {

    private val ctx = activity
    private val fr = Locale.FRANCE

    enum class Mode { NEWS, MESSAGES, LIFE }

    fun build(s: Snapshot, mode: Mode, onMode: (Mode) -> Unit): View {
        val root = UI.column(ctx)
        val news = repo.news()
        val discussions = repo.discussions()

        val seg = UI.row(ctx).apply {
            background = UI.rounded(ctx.getColor(R.color.surface2), ctx.dp(16).toFloat())
            setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
        }
        val unreadNews = news.count { !repo.isRead(it) }
        val unreadMessages = discussions.sumOf { it.unread }
        // Three views share the width: counts stay short ("Messages · 2").
        for ((label, m) in listOf((if (unreadNews > 0) "Actus · $unreadNews" else "Actualités") to Mode.NEWS,
            (if (unreadMessages > 0) "Messages · $unreadMessages" else "Messages") to Mode.MESSAGES,
            "Vie scolaire" to Mode.LIFE)) {
            val on = m == mode
            seg.addView(UI.oneLine(UI.text(ctx, label, 13f, if (on) R.color.text else R.color.text2, 800)).apply {
                gravity = Gravity.CENTER
                if (on) {
                    background = UI.sticker(ctx, ctx.getColor(R.color.surface), ctx.getColor(R.color.edge), 12, 2)
                    setPadding(ctx.dp(4), ctx.dp(8), ctx.dp(4), ctx.dp(10))
                } else setPadding(ctx.dp(4), ctx.dp(8), ctx.dp(4), ctx.dp(8))
                setOnClickListener { if (!on) { Motion.tick(this); onMode(m) } }
            }, UI.weight())
        }
        root.addView(seg, UI.matchWidth().apply { bottomMargin = ctx.dp(4) })

        when (mode) {
            Mode.NEWS -> {
                if (news.isEmpty()) root.addView(empty(R.drawable.ic_news, if (s.status.lastSuccess == 0L) "Les actualités arrivent à la prochaine mise à jour." else "Aucune actualité pour l'instant."), UI.matchWidth().apply { topMargin = ctx.dp(14) })
                // What is still to read comes first, the newest one in big.
                val unread = news.filter { !repo.isRead(it) }
                unread.firstOrNull()?.let { first ->
                    root.addView(UI.section(ctx, if (unread.size > 1) "À lire · ${unread.size}" else "À lire"))
                    root.addView(featureCard(first), UI.matchWidth())
                    if (unread.size > 1) {
                        val card = UI.card(ctx, padding = 6)
                        unread.drop(1).forEachIndexed { i, n ->
                            if (i > 0) card.addView(divider())
                            card.addView(newsRow(n), UI.matchWidth())
                        }
                        root.addView(card, UI.matchWidth().apply { topMargin = ctx.dp(10) })
                    }
                }
                for ((title, items) in news.filter { repo.isRead(it) }.groupBy { section(it.date, s.now) }) {
                    root.addView(UI.section(ctx, if (unread.isEmpty()) title else "Déjà vu · ${title.replaceFirstChar { it.lowercase() }}"))
                    val card = UI.card(ctx, padding = 6)
                    items.forEachIndexed { i, n ->
                        if (i > 0) card.addView(divider())
                        card.addView(newsRow(n), UI.matchWidth())
                    }
                    root.addView(card, UI.matchWidth())
                }
            }
            Mode.MESSAGES -> {
                if (discussions.isEmpty()) root.addView(empty(R.drawable.ic_chat, if (s.status.lastSuccess == 0L) "Tes messages arrivent à la prochaine mise à jour." else "Aucun message."), UI.matchWidth().apply { topMargin = ctx.dp(14) })
                else {
                    root.addView(UI.section(ctx, "Conversations"))
                    val card = UI.card(ctx, padding = 6)
                    discussions.forEachIndexed { i, d ->
                        if (i > 0) card.addView(divider())
                        card.addView(discussionRow(d), UI.matchWidth())
                    }
                    root.addView(card, UI.matchWidth())
                }
            }
            Mode.LIFE -> schoolLife(root)
        }
        val footer = when (mode) {
            Mode.LIFE -> "Lecture seule. Pour justifier une absence, ouvre Pronote."
            Mode.NEWS -> "Une actualité n'est marquée lue sur Pronote que quand tu touches « J'ai vu »."
            Mode.MESSAGES -> "Lecture seule : Sallio ne marque aucun message comme lu. Pour répondre, ouvre Pronote."
        }
        root.addView(UI.text(ctx, footer, 12f, R.color.text3).apply {
            gravity = Gravity.CENTER
            setPadding(0, ctx.dp(20), 0, 0)
        }, UI.matchWidth())
        return root
    }

    // ---- Vie scolaire ----------------------------------------------------------------

    private fun schoolLife(root: LinearLayout) {
        val life = repo.schoolLife()
        if (life == null || life.events.isEmpty()) {
            root.addView(empty(R.drawable.ic_absent, if (life == null) "La vie scolaire arrive à la prochaine mise à jour." else "Aucune absence, aucun retard, aucune punition cette année."),
                UI.matchWidth().apply { topMargin = ctx.dp(14) })
            return
        }
        val absences = life.events.filter { it.kind == SchoolEvent.Kind.ABSENCE }
        val lates = life.events.filter { it.kind == SchoolEvent.Kind.LATE }

        // PRONOTE's own totals for the year.
        val stats = UI.card(ctx, padding = 14).apply { orientation = LinearLayout.HORIZONTAL }
        fun stat(value: String, label: String) = UI.column(ctx).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(UI.title(ctx, value, 24f).apply { gravity = Gravity.CENTER }, UI.matchWidth())
            addView(UI.text(ctx, label, 12f, R.color.text2).apply { gravity = Gravity.CENTER }, UI.matchWidth())
        }
        stats.addView(stat(absences.size.toString(), if (absences.size > 1) "absences" else "absence"), UI.weight())
        stats.addView(stat(life.absenceHours ?: "—", "de cours manqués"), UI.weight())
        stats.addView(stat(lates.size.toString(), if (lates.size > 1) "retards" else "retard"), UI.weight())
        root.addView(UI.section(ctx, "Cette année"))
        root.addView(stats, UI.matchWidth())

        val waiting = life.events.count { it.toJustify }
        if (waiting > 0) {
            root.addView(UI.card(ctx, R.color.warn_bg, R.color.warn_edge, padding = 14).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(UI.icon(ctx, R.drawable.ic_warning, R.color.warn, 22))
                addView(UI.hgap(ctx, 12))
                addView(UI.text(ctx, (if (waiting > 1) "$waiting absences ou retards attendent" else "Une absence ou un retard attend") +
                    " un motif. Tes parents peuvent le donner sur Pronote.", 14f, R.color.text), UI.weight())
            }, UI.matchWidth().apply { topMargin = ctx.dp(12) })
        }

        val groups = listOf(
            "Punitions et sanctions" to life.events.filter { it.kind == SchoolEvent.Kind.PUNISHMENT || it.kind == SchoolEvent.Kind.SANCTION },
            "Absences" to absences,
            "Retards" to lates,
            "Dispenses" to life.events.filter { it.kind == SchoolEvent.Kind.EXEMPTION },
        )
        for ((title, events) in groups) {
            if (events.isEmpty()) continue
            root.addView(UI.section(ctx, title))
            val card = UI.card(ctx, padding = 6)
            events.forEachIndexed { i, e ->
                if (i > 0) card.addView(divider())
                card.addView(eventRow(e), UI.matchWidth())
            }
            root.addView(card, UI.matchWidth())
        }
    }

    private fun eventIcon(e: SchoolEvent) = when (e.kind) {
        SchoolEvent.Kind.ABSENCE -> R.drawable.ic_absent
        SchoolEvent.Kind.LATE -> R.drawable.ic_clock
        SchoolEvent.Kind.EXEMPTION -> e.subject?.let { Subjects.style(it).icon } ?: R.drawable.ic_run
        SchoolEvent.Kind.PUNISHMENT, SchoolEvent.Kind.SANCTION -> R.drawable.ic_warning
    }

    /** The status chip: orange while a reason is awaited, red when refused, green when settled. */
    private fun statusChip(e: SchoolEvent): View? {
        val label = SchoolLifeText.status(e) ?: return null
        val (bg, fg) = when {
            e.toJustify -> R.color.warn_bg to R.color.warn
            e.justified == false -> R.color.alert_bg to R.color.alert
            else -> R.color.success_bg to R.color.success
        }
        return UI.chip(ctx, label, ctx.getColor(bg), ctx.getColor(fg), 11f)
    }

    private fun eventRow(e: SchoolEvent) = UI.row(ctx).apply {
        gravity = Gravity.TOP
        setPadding(ctx.dp(10), ctx.dp(12), ctx.dp(12), ctx.dp(12))
        foreground = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22000000), null, UI.rounded(Color.WHITE, ctx.dp(16).toFloat()))
        addView(tile(eventIcon(e), e.toJustify), LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)))
        addView(UI.hgap(ctx, 12))
        val col = UI.column(ctx)
        col.addView(UI.oneLine(UI.title(ctx, SchoolLifeText.title(e), 16f)), UI.matchWidth())
        col.addView(UI.text(ctx, listOfNotNull(SchoolLifeText.date(e), e.hours).joinToString(" · "), 12f, R.color.text2).apply { setPadding(0, ctx.dp(2), 0, 0) })
        SchoolLifeText.reasons(e)?.let { col.addView(UI.oneLine(UI.text(ctx, it, 14f)).apply { setPadding(0, ctx.dp(4), 0, 0) }) }
        statusChip(e)?.let { col.addView(it, UI.wrap().apply { topMargin = ctx.dp(6) }) }
        addView(col, UI.weight())
        setOnClickListener { Motion.tap(this); showEvent(e) }
    }

    private fun section(date: LocalDateTime?, now: LocalDateTime): String {
        if (date == null) return "Plus ancien"
        val days = java.time.temporal.ChronoUnit.DAYS.between(date.toLocalDate(), now.toLocalDate())
        return when {
            days <= 0 -> "Aujourd'hui"
            days == 1L -> "Hier"
            days < 7 -> "Cette semaine"
            days < 31 -> "Ce mois-ci"
            else -> "Plus ancien"
        }
    }

    private fun divider() = UI.divider(ctx).apply { (layoutParams as LinearLayout.LayoutParams).apply { marginStart = ctx.dp(62); marginEnd = ctx.dp(12) } }

    /** Category of an actualité as shown: "Sondage", "Information", or PRONOTE's own. */
    private fun category(n: News) = if (n.survey) "Sondage" else n.category.ifEmpty { "Information" }

    /** Rounded tile in a family's colours (a news category, a person). */
    private fun familyTile(icon: Int, family: Subjects.Family) = FrameLayout(ctx).apply {
        background = UI.rounded(Subjects.solid(family), ctx.dp(12).toFloat())
        addView(UI.icon(ctx, icon, Subjects.deep(family), 20, tintIsColor = true).apply {
            layoutParams = FrameLayout.LayoutParams(ctx.dp(20), ctx.dp(20), Gravity.CENTER)
        })
    }

    /** Round badge with a person's initials, in a colour of their own. */
    private fun initials(name: String): View {
        val family = Subjects.familyFor(name)
        // "M. MARTIN P." -> "MP": civility dropped, first letter of the first two words.
        val letters = name.replace(Regex("^(M\\.|Mme|Mlle|Mr)\\s+"), "").split(" ", "-")
            .filter { w -> w.any { it.isLetter() } }.take(2)
            .joinToString("") { w -> w.first { it.isLetter() }.uppercase() }.ifEmpty { "?" }
        return android.widget.TextView(ctx).apply {
            text = letters
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = UI.display(ctx)
            setTextColor(Subjects.deep(family))
            background = UI.rounded(Subjects.solid(family), ctx.dp(20).toFloat())
        }
    }

    /** The newest actualité to read, large, in its category's colour. */
    private fun featureCard(n: News): View {
        val family = Subjects.familyFor(category(n))
        val ink = Subjects.onSolid(family)
        val card = UI.column(ctx).apply {
            background = UI.sticker(ctx, Subjects.solid(family), Subjects.edge(ctx, family), 24, 5, pressable = true)
            UI.stickerPadding(this, 18, 14, 5)
        }
        val top = UI.row(ctx)
        top.addView(UI.icon(ctx, if (n.survey) R.drawable.ic_tasks else R.drawable.ic_news, ink, 16, tintIsColor = true))
        top.addView(UI.text(ctx, category(n).uppercase(fr), 12f, weight = 900).apply {
            setTextColor(ink); alpha = 0.85f; letterSpacing = 0.06f; setPadding(ctx.dp(6), 0, 0, 0)
        }, UI.weight())
        shortDate(n.date).takeIf { it.isNotEmpty() }?.let { top.addView(UI.text(ctx, it, 12f, weight = 800).apply { setTextColor(ink); alpha = 0.85f }) }
        card.addView(top, UI.matchWidth())
        card.addView(UI.title(ctx, n.title, 21f).apply { setTextColor(ink); setPadding(0, ctx.dp(8), 0, 0) })
        val preview = if (n.survey) n.questions.firstOrNull()?.let { it.title.ifEmpty { it.text } }.orEmpty() else n.content
        if (preview.isNotEmpty()) card.addView(UI.text(ctx, preview, 15f).apply {
            setTextColor(ink); alpha = 0.92f
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, ctx.dp(6), 0, 0)
        })
        val bottom = UI.row(ctx).apply { setPadding(0, ctx.dp(12), 0, 0) }
        bottom.addView(UI.oneLine(UI.text(ctx, n.author, 13f, weight = 800).apply { setTextColor(ink); alpha = 0.85f }), UI.weight())
        card.addView(bottom, UI.matchWidth())
        // "J'ai vu" right on the card: no need to open it to clear it.
        card.addView(UI.checkButton(ctx, "J'ai vu", "Vue", false) { activity.setNewsRead(n) }, UI.matchWidth().apply { topMargin = ctx.dp(12) })
        card.setOnClickListener { Motion.tap(card); showNews(n) }
        // One vibration: the tap above, none on release.
        Motion.pressable(card, haptic = false)
        return card
    }

    private fun tile(icon: Int, accent: Boolean) = FrameLayout(ctx).apply {
        background = UI.rounded(ctx.getColor(if (accent) R.color.accent_container else R.color.surface2), ctx.dp(12).toFloat())
        addView(UI.icon(ctx, icon, if (accent) R.color.on_accent_container else R.color.text2, 20).apply {
            layoutParams = FrameLayout.LayoutParams(ctx.dp(20), ctx.dp(20), Gravity.CENTER)
        })
    }

    private fun shortDate(d: LocalDateTime?): String =
        d?.let { "${it.dayOfMonth} ${it.month.getDisplayName(TextStyle.SHORT, fr)}" }.orEmpty()

    private fun newsRow(n: News) = UI.row(ctx).apply {
        val read = repo.isRead(n)
        gravity = Gravity.TOP
        setPadding(ctx.dp(10), ctx.dp(12), ctx.dp(12), ctx.dp(12))
        foreground = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22000000), null, UI.rounded(Color.WHITE, ctx.dp(16).toFloat()))
        addView(familyTile(if (n.survey) R.drawable.ic_tasks else R.drawable.ic_news, Subjects.familyFor(category(n))), LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)))
        addView(UI.hgap(ctx, 12))
        val col = UI.column(ctx)
        val top = UI.row(ctx)
        top.addView(UI.oneLine(UI.title(ctx, n.title, 16f, if (read) R.color.text2 else R.color.text)), UI.weight())
        if (!read) top.addView(View(ctx).apply { background = UI.rounded(ctx.getColor(R.color.accent_ink), ctx.dp(5).toFloat()) },
            LinearLayout.LayoutParams(ctx.dp(10), ctx.dp(10)).apply { marginStart = ctx.dp(8) })
        col.addView(top, UI.matchWidth())
        val meta = listOf(category(n), n.author, shortDate(n.date)).filter { it.isNotEmpty() }.joinToString(" · ")
        col.addView(UI.oneLine(UI.text(ctx, meta, 12f, R.color.text2)).apply { setPadding(0, ctx.dp(2), 0, 0) })
        val preview = if (n.survey) n.questions.firstOrNull()?.let { it.title.ifEmpty { it.text } }.orEmpty() else n.content
        if (preview.isNotEmpty()) col.addView(UI.text(ctx, preview, 14f).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, ctx.dp(4), 0, 0)
        })
        addView(col, UI.weight())
        setOnClickListener { Motion.tap(this); showNews(n) }
    }

    private fun discussionRow(d: Discussion) = UI.row(ctx).apply {
        gravity = Gravity.TOP
        setPadding(ctx.dp(10), ctx.dp(12), ctx.dp(12), ctx.dp(12))
        foreground = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22000000), null, UI.rounded(Color.WHITE, ctx.dp(16).toFloat()))
        addView(initials(d.with), LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)))
        addView(UI.hgap(ctx, 12))
        val col = UI.column(ctx)
        val top = UI.row(ctx)
        top.addView(UI.oneLine(UI.title(ctx, d.subject, 16f)), UI.weight())
        top.addView(UI.text(ctx, d.dateLabel, 12f, R.color.text3))
        col.addView(top, UI.matchWidth())
        col.addView(UI.oneLine(UI.text(ctx, d.with, 12f, R.color.text2)).apply { setPadding(0, ctx.dp(2), 0, 0) })
        d.messages.lastOrNull()?.let { m ->
            col.addView(UI.text(ctx, (if (m.mine) "Toi : " else "") + m.text, 14f).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, ctx.dp(4), 0, 0)
            })
        }
        if (d.unread > 0) col.addView(UI.chip(ctx, "${d.unread} non lu${if (d.unread > 1) "s" else ""}", ctx.getColor(R.color.accent), ctx.getColor(R.color.on_accent), 10f), UI.wrap().apply { topMargin = ctx.dp(6) })
        addView(col, UI.weight())
        setOnClickListener { Motion.tap(this); showDiscussion(d) }
    }

    private fun empty(icon: Int, text: String) = UI.card(ctx, padding = 18).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        addView(UI.icon(ctx, icon, R.color.accent_ink, 28))
        addView(UI.text(ctx, text, 15f, R.color.text2, 800).apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(8), 0, 0) }, UI.matchWidth())
    }

    // ---- Sheets ------------------------------------------------------------------

    private fun showNews(n: News) {
        Sheet.open(activity, activity.bottomInsetPx) { sheet, dialog ->
            val family = Subjects.familyFor(category(n))
            sheet.addView(UI.chip(ctx, category(n), Subjects.tint(ctx, family), Subjects.ink(ctx, family), 12f), UI.wrap())
            sheet.addView(UI.title(ctx, n.title, 23f).apply { setPadding(0, ctx.dp(10), 0, 0) })
            sheet.addView(UI.text(ctx, listOf(n.author, n.date?.let { "${Agenda.dayName(it.toLocalDate())} ${it.dayOfMonth} ${it.month.getDisplayName(TextStyle.FULL, fr)}" }.orEmpty())
                .filter { it.isNotEmpty() }.joinToString(" · "), 13f, R.color.text2).apply { setPadding(0, ctx.dp(4), 0, ctx.dp(8)) })
            if (!n.survey) {
                val card = UI.card(ctx, padding = 16)
                val empty = if (n.files == null) "Contenu pas encore chargé : il arrive à la prochaine mise à jour." else "Pas de texte dans cette actualité."
                card.addView(UI.text(ctx, n.content.ifEmpty { empty }, 16f, weight = 600).apply {
                    setLineSpacing(0f, 1.15f)
                    UI.linkify(this)
                })
                sheet.addView(card, UI.matchWidth().apply { topMargin = ctx.dp(8) })
            } else {
                if (n.questions.isEmpty()) sheet.addView(UI.text(ctx, "Questions pas encore chargées : elles arrivent à la prochaine mise à jour.", 14f, R.color.text2))
                n.questions.forEachIndexed { i, q ->
                    sheet.addView(UI.section(ctx, "Question ${i + 1}${if (q.answered) " · répondu" else ""}"))
                    val card = UI.card(ctx, padding = 16)
                    if (q.title.isNotEmpty()) card.addView(UI.title(ctx, q.title, 16f))
                    if (q.text.isNotEmpty() && q.text != q.title) card.addView(UI.text(ctx, q.text, 15f).apply { setPadding(0, ctx.dp(4), 0, 0); UI.linkify(this) })
                    q.choices.forEach { c ->
                        card.addView(UI.row(ctx).apply {
                            setPadding(0, ctx.dp(8), 0, 0)
                            addView(View(ctx).apply { background = UI.rounded(0, ctx.dp(8).toFloat(), ctx.dp(2), ctx.getColor(R.color.box_edge)) }, LinearLayout.LayoutParams(ctx.dp(18), ctx.dp(18)))
                            addView(UI.hgap(ctx, 10))
                            addView(UI.text(ctx, c, 15f), UI.weight())
                        }, UI.matchWidth())
                    }
                    sheet.addView(card, UI.matchWidth())
                }
            }
            val files = n.files.orEmpty()
            if (files.isNotEmpty()) {
                sheet.addView(UI.section(ctx, if (files.size > 1) "Pièces jointes et liens" else if (files[0].link != null) "Lien" else "Pièce jointe"))
                sheet.addView(Attachments.card(activity, files, "news|${n.key}") { logged, name -> Inbox.newsFileUrl(logged, n.key, name) }, UI.matchWidth())
            }
            if (n.survey) sheet.addView(UI.ghostButton(ctx, "Répondre sur Pronote") { activity.openPronote() }, UI.matchWidth().apply { topMargin = ctx.dp(16) })
            // "J'ai vu": marks it read here and on PRONOTE (sent with the homework ticks), only on this tap.
            sheet.addView(UI.checkButton(ctx, "J'ai vu", "Vue", repo.isRead(n)) { activity.setNewsRead(n) }, UI.matchWidth().apply { topMargin = ctx.dp(16) })
        }
    }

    private fun showEvent(e: SchoolEvent) {
        Sheet.open(activity, activity.bottomInsetPx) { sheet, _ ->
            statusChip(e)?.let { sheet.addView(it, UI.wrap()) }
            sheet.addView(UI.title(ctx, SchoolLifeText.title(e), 23f).apply { setPadding(0, ctx.dp(10), 0, 0) })
            sheet.addView(UI.text(ctx, SchoolLifeText.date(e), 13f, R.color.text2).apply { setPadding(0, ctx.dp(4), 0, ctx.dp(8)) })
            val card = UI.card(ctx, padding = 16)
            fun line(label: String, value: String?) {
                if (value.isNullOrBlank()) return
                if (card.childCount > 0) card.addView(UI.gap(ctx, 10))
                card.addView(UI.text(ctx, label, 12f, R.color.text2, 800))
                card.addView(UI.text(ctx, value, 16f, weight = 600).apply { setPadding(0, ctx.dp(2), 0, 0) })
            }
            line("Durée", e.hours?.let { "$it de cours" } ?: e.minutes?.let { "$it min" })
            line(if (e.reasons.size > 1) "Motifs" else "Motif", SchoolLifeText.reasons(e) ?: if (e.toJustify) "Pas encore donné" else null)
            line("Matière", e.subject?.let { Agenda.pretty(it) })
            line("Donnée par", e.by?.let { Agenda.prettyName(it) })
            line("Travail à faire", e.work)
            line("Commentaire", e.comment)
            if (card.childCount > 0) sheet.addView(card, UI.matchWidth().apply { topMargin = ctx.dp(8) })
            if (e.toJustify) sheet.addView(UI.ghostButton(ctx, "Ouvrir Pronote") { activity.openPronote() }, UI.matchWidth().apply { topMargin = ctx.dp(16) })
        }
    }

    private fun showDiscussion(d: Discussion) {
        Sheet.open(activity, activity.bottomInsetPx) { sheet, _ ->
            sheet.addView(UI.title(ctx, d.subject, 22f))
            sheet.addView(UI.text(ctx, d.with, 13f, R.color.text2).apply { setPadding(0, ctx.dp(4), 0, ctx.dp(12)) })
            if (d.messages.isEmpty()) sheet.addView(UI.text(ctx, "Messages pas encore chargés : ils arrivent à la prochaine mise à jour.", 14f, R.color.text2))
            for (m in d.messages) {
                val bubble = UI.column(ctx).apply {
                    background = if (m.mine) UI.sticker(ctx, ctx.getColor(R.color.accent_container), ctx.getColor(R.color.accent_container_edge), 18, 3)
                    else UI.sticker(ctx, ctx.getColor(R.color.surface), ctx.getColor(R.color.edge), 18, 3)
                    UI.stickerPadding(this, 14, 10, 3)
                }
                bubble.addView(UI.text(ctx, listOf(if (m.mine) "Toi" else m.author, m.date?.let { "${it.dayOfMonth}/${it.monthValue} ${Agenda.time(it)}" }.orEmpty())
                    .filter { it.isNotEmpty() }.joinToString(" · "), 12f, R.color.text2, 800))
                bubble.addView(UI.text(ctx, m.text, 15f).apply { setPadding(0, ctx.dp(4), 0, 0); UI.linkify(this) })
                sheet.addView(bubble, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, -2).apply {
                    topMargin = ctx.dp(8)
                    if (m.mine) marginStart = ctx.dp(36) else marginEnd = ctx.dp(36)
                })
                m.files?.takeIf { it.isNotEmpty() }?.let { files ->
                    sheet.addView(Attachments.card(activity, files, "msg|${d.key}") { logged, name -> Inbox.messageFileUrl(logged, d.key, name) },
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, -2).apply {
                            topMargin = ctx.dp(6)
                            if (m.mine) marginStart = ctx.dp(36) else marginEnd = ctx.dp(36)
                        })
                }
            }
            sheet.addView(UI.ghostButton(ctx, "Répondre sur Pronote") { activity.openPronote() }, UI.matchWidth().apply { topMargin = ctx.dp(16) })
        }
    }
}
