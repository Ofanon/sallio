package fr.sallio.app

import fr.sallio.pronote.Lesson
import fr.sallio.pronote.Schools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class AgendaTest {

    private fun lesson(day: Int, h: Int, m: Int, minutes: Long, room: String, canceled: Boolean = false, status: String? = null) =
        LocalDateTime.of(2026, 10, day, h, m).let { start ->
            Lesson(start, start.plusMinutes(minutes), "MATHÉMATIQUES", listOf(room), listOf("GALLET B."), emptyList(),
                status, canceled, status != null, null, Lesson.Kind.LESSON)
        }

    private val monday = listOf(
        lesson(12, 8, 0, 55, "B204"),
        lesson(12, 9, 0, 55, "C102", canceled = true, status = "Prof. absent"),
        lesson(12, 10, 10, 55, "A001"),
    )

    @Test
    fun duringBreakShowsTheNextRoom() {
        val v = Agenda.view(monday, LocalDateTime.of(2026, 10, 12, 10, 2))
        assertEquals("A001", v.focus?.rooms?.single())
        assertNull(v.then)
    }

    @Test
    fun arrivingLateStillShowsTheCurrentRoom() {
        val v = Agenda.view(monday, LocalDateTime.of(2026, 10, 12, 8, 7))
        assertEquals("B204", v.focus?.rooms?.single())
        assertEquals("C102", v.then?.rooms?.single())
    }

    @Test
    fun inTheMiddleOfALessonLooksAhead() {
        val v = Agenda.view(monday, LocalDateTime.of(2026, 10, 12, 8, 30))
        assertEquals("C102", v.focus?.rooms?.single())
        assertEquals("Prof absent", Agenda.alert(v.focus!!))
    }

    @Test
    fun afterTheLastLessonShowsTomorrow() {
        val week = monday + lesson(13, 8, 0, 55, "D300")
        val now = LocalDateTime.of(2026, 10, 12, 18, 0)
        val v = Agenda.view(week, now)
        assertEquals("D300", v.focus?.rooms?.single())
        assertEquals("Demain 8h00", Agenda.when_(v.focus!!, now))
    }

    @Test
    fun nextChangeIsTheNextBoundary() {
        assertEquals(LocalDateTime.of(2026, 10, 12, 8, 55), Agenda.nextChange(monday, LocalDateTime.of(2026, 10, 12, 8, 30)))
        assertEquals(LocalDateTime.of(2026, 10, 12, 8, 10), Agenda.nextChange(monday, LocalDateTime.of(2026, 10, 12, 8, 1)))
    }

    @Test
    fun prettyNames() {
        assertEquals("Mathématiques", Agenda.pretty("MATHÉMATIQUES"))
        assertEquals("Anglais LV1", Agenda.pretty("ANGLAIS LV1"))
        assertEquals("Éducation physique et sportive", Agenda.pretty("ÉDUCATION PHYSIQUE ET SPORTIVE"))
        assertEquals("Gallet B.", Agenda.prettyName("GALLET B."))
        assertEquals("Fanny Parent", MainActivity.prettyStudent("PARENT Fanny"))
        assertEquals("Jean-Paul De La Tour", MainActivity.prettyStudent("DE LA TOUR Jean-Paul"))
    }

    @Test
    fun schoolInputs() {
        assertEquals("https://0750655e.index-education.net/pronote", Schools.urlFromInput("0750655E"))
        assertEquals("https://0750655e.index-education.net/pronote", Schools.urlFromInput("0750655e.index-education.net/pronote/eleve.html"))
        assertEquals("https://x.fr/pronote", Schools.urlFromInput("http://x.fr/pronote/"))
        assertNull(Schools.urlFromInput("lycée"))

        val list = listOf(
            Schools.Nearby("LYCÉE EVARISTE GALOIS", "https://a", "78500", 1.0),
            Schools.Nearby("COLLÈGE EVARISTE GALOIS", "https://b", "92000", 2.0),
            Schools.Nearby("LYCÉE JEAN ZAY", "https://c", "93604", 3.0),
        )
        assertEquals(listOf("https://a"), Schools.filterByName(list, "lycee évariste galois").map { it.url })
        assertEquals(2, Schools.filterByName(list, "Galois").size)
        assertEquals(listOf("https://b"), Schools.filterByName(list, "galois 92").map { it.url })
    }
}
