package fr.sallio.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The student's picture in the corner of the app: a photo they choose (PRONOTE
 * never gives its own to an app session), else an emoji or their initial on a
 * colour of their choice. Everything stays on the phone; the photo sits in the
 * encrypted vault with the rest.
 */
object Avatar {

    const val PICK = 41
    const val CAPTURE = 42

    private const val PREFS = "ui"
    private const val EMOJI = "avatarEmoji"
    private const val COLOR = "avatarColor"

    val emojis = listOf("😎", "🦊", "🐼", "🐸", "🦄", "🐯", "🐙", "🐧", "🌸", "⚡", "🔥", "🌈", "🎧", "🎮", "⚽", "🏀", "🎨", "📚", "🚀", "👾")

    /** Default sun yellow, then the subject colours. */
    private val families = listOf(null, Subjects.SKY, Subjects.CORAL, Subjects.GREEN, Subjects.VIOLET, Subjects.PINK, Subjects.TEAL, Subjects.ORANGE, Subjects.AMBER)
    private val familyNames = listOf("sun", "sky", "coral", "green", "violet", "pink", "teal", "orange", "amber")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun family(context: Context): Subjects.Family? =
        families[familyNames.indexOf(prefs(context).getString(COLOR, "sun")).coerceAtLeast(0)]

    fun fill(context: Context): Int = family(context)?.let { Subjects.solid(it) } ?: context.getColor(R.color.sun)
    fun edge(context: Context): Int = family(context)?.let { Subjects.edge(context, it) } ?: context.getColor(R.color.sun_edge)
    private fun ink(context: Context): Int = family(context)?.let { Subjects.onSolid(it) } ?: context.getColor(R.color.on_sun)

    /** What goes inside the sticker: the photo, else the emoji, else the initial. */
    fun content(activity: MainActivity, first: String, sizeDp: Int, letterSize: Float): View {
        val photo = activity.repo.photo()
        if (photo != null) return ImageView(activity).apply {
            setImageBitmap(photo)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = UI.rounded(fill(activity), activity.dp(sizeDp * 12 / 40).toFloat())
            clipToOutline = true
            layoutParams = FrameLayout.LayoutParams(activity.dp(sizeDp), activity.dp(sizeDp), Gravity.CENTER)
            contentDescription = "Ta photo"
        }
        val emoji = prefs(activity).getString(EMOJI, null)
        if (emoji != null) return UI.text(activity, emoji, letterSize * 1.05f).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            contentDescription = "Ton avatar"
        }
        return UI.title(activity, first.take(1).uppercase().ifEmpty { "?" }, letterSize).apply { setTextColor(ink(activity)) }
    }

    /** The square sticker in the header and in the settings. */
    fun button(activity: MainActivity, first: String, sizeDp: Int, letterSize: Float, onClick: () -> Unit): FrameLayout =
        UI.squareButtonColors(activity, fill(activity), edge(activity), content(activity, first, sizeDp, letterSize), onClick)

    // ---- Editor --------------------------------------------------------------------

    fun edit(activity: MainActivity, first: String, onChanged: () -> Unit) {
        val ctx = activity
        Sheet.open(activity, activity.bottomInsetPx) { sheet, dialog -> fill(activity, first, onChanged, sheet, dialog) }
    }

    /** The editor's content; every choice rebuilds it in place, the sheet stays open. */
    private fun fill(activity: MainActivity, first: String, onChanged: () -> Unit, sheet: LinearLayout, dialog: android.app.Dialog) {
        val ctx = activity
        fun rebuild() {
            sheet.removeAllViews()
            fill(activity, first, onChanged, sheet, dialog)
        }
        run {
            Sheet.title(sheet, "Ton avatar", "Il reste sur ton téléphone, personne d'autre ne le voit.")

            val preview = FrameLayout(ctx)
            fun redraw() {
                preview.removeAllViews()
                preview.addView(button(activity, first, 92, 44f) {}.apply {
                    layoutParams = FrameLayout.LayoutParams(ctx.dp(100), ctx.dp(104), Gravity.CENTER)
                    isClickable = false
                })
            }
            redraw()
            sheet.addView(preview, LinearLayout.LayoutParams(-1, ctx.dp(116)).apply { topMargin = ctx.dp(8) })

            fun changed() { redraw(); onChanged() }

            // Photo.
            sheet.addView(UI.section(ctx, "Photo"))
            val photoRow = UI.row(ctx)
            photoRow.addView(UI.button(ctx, "Galerie") { dialog.dismiss(); pick(activity) }, UI.weight())
            photoRow.addView(UI.hgap(ctx, 10))
            photoRow.addView(UI.ghostButton(ctx, "Appareil photo") { dialog.dismiss(); capture(activity) }, UI.weight())
            sheet.addView(photoRow, UI.matchWidth())
            if (activity.repo.photo() != null) sheet.addView(UI.ghostButton(ctx, "Retirer la photo") {
                activity.repo.setPhoto(null)
                Motion.tap(sheet)
                changed()
                rebuild()
            }, UI.matchWidth().apply { topMargin = ctx.dp(10) })

            // Without a photo: emoji or initial.
            sheet.addView(UI.section(ctx, "Sans photo"))
            val grid = android.widget.GridLayout(ctx).apply { columnCount = 6 }
            val choices = listOf<String?>(null) + emojis
            choices.forEach { e ->
                val selected = prefs(ctx).getString(EMOJI, null) == e
                val cell = FrameLayout(ctx).apply {
                    background = UI.sticker(ctx, ctx.getColor(if (selected) R.color.accent_container else R.color.surface),
                        ctx.getColor(if (selected) R.color.accent else R.color.edge), 14, 2, pressable = true)
                    addView(if (e == null) UI.title(ctx, first.take(1).uppercase().ifEmpty { "?" }, 20f) else UI.text(ctx, e, 22f).apply { includeFontPadding = false },
                        FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { bottomMargin = ctx.dp(2) })
                    contentDescription = e ?: "Initiale"
                    setOnClickListener {
                        Motion.tap(this)
                        prefs(ctx).edit().apply { if (e == null) remove(EMOJI) else putString(EMOJI, e) }.apply()
                        changed()
                        rebuild()
                    }
                }
                val size = (ctx.resources.displayMetrics.widthPixels - ctx.dp(32 + 40)) / 6
                grid.addView(cell, android.widget.GridLayout.LayoutParams().apply {
                    width = size; height = size
                    setMargins(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
                })
            }
            sheet.addView(grid, UI.matchWidth())
            if (activity.repo.photo() != null) sheet.addView(UI.text(ctx, "Ta photo passe devant : retire-la pour voir l'emoji.", 12f, R.color.text2).apply { setPadding(0, ctx.dp(8), 0, 0) })

            // Colour.
            sheet.addView(UI.section(ctx, "Couleur"))
            val colors = UI.row(ctx)
            val current = prefs(ctx).getString(COLOR, "sun")
            familyNames.forEachIndexed { i, name ->
                val f = families[i]
                val fill = f?.let { Subjects.solid(it) } ?: ctx.getColor(R.color.sun)
                val edge = f?.let { Subjects.edge(ctx, it) } ?: ctx.getColor(R.color.sun_edge)
                colors.addView(FrameLayout(ctx).apply {
                    background = UI.rounded(fill, ctx.dp(15).toFloat(), if (name == current) ctx.dp(3) else ctx.dp(2), if (name == current) ctx.getColor(R.color.text) else edge)
                    contentDescription = name
                    setOnClickListener {
                        Motion.tap(this)
                        prefs(ctx).edit().putString(COLOR, name).apply()
                        changed()
                        rebuild()
                    }
                }, LinearLayout.LayoutParams(0, ctx.dp(30), 1f).apply { if (i > 0) marginStart = ctx.dp(6) })
            }
            sheet.addView(colors, UI.matchWidth())
        }
    }

    // ---- Photo ---------------------------------------------------------------------

    private fun pick(activity: Activity) {
        // Android's photo picker: no storage permission, the app only sees the chosen picture.
        val intent = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES)
        else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        runCatching { activity.startActivityForResult(intent, PICK) }
            .onFailure { Toast.makeText(activity, "Pas de galerie sur ce téléphone.", Toast.LENGTH_LONG).show() }
    }

    private fun captureFile(context: Context) = File(File(context.cacheDir, "attachments"), "camera/capture.jpg")

    private fun capture(activity: Activity) {
        val file = captureFile(activity).apply { parentFile?.mkdirs(); delete() }
        val uri = Uri.Builder().scheme("content").authority(Attachments.Provider.authority(activity)).path("camera/capture.jpg").build()
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { file.createNewFile(); activity.startActivityForResult(intent, CAPTURE) }
            .onFailure { Toast.makeText(activity, "Pas d'appareil photo disponible.", Toast.LENGTH_LONG).show() }
    }

    /** From MainActivity.onActivityResult. Returns true when the photo changed. */
    fun onResult(activity: MainActivity, requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != PICK && requestCode != CAPTURE) return false
        if (resultCode != Activity.RESULT_OK) {
            if (requestCode == CAPTURE) captureFile(activity).delete()
            return false
        }
        val bytes = runCatching {
            if (requestCode == PICK) data?.data?.let { u -> activity.contentResolver.openInputStream(u)?.use { it.readBytes() } }
            else captureFile(activity).takeIf { it.length() > 0 }?.readBytes()
        }.getOrNull()
        captureFile(activity).delete()
        val square = bytes?.let { square(it) }
        if (square == null) {
            Toast.makeText(activity, "Impossible de lire cette image.", Toast.LENGTH_LONG).show()
            return false
        }
        activity.repo.setPhoto(square)
        return true
    }

    /** Upright, centred square of 512 px, as JPEG: small enough for the vault and sharp on screen. */
    private fun square(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 512) sample *= 2
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rotation = runCatching {
            when (android.media.ExifInterface(bytes.inputStream()).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        val side = minOf(raw.width, raw.height)
        val scale = minOf(1f, 512f / side)
        val matrix = Matrix().apply { postRotate(rotation); postScale(scale, scale) }
        val cropped = Bitmap.createBitmap(raw, (raw.width - side) / 2, (raw.height - side) / 2, side, side, matrix, true)
        return ByteArrayOutputStream().use { out ->
            cropped.compress(Bitmap.CompressFormat.JPEG, 88, out)
            out.toByteArray()
        }
    }
}
