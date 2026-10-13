package fr.sallio.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/** What makes Sallio's notifications look like the app: the coloured tiles of its lists. */
internal object NotificationLook {

    private const val SIZE = 192

    /** The subject's tile: its colour, its icon in the deeper shade, as on the lesson cards. */
    fun subjectTile(context: Context, subject: String): Bitmap {
        val style = Subjects.style(subject)
        return tile(context, Subjects.solid(style.family), style.icon, Subjects.deep(style.family))
    }

    /** A tile in the app's colours, for alerts without a subject (a message, the canteen). */
    fun appTile(context: Context, icon: Int): Bitmap =
        tile(context, context.getColor(R.color.accent), icon, context.getColor(R.color.on_accent))

    private fun tile(context: Context, fill: Int, icon: Int, ink: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val r = SIZE * 0.3f
        canvas.drawRoundRect(RectF(0f, 0f, SIZE.toFloat(), SIZE.toFloat()), r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
        context.getDrawable(icon)?.mutate()?.let { d ->
            d.setTint(ink)
            val inset = (SIZE * 0.24f).toInt()
            d.setBounds(inset, inset, SIZE - inset, SIZE - inset)
            d.draw(canvas)
        }
        return bitmap
    }
}
