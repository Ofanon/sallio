package fr.sallio.app

import android.content.Context
import android.content.res.Configuration
import java.text.Normalizer

/**
 * Each subject gets a colour family and an icon, so the student recognises a
 * lesson before reading it. By default the family takes the hue PRONOTE gives
 * the subject (so Maths is the colour the student knows from PRONOTE), brought
 * to the softness of Sallio's own palette: PRONOTE's raw colours are set by
 * each school and are often dull or clashing. Without a PRONOTE colour, or with
 * the option off, the subject gets one of Sallio's families from its name.
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

    /** A stable family for anything that is not a subject (a news category, a person). */
    fun familyFor(name: String): Family = ALL[Math.floorMod(fold(name).hashCode(), ALL.size)]

    /** PRONOTE's colour of each subject, by folded name ("#RRGGBB" as PRONOTE writes it). */
    @Volatile private var pronote: Map<String, Int> = emptyMap()
    @Volatile var usePronote = true
        private set

    /**
     * Teaches the colours PRONOTE gives the subjects, from the stored lessons,
     * homework and averages. [use] is the student's choice in the settings.
     */
    @Synchronized
    fun learn(colors: Map<String, String?>, use: Boolean) {
        val parsed = colors.mapNotNull { (subject, hex) -> hex?.let(::parseHex)?.let { fold(subject).trim() to it } }.toMap()
        if (parsed == pronote && use == usePronote) return
        pronote = parsed
        usePronote = use
        cache.clear()
    }

    @Synchronized
    fun style(subject: String): Style = cache.getOrPut(subject) {
        val key = " " + fold(subject) + " "
        val rule = RULES.firstOrNull { rule -> rule.keys.any { it in key } }
        val icon = rule?.icon ?: R.drawable.ic_school
        val fromPronote = if (usePronote) pronoteColor(subject)?.let { familyFrom(it) } else null
        Style(
            fromPronote ?: rule?.family
                // Unknown subject: a stable colour from its name.
                ?: ALL[Math.floorMod(fold(subject).hashCode(), ALL.size)],
            icon,
        )
    }

    /** PRONOTE's colour for [subject]; a sub-service ("Anglais > oral") takes its subject's. */
    private fun pronoteColor(subject: String): Int? =
        pronote[fold(subject).trim()] ?: pronote[fold(subject.substringBefore(" > ")).trim()]

    /** "#1E90FF", "1e90ff" -> 0xFF1E90FF; null for anything else. */
    internal fun parseHex(text: String): Int? {
        val hex = text.trim().removePrefix("#")
        if (hex.length != 6 || hex.any { it.lowercaseChar() !in "0123456789abcdef" }) return null
        return (0xFF000000.toInt()) or hex.toInt(16)
    }

    /**
     * A family with [color]'s hue at the lightness and saturation of Sallio's
     * own families, so a school's harsh red or dull brown still sits well
     * next to the others. Greys stay grey.
     */
    internal fun familyFrom(color: Int): Family {
        val (h, sRaw) = hueSat(color)
        val grey = sRaw < 0.12
        fun c(sat: Double, light: Double): Long = hsl(h, if (grey) sat * 0.15 else sat, light).toLong() and 0xFFFFFFFFL
        return Family(
            tint = c(0.75, 0.92), ink = c(0.50, 0.38), solid = c(0.72, 0.68), edge = c(0.50, 0.58),
            darkTint = c(0.30, 0.21), darkInk = c(0.80, 0.80),
        )
    }

    private fun hueSat(color: Int): Pair<Double, Double> {
        val r = ((color shr 16) and 0xFF) / 255.0
        val g = ((color shr 8) and 0xFF) / 255.0
        val b = (color and 0xFF) / 255.0
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2
        val delta = max - min
        if (delta < 1e-9) return 0.0 to 0.0
        val s = delta / (1 - Math.abs(2 * l - 1))
        val h = when (max) {
            r -> 60 * (((g - b) / delta) % 6)
            g -> 60 * ((b - r) / delta + 2)
            else -> 60 * ((r - g) / delta + 4)
        }
        return (h + 360) % 360 to s.coerceIn(0.0, 1.0)
    }

    private fun hsl(h: Double, s: Double, l: Double): Int {
        val c = (1 - Math.abs(2 * l - 1)) * s
        val x = c * (1 - Math.abs((h / 60) % 2 - 1))
        val m = l - c / 2
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0.0)
            h < 120 -> Triple(x, c, 0.0)
            h < 180 -> Triple(0.0, c, x)
            h < 240 -> Triple(0.0, x, c)
            h < 300 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        fun ch(v: Double) = Math.round((v + m) * 255).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
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
    fun onSolid(f: Family): Int = onSolidColor(f.solid.toInt())

    fun onSolidColor(c: Int): Int {
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
