package fr.sallio.pronote

import fr.sallio.pronote.Pronote.objects
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime

/** An "information" or a "sondage" from the Actualités tab. */
data class News(
    val title: String,
    val author: String,
    val date: LocalDateTime?,
    val category: String,
    val survey: Boolean,
    val read: Boolean,
    /** Text of the information, or of each survey question, HTML removed. */
    val content: String,
    /** Survey questions with their choices, empty for an information. */
    val questions: List<Question>,
    /** Attached files and the links of the text; null when read before the app knew them. */
    val files: List<Homework.Attachment>? = null,
) {
    data class Question(val title: String, val text: String, val choices: List<String>, val answered: Boolean)

    /** Stable across sessions (ids are not). */
    val key: String get() = "$date|$title|$author"

    fun toJson(): JSONObject = JSONObject()
        .put("t", title).put("a", author).put("d", date?.toString() ?: JSONObject.NULL).put("c", category)
        .put("s", survey).put("r", read).put("x", content)
        .put("q", JSONArray(questions.map {
            JSONObject().put("t", it.title).put("x", it.text).put("c", JSONArray(it.choices)).put("a", it.answered)
        }))
        .put("f", files?.let { Inbox.filesJson(it) } ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = News(
            title = o.optString("t"),
            author = o.optString("a"),
            date = if (o.isNull("d")) null else LocalDateTime.parse(o.getString("d")),
            category = o.optString("c"),
            survey = o.optBoolean("s"),
            read = o.optBoolean("r"),
            content = o.optString("x"),
            questions = o.optJSONArray("q")?.objects().orEmpty().map { q ->
                Question(q.optString("t"), q.optString("x"),
                    q.optJSONArray("c")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(), q.optBoolean("a"))
            },
            files = o.optJSONArray("f")?.let { Inbox.filesFromJson(it) },
        )
    }
}

/** A conversation of the Messagerie, with its messages (oldest first). */
data class Discussion(
    val subject: String,
    val with: String,
    val dateLabel: String,
    val unread: Int,
    val count: Int,
    val messages: List<Message>,
) {
    /** [files]: attached files and the links of the text; null when read before the app knew them. */
    data class Message(val author: String, val mine: Boolean, val date: LocalDateTime?, val text: String, val files: List<Homework.Attachment>? = null)

    val key: String get() = "$subject|$with"

    fun toJson(): JSONObject = JSONObject()
        .put("s", subject).put("w", with).put("d", dateLabel).put("u", unread).put("n", count)
        .put("m", JSONArray(messages.map {
            JSONObject().put("a", it.author).put("me", it.mine).put("d", it.date?.toString() ?: JSONObject.NULL).put("t", it.text)
                .put("f", it.files?.let { f -> Inbox.filesJson(f) } ?: JSONObject.NULL)
        }))

    companion object {
        fun fromJson(o: JSONObject) = Discussion(
            subject = o.optString("s"),
            with = o.optString("w"),
            dateLabel = o.optString("d"),
            unread = o.optInt("u"),
            count = o.optInt("n"),
            messages = o.optJSONArray("m")?.objects().orEmpty().map { m ->
                Message(m.optString("a"), m.optBoolean("me"), if (m.isNull("d")) null else LocalDateTime.parse(m.getString("d")), m.optString("t"),
                    m.optJSONArray("f")?.let { Inbox.filesFromJson(it) })
            },
        )
    }
}

/**
 * Read-only access to the Actualités and the Messagerie. Nothing here marks
 * anything as read or answers anything: PRONOTE stays as the student left it.
 */
object Inbox {

    internal fun filesJson(files: List<Homework.Attachment>) =
        JSONArray(files.map { JSONObject().put("n", it.name).put("u", it.link ?: JSONObject.NULL) })

    internal fun filesFromJson(a: JSONArray) =
        a.objects().map { Homework.Attachment(it.optString("n"), if (it.isNull("u")) null else it.optString("u")) }

    /** Files of a PRONOTE list, then the links written in [html] that are not already there. */
    private fun files(list: JSONObject?, html: String): List<Homework.Attachment> {
        val attached = Schoolwork.attachments(list)
        val known = attached.mapNotNull { it.link }.toSet()
        return attached + Schoolwork.htmlLinks(html).filter { it.link !in known }
    }

    // ---- Actualités ------------------------------------------------------------------

    private fun list(session: Session): List<JSONObject> {
        val p = session.properties
        val response = session.call(
            "PageActualites",
            JSONObject()
                .put(p.signature, JSONObject().put("onglet", 8))
                .put(p.data, JSONObject().put("modesAffActus", JSONObject().put("_T", 26).put("V", "[0..3]"))),
        )
        val data = response.optJSONObject(p.data) ?: return emptyList()
        val seen = HashSet<String>()
        return data.optJSONArray("listeModesAff")?.objects().orEmpty()
            .flatMap { it.optJSONObject("listeActualites")?.optJSONArray("V")?.objects().orEmpty() }
            .filter { seen.add(it.optString("N")) }
    }

    private fun base(item: JSONObject) = News(
        title = item.optString("L").ifEmpty { "Sans titre" },
        author = item.optString("auteur"),
        date = (item.optJSONObject("dateDebut") ?: item.optJSONObject("dateCreation"))?.optString("V")?.let { PronoteDates.parse(it) },
        category = item.optJSONObject("nature")?.optJSONObject("V")?.optString("L")
            ?: item.optJSONObject("categorie")?.optJSONObject("V")?.optString("L").orEmpty(),
        survey = item.optBoolean("estSondage"),
        read = item.optBoolean("lue"),
        content = "",
        questions = emptyList(),
    )

    /**
     * The list of actualités, with each one's content. Since PRONOTE 2026.2 the
     * content comes from a second request per item; [known] (by [News.key])
     * avoids asking again for what is already on the phone.
     */
    fun news(logged: LoggedIn, known: Map<String, News>, maxDetails: Int = 8): List<News> {
        val session = logged.session
        var budget = maxDetails
        return list(session).map { item ->
            val base = base(item)
            // Content embedded in the list (older PRONOTE), else cached, else a details request.
            val embedded = item.optJSONObject("listeQuestions")?.optJSONArray("V")?.objects().orEmpty()
            val cached = known[base.key]?.takeIf { (it.content.isNotEmpty() || it.questions.isNotEmpty()) && it.files != null }
            val questions = when {
                embedded.isNotEmpty() -> embedded
                cached != null -> null
                budget-- > 0 -> runCatching { details(session, item.optString("N")) }.getOrNull()
                else -> null
            }
            if (questions == null) cached?.copy(read = base.read) ?: known[base.key]?.copy(read = base.read) ?: base
            else withQuestions(base, questions)
        }.sortedByDescending { it.date }
    }

    private fun details(session: Session, id: String): List<JSONObject> {
        val p = session.properties
        val studentId = session.userResource?.optString("N").orEmpty()
        val response = session.call(
            "PageActualites",
            JSONObject()
                .put(p.signature, JSONObject().put("onglet", 8))
                .put(p.data, JSONObject()
                    .put("actualite", JSONObject().put("N", id).put("genrePublic", 4).put("public", JSONObject().put("N", studentId).put("G", 4)))
                    .put("genreRequeteActualite", 1)
                    .put("modeAffActu", 0)),
        )
        return response.optJSONObject(p.data)?.optJSONObject("detailsActualite")?.optJSONObject("listeQuestions")
            ?.optJSONArray("V")?.objects().orEmpty()
    }

    private fun html(q: JSONObject) = q.optJSONObject("texte")?.optString("V") ?: q.optString("texte")

    private fun withQuestions(base: News, questions: List<JSONObject>): News {
        fun text(q: JSONObject) = Schoolwork.htmlToText(html(q))
        val files = questions.flatMap { files(it.optJSONObject("listePiecesJointes"), html(it)) }.distinctBy { it.name to it.link }
        return if (!base.survey) {
            base.copy(content = questions.firstOrNull()?.let { text(it) }.orEmpty(), files = files)
        } else {
            base.copy(files = files, questions = questions.map { q ->
                News.Question(
                    title = q.optString("titre").ifEmpty { q.optString("L") },
                    text = text(q),
                    choices = q.optJSONObject("listeChoix")?.optJSONArray("V")?.objects().orEmpty().map { it.optString("L") },
                    answered = q.optJSONObject("reponse")?.optJSONObject("V")?.optBoolean("avecReponse") == true,
                )
            })
        }
    }

    /** Address of the file [name] of the actualité [key], in this session (ids change with each one). */
    fun newsFileUrl(logged: LoggedIn, key: String, name: String): String? {
        val session = logged.session
        val item = list(session).firstOrNull { base(it).key == key } ?: return null
        val id = details(session, item.optString("N"))
            .flatMap { Schoolwork.attachments(it.optJSONObject("listePiecesJointes")) }
            .firstOrNull { it.name == name && it.id != null }?.id ?: return null
        return Schoolwork.fileUrl(session, id, name)
    }

    // ---- Messagerie ------------------------------------------------------------------

    private fun discussionItems(session: Session): List<JSONObject> {
        val p = session.properties
        val response = session.call(
            "ListeMessagerie",
            JSONObject()
                .put(p.signature, JSONObject().put("onglet", 131))
                .put(p.data, JSONObject().put("avecLu", true).put("avecMessage", true).put("possessionMessageDiscussionUnique", JSONObject.NULL)),
        )
        return response.optJSONObject(p.data)?.optJSONObject("listeMessagerie")?.optJSONArray("V")?.objects().orEmpty()
            .filter { it.optBoolean("estUneDiscussion") && it.optInt("profondeur", 0) == 0 }
    }

    private fun discussionBase(d: JSONObject) = Discussion(
        subject = d.optString("objet").ifEmpty { "(sans objet)" },
        with = d.optString("public").ifEmpty { d.optString("initiateur") },
        dateLabel = d.optString("libelleDate"),
        unread = d.optInt("nbNonLus"),
        count = d.optInt("nombreMessages"),
        messages = emptyList(),
    )

    /** The conversations, newest first; messages fetched for new or changed ones only. */
    fun discussions(logged: LoggedIn, known: Map<String, Discussion>, maxDetails: Int = 6): List<Discussion> {
        val session = logged.session
        var budget = maxDetails
        return discussionItems(session).map { d ->
            val base = discussionBase(d)
            val cached = known[base.key]
            val complete = cached != null && cached.messages.all { it.files != null }
            if (cached != null && complete && cached.count == base.count) return@map cached.copy(unread = base.unread, dateLabel = base.dateLabel)
            if (budget-- <= 0) return@map cached?.copy(unread = base.unread, dateLabel = base.dateLabel, count = base.count) ?: base
            val messages = runCatching { rawMessages(session, d).map { message(it) }.sortedBy { it.date } }.getOrNull()
            if (messages != null) base.copy(messages = messages) else cached ?: base
        }
    }

    /** `ListeMessages` without marking anything as read. */
    private fun rawMessages(session: Session, discussion: JSONObject): List<JSONObject> {
        val p = session.properties
        val possessions = discussion.optJSONObject("listePossessionsMessages")?.opt("V") ?: JSONArray()
        val response = session.call(
            "ListeMessages",
            JSONObject()
                .put(p.signature, JSONObject().put("onglet", 131))
                .put(p.data, JSONObject().put("listePossessionsMessages", possessions).put("marquerCommeLu", false).put("nbMessagesVus", 0)),
        )
        return response.optJSONObject(p.data)?.optJSONObject("listeMessages")?.optJSONArray("V")?.objects().orEmpty()
            .filterNot { it.optBoolean("brouillon") }
    }

    private fun message(m: JSONObject): Discussion.Message {
        val html = m.optBoolean("estHTML")
        val raw = if (html) m.optJSONObject("contenu")?.optString("V").orEmpty() else m.optString("contenu")
        val author = m.optString("public_gauche")
        return Discussion.Message(
            author = author,
            mine = author == "Moi",
            date = m.optJSONObject("date")?.optString("V")?.let { PronoteDates.parse(it) },
            text = Schoolwork.htmlToText(raw),
            files = files(m.optJSONObject("listeDocumentsJoints"), if (html) raw else ""),
        )
    }

    /** Address of the file [name] in the conversation [key], in this session. */
    fun messageFileUrl(logged: LoggedIn, key: String, name: String): String? {
        val session = logged.session
        val d = discussionItems(session).firstOrNull { discussionBase(it).key == key } ?: return null
        val id = rawMessages(session, d)
            .flatMap { Schoolwork.attachments(it.optJSONObject("listeDocumentsJoints")) }
            .firstOrNull { it.name == name && it.id != null }?.id ?: return null
        return Schoolwork.fileUrl(session, id, name)
    }
}
