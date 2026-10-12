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
    data class Message(val author: String, val mine: Boolean, val date: LocalDateTime?, val text: String)

    val key: String get() = "$subject|$with"

    fun toJson(): JSONObject = JSONObject()
        .put("s", subject).put("w", with).put("d", dateLabel).put("u", unread).put("n", count)
        .put("m", JSONArray(messages.map {
            JSONObject().put("a", it.author).put("me", it.mine).put("d", it.date?.toString() ?: JSONObject.NULL).put("t", it.text)
        }))

    companion object {
        fun fromJson(o: JSONObject) = Discussion(
            subject = o.optString("s"),
            with = o.optString("w"),
            dateLabel = o.optString("d"),
            unread = o.optInt("u"),
            count = o.optInt("n"),
            messages = o.optJSONArray("m")?.objects().orEmpty().map { m ->
                Message(m.optString("a"), m.optBoolean("me"), if (m.isNull("d")) null else LocalDateTime.parse(m.getString("d")), m.optString("t"))
            },
        )
    }
}

/**
 * Read-only access to the Actualités and the Messagerie. Nothing here marks
 * anything as read or answers anything: PRONOTE stays as the student left it.
 */
object Inbox {

    /**
     * The list of actualités, with each one's content. Since PRONOTE 2026.2 the
     * content comes from a second request per item; [known] (by [News.key])
     * avoids asking again for what is already on the phone.
     */
    fun news(logged: LoggedIn, known: Map<String, News>, maxDetails: Int = 8): List<News> {
        val session = logged.session
        val p = session.properties
        val response = session.call(
            "PageActualites",
            JSONObject()
                .put(p.signature, JSONObject().put("onglet", 8))
                .put(p.data, JSONObject().put("modesAffActus", JSONObject().put("_T", 26).put("V", "[0..3]"))),
        )
        val data = response.optJSONObject(p.data) ?: return emptyList()
        val seen = HashSet<String>()
        val items = data.optJSONArray("listeModesAff")?.objects().orEmpty()
            .flatMap { it.optJSONObject("listeActualites")?.optJSONArray("V")?.objects().orEmpty() }
            .filter { seen.add(it.optString("N")) }
        var budget = maxDetails
        return items.map { item ->
            val base = News(
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
            // Content embedded in the list (older PRONOTE), else cached, else a details request.
            val embedded = item.optJSONObject("listeQuestions")?.optJSONArray("V")?.objects().orEmpty()
            val cached = known[base.key]?.takeIf { it.content.isNotEmpty() || it.questions.isNotEmpty() }
            val questions = when {
                embedded.isNotEmpty() -> embedded
                cached != null -> null
                budget-- > 0 -> runCatching { details(session, item.optString("N"), resourceId(session)) }.getOrNull()
                else -> null
            }
            if (questions == null) cached?.copy(read = base.read) ?: base
            else withQuestions(base, questions)
        }.sortedByDescending { it.date }
    }

    private fun resourceId(session: Session): String = session.userResource?.optString("N").orEmpty()

    private fun details(session: Session, id: String, studentId: String): List<JSONObject> {
        val p = session.properties
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

    private fun withQuestions(base: News, questions: List<JSONObject>): News {
        fun text(q: JSONObject) = Schoolwork.htmlToText(q.optJSONObject("texte")?.optString("V") ?: q.optString("texte"))
        return if (!base.survey) {
            base.copy(content = questions.firstOrNull()?.let { text(it) }.orEmpty())
        } else {
            base.copy(questions = questions.map { q ->
                News.Question(
                    title = q.optString("titre").ifEmpty { q.optString("L") },
                    text = text(q),
                    choices = q.optJSONObject("listeChoix")?.optJSONArray("V")?.objects().orEmpty().map { it.optString("L") },
                    answered = q.optJSONObject("reponse")?.optJSONObject("V")?.optBoolean("avecReponse") == true,
                )
            })
        }
    }

    /** The conversations, newest first; messages fetched for new or changed ones only. */
    fun discussions(logged: LoggedIn, known: Map<String, Discussion>, maxDetails: Int = 6): List<Discussion> {
        val session = logged.session
        val p = session.properties
        val response = session.call(
            "ListeMessagerie",
            JSONObject()
                .put(p.signature, JSONObject().put("onglet", 131))
                .put(p.data, JSONObject().put("avecLu", true).put("avecMessage", true).put("possessionMessageDiscussionUnique", JSONObject.NULL)),
        )
        val data = response.optJSONObject(p.data) ?: return emptyList()
        var budget = maxDetails
        return data.optJSONObject("listeMessagerie")?.optJSONArray("V")?.objects().orEmpty()
            .filter { it.optBoolean("estUneDiscussion") && it.optInt("profondeur", 0) == 0 }
            .map { d ->
                val base = Discussion(
                    subject = d.optString("objet").ifEmpty { "(sans objet)" },
                    with = d.optString("public").ifEmpty { d.optString("initiateur") },
                    dateLabel = d.optString("libelleDate"),
                    unread = d.optInt("nbNonLus"),
                    count = d.optInt("nombreMessages"),
                    messages = emptyList(),
                )
                val cached = known[base.key]
                if (cached != null && cached.count == base.count) return@map cached.copy(unread = base.unread, dateLabel = base.dateLabel)
                if (budget-- <= 0) return@map cached?.copy(unread = base.unread, dateLabel = base.dateLabel, count = base.count) ?: base
                val messages = runCatching { messages(session, d.optJSONObject("listePossessionsMessages")?.opt("V") ?: JSONArray()) }.getOrNull()
                if (messages != null) base.copy(messages = messages) else cached ?: base
            }
    }

    /** `ListeMessages` without marking anything as read. */
    private fun messages(session: Session, possessions: Any): List<Discussion.Message> {
        val p = session.properties
        val response = session.call(
            "ListeMessages",
            JSONObject()
                .put(p.signature, JSONObject().put("onglet", 131))
                .put(p.data, JSONObject().put("listePossessionsMessages", possessions).put("marquerCommeLu", false).put("nbMessagesVus", 0)),
        )
        return response.optJSONObject(p.data)?.optJSONObject("listeMessages")?.optJSONArray("V")?.objects().orEmpty()
            .filterNot { it.optBoolean("brouillon") }
            .map { m ->
                val html = m.optBoolean("estHTML")
                val raw = if (html) m.optJSONObject("contenu")?.optString("V").orEmpty() else m.optString("contenu")
                val author = m.optString("public_gauche")
                Discussion.Message(
                    author = author,
                    mine = author == "Moi",
                    date = m.optJSONObject("date")?.optString("V")?.let { PronoteDates.parse(it) },
                    text = Schoolwork.htmlToText(raw),
                )
            }
            .sortedBy { it.date }
    }
}
