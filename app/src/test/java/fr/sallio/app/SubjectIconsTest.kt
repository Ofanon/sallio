package fr.sallio.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Subject names as real PRONOTE servers write them, and the icon each one gets. */
class SubjectIconsTest {

    private fun icon(subject: String) = Subjects.style(subject).icon

    @Test
    fun eachLanguageHasItsOwnBadge() {
        assertEquals(R.drawable.ic_lang_de, icon("ALLEMAND LV2"))
        assertEquals(R.drawable.ic_lang_de, icon("Allemand LV2 > ecrit"))
        assertEquals(R.drawable.ic_lang_en, icon("ANGLAIS LV SECTION"))
        assertEquals(R.drawable.ic_lang_en, icon("English theater"))
        assertEquals(R.drawable.ic_lang_zh, icon("Chinois LV3 > ecrit"))
        assertEquals(R.drawable.ic_lang_es, icon("ESPAGNOL LV2"))
        assertEquals(R.drawable.ic_language, icon("LV3"))
    }

    @Test
    fun otherSubjects() {
        assertEquals(R.drawable.ic_columns, icon("LCA LATIN"))
        assertEquals(R.drawable.ic_columns, icon("Langues et cultures de l'Antiquité"))
        assertEquals(R.drawable.ic_atom, icon("ENSEIGNEMENT SCIENTIFIQUE"))
        assertEquals(R.drawable.ic_flask, icon("PHYSIQUE-CHIMIE"))
        assertEquals(R.drawable.ic_dna, icon("SCIENCES DE LA VIE ET DE LA TERRE"))
        assertEquals(R.drawable.ic_run, icon("ED.PHYSIQUE & SPORT."))
        assertEquals(R.drawable.ic_code, icon("Numerique sc.inform."))
        assertEquals(R.drawable.ic_feather, icon("FRANCAIS"))
        assertEquals(R.drawable.ic_scale, icon("ENSEIGN.MORAL & CIVIQUE"))
        assertEquals(R.drawable.ic_world, icon("HISTOIRE-GÉOGRAPHIE"))
        assertEquals(R.drawable.ic_tool, icon("TECHNOLOGIE"))
        assertEquals(R.drawable.ic_maths, icon("MATHÉMATIQUES"))
    }
}
