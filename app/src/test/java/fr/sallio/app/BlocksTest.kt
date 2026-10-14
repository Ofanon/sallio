package fr.sallio.app

import fr.sallio.pronote.Lesson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class BlocksTest {

    private fun lesson(h: Int, m: Int, minutes: Long, subject: String = "LV3", room: String = "D 102") =
        LocalDateTime.of(2026, 10, 14, h, m).let { start ->
            Lesson(start, start.plusMinutes(minutes), subject, listOf(room), listOf("HERBERT L."), emptyList(),
                null, false, false, null, Lesson.Kind.LESSON)
        }

    @Test fun `two back-to-back hours of the same lesson make one block`() {
        val b = Agenda.blocks(listOf(lesson(16, 40, 55), lesson(15, 40, 55)))
        assertEquals(1, b.size)
        assertEquals(LocalDateTime.of(2026, 10, 14, 15, 40), b[0].start)
        assertEquals(LocalDateTime.of(2026, 10, 14, 17, 35), b[0].end)
    }

    @Test fun `another room or subject stays apart`() {
        assertEquals(2, Agenda.blocks(listOf(lesson(15, 40, 55), lesson(16, 40, 55, room = "D 104"))).size)
        assertEquals(2, Agenda.blocks(listOf(lesson(15, 40, 55), lesson(16, 40, 55, subject = "MATHS"))).size)
    }

    @Test fun `a lunch between two hours is not one block`() =
        assertEquals(2, Agenda.blocks(listOf(lesson(10, 40, 55), lesson(13, 40, 55))).size)

    @Test fun `a perm is found, the 10-minute break is not`() {
        assertNull(Agenda.gap(LocalDateTime.of(2026, 10, 14, 10, 25), LocalDateTime.of(2026, 10, 14, 10, 40)))
        assertEquals("Perm", Agenda.gap(LocalDateTime.of(2026, 10, 14, 9, 25), LocalDateTime.of(2026, 10, 14, 10, 40))!!.label)
        assertEquals("Pause midi", Agenda.gap(LocalDateTime.of(2026, 10, 14, 11, 35), LocalDateTime.of(2026, 10, 14, 13, 30))!!.label)
        assertEquals("Perm et pause midi", Agenda.gap(LocalDateTime.of(2026, 10, 14, 11, 35), LocalDateTime.of(2026, 10, 14, 15, 40))!!.label)
    }

    @Test fun `durations read naturally`() {
        assertEquals("55 min", Agenda.duration(55))
        assertEquals("2 h", Agenda.duration(120))
        assertEquals("4 h 05", Agenda.duration(245))
    }
}
