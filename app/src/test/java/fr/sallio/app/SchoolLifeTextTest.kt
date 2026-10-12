package fr.sallio.app

import fr.sallio.pronote.SchoolEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class SchoolLifeTextTest {

    private val absence = SchoolEvent(SchoolEvent.Kind.ABSENCE, LocalDateTime.of(2026, 9, 8, 8, 0), LocalDateTime.of(2026, 9, 14, 18, 0),
        "Absence", listOf("PROBLEME DE TRANSPORT"), justified = true, toJustify = false, hours = "25h00")
    private val late = SchoolEvent(SchoolEvent.Kind.LATE, LocalDateTime.of(2026, 10, 23, 8, 0), null,
        "Retard", emptyList(), justified = false, toJustify = true, minutes = 5)
    private val warning = SchoolEvent(SchoolEvent.Kind.SANCTION, LocalDateTime.of(2026, 9, 9, 13, 42), null,
        "Avertissement", listOf("Dissipation"), justified = null, toJustify = false, by = "INDEX I.")

    @Test
    fun titlesAndDates() {
        assertEquals("Absence", SchoolLifeText.title(absence))
        assertEquals("Du 8 sept. au 14 sept.", SchoolLifeText.date(absence))
        assertEquals("Retard de 5 min", SchoolLifeText.title(late))
        assertEquals("Vendredi 23 oct. à 8h00", SchoolLifeText.date(late))
        assertEquals("Avertissement", SchoolLifeText.title(warning))
    }

    @Test
    fun details() {
        assertEquals("Du 8 sept. au 14 sept. · 25h00 de cours · Probleme de transport · Justifiée", SchoolLifeText.details(absence))
        assertEquals("Vendredi 23 oct. à 8h00 · À justifier", SchoolLifeText.details(late))
        assertEquals("Mercredi 9 sept. à 13h42 · Dissipation · par Index I.", SchoolLifeText.details(warning))
    }

    @Test
    fun onlyNewEventsAreAlerts() {
        assertTrue(Changes.schoolLife(null, listOf(absence)).isEmpty())
        val item = Changes.schoolLife(listOf(absence), listOf(absence, late)).single()
        assertEquals("Retard de 5 min", item.title)
        assertEquals("Vie scolaire", item.publicTitle)
    }
}
