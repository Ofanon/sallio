package fr.sallio.app

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import java.time.LocalDateTime

/**
 * Quick Settings tile: one swipe down from anywhere, even from the lock
 * screen, shows the next room. Reads the timetable stored on the phone only.
 */
class RoomTile : TileService() {

    override fun onStartListening() {
        val tile = qsTile ?: return
        val repo = Repo.get(applicationContext)
        val now = LocalDateTime.now()
        val focus = if (repo.account() != null) Agenda.view(repo.lessons(), now).focus else null

        if (focus == null) {
            tile.label = if (repo.account() == null) "Sallio" else "Pas de cours"
            if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (repo.account() == null) "Connexion" else "Rien de prévu"
            tile.icon = Icon.createWithResource(this, R.drawable.ic_school)
            tile.state = Tile.STATE_INACTIVE
        } else {
            val style = Subjects.style(focus.subject)
            val alert = Agenda.alert(focus)
            tile.label = if (focus.rooms.isNotEmpty()) Agenda.room(focus) else Agenda.pretty(focus.subject)
            if (Build.VERSION.SDK_INT >= 29) {
                tile.subtitle = alert ?: LessonNotes.text(this, focus) ?: "${Agenda.time(focus.start)} · ${Agenda.pretty(focus.subject)}"
            }
            tile.icon = Icon.createWithResource(this, style.icon)
            tile.state = Tile.STATE_ACTIVE
        }
        tile.updateTile()
    }

    override fun onClick() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_TAB, MainActivity.Tab.TIMETABLE.name)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 30, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** Asks the system to redraw the tile after new data or a lesson change. */
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, RoomTile::class.java)) }
        }
    }
}
