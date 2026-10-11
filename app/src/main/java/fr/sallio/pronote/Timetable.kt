package fr.sallio.pronote

import fr.sallio.pronote.Pronote.objects
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

/** One slot of the timetable, already reduced to what a widget shows. */
data class Lesson(
    val start: LocalDateTime,
    val end: LocalDateTime,
    val subject: String,
    val rooms: List<String>,
    val teachers: List<String>,
    val groups: List<String>,
    /** PRONOTE's own label: "Prof. absent", "Cours annulé", "Changement de salle"... */
    val status: String?,
    val canceled: Boolean,
    /** Replacement, room change, moved lesson: anything PRONOTE flags as modified. */
    val modified: Boolean,
    val color: String?,
    val kind: Kind,
) {
    enum class Kind { LESSON, DETENTION, ACTIVITY }

    val teacherAbsent: Boolean
        // "Prof. absent", "Professeur absent"; not "Classe absente".
        get() = status?.let { TEACHER_ABSENT.containsMatchIn(it) } == true

    fun toJson(): JSONObject = JSONObject()
        .put("s", start.toString())
        .put("e", end.toString())
        .put("sub", subject)
        .put("r", JSONArray(rooms))
        .put("t", JSONArray(teachers))
        .put("g", JSONArray(groups))
        .put("st", status ?: JSONObject.NULL)
        .put("c", canceled)
        .put("m", modified)
        .put("col", color ?: JSONObject.NULL)
        .put("k", kind.name)

    companion object {
        private val TEACHER_ABSENT = Regex("(?i)\\b(prof|professeur|enseignant)[^,;]*absent")

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { getString(it) }

        fun fromJson(o: JSONObject) = Lesson(
            start = LocalDateTime.parse(o.getString("s")),
            end = LocalDateTime.parse(o.getString("e")),
            subject = o.getString("sub"),
            rooms = o.optJSONArray("r").strings(),
            teachers = o.optJSONArray("t").strings(),
            groups = o.optJSONArray("g").strings(),
            status = if (o.isNull("st")) null else o.getString("st"),
            canceled = o.optBoolean("c"),
            modified = o.optBoolean("m"),
            color = if (o.isNull("col")) null else o.getString("col"),
            kind = runCatching { Kind.valueOf(o.getString("k")) }.getOrDefault(Kind.LESSON),
        )
    }
}

object Timetable {

    /** `PageEmploiDuTemps` between two dates (both included), sorted, ghost lessons removed. */
    fun fetch(logged: LoggedIn, from: LocalDate, to: LocalDate): List<Lesson> {
        val session = logged.session
        val p = session.properties
        val resource = session.userResource ?: throw PronoteException.Protocol("Pas d'élève")

        val data = JSONObject()
            .put("estEDTAnnuel", false)
            .put("estEDTPermanence", false)
            .put("avecAbsencesEleve", false)
            .put("avecRessourcesLibrePiedHoraire", false)
            .put("avecAbsencesRessource", true)
            .put("avecInfosPrefsGrille", true)
            .put("avecConseilDeClasse", true)
            .put("avecCoursSortiePeda", true)
            .put("avecDisponibilites", true)
            .put("avecRetenuesEleve", true)
            .put("edt", JSONObject().put("G", 16).put("L", "Emploi du temps"))
        // PRONOTE versions disagree on the case of these keys, so both are sent.
        for (key in listOf("ressource", "Ressource")) data.put(key, resource)
        val start = JSONObject().put("_T", 7).put("V", PronoteDates.format(from.atStartOfDay()))
        val end = JSONObject().put("_T", 7).put("V", PronoteDates.format(to.atTime(23, 59, 59)))
        for (key in listOf("dateDebut", "DateDebut")) data.put(key, start)
        for (key in listOf("dateFin", "DateFin")) data.put(key, end)

        val response = session.call(
            "PageEmploiDuTemps",
            JSONObject()
                .put(p.signature, JSONObject().put("onglet", 16))
                .put(p.data, data),
        )
        val page = response.optJSONObject(p.data) ?: throw PronoteException.Protocol("Emploi du temps vide")
        val instance = session.instance
        val lessons = page.optJSONArray("ListeCours")?.objects().orEmpty()
            .mapNotNull { decode(it, instance) }
            .sortedBy { it.start }
        return hideSuperposedCanceled(lessons)
    }

    internal fun decode(item: JSONObject, instance: InstanceParameters?): Lesson? {
        val start = PronoteDates.parse(item.optJSONObject("DateDuCours")?.optString("V") ?: return null) ?: return null
        val end = item.optJSONObject("DateDuCoursFin")?.optString("V")?.let { PronoteDates.parse(it) }
            ?: endFromBlocks(start, item.optInt("place"), item.optInt("duree", 1), instance)

        var subject: String? = null
        val rooms = mutableListOf<String>()
        val teachers = mutableListOf<String>()
        val groups = mutableListOf<String>()
        item.optJSONObject("ListeContenus")?.optJSONArray("V")?.objects()?.forEach { c ->
            val label = c.optString("L").trim()
            if (label.isEmpty()) return@forEach
            when (c.optInt("G", -1)) {
                16 -> subject = label
                3 -> teachers += label
                34 -> teachers += label // "personnel": surveillant, CPE...
                17 -> rooms += label
                2, 1 -> groups += label
            }
        }

        val kind = when {
            item.optBoolean("estSortiePedagogique") -> Lesson.Kind.ACTIVITY
            item.has("estRetenue") -> Lesson.Kind.DETENTION
            else -> Lesson.Kind.LESSON
        }
        val status = item.optString("Statut").trim().ifEmpty { null }
        return Lesson(
            start = start,
            end = end,
            subject = subject ?: when (kind) {
                Lesson.Kind.DETENTION -> "Retenue"
                Lesson.Kind.ACTIVITY -> item.optString("motif").ifEmpty { "Sortie pédagogique" }
                Lesson.Kind.LESSON -> "Cours"
            },
            rooms = rooms,
            teachers = teachers,
            groups = groups,
            status = status,
            canceled = item.optBoolean("estAnnule"),
            // G = 2 is EnseignementRemplacement; any status label means "something changed".
            modified = item.optInt("G") == 2 || status != null,
            color = item.optString("CouleurFond").ifEmpty { null },
            kind = kind,
        )
    }

    private fun endFromBlocks(start: LocalDateTime, place: Int, length: Int, instance: InstanceParameters?): LocalDateTime {
        val endings = instance?.endings.orEmpty()
        val perDay = instance?.blocksPerDay ?: 0
        if (endings.isEmpty() || perDay == 0) return start.plusHours(1)
        var position = place % perDay + length - 1
        if (position >= endings.size) position %= (endings.size - 1).coerceAtLeast(1)
        val (h, m) = endings[position].split("h").map { it.trim().toIntOrNull() ?: 0 }.let { it[0] to it.getOrElse(1) { 0 } }
        return start.toLocalDate().atTime(h, m)
    }

    /**
     * PRONOTE often keeps the canceled lesson and its replacement on the same
     * slot. Its own app hides the canceled one when something else takes the
     * slot; a canceled lesson alone stays visible ("Prof. absent").
     */
    internal fun hideSuperposedCanceled(lessons: List<Lesson>): List<Lesson> =
        lessons.filterNot { lesson ->
            lesson.canceled && lessons.any { other ->
                other !== lesson && !other.canceled && other.start < lesson.end && lesson.start < other.end
            }
        }
}

internal object PronoteDates {
    private val LONG = Regex("""^(\d{1,2})/(\d{1,2})/(\d{4}) (\d{1,2}):(\d{1,2}):(\d{1,2})$""")
    private val SHORT = Regex("""^(\d{1,2})/(\d{1,2})/(\d{4})$""")
    private val COMPACT = Regex("""^(\d{2})/(\d{2})/(\d{2}) (\d{2})h(\d{2})$""")

    fun parse(text: String): LocalDateTime? {
        val t = text.trim()
        LONG.matchEntire(t)?.destructured?.let { (d, mo, y, h, mi, s) ->
            return LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt())
        }
        SHORT.matchEntire(t)?.destructured?.let { (d, mo, y) ->
            return LocalDate.of(y.toInt(), mo.toInt(), d.toInt()).atStartOfDay()
        }
        COMPACT.matchEntire(t)?.destructured?.let { (d, mo, y, h, mi) ->
            return LocalDateTime.of(2000 + y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt())
        }
        return null
    }

    /** `d/m/yyyy H:M:S` without padding, as PRONOTE writes it. */
    fun format(t: LocalDateTime): String =
        "${t.dayOfMonth}/${t.monthValue}/${t.year} ${t.hour}:${t.minute}:${t.second}"

    /** "[1,3..5]" -> [1, 3, 4, 5] */
    fun domain(text: String): Set<Int> {
        val t = text.trim()
        if (!t.startsWith("[") || !t.endsWith("]")) return emptySet()
        val inner = t.substring(1, t.length - 1)
        if (inner.isBlank()) return emptySet()
        val out = mutableSetOf<Int>()
        for (part in inner.split(",")) {
            if (".." in part) {
                val (a, b) = part.split("..").map { it.trim().toInt() }
                out.addAll(a..b)
            } else part.trim().toIntOrNull()?.let { out += it }
        }
        return out
    }
}
