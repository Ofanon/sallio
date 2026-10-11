package fr.masalle.app

import fr.masalle.pronote.Lesson
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Decides what the widgets show. Pure functions of (lessons, now), so the
 * rules are unit tested and the widgets only render.
 */
object Agenda {

    /** A lesson that started less than this long ago is still "where you should be". */
    val LATE_ARRIVAL: Duration = Duration.ofMinutes(10)

    class View(
        /** The lesson the student is heading to (or just walked into). */
        val focus: Lesson?,
        /** The one after it, same day only. */
        val then: Lesson?,
        /** All lessons of the focus day, for the bigger widget. */
        val day: List<Lesson>,
        val dayDate: LocalDate?,
    )

    fun view(lessons: List<Lesson>, now: LocalDateTime): View {
        val sorted = lessons.sortedBy { it.start }
        val focus = sorted.firstOrNull { it.start > now.minus(LATE_ARRIVAL) && it.end > now }
            ?: return View(null, null, emptyList(), null)
        val date = focus.start.toLocalDate()
        val day = sorted.filter { it.start.toLocalDate() == date }
        val then = day.getOrNull(day.indexOf(focus) + 1)
        return View(focus, then, day, date)
    }

    /** Next moment the widget content changes, so it can be redrawn right then. */
    fun nextChange(lessons: List<Lesson>, now: LocalDateTime): LocalDateTime {
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay()
        val candidates = lessons.flatMap { listOf(it.start.minusMinutes(60), it.start, it.start.plus(LATE_ARRIVAL), it.end) }
        return (candidates.filter { it > now } + midnight).min()
    }

    // ---- Formatting -------------------------------------------------------

    private val FRENCH = Locale.FRANCE
    private val ACRONYMS = setOf(
        "EPS", "SVT", "SES", "NSI", "SNT", "EMC", "LV1", "LV2", "LV3", "LCA", "HGGSP", "HLP",
        "LLCER", "AMC", "STI2D", "STMG", "ST2S", "STL", "CDI", "TP", "TD", "DS", "DST", "AP", "EPI",
        "SI", "PC", "HG", "UE", "CPE", "ULIS",
    )

    /** "MATHÉMATIQUES" -> "Mathématiques", "ANGLAIS LV1" -> "Anglais LV1". Mixed case is left alone. */
    fun pretty(text: String): String {
        if (text.any { it.isLowerCase() }) return text
        val words = text.lowercase(FRENCH).split(" ").map { word ->
            val upper = word.uppercase(FRENCH)
            if (upper in ACRONYMS || word.any { it.isDigit() }) upper else word
        }
        return words.joinToString(" ").replaceFirstChar { it.titlecase(FRENCH) }
    }

    /** "GALLET B." -> "Gallet B." */
    fun prettyName(name: String): String =
        name.split(" ").joinToString(" ") { part ->
            if (part.endsWith(".")) part
            else part.lowercase(FRENCH).split("-").joinToString("-") { it.replaceFirstChar { c -> c.titlecase(FRENCH) } }
        }

    fun room(lesson: Lesson): String = lesson.rooms.joinToString(" / ").ifEmpty { "—" }

    fun time(t: LocalDateTime): String = "${t.hour}h${t.minute.toString().padStart(2, '0')}"

    /** "Maintenant", "À 10h05 · dans 5 min", "Demain 8h00", "Lundi 8h00". */
    fun when_(lesson: Lesson, now: LocalDateTime): String {
        val date = lesson.start.toLocalDate()
        val today = now.toLocalDate()
        return when {
            lesson.start <= now -> "Maintenant · jusqu'à ${time(lesson.end)}"
            date == today -> {
                val minutes = Duration.between(now, lesson.start).toMinutes()
                if (minutes < 60) "À ${time(lesson.start)} · dans ${minutes.coerceAtLeast(1)} min"
                else "À ${time(lesson.start)}"
            }
            date == today.plusDays(1) -> "Demain ${time(lesson.start)}"
            else -> "${dayName(date)} ${time(lesson.start)}"
        }
    }

    fun dayName(date: LocalDate): String =
        date.dayOfWeek.getDisplayName(TextStyle.FULL, FRENCH).replaceFirstChar { it.titlecase(FRENCH) }

    fun dayTitle(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Aujourd'hui"
        today.plusDays(1) -> "Demain"
        else -> "${dayName(date)} ${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.SHORT, FRENCH)}"
    }

    /** The warning a student must not miss, or null. */
    fun alert(lesson: Lesson): String? = when {
        lesson.teacherAbsent -> "Prof absent"
        lesson.canceled -> lesson.status ?: "Cours annulé"
        lesson.status != null -> lesson.status
        else -> null
    }

    /** "il y a 5 min", "à 7h12", "hier à 18h30". */
    fun ago(millis: Long, now: LocalDateTime): String {
        if (millis <= 0) return "jamais"
        val then = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), java.time.ZoneId.systemDefault())
        val minutes = Duration.between(then, now).toMinutes()
        return when {
            minutes < 1 -> "à l'instant"
            minutes < 60 -> "il y a $minutes min"
            then.toLocalDate() == now.toLocalDate() -> "à ${time(then)}"
            then.toLocalDate() == now.toLocalDate().minusDays(1) -> "hier à ${time(then)}"
            else -> "le ${then.dayOfMonth}/${then.monthValue}"
        }
    }
}
