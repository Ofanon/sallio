package fr.sallio.turboself

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Reads a real TurboSelf account, only when its owner asks for it:
 *
 *   TURBOSELF_USER=... TURBOSELF_PASS=... ./gradlew testDebugUnitTest --tests "*TurboselfProbeTest"
 *
 * Read-only: no booking is changed. Prints the shape of the answers (units of
 * the amounts, numbering of days and weeks), with the card number masked.
 */
class TurboselfProbeTest {

    @Test
    fun probe() {
        val user = System.getenv("TURBOSELF_USER")
        val pass = System.getenv("TURBOSELF_PASS")
        assumeTrue(!user.isNullOrEmpty() && !pass.isNullOrEmpty())

        // Raw answers as well as the parsed ones, to see the fields as TurboSelf sends them.
        val raw = mutableMapOf<String, String>()
        val t = Turboself.Transport { method, url, body, token ->
            Turboself.https.send(method, url, body, token).also { raw[url.substringAfter("/api/")] = it.second }
        }
        val s = Turboself.login(t, user!!, pass!!)
        println("session hote=${s.hostId} user=${s.userId}")

        val host = Turboself.host(t, s)
        println("hote mode=${host.mode} classe=${host.className} prixDej=${host.lunchPrice} carte=${host.cardNumber?.toString()?.let { "…" + it.takeLast(2) + " (${it.length} chiffres)" }} reservation=${host.canBook} soldeNegatif=${host.canBookWithNegativeBalance}")
        println("hote prenom/nom renseignés : prenom=${host.firstName.isNotEmpty()} nom=${host.lastName.isNotEmpty()}")
        raw.entries.firstOrNull { it.key.startsWith("v1/hotes/") }?.let { e ->
            println("champs hote : " + JSONObject(e.value).keys().asSequence().sorted().joinToString())
        }

        for (b in Turboself.balances(t, s)) println("solde ${b.label} montant=${b.amount} estime=${b.estimatedAmount} msg=${b.estimatedMessage}")

        val days = Turboself.bookings(t, s)
        raw.entries.firstOrNull { it.key.contains("semaines") }?.let { e ->
            val o = JSONObject(e.value)
            println("reservations : dateSemaine=${o.opt("dateSemaine")} numSemaines=${o.opt("numSemaines")} soir=${o.opt("isResaSoirActive")}")
            o.optJSONArray("rsvWebDto")?.optJSONObject(0)?.let { w ->
                println("semaine annee=${w.opt("annee")} semaine=${w.opt("semaine")} joursAutorises=${w.opt("joursAutorises")} usage=${w.opt("usage")}")
                println("champs jour : " + w.optJSONArray("jours")?.optJSONObject(0)?.keys()?.asSequence()?.sorted()?.joinToString())
            }
        }
        for (d in days) println("jour ${d.label} dayOfWeek=${d.dayOfWeek} reserve=${d.booked} autorise=${d.allowed} borne=${d.terminal} msg=${d.message}")

        val history = Turboself.history(t, s)
        println("historique : ${history.size} lignes")
        history.take(4).forEach { println("  ${it.date} ${it.detail} ${it.amount}") }
        raw.entries.firstOrNull { it.key.startsWith("v1/historiques") }?.let { e ->
            JSONArray(e.value).optJSONObject(0)?.let { println("champs historique : " + it.keys().asSequence().sorted().joinToString()) }
        }
    }
}
