package fr.sallio.pronote

import fr.sallio.pronote.Pronote.objects
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** One "travail à faire" from the cahier de textes. */
data class Homework(
    val id: String,
    val subject: String,
    /** Plain text, HTML removed. */
    val description: String,
    val due: LocalDate,
    /** As ticked on PRONOTE. */
    val done: Boolean,
    val color: String?,
    val attachments: Int,
    /** Day the teacher gave it. */
    val given: LocalDate? = null,
    val teachers: List<String> = emptyList(),
    /** Estimated time, in minutes, when the teacher filled it in. */
    val minutes: Int? = null,
    /** Attached documents: name, and the address for web links (files have none). */
    val files: List<Attachment> = emptyList(),
) {
    /**
     * [id] is the file's PRONOTE id, only valid in the session that read it,
     * so it is never stored: see [Schoolwork.attachmentUrl].
     */
    data class Attachment(val name: String, val link: String?, val id: String? = null)

    /**
     * PRONOTE re-encrypts every id ("147#...") for each session, so [id] is
     * only valid in the session that fetched it. This key survives logins.
     */
    val key: String get() = "$due|$subject|${description.hashCode()}"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("sub", subject).put("d", description).put("due", due.toString())
        .put("done", done).put("col", color ?: JSONObject.NULL).put("att", attachments)
        .put("given", given?.toString() ?: JSONObject.NULL)
        .put("teachers", JSONArray(teachers))
        .put("min", minutes ?: JSONObject.NULL)
        .put("files", JSONArray(files.map { JSONObject().put("n", it.name).put("u", it.link ?: JSONObject.NULL) }))

    companion object {
        fun fromJson(o: JSONObject) = Homework(
            id = o.getString("id"),
            subject = o.getString("sub"),
            description = o.optString("d"),
            due = LocalDate.parse(o.getString("due")),
            done = o.optBoolean("done"),
            color = if (o.isNull("col")) null else o.optString("col"),
            attachments = o.optInt("att"),
            given = if (o.isNull("given") || !o.has("given")) null else LocalDate.parse(o.getString("given")),
            teachers = o.optJSONArray("teachers")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            minutes = if (o.isNull("min") || !o.has("min")) null else o.optInt("min"),
            files = o.optJSONArray("files")?.objects().orEmpty().map { Attachment(it.optString("n"), if (it.isNull("u")) null else it.optString("u")) },
        )
    }
}

/** One mark. [value] is what PRONOTE displays: "15,5", "Abs", "Disp"... */
data class Grade(
    val id: String,
    val subject: String,
    val date: LocalDate,
    val value: String,
    /** Null when it is not a number (absent, exempted...). */
    val points: Double?,
    val outOf: Double?,
    val classAverage: Double?,
    val coefficient: Double?,
    val comment: String?,
    /** Lowest and highest mark of the class, when the school publishes them. */
    val min: Double? = null,
    val max: Double? = null,
) {
    /** Stable across sessions, unlike [id] (see [Homework.key]). */
    val key: String get() = "$date|$subject|$value|$outOf|$coefficient|$comment"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("sub", subject).put("date", date.toString()).put("v", value)
        .put("min", min ?: JSONObject.NULL).put("max", max ?: JSONObject.NULL)
        .put("p", points ?: JSONObject.NULL).put("o", outOf ?: JSONObject.NULL)
        .put("avg", classAverage ?: JSONObject.NULL).put("coef", coefficient ?: JSONObject.NULL)
        .put("c", comment ?: JSONObject.NULL)

    companion object {
        private fun JSONObject.double(key: String): Double? = if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

        fun fromJson(o: JSONObject) = Grade(
            id = o.getString("id"),
            subject = o.getString("sub"),
            date = LocalDate.parse(o.getString("date")),
            value = o.getString("v"),
            points = o.double("p"),
            outOf = o.double("o"),
            classAverage = o.double("avg"),
            coefficient = o.double("coef"),
            comment = if (o.isNull("c")) null else o.optString("c"),
            min = o.double("min"),
            max = o.double("max"),
        )
    }
}

data class SubjectAverage(val subject: String, val student: Double?, val classAverage: Double?, val color: String?) {
    fun toJson(): JSONObject = JSONObject().put("sub", subject)
        .put("s", student ?: JSONObject.NULL).put("c", classAverage ?: JSONObject.NULL).put("col", color ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = SubjectAverage(
            o.getString("sub"),
            if (o.isNull("s")) null else o.optDouble("s"),
            if (o.isNull("c")) null else o.optDouble("c"),
            if (o.isNull("col")) null else o.optString("col"),
        )
    }
}

data class Report(
    val periodName: String,
    val grades: List<Grade>,
    val subjects: List<SubjectAverage>,
    val overall: Double?,
    val classOverall: Double?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("period", periodName)
        .put("grades", JSONArray(grades.map { it.toJson() }))
        .put("subjects", JSONArray(subjects.map { it.toJson() }))
        .put("overall", overall ?: JSONObject.NULL)
        .put("classOverall", classOverall ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = Report(
            periodName = o.optString("period"),
            grades = o.optJSONArray("grades")?.objects().orEmpty().map { Grade.fromJson(it) },
            subjects = o.optJSONArray("subjects")?.objects().orEmpty().map { SubjectAverage.fromJson(it) },
            overall = if (o.isNull("overall")) null else o.optDouble("overall"),
            classOverall = if (o.isNull("classOverall")) null else o.optDouble("classOverall"),
        )
    }
}

object Schoolwork {

    /** `PageCahierDeTexte` (onglet 88): homework due between the two dates. */
    fun homework(logged: LoggedIn, from: LocalDate, to: LocalDate): List<Homework> {
        val session = logged.session
        val p = session.properties
        val firstMonday = session.instance?.firstMonday ?: throw PronoteException.Protocol("Pas de premier lundi")
        // Week numbers count from the first Monday of the school year, starting at 1.
        val week = { d: LocalDate -> 1 + Math.floorDiv(ChronoUnit.DAYS.between(firstMonday, d), 7L) }
        val response = session.call(
            "PageCahierDeTexte",
            JSONObject()
                .put(p.signature, JSONObject().put("onglet", 88))
                .put(p.data, JSONObject().put("domaine", JSONObject().put("_T", 8).put("V", "[${week(from)}..${week(to)}]"))),
        )
        val list = response.optJSONObject(p.data)?.optJSONObject("ListeTravauxAFaire")?.optJSONArray("V")?.objects().orEmpty()
        return list.mapNotNull { item ->
            val due = item.optJSONObject("PourLe")?.optString("V")?.let { PronoteDates.parse(it)?.toLocalDate() } ?: return@mapNotNull null
            Homework(
                id = item.optString("N"),
                subject = item.optJSONObject("Matiere")?.optJSONObject("V")?.optString("L").orEmpty().ifEmpty { "Devoir" },
                description = htmlToText(item.optJSONObject("descriptif")?.optString("V").orEmpty()),
                due = due,
                done = item.optBoolean("TAFFait"),
                color = item.optString("CouleurFond").ifEmpty { null },
                attachments = item.optJSONObject("ListePieceJointe")?.optJSONArray("V")?.length() ?: 0,
                given = item.optJSONObject("DonneLe")?.optString("V")?.let { PronoteDates.parse(it)?.toLocalDate() },
                teachers = item.optJSONObject("listeProfesseurs")?.optJSONArray("V")?.objects().orEmpty().map { it.optString("L") }.filter { it.isNotEmpty() },
                minutes = item.optInt("duree", 0).takeIf { it > 0 },
                // G = 0 is a web link (its address in "url" or the label), G = 1 a file.
                files = item.optJSONObject("ListePieceJointe")?.optJSONArray("V")?.objects().orEmpty().map { a ->
                    val isLink = a.optInt("G", 1) == 0
                    Homework.Attachment(
                        a.optString("L").ifEmpty { "Document" },
                        if (isLink) a.optString("url").ifEmpty { a.optString("L") } else null,
                        if (isLink) null else a.optString("N").ifEmpty { null },
                    )
                },
            )
        }.filter { it.due in from..to }.sortedWith(compareBy({ it.due }, { it.subject }))
    }

    /**
     * Ticks or unticks homework on PRONOTE (`SaisieTAFFaitEleve`), the same
     * request as the official app. [changes] maps ids of this session to
     * their new state.
     */
    /**
     * Address of the file [name] attached to [h], valid while [logged] lives.
     * File ids change with every session, so the homework is read again first.
     */
    fun attachmentUrl(logged: LoggedIn, h: Homework, name: String): String? {
        val fresh = homework(logged, h.due, h.due).firstOrNull { it.key == h.key } ?: return null
        val id = fresh.files.firstOrNull { it.name == name }?.id ?: return null
        return fileUrl(logged.session, id, name)
    }

    /**
     * PRONOTE's own address for a file (composeUrlFichierExterne): the element
     * {N, Actif} encrypted with the session key, then the file name. The demo
     * server answers 404 to every such address; real schools serve the file.
     */
    internal fun fileUrl(session: Session, id: String, name: String): String {
        val entity = "{\"N\":${JSONObject.quote(id)},\"Actif\":true}".toByteArray(Charsets.UTF_8)
        val encrypted = Crypto.hex(Crypto.aesEncrypt(entity, session.aesKey, session.aesIv))
        val file = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "${session.base}/FichiersExternes/$encrypted/$file?Session=${session.id}"
    }

    fun setHomeworkDone(logged: LoggedIn, changes: Map<String, Boolean>) {
        if (changes.isEmpty()) return
        val session = logged.session
        val p = session.properties
        val list = org.json.JSONArray()
        // E = 2: the entity is modified (EntityState.MODIFICATION).
        for ((id, done) in changes) list.put(JSONObject().put("E", 2).put("TAFFait", done).put("N", id))
        session.call(
            "SaisieTAFFaitEleve",
            JSONObject()
                .put(p.signature, JSONObject().put("onglet", 88))
                .put(p.data, JSONObject().put("listeTAF", list)),
        )
    }

    /** `DernieresNotes` (onglet 198) for the period PRONOTE shows by default. */
    fun report(logged: LoggedIn): Report {
        val session = logged.session
        val p = session.properties
        val periods = session.instance?.periods.orEmpty()
        val today = LocalDate.now()
        val period = session.defaultPeriods[198]?.let { id -> periods.firstOrNull { it.entity.optString("N") == id } }
            ?: periods.firstOrNull { (it.start == null || it.start <= today) && (it.end == null || today <= it.end) }
            ?: periods.firstOrNull()
            ?: throw PronoteException.Protocol("Pas de période")

        val response = session.call(
            "DernieresNotes",
            JSONObject()
                .put(p.signature, JSONObject().put("onglet", 198))
                .put(p.data, JSONObject().put("Periode", period.entity)),
        )
        val data = response.optJSONObject(p.data) ?: throw PronoteException.Protocol("Notes vides")

        val grades = data.optJSONObject("listeDevoirs")?.optJSONArray("V")?.objects().orEmpty().mapNotNull { g ->
            val date = g.optJSONObject("date")?.optString("V")?.let { PronoteDates.parse(it)?.toLocalDate() } ?: return@mapNotNull null
            val raw = g.optJSONObject("note")?.opt("V")
            Grade(
                id = g.optString("N"),
                subject = g.optJSONObject("service")?.optJSONObject("V")?.optString("L").orEmpty(),
                date = date,
                value = gradeLabel(raw),
                points = gradePoints(raw),
                outOf = gradePoints(g.optJSONObject("bareme")?.opt("V")),
                classAverage = gradePoints(g.optJSONObject("moyenne")?.opt("V")),
                coefficient = g.opt("coefficient")?.toString()?.replace(",", ".")?.toDoubleOrNull(),
                comment = g.optString("commentaire").trim().ifEmpty { null },
                min = gradePoints(g.optJSONObject("noteMin")?.opt("V")),
                max = gradePoints(g.optJSONObject("noteMax")?.opt("V")),
            )
        }.sortedByDescending { it.date }

        val subjects = data.optJSONObject("listeServices")?.optJSONArray("V")?.objects().orEmpty().map { s ->
            SubjectAverage(
                subject = s.optString("L"),
                student = gradePoints(s.optJSONObject("moyEleve")?.opt("V")),
                classAverage = gradePoints(s.optJSONObject("moyClasse")?.opt("V")),
                color = s.optString("couleur").ifEmpty { null },
            )
        }

        return Report(
            periodName = period.name,
            grades = grades,
            subjects = subjects,
            overall = gradePoints(data.optJSONObject("moyGenerale")?.opt("V")),
            classOverall = gradePoints(data.optJSONObject("moyGeneraleClasse")?.opt("V")),
        )
    }

    /** "15,5" -> 15.5; "|1" (absent) and other special marks -> null. */
    internal fun gradePoints(raw: Any?): Double? = when (raw) {
        is Number -> raw.toDouble()
        is String -> if (raw.contains("|")) null else raw.replace(",", ".").trim().toDoubleOrNull()
        else -> null
    }

    /** What PRONOTE writes instead of a number, see `typenote.js`. */
    internal fun gradeLabel(raw: Any?): String {
        val text = raw?.toString().orEmpty()
        if (!text.contains("|")) return text.trim().ifEmpty { "—" }
        return when (text.substringAfter("|").trim().toIntOrNull()) {
            1 -> "Abs"
            2 -> "Disp"
            3 -> "N.Not"
            4 -> "Inap"
            5 -> "N.Rdu"
            6 -> "Abs 0"
            7 -> "N.Rdu 0"
            8 -> "Félicitations"
            else -> "—"
        }
    }

    /** Teachers write homework in a rich text editor; a widget-sized summary needs plain text. */
    internal fun htmlToText(html: String): String =
        html.replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>"), "\n")
            .replace(Regex("(?i)<li[^>]*>"), "• ")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&rsquo;", "’")
            .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: "" }
            .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
}
