package fr.sallio.app

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.WindowManager
import fr.sallio.pronote.Lesson
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Lock screen wallpaper with the rooms of the day: the fastest way to the
 * room there is, the phone does not even need to be unlocked. Drawn on the
 * phone from the stored timetable; nothing is sent anywhere.
 */
object Wallpaper {

    private const val PREF = "lockWallpaper"
    private const val HASH = "lockWallpaperHash"

    fun isEnabled(context: Context) = prefs(context).getBoolean(PREF, false)

    private fun prefs(context: Context) = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    /** Turns the feature on; the caller then runs [update] off the main thread. */
    fun enable(context: Context) {
        prefs(context).edit().putBoolean(PREF, true).remove(HASH).commit()
    }

    /** Gives the lock screen back the home screen wallpaper. Worker thread. */
    fun disable(context: Context) {
        prefs(context).edit().putBoolean(PREF, false).remove(HASH).commit()
        runCatching { WallpaperManager.getInstance(context).clear(WallpaperManager.FLAG_LOCK) }
    }

    /** What the wallpaper would look like right now, for the preview. */
    fun preview(context: Context): android.graphics.Bitmap =
        render(context, Agenda.view(Repo.get(context).lessons(), LocalDateTime.now()), LocalDateTime.now())

    /** Redraws only when what it shows changed (another day, new lessons). */
    fun update(context: Context) {
        if (!isEnabled(context)) return
        val repo = Repo.get(context)
        if (repo.account() == null) return
        val now = LocalDateTime.now()
        val view = Agenda.view(repo.lessons(), now)
        val signature = (view.dayDate?.toString() ?: "none") + view.day.joinToString { "${it.start}${it.rooms}${it.status}${it.canceled}" }
        val hash = signature.hashCode()
        if (prefs(context).getInt(HASH, 0) == hash && prefs(context).contains(HASH)) return
        val bitmap = render(context, view, now)
        runCatching {
            WallpaperManager.getInstance(context).setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
            prefs(context).edit().putInt(HASH, hash).apply()
        }
        bitmap.recycle()
    }

    private fun screen(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(WindowManager::class.java)
        return if (android.os.Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    fun render(context: Context, view: Agenda.View, now: LocalDateTime): Bitmap {
        val (w, h) = screen(context)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val d = context.resources.displayMetrics.density
        fun dp(v: Float) = v * d

        // Night ink background with a soft blue glow, readable under any clock style.
        val bg = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0xFF1E2A4A.toInt(), 0xFF16141F.toInt(), 0xFF121018.toInt()), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)

        val display = UI.display(context, 600)
        val body = UI.body(context, 800)
        val margin = dp(24f)
        // The top ~36 % stays empty for the lock screen clock.
        var y = h * 0.36f

        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = display; textSize = dp(26f) }
        val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB0A79A.toInt(); typeface = body; textSize = dp(13f) }
        val date = view.dayDate
        val heading = when {
            date == null -> "Pas de cours à venir"
            date == now.toLocalDate() -> "Aujourd'hui"
            date == now.toLocalDate().plusDays(1) -> "Demain"
            else -> Agenda.dayName(date)
        }
        c.drawText(heading, margin, y, title)
        if (date != null) {
            c.drawText("${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.FULL, Locale.FRANCE)} · Sallio", margin, y + dp(20f), small)
        }
        y += dp(38f)

        val lessons = view.day.take(8)
        if (lessons.isEmpty()) return bitmap
        val bottom = h * 0.86f
        val rowH = ((bottom - y) / lessons.size).coerceAtMost(dp(82f))
        for (lesson in lessons) {
            drawRow(context, c, lesson, margin, y, w - margin, rowH - dp(8f), display, body)
            y += rowH
        }
        return bitmap
    }

    private fun drawRow(context: Context, c: Canvas, lesson: Lesson, left: Float, top: Float, right: Float, height: Float, display: android.graphics.Typeface, body: android.graphics.Typeface) {
        val d = context.resources.displayMetrics.density
        fun dp(v: Float) = v * d
        val style = Subjects.style(lesson.subject)
        val depth = dp(3f)
        val radius = dp(18f)
        val card = RectF(left, top, right, top + height - depth)
        c.drawRoundRect(RectF(left, top + depth, right, top + height), radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B0A10.toInt() })
        c.drawRoundRect(card, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF24212C.toInt() })

        val cy = card.centerY()
        val time = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = display; textSize = dp(16f) }
        c.drawText(Agenda.time(lesson.start), card.left + dp(14f), cy + dp(5f), time)

        val tileSize = (card.height() - dp(16f)).coerceAtMost(dp(36f))
        val tile = RectF(card.left + dp(64f), cy - tileSize / 2, card.left + dp(64f) + tileSize, cy + tileSize / 2)
        c.drawRoundRect(tile, dp(11f), dp(11f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.family.darkTint.toInt() })
        context.getDrawable(style.icon)?.mutate()?.let { icon ->
            icon.setTint(style.family.darkInk.toInt())
            val pad = tileSize * 0.22f
            icon.setBounds((tile.left + pad).toInt(), (tile.top + pad).toInt(), (tile.right - pad).toInt(), (tile.bottom - pad).toInt())
            icon.draw(c)
        }

        val textLeft = tile.right + dp(12f)
        var textRight = card.right - dp(14f)
        val alert = Agenda.alert(lesson)
        if (alert != null) {
            val chip = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFB2A6.toInt(); typeface = body; textSize = dp(11f) }
            val width = chip.measureText(alert) + dp(14f)
            val box = RectF(textRight - width, cy - dp(11f), textRight, cy + dp(11f))
            c.drawRoundRect(box, dp(9f), dp(9f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF41262A.toInt() })
            c.drawText(alert, box.left + dp(7f), cy + dp(4f), chip)
            textRight = box.left - dp(8f)
        }
        val room = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (lesson.canceled) 0xFF7E776D.toInt() else Color.WHITE
            typeface = display
            textSize = dp(21f)
            isStrikeThruText = lesson.canceled
        }
        val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB0A79A.toInt(); typeface = body; textSize = dp(11.5f) }
        val avail = (textRight - textLeft).coerceAtLeast(dp(40f))
        val roomText = if (lesson.rooms.isNotEmpty()) Agenda.room(lesson) else Agenda.pretty(lesson.subject)
        val subText = (if (lesson.rooms.isNotEmpty()) Agenda.pretty(lesson.subject) else lesson.teachers.firstOrNull()?.let { Agenda.prettyName(it) } ?: "") +
            (LessonNotes.text(context, lesson)?.let { " · $it" } ?: "")
        c.drawText(TextUtils.ellipsize(roomText, room, avail, TextUtils.TruncateAt.END).toString(), textLeft, cy + dp(1f), room)
        c.drawText(TextUtils.ellipsize(subText, sub, avail, TextUtils.TruncateAt.END).toString(), textLeft, cy + dp(16f), sub)
    }
}
