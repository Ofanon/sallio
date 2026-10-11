package fr.sallio.app

import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import fr.sallio.pronote.Schools
import java.util.Locale

/**
 * Picking a school among the ~1000 around: a search box filtering as you
 * type, results grouped (lycées first), readable names, distance.
 */
object SchoolPicker {

    private enum class Kind(val title: String, val icon: Int) {
        LYCEE("Lycées", R.drawable.ic_school),
        COLLEGE("Collèges", R.drawable.ic_book),
        OTHER("Autres établissements", R.drawable.ic_place),
    }

    /** "LYCEE GENERAL ET TECHNOLOGIQUE EVARISTE GALOIS" -> ("Lycée général et techno.", "Evariste Galois", LYCEE) */
    private class Name(val type: String, val name: String, val kind: Kind)

    private val PREFIXES = listOf(
        "LYCEE GENERAL ET TECHNOLOGIQUE" to "Lycée général et techno.",
        "LYCEE GENERAL ET TECHNO" to "Lycée général et techno.",
        "LYCEE POLYVALENT" to "Lycée polyvalent",
        "LYCEE PROFESSIONNEL" to "Lycée professionnel",
        "LYCEE TECHNOLOGIQUE" to "Lycée technologique",
        "LYCEE GENERAL" to "Lycée général",
        "LPO LYCEE DES METIERS" to "Lycée des métiers",
        "LYCEE DES METIERS" to "Lycée des métiers",
        "LYCEE AGRICOLE" to "Lycée agricole",
        "LYCEE PROF." to "Lycée professionnel",
        "LYCEE POL." to "Lycée polyvalent",
        "LYCEE GEN. ET TECHNOL." to "Lycée général et techno.",
        "LYCEE GEN. ET TECHNO." to "Lycée général et techno.",
        "LYCEE TECHNOL." to "Lycée technologique",
        "LYCEE GEN." to "Lycée général",
        "LYCEE PRIVE" to "Lycée privé",
        "COLLEGE PRIVE" to "Collège privé",
        "LPO" to "Lycée polyvalent",
        "LGT" to "Lycée général et techno.",
        "LP" to "Lycée professionnel",
        "LYCEE" to "Lycée",
        "CITE SCOLAIRE" to "Cité scolaire",
        "COLLEGE" to "Collège",
        "EREA" to "EREA",
    )
    private val SMALL = setOf("de", "du", "des", "la", "le", "les", "et", "en", "aux", "au", "sur", "sous", "d", "l")

    private fun split(raw: String): Name {
        val upper = raw.uppercase(Locale.FRANCE).replace("É", "E").replace("È", "E").trim()
        val (prefix, label) = PREFIXES.firstOrNull { (p, _) -> upper == p || upper.startsWith("$p ") } ?: ("" to "Établissement")
        val rest = raw.trim().drop(prefix.length).trim().ifEmpty { raw.trim() }
        val kind = when {
            label.startsWith("Lycée") || label == "Cité scolaire" -> Kind.LYCEE
            label.startsWith("Collège") -> Kind.COLLEGE
            else -> Kind.OTHER
        }
        return Name(label, titleCase(rest), kind)
    }

    /** "VICTOR HUGO" -> "Victor Hugo", "JEAN DE LA FONTAINE" -> "Jean de la Fontaine", "D'ARSONVAL" -> "d'Arsonval". */
    fun titleCase(text: String): String {
        if (text.any { it.isLowerCase() }) return text
        return text.lowercase(Locale.FRANCE).split(" ").filter { it.isNotEmpty() }.mapIndexed { i, word ->
            word.split("-").joinToString("-") { part ->
                val apostrophe = part.indexOf('\'').takeIf { it in 1..2 } ?: part.indexOf('’').takeIf { it in 1..2 }
                when {
                    apostrophe != null -> part.substring(0, apostrophe + 1) + part.substring(apostrophe + 1).replaceFirstChar { it.titlecase(Locale.FRANCE) }
                    i > 0 && part in SMALL -> part
                    part.length <= 4 && part.none { c -> c in "aeiouyéèê" } -> part.uppercase(Locale.FRANCE)
                    else -> part.replaceFirstChar { it.titlecase(Locale.FRANCE) }
                }
            }
        }.joinToString(" ")
    }

    fun show(activity: MainActivity, schools: List<Schools.Nearby>, bottomInset: Int, onPick: (Schools.Nearby) -> Unit) {
        val ctx = activity
        Sheet.open(activity, bottomInset) { sheet, dialog ->
            Sheet.title(sheet, "Établissements autour de toi", "${schools.size} trouvés. Tape le nom du tien pour aller plus vite.")
            val field = EditText(ctx).apply {
                hint = "Nom du lycée, ville ou code postal"
                setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                background = UI.rounded(ctx.getColor(R.color.surface), ctx.dp(16).toFloat(), ctx.dp(2), ctx.getColor(R.color.edge))
                setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
                textSize = 16f
                typeface = UI.body(ctx, 700)
                setTextColor(ctx.getColor(R.color.text))
                setHintTextColor(ctx.getColor(R.color.text3))
            }
            sheet.addView(field, UI.matchWidth().apply { topMargin = ctx.dp(12) })
            val list = UI.column(ctx)
            sheet.addView(list, UI.matchWidth())

            fun draw(query: String) {
                list.removeAllViews()
                val found = if (query.isBlank()) schools else Schools.filterByName(schools, query)
                if (found.isEmpty()) {
                    list.addView(UI.text(ctx, "Aucun établissement de ce nom autour de toi. Essaie le code de ton lycée (sur ton carnet ou sur Pronote).", 14f, R.color.text2).apply {
                        gravity = Gravity.CENTER
                        setPadding(ctx.dp(8), ctx.dp(24), ctx.dp(8), ctx.dp(8))
                    }, UI.matchWidth())
                    return
                }
                val named = found.take(60).map { it to split(it.name) }
                for (kind in Kind.values()) {
                    val group = named.filter { it.second.kind == kind }
                    if (group.isEmpty()) continue
                    list.addView(UI.section(ctx, kind.title))
                    val card = UI.card(ctx, padding = 6)
                    group.forEachIndexed { i, (school, name) ->
                        if (i > 0) card.addView(UI.divider(ctx).apply { (layoutParams as LinearLayout.LayoutParams).apply { marginStart = ctx.dp(62); marginEnd = ctx.dp(12) } })
                        card.addView(row(ctx, school, name, kind) {
                            dialog.dismiss()
                            onPick(school)
                        }, UI.matchWidth())
                    }
                    list.addView(card, UI.matchWidth())
                }
            }
            draw("")
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = draw(s?.toString().orEmpty())
            })
        }
    }

    private fun row(ctx: MainActivity, school: Schools.Nearby, name: Name, kind: Kind, onClick: () -> Unit) = UI.row(ctx).apply {
        setPadding(ctx.dp(10), ctx.dp(11), ctx.dp(12), ctx.dp(11))
        foreground = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22000000), null, UI.rounded(Color.WHITE, ctx.dp(16).toFloat()))
        val tile = FrameLayout(ctx).apply {
            background = UI.rounded(ctx.getColor(if (kind == Kind.LYCEE) R.color.accent_container else R.color.surface2), ctx.dp(12).toFloat())
            addView(UI.icon(ctx, kind.icon, if (kind == Kind.LYCEE) R.color.accent else R.color.text2, 20).apply {
                layoutParams = FrameLayout.LayoutParams(ctx.dp(20), ctx.dp(20), Gravity.CENTER)
            })
        }
        addView(tile, LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)))
        addView(UI.hgap(ctx, 12))
        val col = UI.column(ctx)
        col.addView(UI.oneLine(UI.title(ctx, name.name, 16f)))
        col.addView(UI.oneLine(UI.text(ctx, "${name.type} · ${school.postalCode}", 12f, R.color.text2)).apply { setPadding(0, ctx.dp(2), 0, 0) })
        addView(col, UI.weight())
        addView(UI.chip(ctx, String.format(Locale.FRANCE, "%.1f km", school.distanceKm), ctx.getColor(R.color.surface2), ctx.getColor(R.color.text2), 11f), UI.wrap().apply { marginStart = ctx.dp(8) })
        setOnClickListener { Motion.tap(this); onClick() }
    }
}
