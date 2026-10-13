package fr.sallio.app

import fr.sallio.pronote.Discussion
import fr.sallio.pronote.Grade
import fr.sallio.pronote.Lesson
import fr.sallio.pronote.News
import fr.sallio.pronote.SchoolEvent
import java.time.LocalDateTime

/**
 * What changed between the copy on the phone and what PRONOTE just sent,
 * worth telling the student about. Pure functions of (before, after, now),
 * unit tested; [Alerts] turns them into notifications.
 *
 * A null "before" means the phone had nothing yet: on a first download
 * nothing is new, everything was already on PRONOTE.
 */
object Changes {

    enum class Kind { LESSON, GRADE, INBOX, SCHOOL_LIFE, CANTEEN }

    class Item(
        val kind: Kind,
        /** Stable across syncs (never a PRONOTE id), so the same change keeps the same notification. */
        val key: String,
        val title: String,
        val text: String,
        /** What the lock screen may show; null when [title] is fine there. */
        val publicTitle: String? = null,
        val subject: String? = null,
        /** When the change stops mattering (end of the lesson), null for never. */
        val until: LocalDateTime? = null,
    )

    /** Lesson changes further away than this are left for the app to show. */
    const val HORIZON_DAYS = 7L

    // ---- Timetable -------------------------------------------------------------------

    private fun identity(l: Lesson) = "${l.start}|${l.subject}|${l.groups.sorted()}"

    fun lessons(before: List<Lesson>?, after: List<Lesson>, now: LocalDateTime): List<Item> {
        if (before.isNullOrEmpty()) return emptyList()
        // Only the days both downloads covered: a day that just entered the window is not "new".
        val last = minOf(before.maxOf { it.start.toLocalDate() }, now.toLocalDate().plusDays(HORIZON_DAYS))
        fun watched(l: Lesson) = l.end > now && !l.start.toLocalDate().isAfter(last)

        val old = before.filter(::watched).groupBy(::identity).mapValues { it.value.toMutableList() }
        val items = mutableListOf<Item>()
        for (l in after.filter(::watched).sortedBy { it.start }) {
            val was = old[identity(l)]?.removeFirstOrNull()
            describe(was, l)?.let { items += item(l, it, now, was) }
        }
        // Lessons PRONOTE no longer lists at all.
        for (l in old.values.flatten().sortedBy { it.start }) {
            if (!l.canceled) items += item(l, "Retiré de l'emploi du temps", now)
        }
        return items
    }

    /** What happened to a lesson, or null when nothing the student needs to know. */
    internal fun describe(was: Lesson?, now: Lesson): String? {
        val alert = Agenda.alert(now)
        if (was == null) {
            return when {
                alert != null -> alert
                now.modified || now.kind != Lesson.Kind.LESSON -> "Cours ajouté"
                else -> null
            }
        }
        val wasAlert = Agenda.alert(was)
        return when {
            alert != null && alert != wasAlert -> alert
            alert == null && wasAlert != null -> "Finalement maintenu"
            !now.canceled && was.rooms.isNotEmpty() && now.rooms.isNotEmpty() && was.rooms.toSet() != now.rooms.toSet() ->
                "Salle changée"
            else -> null
        }
    }

    private fun item(l: Lesson, what: String, now: LocalDateTime, was: Lesson? = null): Item {
        val day = Agenda.dayTitle(l.start.toLocalDate(), now.toLocalDate())
        val where = if (l.canceled) null else Agenda.room(l).takeIf { it != "—" }?.let { room ->
            if (was != null && was.rooms.isNotEmpty() && was.rooms.toSet() != l.rooms.toSet()) "${Agenda.room(was)} → $room" else room
        }
        val who = l.teachers.firstOrNull()?.let { Agenda.prettyName(it) }
        return Item(
            Kind.LESSON,
            key = "lesson|${identity(l)}",
            // The change first: a long subject name must not push it out of a collapsed notification.
            title = "${what.replaceFirstChar { it.titlecase() }} · ${Agenda.pretty(l.subject)}",
            text = listOfNotNull("$day à ${Agenda.time(l.start)}", where, who).joinToString(" · "),
            subject = l.subject,
            until = l.end,
        )
    }

    // ---- Grades ----------------------------------------------------------------------

    fun grades(before: List<Grade>?, after: List<Grade>): List<Item> {
        if (before == null) return emptyList()
        val known = before.map { it.key }.toSet()
        return after.filter { it.key !in known }.sortedBy { it.date }.map { g ->
            val subject = Agenda.pretty(g.subject)
            val value = g.value + (g.outOf?.takeIf { g.points != null }?.let { "/" + number(it) } ?: "")
            val details = listOfNotNull(
                g.classAverage?.let { "classe ${number(it)}" },
                g.coefficient?.takeIf { it != 1.0 }?.let { "coef ${number(it)}" },
                g.comment?.takeIf { it.isNotBlank() },
            )
            Item(
                Kind.GRADE,
                key = "grade|${g.key}",
                title = "$subject : $value",
                text = details.joinToString(" · ").ifEmpty { "Nouvelle note" },
                // The grade itself stays off the lock screen.
                publicTitle = "Nouvelle note en $subject",
                subject = g.subject,
            )
        }
    }

    // ---- Actualités and Messagerie ---------------------------------------------------

    fun news(before: List<News>?, after: List<News>): List<Item> {
        if (before == null) return emptyList()
        val known = before.map { it.key }.toSet()
        return after.filter { !it.read && it.key !in known }.map { n ->
            Item(
                Kind.INBOX,
                key = "news|${n.key}",
                title = (if (n.survey) "Sondage : " else "Actualité : ") + n.title,
                text = n.author,
                publicTitle = if (n.survey) "Nouveau sondage" else "Nouvelle actualité",
            )
        }
    }

    fun messages(before: List<Discussion>?, after: List<Discussion>): List<Item> {
        if (before == null) return emptyList()
        val unreadBefore = before.associate { it.key to it.unread }
        return after.filter { it.unread > (unreadBefore[it.key] ?: 0) }.map { d ->
            val last = d.messages.lastOrNull { !it.mine }
            val from = last?.author?.ifBlank { null } ?: d.with
            Item(
                Kind.INBOX,
                // Keyed on the count too: a later message is a new notification, not an edit.
                key = "message|${d.key}|${d.count}",
                title = "Message de $from",
                text = listOfNotNull(d.subject.ifBlank { null }, last?.text?.let(::oneLine)?.ifBlank { null }).joinToString(" · "),
                publicTitle = "Nouveau message",
            )
        }
    }

    // ---- Vie scolaire ----------------------------------------------------------------

    fun schoolLife(before: List<SchoolEvent>?, after: List<SchoolEvent>): List<Item> {
        if (before == null) return emptyList()
        val known = before.map { it.key }.toSet()
        return after.filter { it.key !in known }.sortedBy { it.start }.map { e ->
            Item(
                Kind.SCHOOL_LIFE,
                key = "life|${e.key}",
                title = SchoolLifeText.title(e),
                text = SchoolLifeText.details(e),
                publicTitle = "Vie scolaire",
                subject = e.subject,
            )
        }
    }

    private fun oneLine(text: String) = text.replace(Regex("\\s+"), " ").trim().let { if (it.length > 300) it.take(299) + "…" else it }

    /** "12.5" -> "12,5", "20.0" -> "20". */
    internal fun number(value: Double): String {
        val rounded = Math.round(value * 100) / 100.0
        val text = if (rounded == Math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
        return text.replace('.', ',')
    }
}
