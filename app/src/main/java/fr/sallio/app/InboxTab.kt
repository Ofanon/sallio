package fr.sallio.app

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import fr.sallio.pronote.Discussion
import fr.sallio.pronote.Inbox
import fr.sallio.pronote.News
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/** The "Infos" tab: actualités, sondages and the messagerie, read-only. */
class InboxTab(private val activity: MainActivity, private val repo: Repo) {

    private val ctx = activity
    private val fr = Locale.FRANCE

    fun build(s: Snapshot, messages: Boolean, onMode: (Boolean) -> Unit): View {
        val root = UI.column(ctx)
        val news = repo.news()
        val discussions = repo.discussions()

        val seg = UI.row(ctx).apply {
            background = UI.rounded(ctx.getColor(R.color.surface2), ctx.dp(16).toFloat())
            setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
        }
        val unreadNews = news.count { !it.read }
        val unreadMessages = discussions.sumOf { it.unread }
        for ((label, mode) in listOf((if (unreadNews > 0) "Actualités · $unreadNews" else "Actualités") to false,
            (if (unreadMessages > 0) "Messages · $unreadMessages" else "Messages") to true)) {
            val on = mode == messages
            seg.addView(UI.text(ctx, label, 14f, if (on) R.color.text else R.color.text2, 800).apply {
                gravity = Gravity.CENTER
                if (on) {
                    background = UI.sticker(ctx, ctx.getColor(R.color.surface), ctx.getColor(R.color.edge), 12, 2)
                    setPadding(0, ctx.dp(8), 0, ctx.dp(10))
                } else setPadding(0, ctx.dp(8), 0, ctx.dp(8))
                setOnClickListener { if (!on) { Motion.tick(this); onMode(mode) } }
            }, UI.weight())
        }
        root.addView(seg, UI.matchWidth().apply { bottomMargin = ctx.dp(4) })

        if (!messages) {
            if (news.isEmpty()) root.addView(empty(R.drawable.ic_news, if (s.status.lastSuccess == 0L) "Les actualités arrivent à la prochaine mise à jour." else "Aucune actualité pour l'instant."), UI.matchWidth().apply { topMargin = ctx.dp(14) })
            for ((title, items) in news.groupBy { section(it.date, s.now) }) {
                root.addView(UI.section(ctx, title))
                val card = UI.card(ctx, padding = 6)
                items.forEachIndexed { i, n ->
                    if (i > 0) card.addView(divider())
                    card.addView(newsRow(n, s), UI.matchWidth())
                }
                root.addView(card, UI.matchWidth())
            }
        } else {
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
        root.addView(UI.text(ctx, "Lecture seule : Sallio ne marque rien comme lu sur Pronote. Pour répondre, ouvre Pronote.", 12f, R.color.text3).apply {
            gravity = Gravity.CENTER
            setPadding(0, ctx.dp(20), 0, 0)
        }, UI.matchWidth())
        return root
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

    private fun tile(icon: Int, accent: Boolean) = FrameLayout(ctx).apply {
        background = UI.rounded(ctx.getColor(if (accent) R.color.accent_container else R.color.surface2), ctx.dp(12).toFloat())
        addView(UI.icon(ctx, icon, if (accent) R.color.accent else R.color.text2, 20).apply {
            layoutParams = FrameLayout.LayoutParams(ctx.dp(20), ctx.dp(20), Gravity.CENTER)
        })
    }

    private fun shortDate(d: LocalDateTime?): String =
        d?.let { "${it.dayOfMonth} ${it.month.getDisplayName(TextStyle.SHORT, fr)}" }.orEmpty()

    private fun newsRow(n: News, s: Snapshot) = UI.row(ctx).apply {
        gravity = Gravity.TOP
        setPadding(ctx.dp(10), ctx.dp(12), ctx.dp(12), ctx.dp(12))
        foreground = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22000000), null, UI.rounded(Color.WHITE, ctx.dp(16).toFloat()))
        addView(tile(if (n.survey) R.drawable.ic_tasks else R.drawable.ic_news, !n.read), LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)))
        addView(UI.hgap(ctx, 12))
        val col = UI.column(ctx)
        val top = UI.row(ctx)
        top.addView(UI.oneLine(UI.title(ctx, n.title, 16f)), UI.weight())
        if (!n.read) top.addView(View(ctx).apply { background = UI.rounded(ctx.getColor(R.color.accent), ctx.dp(5).toFloat()) },
            LinearLayout.LayoutParams(ctx.dp(10), ctx.dp(10)).apply { marginStart = ctx.dp(8) })
        col.addView(top, UI.matchWidth())
        val meta = listOf(if (n.survey) "Sondage" else n.category.ifEmpty { "Information" }, n.author, shortDate(n.date)).filter { it.isNotEmpty() }.joinToString(" · ")
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
        addView(tile(R.drawable.ic_mail, d.unread > 0), LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)))
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
        if (d.unread > 0) col.addView(UI.chip(ctx, "${d.unread} non lu${if (d.unread > 1) "s" else ""}", ctx.getColor(R.color.accent), Color.WHITE, 10f), UI.wrap().apply { topMargin = ctx.dp(6) })
        addView(col, UI.weight())
        setOnClickListener { Motion.tap(this); showDiscussion(d) }
    }

    private fun empty(icon: Int, text: String) = UI.card(ctx, padding = 18).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        addView(UI.icon(ctx, icon, R.color.accent, 28))
        addView(UI.text(ctx, text, 15f, R.color.text2, 800).apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(8), 0, 0) }, UI.matchWidth())
    }

    // ---- Sheets ------------------------------------------------------------------

    private fun showNews(n: News) {
        Sheet.open(activity, activity.bottomInsetPx) { sheet, _ ->
            sheet.addView(UI.chip(ctx, if (n.survey) "Sondage" else n.category.ifEmpty { "Information" }, ctx.getColor(R.color.accent_container), ctx.getColor(R.color.accent), 12f), UI.wrap())
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
            if (n.survey) sheet.addView(UI.button(ctx, "Répondre sur Pronote", R.drawable.ic_open) { activity.openPronote() }, UI.matchWidth().apply { topMargin = ctx.dp(16) })
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
