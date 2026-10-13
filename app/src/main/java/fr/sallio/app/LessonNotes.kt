package fr.sallio.app

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import fr.sallio.pronote.Lesson
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The student's own notes on a lesson ("amener sa blouse"), kept encrypted
 * in the [Vault] and never sent anywhere. A note belongs to one lesson, found
 * again by its start and subject; notes of lessons more than a week old are
 * dropped.
 */
object LessonNotes {

    private const val ENTRY = "lesson_notes"
    @Volatile private var vault: Vault? = null

    private fun vault(context: Context) = vault ?: synchronized(this) { vault ?: Vault(context.applicationContext).also { vault = it } }

    fun key(lesson: Lesson) = "${lesson.start}|${lesson.subject}"

    @Synchronized
    private fun all(context: Context): MutableMap<String, List<String>> {
        val o = runCatching { JSONObject(vault(context).read(ENTRY) ?: "{}") }.getOrDefault(JSONObject())
        val map = LinkedHashMap<String, List<String>>()
        for (k in o.keys()) {
            val a = o.optJSONArray(k) ?: continue
            map[k] = (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
        }
        return map
    }

    @Synchronized
    private fun save(context: Context, map: Map<String, List<String>>) {
        val limit = LocalDateTime.now().minusDays(7)
        val o = JSONObject()
        for ((k, v) in map) {
            val start = runCatching { LocalDateTime.parse(k.substringBefore('|')) }.getOrNull() ?: continue
            if (v.isNotEmpty() && start > limit) o.put(k, JSONArray(v))
        }
        vault(context).write(ENTRY, o.toString())
    }

    fun of(context: Context, lesson: Lesson): List<String> = all(context)[key(lesson)].orEmpty()

    fun add(context: Context, lesson: Lesson, text: String) {
        val t = text.trim().takeIf { it.isNotEmpty() } ?: return
        val map = all(context)
        map[key(lesson)] = map[key(lesson)].orEmpty() + t
        save(context, map)
        Analytics.event("note_cours_ajoutee")
    }

    fun remove(context: Context, lesson: Lesson, index: Int) {
        val map = all(context)
        map[key(lesson)] = map[key(lesson)].orEmpty().filterIndexed { i, _ -> i != index }
        save(context, map)
    }

    /** The day's notes, lesson by lesson in the order of the day. */
    fun ofDay(context: Context, lessons: List<Lesson>, day: LocalDate): List<Pair<Lesson, String>> {
        val map = all(context)
        return lessons.filter { it.start.toLocalDate() == day }.sortedBy { it.start }
            .flatMap { l -> map[key(l)].orEmpty().map { l to it } }
    }

    /** Logging out erases the vault: forget what was read from it. */
    fun clear() = synchronized(this) { vault = null }

    // ---- Views -------------------------------------------------------------------------

    /** "📌 Amener sa blouse", one line, for the lesson cards; null without a note. */
    fun line(context: Context, lesson: Lesson, color: Int, sizeSp: Float = 12f): View? {
        val notes = of(context, lesson)
        if (notes.isEmpty()) return null
        return UI.row(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(4), 0, 0)
            addView(UI.icon(context, R.drawable.ic_pin, color, 13, tintIsColor = true))
            addView(UI.hgap(context, 4))
            addView(UI.oneLine(UI.text(context, notes.first() + if (notes.size > 1) " · +${notes.size - 1}" else "", sizeSp, weight = 800).apply { setTextColor(color) }), UI.weight())
        }
    }

    /** The lesson sheet's "À ne pas oublier": the notes, each removable, and a field to add one. */
    fun section(activity: MainActivity, lesson: Lesson): View {
        val ctx = activity
        val box = UI.column(ctx)
        box.addView(UI.section(ctx, "À ne pas oublier"))
        val card = UI.card(ctx, padding = 12)
        box.addView(card, UI.matchWidth())
        val list = UI.column(ctx)
        card.addView(list, UI.matchWidth())
        fun fill() {
            list.removeAllViews()
            of(ctx, lesson).forEachIndexed { i, note ->
                list.addView(UI.row(ctx).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(ctx.dp(2), ctx.dp(8), 0, ctx.dp(8))
                    addView(UI.icon(ctx, R.drawable.ic_pin, R.color.accent_ink, 18))
                    addView(UI.hgap(ctx, 10))
                    addView(UI.text(ctx, note, 15f, R.color.text, 800), UI.weight())
                    addView(UI.icon(ctx, R.drawable.ic_close, R.color.text3, 18).apply {
                        contentDescription = "Supprimer la note"
                        setPadding(ctx.dp(6), ctx.dp(6), ctx.dp(6), ctx.dp(6))
                        layoutParams = LinearLayout.LayoutParams(ctx.dp(32), ctx.dp(32))
                        setOnClickListener {
                            Motion.tick(this)
                            remove(ctx, lesson, i)
                            fill()
                            activity.refreshScreen()
                            Widgets.refreshAll(ctx.applicationContext)
                        }
                    })
                }, UI.matchWidth())
            }
        }
        fill()
        val field = EditText(ctx).apply {
            hint = "Ex. : amener sa blouse"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = UI.rounded(ctx.getColor(R.color.surface2), ctx.dp(14).toFloat(), ctx.dp(2), ctx.getColor(R.color.edge))
            setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
            textSize = 15f
            typeface = UI.body(ctx, 700)
            setTextColor(ctx.getColor(R.color.text))
            setHintTextColor(ctx.getColor(R.color.text3))
        }
        fun submit() {
            if (field.text.isBlank()) return
            add(ctx, lesson, field.text.toString())
            field.setText("")
            Motion.success(field)
            fill()
            activity.refreshScreen()
            Widgets.refreshAll(ctx.applicationContext)
        }
        field.setOnEditorActionListener { _, _, _ -> submit(); true }
        val row = UI.row(ctx).apply { setPadding(0, ctx.dp(6), 0, 0) }
        row.addView(field, UI.weight())
        row.addView(UI.hgap(ctx, 8))
        row.addView(UI.button(ctx, "Ajouter") { submit() })
        card.addView(row, UI.matchWidth())
        box.addView(UI.text(ctx, "Seulement sur ton téléphone, rappelé la veille avec le cartable.", 12f, R.color.text3).apply { setPadding(ctx.dp(4), ctx.dp(6), 0, 0) })
        return box
    }
}
