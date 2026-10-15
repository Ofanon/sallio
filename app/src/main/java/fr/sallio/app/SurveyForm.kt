package fr.sallio.app

import android.app.AlertDialog
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import fr.sallio.pronote.News

/**
 * A survey of the Actualités, answered in Sallio: one choice (rounds), several
 * (boxes, up to PRONOTE's maximum), or a typed answer. Nothing leaves before
 * "Envoyer mes réponses" and its confirmation; then the answers go to PRONOTE
 * at once, as on its own page.
 */
object SurveyForm {

    fun add(activity: MainActivity, sheet: LinearLayout, n: News, close: () -> Unit) {
        val ctx = activity
        val draft = n.questions.map { it.answer }.toMutableList()
        n.questions.forEachIndexed { i, q ->
            sheet.addView(UI.section(ctx, "Question ${i + 1}${if (q.answered) " · répondu" else ""}"))
            val card = UI.card(ctx, padding = 16)
            // PRONOTE often titles them just "Question 1", already the section's heading.
            if (q.title.isNotEmpty() && !q.title.trim().equals("Question ${i + 1}", ignoreCase = true)) card.addView(UI.title(ctx, q.title, 16f))
            if (q.text.isNotEmpty() && q.text != q.title) card.addView(UI.text(ctx, q.text, 15f).apply { setPadding(0, ctx.dp(4), 0, 0); UI.linkify(this) })
            when (q.kind) {
                News.KIND_UNIQUE, News.KIND_MULTIPLE -> choices(activity, card, q, draft, i)
                News.KIND_TEXT -> card.addView(field(activity, draft[i].text, "Ta réponse") { draft[i] = draft[i].copy(text = it) }, UI.matchWidth().apply { topMargin = ctx.dp(10) })
            }
            sheet.addView(card, UI.matchWidth())
        }
        if (n.questions.none { it.needsInput }) return
        val notice = UI.text(ctx, "", 14f, R.color.alert, 800).apply { visibility = View.GONE; setPadding(ctx.dp(4), ctx.dp(12), 0, 0) }
        sheet.addView(notice, UI.matchWidth())
        val already = n.questions.any { it.answered }
        lateinit var send: View
        send = UI.button(ctx, if (already) "Modifier mes réponses" else "Envoyer mes réponses") {
            val missing = n.questions.indices.firstOrNull { i -> n.questions[i].needsInput && draft[i].chosen.isEmpty() && draft[i].text.isBlank() }
            if (missing != null) {
                notice.text = "Il manque la réponse à la question ${missing + 1}."
                notice.visibility = View.VISIBLE
                Motion.failure(send)
                return@button
            }
            AlertDialog.Builder(ctx)
                .setTitle("Envoyer tes réponses ?")
                .setMessage("Elles partent sur Pronote" + (if (n.author.isNotEmpty()) ", pour ${n.author}" else "") + ".")
                .setPositiveButton("Envoyer") { _, _ ->
                    send.isEnabled = false
                    send.alpha = 0.5f
                    notice.visibility = View.GONE
                    activity.worker.execute {
                        val error = activity.repo.answerNews(n, draft.toList())
                        activity.runOnUiThread {
                            if (error == null) {
                                Motion.success(send)
                                android.widget.Toast.makeText(ctx, "Réponses envoyées à Pronote", android.widget.Toast.LENGTH_SHORT).show()
                                activity.refreshScreen()
                                close()
                            } else {
                                send.isEnabled = true
                                send.alpha = 1f
                                notice.text = error
                                notice.visibility = View.VISIBLE
                                Motion.failure(send)
                            }
                        }
                    }
                }
                .setNegativeButton("Annuler", null)
                .show()
        }
        sheet.addView(send, UI.matchWidth().apply { topMargin = ctx.dp(16) })
        sheet.addView(UI.text(ctx, "Tes réponses ne partent qu'avec ce bouton.", 12f, R.color.text3).apply {
            gravity = Gravity.CENTER
            setPadding(0, ctx.dp(8), 0, 0)
        }, UI.matchWidth())
    }

    /** The choices of [q]: rounds for one, boxes for several; a typed field under "Autre". */
    private fun choices(activity: MainActivity, card: LinearLayout, q: News.Question, draft: MutableList<News.Answer>, i: Int) {
        val ctx = activity
        val multiple = q.kind == News.KIND_MULTIPLE
        val boxes = mutableListOf<() -> Unit>()
        var free: View? = null
        fun repaintAll() {
            boxes.forEach { it() }
            free?.visibility = if (draft[i].chosen.any { it in q.freeChoices }) View.VISIBLE else View.GONE
        }
        q.choices.forEachIndexed { c, label ->
            val rank = q.ranks.getOrElse(c) { c + 1 }
            val box = FrameLayout(ctx)
            fun paint() {
                val on = rank in draft[i].chosen
                box.removeAllViews()
                val radius = ctx.dp(if (multiple) 7 else 11).toFloat()
                box.background = if (on) UI.rounded(ctx.getColor(R.color.accent), radius, ctx.dp(2), ctx.getColor(R.color.accent_edge))
                else UI.rounded(0, radius, ctx.dp(2), ctx.getColor(R.color.box_edge))
                if (on) box.addView(if (multiple) UI.icon(ctx, R.drawable.ic_check, R.color.on_accent, 14).apply {
                    layoutParams = FrameLayout.LayoutParams(ctx.dp(14), ctx.dp(14), Gravity.CENTER)
                } else View(ctx).apply { background = UI.rounded(ctx.getColor(R.color.on_accent), ctx.dp(4).toFloat()) }.also {
                    it.layoutParams = FrameLayout.LayoutParams(ctx.dp(8), ctx.dp(8), Gravity.CENTER)
                })
            }
            paint()
            boxes += ::paint
            card.addView(UI.row(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, ctx.dp(10), 0, 0)
                addView(box, LinearLayout.LayoutParams(ctx.dp(22), ctx.dp(22)))
                addView(UI.hgap(ctx, 12))
                addView(UI.text(ctx, label, 15f), UI.weight())
                setOnClickListener {
                    val chosen = draft[i].chosen
                    draft[i] = draft[i].copy(chosen = when {
                        !multiple -> setOf(rank)
                        rank in chosen -> chosen - rank
                        q.maxChoices != null && chosen.size >= q.maxChoices -> { Motion.failure(this); return@setOnClickListener }
                        else -> chosen + rank
                    })
                    Motion.tick(this)
                    repaintAll()
                }
            }, UI.matchWidth())
        }
        q.maxChoices?.let { if (multiple) card.addView(UI.text(ctx, "$it choix au maximum", 12f, R.color.text3).apply { setPadding(0, ctx.dp(8), 0, 0) }) }
        if (q.freeChoices.isNotEmpty()) {
            free = field(activity, draft[i].text, "Précise ta réponse") { draft[i] = draft[i].copy(text = it) }
            card.addView(free, UI.matchWidth().apply { topMargin = ctx.dp(10) })
        }
        repaintAll()
    }

    private fun field(activity: MainActivity, text: String, hint: String, onText: (String) -> Unit): EditText = EditText(activity).apply {
        this.hint = hint
        setText(text)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        minLines = 2
        gravity = Gravity.TOP
        background = UI.rounded(activity.getColor(R.color.surface2), activity.dp(14).toFloat(), activity.dp(2), activity.getColor(R.color.edge))
        setPadding(activity.dp(14), activity.dp(12), activity.dp(14), activity.dp(12))
        textSize = 15f
        typeface = UI.body(activity, 700)
        setTextColor(activity.getColor(R.color.text))
        setHintTextColor(activity.getColor(R.color.text3))
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = onText(s?.toString().orEmpty())
        })
    }

    /**
     * "J'ai pris connaissance": the acknowledgement PRONOTE waits for on some
     * informations. Spins while it travels; refused, the button comes back
     * unticked so it can be tried again.
     */
    fun ackButton(activity: MainActivity, n: News): View {
        val slot = FrameLayout(activity)
        var busy = false
        fun build(on: Boolean) {
            slot.removeAllViews()
            slot.addView(UI.checkButton(activity, "J'ai pris connaissance", "Pris connaissance", on, { busy }) {
                busy = true
                UI.refreshChecks()
                activity.worker.execute {
                    val error = activity.repo.answerNews(n, listOf(News.Answer()))
                    activity.runOnUiThread {
                        busy = false
                        if (error == null) activity.refreshScreen()
                        else {
                            android.widget.Toast.makeText(activity, error, android.widget.Toast.LENGTH_LONG).show()
                            build(false)
                        }
                        UI.refreshChecks()
                    }
                }
            }, FrameLayout.LayoutParams(-1, -2))
        }
        build(n.acked)
        return slot
    }
}
