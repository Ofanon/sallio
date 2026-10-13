package fr.sallio.app

import fr.sallio.pronote.Lesson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class BagTest {

    private fun lesson(day: Int, h: Int, subject: String, canceled: Boolean = false) =
        LocalDateTime.of(2026, 10, day, h, 0).let { start ->
            Lesson(start, start.plusMinutes(55), subject, listOf("B204"), emptyList(), emptyList(),
                null, canceled, canceled, null, Lesson.Kind.LESSON)
        }

    // Friday 16, Monday 19 October 2026.
    private val week = listOf(
        lesson(16, 8, "MATHS"),
        lesson(19, 10, "ANGLAIS"),
        lesson(19, 8, "MATHS"),
        lesson(19, 14, "MATHS"),
        lesson(19, 15, "SVT", canceled = true),
    )

    @Test fun `Friday evening packs for Monday`() =
        assertEquals(LocalDate.of(2026, 10, 19), Bag.nextDay(week, LocalDate.of(2026, 10, 16)))

    @Test fun `each subject once, in the order of the day, without cancelled lessons`() =
        assertEquals(listOf("MATHS", "ANGLAIS"), Bag.subjects(week, LocalDate.of(2026, 10, 19)))

    @Test fun `a day where everything is cancelled is not a school day`() =
        assertNull(Bag.nextDay(listOf(lesson(20, 8, "SVT", canceled = true)), LocalDate.of(2026, 10, 19)))

    @Test fun `title says tomorrow, or the day's name further away`() {
        assertEquals("Demain", Bag.title(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 19)))
        assertEquals("Lundi", Bag.title(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 17)))
    }
}
