package fr.sallio.pronote

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/**
 * Talks to Index Éducation's public demo (fake students, published
 * credentials). Off by default so the build never depends on the network:
 * `PRONOTE_DEMO=1 ./gradlew testDebugUnitTest`.
 */
class DemoServerTest {

    private val demo = "https://demo.index-education.net/pronote/eleve.html"

    @Test
    fun credentialsThenTokenThenTimetable() {
        assumeTrue(System.getenv("PRONOTE_DEMO") == "1")
        val http = UrlConnectionHttp()
        val device = UUID.randomUUID().toString()

        val first = Pronote.loginCredentials(http, demo, "demonstration", "pronotevs", device)
        println("élève=${first.studentName} classe=${first.className} établissement=${first.schoolName}")
        assertTrue(first.credentials.token.isNotEmpty())

        // The demo is read only (its "last connection" is frozen in 2023), so it
        // cannot store a device token: the token login has to be tested on a
        // real school. Here we only check it fails cleanly, as a refused login.
        val refused = runCatching { Pronote.loginToken(http, first.credentials) }.exceptionOrNull()
        assertTrue("$refused", refused == null || refused is PronoteException.BadCredentials)

        val monday = LocalDate.now().with(java.time.DayOfWeek.MONDAY)
        val lessons = Timetable.fetch(first, monday, monday.plusDays(13))
        lessons.take(12).forEach { println("${it.start} → ${it.end.toLocalTime()} ${it.subject} ${it.rooms} ${it.teachers} ${it.status ?: ""}") }
        println("${lessons.size} cours, ${lessons.count { it.canceled }} annulés, ${lessons.count { it.modified }} modifiés")
        assertTrue(lessons.isNotEmpty())

        // The demo only has homework in September.
        val homework = Schoolwork.homework(first, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 31))
        println("${homework.size} devoirs")
        assertTrue(homework.isNotEmpty())
        homework.take(4).forEach { println("devoir ${it.due} ${it.subject} : ${it.description.take(60)} fait=${it.done}") }
        val report = Schoolwork.report(first)
        println("${report.periodName} moyenne=${report.overall} classe=${report.classOverall} ${report.grades.size} notes ${report.subjects.size} matières")
        report.grades.take(4).forEach { println("note ${it.date} ${it.subject} ${it.value}/${it.outOf} classe=${it.classAverage}") }
        assertTrue(report.subjects.isNotEmpty())
        assertTrue(lessons.all { it.end > it.start })
    }
}
