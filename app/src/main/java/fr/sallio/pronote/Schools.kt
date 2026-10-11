package fr.sallio.pronote

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Finding the PRONOTE address of a school. */
object Schools {

    class Info(val name: String, val usesEnt: Boolean)

    class Nearby(val name: String, val url: String, val postalCode: String, val distanceKm: Double)

    private val UAI = Regex("^[0-9]{7}[A-Za-z]$")

    /**
     * Accepts what a student is likely to paste or type: the full PRONOTE
     * address, the address without https, or the school's UAI code
     * ("0750655E"), which is the subdomain of schools hosted by Index Éducation.
     */
    fun urlFromInput(input: String): String? {
        val t = input.trim().replace(" ", "")
        if (t.isEmpty()) return null
        if (UAI.matches(t)) return "https://${t.lowercase(Locale.ROOT)}.index-education.net/pronote"
        val withScheme = when {
            t.startsWith("https://", ignoreCase = true) -> t
            t.startsWith("http://", ignoreCase = true) -> "https://" + t.substring(7)
            t.contains(".") -> "https://$t"
            else -> return null
        }
        return runCatching { Pronote.cleanUrl(withScheme) }.getOrNull()
    }

    /** Asks the school's PRONOTE who it is, which also proves the address is right. */
    fun info(http: Http, base: String): Info {
        val text = http.get("$base/infoMobileApp.json?id=0D264427-EEFC-4810-A9E9-346942A862A4")
        val json = runCatching { JSONObject(text) }.getOrElse {
            throw Session.classifyErrorPage(text) ?: PronoteException.Unavailable()
        }
        val name = json.optString("nomEtab").trim().ifEmpty { throw PronoteException.Unavailable() }
        return Info(prettySchool(name), json.optJSONObject("CAS")?.optBoolean("actif") == true)
    }

    /**
     * Schools around a position, from the service the official app uses
     * (Index Éducation, who publishes PRONOTE). Callers round the position:
     * a ~1 km precision is enough to list the schools of a town.
     */
    fun nearby(http: Http, latitude: Double, longitude: Double): List<Nearby> {
        val lat = String.format(Locale.ROOT, "%.2f", latitude)
        val lon = String.format(Locale.ROOT, "%.2f", longitude)
        val body = """data={"nomFonction":"geoLoc","lat":"$lat","long":"$lon"}"""
        val text = http.post(
            "https://www.index-education.com/swie/geoloc.php",
            body,
            "application/x-www-form-urlencoded;charset=UTF-8",
        )
        if (text.trim() == "{}" || text.isBlank()) return emptyList()
        val array = runCatching { JSONArray(text) }.getOrElse { throw PronoteException.Unavailable() }
        // The directory is hand-filled by schools: some addresses contain spaces
        // or are broken. A bad row is skipped, it must not hide all the others.
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapNotNull {
            val la = it.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
            val lo = it.optString("long").toDoubleOrNull() ?: return@mapNotNull null
            val url = runCatching {
                Pronote.cleanUrl(it.optString("url").replace(Regex("\\s"), "").lowercase(Locale.ROOT))
            }.getOrNull()?.takeIf { u -> u.startsWith("https://") } ?: return@mapNotNull null
            Nearby(
                name = prettySchool(it.optString("nomEtab")),
                url = url,
                postalCode = it.optString("cp"),
                distanceKm = haversine(latitude, longitude, la, lo),
            )
        }.sortedWith(compareBy({ it.distanceKm }, { it.name }))
    }

    /** "evariste galois" finds "LYCÉE EVARISTE GALOIS": every word, accents and case ignored. */
    fun filterByName(schools: List<Nearby>, query: String): List<Nearby> {
        val words = fold(query).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return schools.filter { school ->
            val name = fold(school.name + " " + school.postalCode)
            words.all { it in name }
        }
    }

    private fun fold(text: String): String =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)

    fun prettySchool(name: String): String =
        name.trim().replace("COLLEGE", "COLLÈGE").replace("LYCEE", "LYCÉE")

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}
