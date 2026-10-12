package fr.sallio.turboself

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Parsing of TurboSelf's answers, shaped as turboself-api 2.1.9 types them. */
class TurboselfTest {

    @Test
    fun host() {
        val h = Turboself.parseHost(JSONObject("""{"id":1,"idOrig":2,"nom":"FANON","prenom":"Oscar","mode":"Argent","division":"1G06",
            "prixDej":3.5,"droitReservation":true,"autoriseReservSoldeIns":false,"carteCodee":123456789}"""))
        assertEquals("Argent", h.mode)
        assertEquals("1G06", h.className)
        assertEquals(3.5, h.lunchPrice!!, 1e-9)
        assertEquals(123456789L, h.cardNumber)
        assertEquals(true, h.canBook)
        assertNull(Turboself.parseHost(JSONObject("""{"carteCodee":0}""")).cardNumber)
    }

    @Test
    fun balances() {
        val b = Turboself.parseBalances(JSONArray("""[{"id":1,"compte":2,"appli":{"id":3,"idOrig":4,"lib":"Self"},"hote":{"id":5},
            "montant":12.4,"montantEstime":8.9,"montantEstimeMsg":"Solde estimé au 24/10/2026"}]""")).single()
        assertEquals("Self", b.label)
        assertEquals(12.4, b.amount, 1e-9)
        assertEquals(8.9, b.estimatedAmount, 1e-9)
    }

    @Test
    fun bookings() {
        val days = Turboself.parseBookings(JSONObject("""{"rsvWebDto":[{"id":"w1","hote":{"id":5},"annee":2026,"semaine":42,
            "borne":{"id":1,"code2p5":2,"idOrig":3,"lib":"Self midi","prix":[]},"joursAutorises":31,"usage":1,
            "jours":[{"dayReserv":1,"reservDernSynchro":0,"dayOfWeek":1,"web":{"id":"w1"},"autorise":true,"dayLabel":"Lundi"},
                     {"dayReserv":0,"reservDernSynchro":0,"dayOfWeek":3,"web":{"id":"w1"},"autorise":false,"msg":"Fermé","dayLabel":"Mercredi"}]}],
            "numSemaines":[42,43],"isResaSoirActive":false,"dateSemaine":"x"}"""))
        assertEquals(2, days.size)
        assertEquals("Self midi", days[0].terminal)
        assertEquals(1, days[0].booked)
        assertEquals("Fermé", days[1].message)
        assertEquals(42, days[1].week)
    }

    @Test
    fun loginAndExpiry() {
        val t = Turboself.Transport { _, url, _, token ->
            when {
                url.endsWith("auth/login") -> 200 to """{"access_token":"abc","userId":7,"hoteId":5}"""
                token == "abc" -> 401 to "{}"
                else -> 500 to ""
            }
        }
        val s = Turboself.login(t, "eleve@example.fr", "x")
        assertEquals(5L, s.hostId)
        val failure = runCatching { Turboself.balances(t, s) }.exceptionOrNull()
        assertEquals(Turboself.Failure.Expired::class, failure!!::class)
        val bad = Turboself.Transport { _, _, _, _ -> 401 to """{"message":"Bad credentials"}""" }
        assertEquals(Turboself.Failure.BadCredentials::class, runCatching { Turboself.login(bad, "a", "b") }.exceptionOrNull()!!::class)
    }
}
