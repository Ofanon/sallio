package fr.sallio.app

import fr.sallio.pronote.Holiday
import fr.sallio.pronote.Lesson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class VacationTest {

    // Toussaint 2026 as a school would set it: Saturday 17 October to Sunday 1 November.
    private val toussaint = Holiday("Vacances de la Toussaint", LocalDate.of(2026, 10, 17), LocalDate.of(2026, 11, 1))
    private val friday = LocalDateTime.of(2026, 10, 16, 8, 30).let { s ->
        Lesson(s, s.plusMinutes(55), "MATHS", listOf("B204"), emptyList(), emptyList(), null, false, false, null, Lesson.Kind.LESSON)
    }

    @Test fun `back on the first weekday after the holiday`() =
        assertEquals(LocalDate.of(2026, 11, 2), Vacation.backDay(toussaint, listOf(toussaint)))

    @Test fun `on Friday after the last lesson the holiday is already there`() {
        assertNull(Vacation.current(listOf(toussaint), listOf(friday), LocalDateTime.of(2026, 10, 16, 8, 0)))
        assertEquals(toussaint, Vacation.current(listOf(toussaint), listOf(friday), LocalDateTime.of(2026, 10, 16, 17, 0)))
    }

    @Test fun `a single day off is not a holiday`() =
        assertNull(Vacation.current(listOf(Holiday("Armistice", LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 11))), emptyList(), LocalDateTime.of(2026, 11, 11, 10, 0)))

    @Test fun `the fetch reaches the week back at school`() =
        assertEquals(LocalDate.of(2026, 11, 8), Vacation.fetchUntil(listOf(toussaint), LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 29)))

    @Test fun `short names for the widget`() {
        assertEquals("Toussaint", Vacation.shortName(toussaint))
        assertEquals("Noël", Vacation.shortName(Holiday("Vacances de Noël", toussaint.start, toussaint.end)))
        assertEquals("Hiver", Vacation.shortName(Holiday("Vacances d'hiver", toussaint.start, toussaint.end)))
        assertEquals("Vacances", Vacation.shortName(Holiday("", toussaint.start, toussaint.end)))
    }
}
