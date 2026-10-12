package fr.sallio.app

import fr.sallio.pronote.Discussion
import fr.sallio.pronote.Grade
import fr.sallio.pronote.Lesson
import fr.sallio.pronote.News
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ChangesTest {

    private val now = LocalDateTime.of(2026, 10, 12, 7, 30)

    private fun lesson(day: Int, h: Int, room: String = "B204", status: String? = null, canceled: Boolean = false, modified: Boolean = status != null) =
        LocalDateTime.of(2026, 10, day, h, 0).let { start ->
            Lesson(start, start.plusMinutes(55), "MATHÉMATIQUES", listOf(room), listOf("GALLET B."), emptyList(),
                status, canceled, modified, null, Lesson.Kind.LESSON)
        }

    private val week = listOf(lesson(12, 8), lesson(12, 9), lesson(13, 8), lesson(20, 8))

    @Test
    fun sameTimetableSaysNothing() {
        assertTrue(Changes.lessons(week, week, now).isEmpty())
    }

    @Test
    fun firstDownloadSaysNothing() {
        assertTrue(Changes.lessons(null, week, now).isEmpty())
        assertTrue(Changes.lessons(emptyList(), week, now).isEmpty())
    }

    @Test
    fun teacherAbsentTomorrow() {
        val after = week.map { if (it.start.dayOfMonth == 13) lesson(13, 8, status = "Prof. absent", canceled = true) else it }
        val items = Changes.lessons(week, after, now)
        assertEquals(1, items.size)
        assertEquals("Prof absent · Mathématiques", items[0].title)
        assertEquals("Demain à 8h00 · Gallet B.", items[0].text)
    }

    @Test
    fun roomChange() {
        val after = week.map { if (it.start.hour == 9) lesson(12, 9, room = "C102") else it }
        val items = Changes.lessons(week, after, now)
        assertEquals("Salle changée · Mathématiques", items.single().title)
        assertEquals("Aujourd'hui à 9h00 · B204 → C102 · Gallet B.", items.single().text)
    }

    @Test
    fun backToNormal() {
        val before = week.map { if (it.start.hour == 9) lesson(12, 9, status = "Prof. absent", canceled = true) else it }
        assertEquals("Finalement maintenu · Mathématiques", Changes.lessons(before, week, now).single().title)
    }

    @Test
    fun pastAndFarLessonsAreIgnored() {
        val later = LocalDateTime.of(2026, 10, 12, 10, 0)
        // 12th 8h and 9h are over, the 20th is beyond the horizon of a week.
        val after = listOf(lesson(12, 8, room = "X"), lesson(12, 9, room = "X"), lesson(13, 8), lesson(20, 8, room = "X"))
        assertTrue(Changes.lessons(week, after, later).isEmpty())
    }

    @Test
    fun aDayThatJustEnteredTheWindowIsNotNew() {
        val before = listOf(lesson(12, 8))
        val after = listOf(lesson(12, 8), lesson(13, 8, status = "Prof. absent"))
        assertTrue(Changes.lessons(before, after, now).isEmpty())
    }

    @Test
    fun removedAndAddedLessons() {
        val after = listOf(lesson(12, 8), lesson(12, 10, status = "Cours déplacé"), lesson(13, 8), lesson(20, 8))
        val titles = Changes.lessons(week, after, now).map { it.title }
        assertEquals(listOf("Cours déplacé · Mathématiques", "Retiré de l'emploi du temps · Mathématiques"), titles)
    }

    private fun grade(subject: String, value: String, day: Int = 10) =
        Grade("id-$subject-$value", subject, LocalDate.of(2026, 10, day), value, value.toDoubleOrNull(), 20.0, 11.5, 2.0, null)

    @Test
    fun newGradeKeepsItsValueOffTheLockScreen() {
        val before = listOf(grade("ANGLAIS LV1", "14"))
        val after = before + grade("MATHÉMATIQUES", "15.5", 11)
        val item = Changes.grades(before, after).single()
        assertEquals("Mathématiques : 15.5/20", item.title)
        assertEquals("classe 11,5 · coef 2", item.text)
        assertEquals("Nouvelle note en Mathématiques", item.publicTitle)
        assertTrue(Changes.grades(null, after).isEmpty())
    }

    @Test
    fun newMessageOnlyWhenUnreadGrows() {
        fun d(unread: Int, count: Int) = Discussion("Sortie", "M. Martin", "lun.", unread, count,
            listOf(Discussion.Message("M. MARTIN", false, null, "Pensez à\n l'autorisation.")))
        assertTrue(Changes.messages(listOf(d(1, 3)), listOf(d(1, 3))).isEmpty())
        assertTrue(Changes.messages(listOf(d(1, 3)), listOf(d(0, 3))).isEmpty())
        val item = Changes.messages(listOf(d(0, 3)), listOf(d(1, 4))).single()
        assertEquals("Message de M. MARTIN", item.title)
        assertEquals("Sortie · Pensez à l'autorisation.", item.text)
    }

    @Test
    fun newUnreadNews() {
        fun n(title: String, read: Boolean = false) = News(title, "Vie scolaire", null, "Info", false, read, "", emptyList())
        val items = Changes.news(listOf(n("Photos de classe")), listOf(n("Photos de classe"), n("Bac blanc"), n("Lu", read = true)))
        assertEquals(listOf("Actualité : Bac blanc"), items.map { it.title })
    }
}
