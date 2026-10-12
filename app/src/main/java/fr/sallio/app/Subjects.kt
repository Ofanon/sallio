package fr.sallio.app

import android.content.Context
import android.content.res.Configuration
import java.text.Normalizer

/**
 * Each subject gets a colour family and an icon, so the student recognises a
 * lesson before reading it. PRONOTE's own colours are set by each school and
 * are often dull or clashing, so they are not used for the design.
 */
object Subjects {

    class Family(
        val tint: Long, val ink: Long, val solid: Long, val edge: Long,
        val darkTint: Long, val darkInk: Long,
    )

    // Same softness for every family, so no two subjects clash side by side.
    val CORAL = Family(0xFFFDE6DF, 0xFFB2503C, 0xFFF28B74, 0xFFD9705A, 0xFF432B28, 0xFFFFB2A1)
    val SKY = Family(0xFFE1F0FA, 0xFF2F73A3, 0xFF6FB3E0, 0xFF4F95C4, 0xFF1F3646, 0xFF9BD0F2)
    val GREEN = Family(0xFFE2F3E9, 0xFF2F7A52, 0xFF7CC49A, 0xFF5BA67C, 0xFF20382B, 0xFF9FDBB7)
    val VIOLET = Family(0xFFECE6FB, 0xFF6A52C2, 0xFFA993E6, 0xFF8B73CC, 0xFF2E2846, 0xFFC9BBF5)
    val AMBER = Family(0xFFFDEEDD, 0xFFA8681A, 0xFFF2B65E, 0xFFD49A40, 0xFF3B2E1A, 0xFFF5CB8A)
    val PINK = Family(0xFFFCE6EF, 0xFFB0406F, 0xFFEE8FB7, 0xFFD06F99, 0xFF3F2433, 0xFFF7B1CE)
    val ORANGE = Family(0xFFFDEBDC, 0xFFB0601E, 0xFFF5A15C, 0xFFD7843F, 0xFF3D2A1A, 0xFFF8BE8A)
    val TEAL = Family(0xFFDFF3F2, 0xFF2A7C7A, 0xFF6CC3C1, 0xFF4DA5A3, 0xFF1C3837, 0xFF94DCDA)
    private val ALL = listOf(CORAL, SKY, GREEN, VIOLET, AMBER, PINK, ORANGE, TEAL)

    class Style(val family: Family, val icon: Int)

    private class Rule(val keys: List<String>, val family: Family, val icon: Int)

    // Short keys carry a leading space so "ses" does not match "chinoises".
    // Order matters: "Ed. physique & sport" must be sport before "physique" is chemistry.
    private val RULES = listOf(
        Rule(listOf(" eps", "sport", "ed.physique", "education physique"), ORANGE, R.drawable.ic_run),
        Rule(listOf(" nsi", "numerique", "informat", " snt", "programm"), TEAL, R.drawable.ic_code),
        Rule(listOf("math"), CORAL, R.drawable.ic_maths),
        Rule(listOf("physique", "chimie"), SKY, R.drawable.ic_flask),
        Rule(listOf("svt", "vie et", "biolog", "science"), GREEN, R.drawable.ic_leaf),
        Rule(listOf("philo"), VIOLET, R.drawable.ic_brain),
        Rule(listOf("francais", "lettre", "litter"), VIOLET, R.drawable.ic_book),
        Rule(listOf("histoire", "geo", "hggsp", " emc", "civique"), AMBER, R.drawable.ic_world),
        Rule(listOf(" ses ", "econom", "gestion", "management", "droit"), AMBER, R.drawable.ic_chart),
        Rule(listOf("latin", "grec", "lca"), PINK, R.drawable.ic_book),
        Rule(
            listOf("anglais", "allemand", "espagnol", "italien", "chinois", "arabe", "portugais", "russe",
                "japonais", "lv1", "lv2", "lv3", "langue", "llcer", "lv "),
            PINK, R.drawable.ic_language,
        ),
        Rule(listOf("musique", "music"), VIOLET, R.drawable.ic_music),
        Rule(listOf(" art", "plastique", "dessin", "cinema", "theatre"), VIOLET, R.drawable.ic_palette),
    )

    private val cache = HashMap<String, Style>()

    fun style(subject: String): Style = cache.getOrPut(subject) {
        val key = " " + fold(subject) + " "
        RULES.firstOrNull { rule -> rule.keys.any { it in key } }?.let { Style(it.family, it.icon) }
            // Unknown subject: a stable colour from its name, the school icon.
            ?: Style(ALL[Math.floorMod(fold(subject).hashCode(), ALL.size)], R.drawable.ic_school)
    }

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

    fun isDark(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** Pale background of the subject chip / icon tile. */
    fun tint(context: Context, f: Family): Int = (if (isDark(context)) f.darkTint else f.tint).toInt()

    /** Text and icon colour on [tint]. */
    fun ink(context: Context, f: Family): Int = (if (isDark(context)) f.darkInk else f.ink).toInt()

    fun solid(f: Family): Int = f.solid.toInt()

    /** Text on [solid]: white on the deeper colours, ink on the light ones (amber, teal...). */
    fun onSolid(f: Family): Int {
        val c = f.solid.toInt()
        fun lin(v: Int): Double = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        val l = 0.2126 * lin((c shr 16) and 0xFF) + 0.7152 * lin((c shr 8) and 0xFF) + 0.0722 * lin(c and 0xFF)
        return if (l > 0.42) 0xFF2E2A3A.toInt() else 0xFFFFFFFF.toInt()
    }

    /**
     * Sticker edge of a solid card: a deeper shade of the subject colour, in
     * light and dark alike, so a coral card casts a coral shadow.
     */
    fun edge(context: Context, f: Family): Int = darker(f.solid.toInt(), if (isDark(context)) 0.55f else 0.78f)

    /** Edge of a pale (tinted) card. */
    fun softEdge(context: Context, f: Family): Int = if (isDark(context)) darker(f.darkTint.toInt(), 0.6f) else darker(f.tint.toInt(), 0.86f)

    fun darker(color: Int, factor: Float): Int {
        val r = ((color shr 16) and 0xFF) * factor
        val g = ((color shr 8) and 0xFF) * factor
        val b = (color and 0xFF) * factor
        return (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    }
}
