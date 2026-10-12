package fr.sallio.app

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import fr.sallio.pronote.Homework
import fr.sallio.pronote.LoggedIn
import java.io.File
import java.security.MessageDigest

/**
 * Homework files, downloaded from PRONOTE by the app itself (no browser, no
 * second login on the ENT) and kept in the app's private cache. They are
 * opened in the student's own viewer through [Provider], which hands out one
 * file at a time, read only, to the app chosen to show it.
 */
object Attachments {

    private const val DIR = "attachments"
    private const val KEEP_MS = 21L * 24 * 3600_000

    sealed class Result {
        class Ready(val file: File) : Result()
        class Wait(val ms: Long) : Result()
        class Failed(val message: String) : Result()
    }

    private fun root(context: Context) = File(context.cacheDir, DIR)

    /** Where [name] of the item [key] lives once downloaded: one folder per item and file. */
    fun file(context: Context, key: String, name: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest("$key|$name".toByteArray())
        val folder = digest.take(8).joinToString("") { "%02x".format(it) }
        val safe = name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_").take(120).ifEmpty { "document" }
        return File(File(root(context), folder), safe)
    }

    /** Files of homework long gone are not worth the space. */
    fun prune(context: Context) {
        val limit = System.currentTimeMillis() - KEEP_MS
        root(context).listFiles()?.forEach { folder ->
            if (folder.lastModified() < limit) folder.deleteRecursively()
        }
    }

    fun clear(context: Context) {
        root(context).deleteRecursively()
    }

    fun mime(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    /** Downloads (worker thread) then opens (main thread) the file, with a word when it cannot. */
    fun open(activity: Activity, repo: Repo, key: String, name: String, resolve: (LoggedIn, String) -> String?, onDone: () -> Unit) {
        Thread {
            val result = repo.attachment(key, name, resolve)
            activity.runOnUiThread {
                onDone()
                when (result) {
                    is Result.Ready -> view(activity, result.file)
                    is Result.Wait -> toast(activity, "Pronote demande d'attendre un peu entre deux connexions : réessaie dans ${minutes(result.ms)}.")
                    is Result.Failed -> toast(activity, result.message)
                }
            }
        }.start()
    }

    /**
     * The rows of a list of documents: links open in the browser, files are
     * downloaded once with [resolve] (their PRONOTE address in a fresh session)
     * then opened in the student's viewer. When the names are not [known] yet,
     * a tap opens PRONOTE instead.
     */
    fun card(activity: MainActivity, files: List<Homework.Attachment>, key: String, known: Boolean = true, resolve: (LoggedIn, String) -> String?): View {
        val ctx = activity
        val card = UI.card(ctx, padding = 6)
        files.forEachIndexed { i, f ->
            if (i > 0) card.addView(UI.divider(ctx).apply { (layoutParams as LinearLayout.LayoutParams).apply { marginStart = ctx.dp(62); marginEnd = ctx.dp(12) } })
            card.addView(UI.row(ctx).apply {
                setPadding(ctx.dp(10), ctx.dp(12), ctx.dp(12), ctx.dp(12))
                foreground = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22000000), null, UI.rounded(Color.WHITE, ctx.dp(16).toFloat()))
                addView(FrameLayout(ctx).apply {
                    background = UI.rounded(ctx.getColor(R.color.accent_container), ctx.dp(12).toFloat())
                    addView(UI.icon(ctx, if (f.link != null) R.drawable.ic_open else R.drawable.ic_attach, R.color.accent, 20).apply {
                        layoutParams = FrameLayout.LayoutParams(ctx.dp(20), ctx.dp(20), Gravity.CENTER)
                    })
                }, LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)))
                addView(UI.hgap(ctx, 12))
                val col = UI.column(ctx)
                col.addView(UI.oneLine(UI.title(ctx, f.name, 15f)))
                fun hint() = when {
                    f.link != null -> "Lien · ${Uri.parse(link(f.link)).host?.removePrefix("www.") ?: "s'ouvre dans le navigateur"}"
                    !known -> "Fichier · s'ouvre dans Pronote"
                    file(ctx, key, f.name).isFile -> "Fichier · déjà sur ton téléphone"
                    else -> "Fichier · ${f.name.substringAfterLast('.', "").uppercase().ifEmpty { "document" }}"
                }
                val sub = UI.oneLine(UI.text(ctx, hint(), 12f, R.color.text2))
                col.addView(sub)
                addView(col, UI.weight())
                setOnClickListener {
                    Motion.tap(this)
                    if (f.link != null) openLink(activity, f.link)
                    else if (!known) activity.openPronote()
                    else if (sub.tag == null) {
                        sub.tag = "busy"
                        sub.text = "Téléchargement…"
                        open(activity, activity.repo, key, f.name, resolve) {
                            sub.tag = null
                            sub.text = hint()
                        }
                    }
                }
            }, UI.matchWidth())
        }
        return card
    }

    private fun link(raw: String) = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"

    fun openLink(activity: Activity, raw: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link(raw))))
        } catch (e: android.content.ActivityNotFoundException) {
            toast(activity, "Aucun navigateur pour ouvrir ce lien.")
        }
    }

    private fun minutes(ms: Long): String {
        val m = ((ms + 59_999) / 60_000).coerceAtLeast(1)
        return if (m == 1L) "1 minute" else "$m minutes"
    }

    private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    private fun view(activity: Activity, file: File) {
        val relative = file.relativeTo(root(activity)).invariantSeparatorsPath
        val uri = Uri.Builder().scheme("content").authority(Provider.authority(activity))
            .path(relative).build()
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime(file.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            toast(activity, "Aucune appli de ton téléphone ne sait ouvrir « ${file.name} ».")
        }
    }

    /** Read-only access to the downloaded files, for the viewer the student picked. */
    class Provider : ContentProvider() {

        companion object {
            fun authority(context: Context) = context.packageName + ".files"
        }

        override fun onCreate() = true

        private fun resolve(uri: Uri): File? {
            val context = context ?: return null
            val base = root(context).canonicalFile
            val file = File(base, uri.path.orEmpty().trimStart('/')).canonicalFile
            return file.takeIf { it.path.startsWith(base.path + File.separator) && it.isFile }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val file = resolve(uri) ?: throw java.io.FileNotFoundException(uri.toString())
            if (mode == "r") return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            // The only writable file: the camera's shot for the avatar, handed out for one capture.
            if (uri.path?.trimStart('/') != "camera/capture.jpg") throw SecurityException("lecture seule")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
        }

        override fun getType(uri: Uri): String? = resolve(uri)?.let { mime(it.name) }

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            val file = resolve(uri) ?: return null
            val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            return MatrixCursor(columns, 1).apply {
                addRow(columns.map { if (it == OpenableColumns.SIZE) file.length() else if (it == OpenableColumns.DISPLAY_NAME) file.name else null })
            }
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
