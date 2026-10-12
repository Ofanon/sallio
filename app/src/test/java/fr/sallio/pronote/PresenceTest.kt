package fr.sallio.pronote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

/** `PagePresence` as the demo server answered it in October 2026, shortened. */
class PresenceTest {

    private val answer = """
    {"listeRecapitulatifs":{"_T":24,"V":[{"NbrHeures":"166h30","G":13,"NombreTotal":61,"NombreNonJustifie":10}]},
     "listeAbsences":{"_T":24,"V":[
      {"justifie":true,"listeMotifs":{"_T":24,"V":[{"L":"PROBLEME DE TRANSPORT","N":"89#a"}]},"G":13,"N":"1#a","aJustifierParParents":false,
       "NbrHeures":"25h00","dateDebut":{"_T":7,"V":"08/09/2026 08:00:00"},"dateFin":{"_T":7,"V":"14/09/2026 18:00:00"},"NbrJours":9},
      {"date":{"_T":7,"V":"23/10/2026 08:00:00"},"aJustifierParParents":true,"justifie":false,"listeMotifs":{"_T":24,"V":[]},"G":14,
       "duree":5,"justification":"","N":"127#b"},
      {"justifie":true,"dateDebut":{"_T":7,"V":"02/12/2026 08:00:00"},"G":40,"dateFin":{"_T":7,"V":"04/12/2026 08:30:00"},
       "strGenreDispense":"Dispense sur une matière","N":"35#c","matiere":{"_T":24,"V":{"L":"ÉDUCATION PHYSIQUE ET SPORTIVE","N":"79#d"}}},
      {"nature":{"_T":24,"V":{"G":0,"L":"Avertissement","N":"99#e"}},"listeMotifs":{"_T":24,"V":[{"L":"Dissipation","N":"94#f"}]},"G":42,
       "demandeur":{"_T":24,"V":{"G":2,"L":"Index I.","N":"106#g"}},"estUneExclusion":false,"circonstances":"","duree":0,
       "dateDebut":{"_T":7,"V":"31/08/2026"},"dateFin":{"_T":7,"V":"31/08/2026"},"dateDemande":{"_T":7,"V":"09/09/2026 13:42:37"},"commentaire":""},
      {"G":99,"N":"0#unknown"}
     ]}}
    """

    @Test
    fun readsEveryKind() {
        val life = Presence.parse(JSONObject(answer))
        assertEquals("166h30", life.absenceHours)
        assertEquals(10, life.unjustifiedAbsences)
        assertEquals(listOf(SchoolEvent.Kind.EXEMPTION, SchoolEvent.Kind.LATE, SchoolEvent.Kind.SANCTION, SchoolEvent.Kind.ABSENCE), life.events.map { it.kind })

        val absence = life.events.last()
        assertEquals(LocalDateTime.of(2026, 9, 8, 8, 0), absence.start)
        assertEquals("25h00", absence.hours)
        assertEquals(listOf("PROBLEME DE TRANSPORT"), absence.reasons)
        assertEquals(true, absence.justified)

        val late = life.events[1]
        assertEquals(5, late.minutes)
        assertEquals(true, late.toJustify)
        assertNull(late.comment)

        val warning = life.events[2]
        assertEquals("Avertissement", warning.label)
        assertEquals("Index I.", warning.by)
        assertEquals(listOf("Dissipation"), warning.reasons)

        assertEquals("ÉDUCATION PHYSIQUE ET SPORTIVE", life.events[0].subject)
        assertEquals(life, SchoolLife.fromJson(JSONObject(life.toJson().toString())))
    }
}
