package fr.sallio.app

import android.content.Context
import java.io.File
import java.time.LocalDateTime

/**
 * Diagnostics of debug builds: logcat (tag Sallio) and a small file in the
 * app's private storage, which outlives the phone's tiny log buffer:
 *
 *   adb shell run-as fr.sallio.app.debug cat files/debug.log
 *
 * Release builds write nothing. Never a password, token or card number.
 */
object DebugLog {
    private const val MAX_BYTES = 200_000L
    @Volatile private var file: File? = null

    fun init(context: Context) {
        if (BuildConfig.DEBUG) file = File(context.filesDir, "debug.log")
    }

    @Synchronized
    fun i(message: String, error: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        if (error == null) android.util.Log.i("Sallio", message) else android.util.Log.w("Sallio", message, error)
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) f.writeText(f.readText().takeLast((MAX_BYTES / 2).toInt()))
            f.appendText("${LocalDateTime.now().withNano(0)} $message${error?.let { " ($it)" } ?: ""}\n")
        }
    }
}
