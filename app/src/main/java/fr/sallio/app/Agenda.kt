package fr.sallio.app

import fr.sallio.pronote.Lesson
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

    /** Free time between two lessons of a day. */
    class Gap(val start: LocalDateTime, val end: LocalDateTime, val lunch: Boolean) {
        val minutes get() = java.time.Duration.between(start, end).toMinutes()
        val label get() = when {
            lunch && minutes <= 120 -> "Pause midi"
            lunch -> "Perm et pause midi"
            else -> "Perm"
        }
    }

    /**
     * The gap from [from] to [to] when it is worth a row: 45 min or more (a
     * 10-minute break between lessons is not). It is the lunch break when it
     * covers at least 45 min of 11h30–14h.
     */
    fun gap(from: LocalDateTime, to: LocalDateTime): Gap? {
        if (java.time.Duration.between(from, to).toMinutes() < 45) return null
        val noonStart = from.toLocalDate().atTime(11, 30)
        val noonEnd = from.toLocalDate().atTime(14, 0)
        val overlap = java.time.Duration.between(maxOf(from, noonStart), minOf(to, noonEnd)).toMinutes()
        return Gap(from, to, overlap >= 45)
    }

    /**
     * Back-to-back hours of the same lesson (same subject, room, teachers and
     * state, at most 15 minutes apart) as one block: PRONOTE sends a two-hour
     * LV3 as two lessons, the student lives it as one.
     */
    fun blocks(lessons: List<Lesson>): List<Lesson> {
        val out = mutableListOf<Lesson>()
        for (l in lessons.sortedBy { it.start }) {
            val last = out.lastOrNull()
            if (last != null && continues(last, l)) out[out.size - 1] = last.copy(end = maxOf(last.end, l.end))
            else out += l
        }
        return out
    }

    /** [next] carries on [prev] without a real break. */
    fun continues(prev: Lesson, next: Lesson): Boolean =
        prev.subject == next.subject && prev.rooms == next.rooms && prev.teachers == next.teachers &&
            prev.canceled == next.canceled && prev.status == next.status &&
            !next.start.isBefore(prev.start) && Duration.between(prev.end, next.start).toMinutes() in 0..15

    /** "55 min", "2 h", "1 h 05". */
    fun duration(minutes: Long): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0L -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')}"
    }

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
    /** Minutes before a lesson when the countdown shows (the reminder comes at 5). */
    const val COUNTDOWN_MIN = 10L

    /** Time left before [lesson] when the countdown should show, null otherwise. */
    fun countdownMillis(lesson: Lesson, now: LocalDateTime): Long? {
        if (lesson.canceled) return null
        val left = Duration.between(now, lesson.start).toMillis()
        return left.takeIf { it > 0 && it <= COUNTDOWN_MIN * 60_000 }
    }

    fun nextChange(lessons: List<Lesson>, now: LocalDateTime): LocalDateTime {
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay()
        // A cancelled lesson has no countdown to show.
        val candidates = lessons.flatMap { listOfNotNull(it.start.minusMinutes(60), it.start.minusMinutes(COUNTDOWN_MIN).takeIf { _ -> !it.canceled }, it.start, it.start.plus(LATE_ARRIVAL), it.end) }
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
