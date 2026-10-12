package fr.sallio.pronote

import fr.sallio.pronote.Pronote.objects
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime

/** One line of the vie scolaire: an absence, a late arrival, an exemption or a punishment. */
data class SchoolEvent(
    val kind: Kind,
    val start: LocalDateTime,
    val end: LocalDateTime?,
    /** "Absence", "Retenue", "Avertissement", "Dispense sur une matière"... */
    val label: String,
    val reasons: List<String>,
    /** Null when PRONOTE does not say (punishments). */
    val justified: Boolean?,
    /** PRONOTE waits for the parents to give a reason. */
    val toJustify: Boolean,
    /** Absence: "25h00". */
    val hours: String? = null,
    /** Late arrival or punishment length. */
    val minutes: Int? = null,
    val subject: String? = null,
    /** Who gave the punishment. */
    val by: String? = null,
    val comment: String? = null,
    /** Work set as a punishment. */
    val work: String? = null,
) {
    enum class Kind { ABSENCE, LATE, EXEMPTION, PUNISHMENT, SANCTION }

    /** Stable across sessions, unlike PRONOTE's ids (see [Homework.key]). */
    val key: String get() = "$kind|$start|$label"

    fun toJson(): JSONObject = JSONObject()
        .put("k", kind.name).put("s", start.toString()).put("e", end?.toString() ?: JSONObject.NULL)
        .put("l", label).put("r", JSONArray(reasons))
        .put("j", justified ?: JSONObject.NULL).put("tj", toJustify)
        .put("h", hours ?: JSONObject.NULL).put("m", minutes ?: JSONObject.NULL)
        .put("sub", subject ?: JSONObject.NULL).put("by", by ?: JSONObject.NULL)
        .put("c", comment ?: JSONObject.NULL).put("w", work ?: JSONObject.NULL)

    companion object {
        private fun JSONObject.str(name: String): String? = if (isNull(name)) null else optString(name)

        fun fromJson(o: JSONObject) = SchoolEvent(
            kind = Kind.valueOf(o.getString("k")),
            start = LocalDateTime.parse(o.getString("s")),
            end = o.str("e")?.let { LocalDateTime.parse(it) },
            label = o.optString("l"),
            reasons = o.optJSONArray("r")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            justified = if (o.isNull("j")) null else o.optBoolean("j"),
            toJustify = o.optBoolean("tj"),
            hours = o.str("h"),
            minutes = if (o.isNull("m")) null else o.optInt("m"),
            subject = o.str("sub"),
            by = o.str("by"),
            comment = o.str("c"),
            work = o.str("w"),
        )
    }
}

/** The school year's vie scolaire, newest first, with PRONOTE's own totals. */
data class SchoolLife(
    val events: List<SchoolEvent>,
    /** "166h30" of absence over the year. */
    val absenceHours: String?,
    val unjustifiedAbsences: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("events", JSONArray(events.map { it.toJson() }))
        .put("hours", absenceHours ?: JSONObject.NULL)
        .put("unjustified", unjustifiedAbsences)

    companion object {
        fun fromJson(o: JSONObject) = SchoolLife(
            events = o.optJSONArray("events")?.objects().orEmpty().map { SchoolEvent.fromJson(it) },
            absenceHours = if (o.isNull("hours")) null else o.optString("hours"),
            unjustifiedAbsences = o.optInt("unjustified"),
        )
    }
}

/**
 * `PagePresence` (onglet 19), read-only: absences, late arrivals, exemptions
 * and punishments come in one list, told apart by their genre G. The dates
 * sent decide the range; the whole school year is asked for.
 */
object Presence {

    private const val ABSENCE = 13
    private const val LATE = 14
    private const val EXEMPTION = 40
    private const val PUNISHMENT = 41
    private const val SANCTION = 42
    private const val PROTECTIVE_MEASURE = 71

    fun fetch(logged: LoggedIn): SchoolLife {
        val session = logged.session
        val p = session.properties
        val periods = session.instance?.periods.orEmpty()
        val start = periods.mapNotNull { it.start }.minOrNull() ?: throw PronoteException.Protocol("Pas de période")
        val end = periods.mapNotNull { it.end }.maxOrNull() ?: throw PronoteException.Protocol("Pas de période")
        // G = 3 is the whole year ("Année continue"); otherwise the period PRONOTE opens this page on.
        val period = periods.firstOrNull { it.entity.optInt("G") == 3 }
            ?: session.defaultPeriods[19]?.let { id -> periods.firstOrNull { it.entity.optString("N") == id } }
            ?: periods.first()
        val response = session.call(
            "PagePresence",
            JSONObject()
                .put(p.signature, JSONObject().put("onglet", 19))
                .put(p.data, JSONObject()
                    .put("periode", period.entity)
                    .put("DateDebut", JSONObject().put("_T", 7).put("V", PronoteDates.format(start.atStartOfDay())))
                    .put("DateFin", JSONObject().put("_T", 7).put("V", PronoteDates.format(end.atTime(23, 59, 59))))),
        )
        val data = response.optJSONObject(p.data) ?: throw PronoteException.Protocol("Vie scolaire vide")
        return parse(data)
    }

    internal fun parse(data: JSONObject): SchoolLife {
        val events = data.optJSONObject("listeAbsences")?.optJSONArray("V")?.objects().orEmpty().mapNotNull(::event)
        val recap = data.optJSONObject("listeRecapitulatifs")?.optJSONArray("V")?.objects().orEmpty()
            .firstOrNull { it.optInt("G") == ABSENCE }
        return SchoolLife(
            events = events.sortedByDescending { it.start },
            absenceHours = recap?.optString("NbrHeures")?.ifEmpty { null },
            unjustifiedAbsences = recap?.optInt("NombreNonJustifie") ?: events.count { it.kind == SchoolEvent.Kind.ABSENCE && it.justified == false },
        )
    }

    private fun date(o: JSONObject, name: String): LocalDateTime? =
        o.optJSONObject(name)?.optString("V")?.let { PronoteDates.parse(it) }

    private fun label(o: JSONObject?, name: String): String? = o?.optJSONObject(name)?.optJSONObject("V")?.optString("L")?.trim()?.ifEmpty { null }

    private fun reasons(o: JSONObject) =
        o.optJSONObject("listeMotifs")?.optJSONArray("V")?.objects().orEmpty().map { it.optString("L").trim() }.filter { it.isNotEmpty() }

    private fun text(o: JSONObject, name: String) = o.optString(name).let { Schoolwork.htmlToText(it) }.trim().ifEmpty { null }

    private fun event(o: JSONObject): SchoolEvent? = when (o.optInt("G")) {
        ABSENCE -> date(o, "dateDebut")?.let { start ->
            SchoolEvent(SchoolEvent.Kind.ABSENCE, start, date(o, "dateFin"), "Absence", reasons(o),
                justified = o.optBoolean("justifie"), toJustify = o.optBoolean("aJustifierParParents"),
                hours = o.optString("NbrHeures").ifEmpty { null })
        }
        LATE -> date(o, "date")?.let { start ->
            val minutes = o.optInt("duree", 0).takeIf { it > 0 }
            SchoolEvent(SchoolEvent.Kind.LATE, start, null, "Retard", reasons(o),
                justified = o.optBoolean("justifie"), toJustify = o.optBoolean("aJustifierParParents"),
                minutes = minutes, comment = text(o, "justification"))
        }
        EXEMPTION -> date(o, "dateDebut")?.let { start ->
            SchoolEvent(SchoolEvent.Kind.EXEMPTION, start, date(o, "dateFin"), o.optString("strGenreDispense").ifEmpty { "Dispense" }, emptyList(),
                justified = null, toJustify = false, subject = label(o, "matiere"))
        }
        PUNISHMENT, SANCTION, PROTECTIVE_MEASURE -> date(o, "dateDemande")?.let { given ->
            val kind = if (o.optInt("G") == PUNISHMENT) SchoolEvent.Kind.PUNISHMENT else SchoolEvent.Kind.SANCTION
            SchoolEvent(kind, given, null, label(o, "nature") ?: if (kind == SchoolEvent.Kind.PUNISHMENT) "Punition" else "Sanction", reasons(o),
                justified = null, toJustify = false,
                minutes = o.optInt("duree", 0).takeIf { it > 0 && kind == SchoolEvent.Kind.PUNISHMENT },
                by = label(o, "demandeur"),
                comment = text(o, "circonstances") ?: text(o, "commentaire"),
                work = text(o, "travailAFaire"))
        }
        else -> null
    }
}
