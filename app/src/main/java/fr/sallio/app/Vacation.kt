package fr.sallio.app

import fr.sallio.pronote.Holiday
import fr.sallio.pronote.Lesson
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The school holidays (Oscar, 2026-10-16): when the next school day is after a
 * holiday, the app says so instead of "no lesson", counts the days, and shows
 * the homework for the way back.
 */
object Vacation {

    /** A real holiday (two days or more), not a single day off. */
    private fun isBreak(h: Holiday) = ChronoUnit.DAYS.between(h.start, h.end) >= 1

    /** The holiday [day] is in, or that begins right after the last school day before it. */
    fun current(holidays: List<Holiday>, lessons: List<Lesson>, now: LocalDateTime): Holiday? {
        val today = now.toLocalDate()
        holidays.firstOrNull { isBreak(it) && today in it.start..it.end }?.let { return it }
        // Friday after the last lesson, or the weekend before: the holiday is already there.
        val upcoming = holidays.firstOrNull { isBreak(it) && it.start > today } ?: return null
        val lessonsLeft = lessons.any { it.end > now && it.start.toLocalDate() < upcoming.start && !it.canceled }
        return if (!lessonsLeft && ChronoUnit.DAYS.between(today, upcoming.start) <= 3) upcoming else null
    }

    /** The first school day after [h]: the next weekday that is not off. */
    fun backDay(h: Holiday, holidays: List<Holiday>): LocalDate {
        var d = h.end.plusDays(1)
        while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY || holidays.any { d in it.start..it.end }) d = d.plusDays(1)
        return d
    }

    /** "lundi 2 novembre". */
    fun dayText(d: LocalDate) = "${d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.FRANCE)} ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.FULL, Locale.FRANCE)}"

    /** "Vacances de la Toussaint", or "Vacances" when PRONOTE's label is empty. */
    fun name(h: Holiday) = h.name.ifBlank { "Vacances" }.replaceFirstChar { it.uppercase() }

    /** "Toussaint", "Noël", "Hiver": the name without "Vacances de la", for the big text of a widget. */
    fun shortName(h: Holiday): String =
        h.name.replace(Regex("(?i)^\\s*vacances\\s+(de\\s+la\\s+|de\\s+l'|d'|des\\s+|du\\s+|de\\s+)?"), "").trim()
            .ifEmpty { "Vacances" }.replaceFirstChar { it.uppercase() }

    /** The end of the fetch window: past a holiday inside it, to the week back at school. */
    fun fetchUntil(holidays: List<Holiday>, today: LocalDate, until: LocalDate): LocalDate {
        val inside = holidays.filter { isBreak(it) && it.start <= until && it.end >= today }
        val back = inside.maxOfOrNull { backDay(it, holidays) } ?: return until
        return maxOf(until, back.plusDays(6)).coerceAtMost(today.plusDays(35))
    }

    /** The holiday covering [day], if any (for the empty days of the Cours tab). */
    fun on(holidays: List<Holiday>, day: LocalDate): Holiday? = holidays.firstOrNull { day in it.start..it.end }
}
