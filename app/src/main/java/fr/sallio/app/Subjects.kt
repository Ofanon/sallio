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

    val CORAL = Family(0xFFFFE5E1, 0xFFC2402F, 0xFFFF7A6B, 0xFFE0574A, 0xFF3D2026, 0xFFFFAA9E)
    val SKY = Family(0xFFDFF1FF, 0xFF1A7FC1, 0xFF4CB3F4, 0xFF2B8FD0, 0xFF173A52, 0xFF7FCBFF)
    val GREEN = Family(0xFFE6F8EF, 0xFF1C7A43, 0xFF3CC48D, 0xFF24A06E, 0xFF17392A, 0xFF8BE0AC)
    val VIOLET = Family(0xFFEFE9FF, 0xFF6A4CDC, 0xFF9B7BFF, 0xFF7A59E6, 0xFF2E2556, 0xFFC7B8FF)
    val AMBER = Family(0xFFFFF0D6, 0xFFA8640A, 0xFFFFB547, 0xFFE0951F, 0xFF3B2C10, 0xFFFFCC80)
    val PINK = Family(0xFFFFE6F2, 0xFFC23B7D, 0xFFFF7EB6, 0xFFE0568F, 0xFF3D1D30, 0xFFFF9FCB)
    val ORANGE = Family(0xFFFFEEDD, 0xFFC9650C, 0xFFFF9F43, 0xFFE07B17, 0xFF3B2512, 0xFFFFB877)
    val TEAL = Family(0xFFDDF6F6, 0xFF11807F, 0xFF2EC4C1, 0xFF18A09D, 0xFF123636, 0xFF7FE0DD)
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

    /** Sticker edge of a solid card: the darker shade by day, the night outline by night. */
    fun edge(context: Context, f: Family): Int = if (isDark(context)) context.getColor(R.color.edge) else f.edge.toInt()
}
