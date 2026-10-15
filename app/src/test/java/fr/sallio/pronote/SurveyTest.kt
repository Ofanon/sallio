package fr.sallio.pronote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyTest {

    @Test fun `positions read and written as PRONOTE does`() {
        assertEquals(setOf(1, 3, 4, 5), Inbox.ranges("[1,3..5]"))
        assertEquals(emptySet<Int>(), Inbox.ranges("[]"))
        assertEquals("[1,3..5]", Inbox.ranges(setOf(5, 1, 4, 3)))
        assertEquals("[2]", Inbox.ranges(setOf(2)))
        assertEquals("[]", Inbox.ranges(emptySet()))
    }

    // Shapes of PageActualites' listeQuestions, as Pawnote reads them.
    private val multiple = JSONObject("""{"N":"q1","L":"Q1","titre":"Tes options","genreReponse":3,"avecMaximum":true,"nombreReponsesMax":2,
        "listeChoix":{"V":[{"L":"Latin","rang":1},{"L":"Grec","rang":2},{"L":"Autre","rang":3,"estReponseLibre":true}]},
        "reponse":{"V":{"N":"77","avecReponse":true,"estReponseAttendue":true,"valeurReponse":{"_T":8,"V":"[1,3]"},"valeurReponseLibre":"Chinois"}}}""")

    @Test fun `a several-choice question with its answer`() {
        val q = Inbox.question(multiple, "")
        assertEquals(News.KIND_MULTIPLE, q.kind)
        assertEquals(listOf("Latin", "Grec", "Autre"), q.choices)
        assertEquals(listOf(1, 2, 3), q.ranks)
        assertEquals(setOf(3), q.freeChoices)
        assertEquals(2, q.maxChoices)
        assertTrue(q.answered)
        assertEquals(News.Answer(setOf(1, 3), "Chinois"), q.answer)
    }

    @Test fun `a typed question not answered yet`() {
        val q = Inbox.question(JSONObject("""{"N":"q2","L":"Q2","genreReponse":1,"reponse":{"V":{"N":"78","avecReponse":false,"estReponseAttendue":true}}}"""), "Pourquoi ?")
        assertEquals(News.KIND_TEXT, q.kind)
        assertTrue(q.needsInput)
        assertFalse(q.answered)
        assertEquals(News.Answer(), q.answer)
    }

    @Test fun `answers survive the phone's copy`() {
        val n = News("Sondage", "M. X", null, "Vie du lycée", survey = true, read = false, content = "",
            questions = listOf(Inbox.question(multiple, "")), files = emptyList(), needsAck = false, acked = false)
        assertEquals(n, News.fromJson(n.toJson()))
    }
}
