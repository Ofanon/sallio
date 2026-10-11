package fr.masalle.pronote

import org.junit.Assume.assumeTrue
import org.junit.Test

/** Live call to Index Éducation's school search: `PRONOTE_DEMO=1`. */
class NearbyLiveTest {
    @Test
    fun listsSchoolsAroundParis() {
        assumeTrue(System.getenv("PRONOTE_DEMO") == "1")
        val schools = Schools.nearby(UrlConnectionHttp(), 48.8566, 2.3522)
        schools.take(5).forEach { println("${it.name} ${it.postalCode} ${"%.1f".format(it.distanceKm)} km ${it.url}") }
        println("${schools.size} établissements")
        org.junit.Assert.assertTrue(schools.isNotEmpty())
    }
}
