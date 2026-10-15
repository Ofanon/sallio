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
    /** An information that asks for an acknowledgement ("J'ai pris connaissance"). */
    val needsAck: Boolean = false,
    /** That acknowledgement was given. */
    val acked: Boolean = false,
) {
    /**
     * A survey question. [kind] is PRONOTE's `genreReponse`: 0 information text,
     * 1 free text, 2 one choice, 3 several choices, 5 text block without answer.
     * [answer] holds the chosen positions (1-based, PRONOTE's `rang`) and the
     * typed text, as they are on PRONOTE or as just sent.
     */
    data class Question(
        val title: String,
        val text: String,
        val choices: List<String>,
        val answered: Boolean,
        val kind: Int = KIND_UNIQUE,
        /** Positions of the choices that take a typed answer ("Autre : …"). */
        val freeChoices: Set<Int> = emptySet(),
        /** At most this many choices, for [KIND_MULTIPLE]; null for no limit. */
        val maxChoices: Int? = null,
        val answer: Answer = Answer(),
        /** PRONOTE's position (`rang`) of each of [choices], what answers are made of. */
        val ranks: List<Int> = choices.indices.map { it + 1 },
    ) {
        val needsInput get() = kind == KIND_TEXT || kind == KIND_UNIQUE || kind == KIND_MULTIPLE
    }

    data class Answer(val chosen: Set<Int> = emptySet(), val text: String = "")

    /** Stable across sessions (ids are not). */
    val key: String get() = "$date|$title|$author"

    fun toJson(): JSONObject = JSONObject()
        .put("t", title).put("a", author).put("d", date?.toString() ?: JSONObject.NULL).put("c", category)
        .put("s", survey).put("r", read).put("x", content)
        .put("q", JSONArray(questions.map {
            JSONObject().put("t", it.title).put("x", it.text).put("c", JSONArray(it.choices)).put("a", it.answered)
                .put("k", it.kind).put("fc", JSONArray(it.freeChoices.toList())).put("max", it.maxChoices ?: JSONObject.NULL)
                .put("ac", JSONArray(it.answer.chosen.toList())).put("at", it.answer.text).put("cr", JSONArray(it.ranks))
        }))
        .put("na", needsAck).put("ak", acked)
        .put("f", files?.let { Inbox.filesJson(it) } ?: JSONObject.NULL)

    companion object {
        const val KIND_INFO = 0
        const val KIND_TEXT = 1
        const val KIND_UNIQUE = 2
        const val KIND_MULTIPLE = 3

        fun fromJson(o: JSONObject) = News(
            title = o.optString("t"),
            author = o.optString("a"),
            date = if (o.isNull("d")) null else LocalDateTime.parse(o.getString("d")),
            category = o.optString("c"),
            survey = o.optBoolean("s"),
            read = o.optBoolean("r"),
            content = o.optString("x"),
            questions = o.optJSONArray("q")?.objects().orEmpty().map { q ->
                fun ints(name: String) = q.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty().toSet()
                Question(q.optString("t"), q.optString("x"),
                    q.optJSONArray("c")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(), q.optBoolean("a"),
                    kind = q.optInt("k", KIND_UNIQUE), freeChoices = ints("fc"),
                    maxChoices = if (q.isNull("max") || !q.has("max")) null else q.optInt("max"),
                    answer = Answer(ints("ac"), q.optString("at")),
                    ranks = q.optJSONArray("cr")?.let { a -> (0 until a.length()).map { a.getInt(it) } }
                        ?: q.optJSONArray("c")?.let { a -> (1..a.length()).toList() }.orEmpty())
            },
            // Copies made before survey answers and acknowledgements were read have no "na":
            // without files they count as not fully read, so the next sync asks for them again.
            files = if (o.has("na")) o.optJSONArray("f")?.let { Inbox.filesFromJson(it) } else null,
            needsAck = o.optBoolean("na"),
            acked = o.optBoolean("ak"),
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
 * Access to the Actualités and the Messagerie. Only [setNewsRead] writes, when
 * the student asks for it; nothing else marks anything as read or answers
 * anything: PRONOTE stays as the student left it.
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
    fun news(logged: LoggedIn, known: Map<String, News>, maxDetails: Int = 40): List<News> {
        val session = logged.session
        var budget = maxDetails
        return list(session).map { item ->
            val base = base(item)
            // Content embedded in the list (older PRONOTE), else cached, else a details request.
            val embedded = item.optJSONObject("listeQuestions")?.optJSONArray("V")?.objects().orEmpty()
            val cached = known[base.key]?.takeIf { it.files != null }
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

    internal fun question(q: JSONObject, text: String): News.Question {
        val choices = q.optJSONObject("listeChoix")?.optJSONArray("V")?.objects().orEmpty()
        val reply = q.optJSONObject("reponse")?.optJSONObject("V")
        val answered = reply?.optBoolean("avecReponse") == true
        // The answer as PRONOTE has it: typed text, or chosen positions ("[1,3]") plus the "Autre" text.
        val value = reply?.opt("valeurReponse")
        val answer = when {
            !answered || value == null -> News.Answer()
            value is String -> News.Answer(text = value)
            value is JSONObject -> News.Answer(ranges(value.optString("V")), reply.optString("valeurReponseLibre"))
            else -> News.Answer()
        }
        return News.Question(
            title = q.optString("titre").ifEmpty { q.optString("L") },
            text = text,
            choices = choices.sortedBy { it.optInt("rang") }.map { it.optString("L") },
            ranks = choices.map { it.optInt("rang") }.sorted(),
            answered = answered,
            kind = q.optInt("genreReponse", News.KIND_UNIQUE),
            freeChoices = choices.filter { it.optBoolean("estReponseLibre") }.map { it.optInt("rang") }.toSet(),
            maxChoices = if (q.optBoolean("avecMaximum")) q.optInt("nombreReponsesMax").takeIf { it > 0 } else null,
            answer = answer,
        )
    }

    /** "[1,3..5]" to {1, 3, 4, 5}. */
    internal fun ranges(text: String): Set<Int> {
        val inner = text.trim().removePrefix("[").removeSuffix("]").trim()
        if (inner.isEmpty()) return emptySet()
        return inner.split(",").flatMap { part ->
            val bounds = part.split("..").mapNotNull { it.trim().toIntOrNull() }
            if (bounds.size == 2) (bounds[0]..bounds[1]).toList() else bounds
        }.toSet()
    }

    /** {1, 3, 4, 5} to "[1,3..5]", as PRONOTE writes them. */
    internal fun ranges(values: Set<Int>): String {
        val sorted = values.sorted()
        val parts = mutableListOf<String>()
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
            parts += if (j > i) "${sorted[i]}..${sorted[j]}" else "${sorted[i]}"
            i = j + 1
        }
        return "[${parts.joinToString(",")}]"
    }

    private fun html(q: JSONObject) = q.optJSONObject("texte")?.optString("V") ?: q.optString("texte")

    private fun withQuestions(base: News, questions: List<JSONObject>): News {
        fun text(q: JSONObject) = Schoolwork.htmlToText(html(q))
        val files = questions.flatMap { files(it.optJSONObject("listePiecesJointes"), html(it)) }.distinctBy { it.name to it.link }
        return if (!base.survey) {
            // An information has one question: its text, and whether PRONOTE waits for an acknowledgement.
            val reply = questions.firstOrNull()?.optJSONObject("reponse")?.optJSONObject("V")
            base.copy(content = questions.firstOrNull()?.let { text(it) }.orEmpty(), files = files,
                needsAck = reply?.optBoolean("estReponseAttendue") == true, acked = reply?.optBoolean("avecReponse") == true)
        } else {
            base.copy(files = files, questions = questions.map { q -> question(q, text(q)) })
        }
    }

    /**
     * Marks actualités as read or unread on PRONOTE (`SaisieActualites` with
     * `marqueLueSeulement`, as Pawnote's `newsRead`): nothing else changes, a
     * survey stays unanswered. [wanted] maps [News.key] to the wanted state.
     * Returns the keys now settled: sent, or gone from PRONOTE.
     */
    fun setNewsRead(
        logged: LoggedIn,
        wanted: Map<String, Boolean>,
        log: (String) -> Unit = {},
        onAnswer: (variant: String, accepted: Boolean) -> Unit = { _, _ -> },
    ): Set<String> {
        if (wanted.isEmpty()) return emptySet()
        val session = logged.session
        val p = session.properties
        val items = list(session)
        val settled = HashSet<String>()
        for (item in items) {
            val key = base(item).key
            val read = wanted[key] ?: continue
            // Opened first, as PRONOTE's own page does before marking it read.
            runCatching { details(session, item.optString("N")) }
            val listed = item.optJSONObject("public")?.optJSONObject("V")
            val student = JSONObject().put("N", session.userResource?.optString("N").orEmpty()).put("G", 4)
            // PRONOTE answers {"RapportSaisie":{"_erreurSaisie_":true}} to a request it will not
            // apply (seen 2026-10-13 with the student as audience). The known shapes, in turn:
            // Pawnote's with the item's own audience, the same marked as modified (E = 2, as
            // homework ticks need), then pronotepy's minimal one.
            fun pawnote(audience: JSONObject) = JSONObject()
                .put("N", item.optString("N"))
                .put("L", item.optString("L"))
                .put("genrePublic", audience.optInt("G", 4))
                .put("public", audience)
                .put("validationDirecte", true)
                .put("listeQuestions", JSONArray())
                .put("marqueLueSeulement", true)
                .put("lue", read)
                .put("supprimee", false)
                .put("saisieActualite", false)
            val variants = listOf(
                "pawnote" to pawnote(listed ?: student),
                "pawnote+E2" to pawnote(listed ?: student).put("E", 2),
                "pronotepy" to JSONObject().put("N", item.optString("N")).put("validationDirecte", true)
                    .put("genrePublic", 4).put("public", student).put("lue", read),
            )
            for ((name, entry) in variants) {
                val answer = session.call(
                    "SaisieActualites",
                    JSONObject()
                        .put(p.signature, JSONObject().put("onglet", 8))
                        .put(p.data, JSONObject().put("listeActualites", JSONArray().put(entry)).put("saisieActualite", false)),
                )
                val refused = answer.optJSONObject("RapportSaisie")?.optBoolean("_erreurSaisie_") == true
                log("SaisieActualites $name lue=$read: ${if (refused) "refusé" else "accepté"} ${answer.toString().take(200)}")
                onAnswer(name, !refused)
                if (!refused) break
            }
            settled += key
        }
        // No longer on PRONOTE: nothing left to mark.
        settled += wanted.keys - items.map { base(it).key }.toSet()
        return settled
    }

    /**
     * Answers the actualité [key] on PRONOTE, as its own page does (Pawnote's
     * `newsSurveySend` and `newsInformationAcknowledge`): every question with
     * its answer, and the item marked read. [answers] has one entry per
     * question in PRONOTE's order; for an information, an empty answer is the
     * acknowledgement. Returns false when PRONOTE refuses (`_erreurSaisie_`),
     * throws when the actualité is gone.
     */
    fun answerNews(logged: LoggedIn, key: String, answers: List<News.Answer>, log: (String) -> Unit = {}): Boolean {
        val session = logged.session
        val p = session.properties
        val item = list(session).firstOrNull { base(it).key == key } ?: throw PronoteException.Protocol("Actualité introuvable")
        val raw = details(session, item.optString("N"))
        val student = JSONObject().put("N", session.userResource?.optString("N").orEmpty()).put("G", 4)
        val audience = item.optJSONObject("public")?.optJSONObject("V") ?: student
        val questions = JSONArray()
        raw.forEachIndexed { i, q ->
            val kind = q.optInt("genreReponse")
            val answer = answers.getOrElse(i) { News.Answer() }
            val reply = q.optJSONObject("reponse")?.optJSONObject("V")
            val free = q.optJSONObject("listeChoix")?.optJSONArray("V")?.objects().orEmpty().any { it.optBoolean("estReponseLibre") }
            val value: Any = if (kind == News.KIND_INFO || kind == News.KIND_TEXT) answer.text
                else JSONObject().put("_T", 8).put("V", ranges(answer.chosen))
            questions.put(JSONObject()
                .put("N", q.optString("N"))
                .put("L", q.optString("L"))
                .put("genreReponse", kind)
                .put("reponse", JSONObject()
                    .put("N", reply?.optString("N")?.toIntOrNull() ?: 0)
                    .put("Actif", true)
                    .put("valeurReponse", value)
                    .apply { if (free) put("valeurReponseLibre", answer.text) }
                    .put("avecReponse", true)
                    .put("estReponseAttendue", reply?.optBoolean("estReponseAttendue") == true)
                    .put("_validationSaisie", true)))
        }
        val entry = JSONObject()
            .put("N", item.optString("N"))
            .put("L", item.optString("L"))
            .put("genrePublic", audience.optInt("G", 4))
            .put("public", audience)
            .put("validationDirecte", true)
            .put("listeQuestions", questions)
            .put("marqueLueSeulement", false)
            .put("lue", true)
            .put("supprimee", false)
            .put("saisieActualite", false)
        val answer = session.call(
            "SaisieActualites",
            JSONObject()
                .put(p.signature, JSONObject().put("onglet", 8))
                .put(p.data, JSONObject().put("listeActualites", JSONArray().put(entry)).put("saisieActualite", false)),
        )
        val refused = answer.optJSONObject("RapportSaisie")?.optBoolean("_erreurSaisie_") == true
        log("SaisieActualites answers (${raw.size} questions): ${if (refused) "refusé" else "accepté"} ${answer.toString().take(200)}")
        return !refused
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
    fun discussions(logged: LoggedIn, known: Map<String, Discussion>, maxDetails: Int = 20): List<Discussion> {
        val session = logged.session
        var budget = maxDetails
        return discussionItems(session).map { d ->
            val base = discussionBase(d)
            val cached = known[base.key]
            val complete = cached != null && (cached.messages.isNotEmpty() || cached.count == 0) && cached.messages.all { it.files != null }
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
