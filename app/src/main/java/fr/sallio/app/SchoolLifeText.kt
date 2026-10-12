package fr.sallio.app

import fr.sallio.pronote.SchoolEvent
import java.time.format.TextStyle
import java.util.Locale

/** Words for the vie scolaire, shared by the tab and the notifications. Pure, unit tested. */
object SchoolLifeText {

    private val FR = Locale.FRANCE

    /** "Absence", "Retard de 5 min", "Avertissement", "Dispense · EPS". */
    fun title(e: SchoolEvent): String = when (e.kind) {
        SchoolEvent.Kind.ABSENCE -> "Absence"
        SchoolEvent.Kind.LATE -> "Retard" + (e.minutes?.let { " de $it min" } ?: "")
        SchoolEvent.Kind.EXEMPTION -> listOfNotNull(e.label, e.subject?.let { Agenda.pretty(it) }).joinToString(" · ")
        SchoolEvent.Kind.PUNISHMENT, SchoolEvent.Kind.SANCTION -> Agenda.pretty(e.label)
    }

    private fun day(d: java.time.LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, FR)}"

    /** "Mardi 8 sept. à 8h00", "Du 8 sept. au 14 sept.", "Le 9 sept., 8h00 – 10h00". */
    fun date(e: SchoolEvent): String {
        val start = e.start
        val end = e.end
        val startDay = start.toLocalDate()
        return when {
            end != null && end.toLocalDate() != startDay -> "Du ${day(startDay)} au ${day(end.toLocalDate())}"
            end != null && end != start -> "${Agenda.dayName(startDay)} ${day(startDay)}, ${Agenda.time(start)} – ${Agenda.time(end)}"
            start.toLocalTime() == java.time.LocalTime.MIDNIGHT -> "${Agenda.dayName(startDay)} ${day(startDay)}"
            else -> "${Agenda.dayName(startDay)} ${day(startDay)} à ${Agenda.time(start)}"
        }
    }

    /** "À justifier", "Justifiée", "Non justifiée", or null when it does not apply. */
    fun status(e: SchoolEvent): String? {
        val feminine = e.kind == SchoolEvent.Kind.ABSENCE
        return when {
            e.toJustify -> "À justifier"
            e.justified == true -> if (feminine) "Justifiée" else "Justifié"
            e.justified == false -> if (feminine) "Non justifiée" else "Non justifié"
            else -> null
        }
    }

    fun reasons(e: SchoolEvent): String? = e.reasons.joinToString(", ") { Agenda.pretty(it) }.ifEmpty { null }

    /** One line for a notification or a row: date, length, reason, status. */
    fun details(e: SchoolEvent): String = listOfNotNull(
        date(e),
        e.hours?.let { "$it de cours" },
        reasons(e),
        e.by?.let { "par ${Agenda.prettyName(it)}" },
        status(e),
    ).joinToString(" · ")
}
