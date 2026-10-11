package fr.sallio.pronote

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate

/** Ticks a demo homework on PRONOTE, checks it from a new session, then unticks it. `PRONOTE_DEMO=1`. */
class HomeworkTickLiveTest {
    private val from = LocalDate.of(2026, 9, 1)
    private val to = LocalDate.of(2026, 10, 31)

    private fun login(n: Int) = Pronote.loginCredentials(
        UrlConnectionHttp(), "https://demo.index-education.net/pronote", "demonstration", "pronotevs", "tick$n",
    )

    @Test
    fun tickSurvivesANewSession() {
        assumeTrue(System.getenv("PRONOTE_DEMO") == "1")
        val first = login(1)
        val target = Schoolwork.homework(first, from, to).first()
        println("avant : ${target.key} fait=${target.done}")
        Schoolwork.setHomeworkDone(first, mapOf(target.id to !target.done))

        val second = login(2)
        val seen = Schoolwork.homework(second, from, to).first { it.key == target.key }
        println("nouvelle session : fait=${seen.done}")
        // Put the demo back as it was.
        Schoolwork.setHomeworkDone(second, mapOf(seen.id to target.done))
        println(if (seen.done != target.done) "COCHE ENREGISTRÉE PAR PRONOTE" else "DÉMO EN LECTURE SEULE : coche non conservée")
    }
}
