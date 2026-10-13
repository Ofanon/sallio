package fr.sallio.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Writes QR codes as PGM images (plain greyscale text, no image library
 * needed) in app/build/qr/, so a real decoder can read them back:
 * tools/check_qr.py, with OpenCV. Here: sizes and finder patterns only.
 */
class QrCodeTest {

    private val samples = listOf(
        "1234567",
        "123456789",
        "4294967295",
        "Sallio, la carte de cantine",
        "https://github.com/Ofanon/sallio/releases/tag/v0.6.1?pourquoi=tester+les+versions+plus+grandes",
        "x".repeat(150),
    )

    @Test
    fun writesDecodableImages() {
        val dir = File("build/qr").apply { mkdirs() }
        val index = StringBuilder()
        samples.forEachIndexed { i, text ->
            val qr = QrCode.encode(text)
            assertEquals(qr.version * 4 + 17, qr.size)
            // Finder pattern corners are dark, the separator next to them light.
            assertEquals(true, qr.modules[0][0])
            assertEquals(false, qr.modules[7][7])
            val scale = 8
            val border = 4
            val px = (qr.size + 2 * border) * scale
            val pgm = StringBuilder("P2\n$px $px\n255\n")
            for (y in 0 until px) {
                for (x in 0 until px) {
                    val mx = x / scale - border
                    val my = y / scale - border
                    val dark = mx in 0 until qr.size && my in 0 until qr.size && qr.modules[my][mx]
                    pgm.append(if (dark) "0 " else "255 ")
                }
                pgm.append('\n')
            }
            File(dir, "qr$i.pgm").writeText(pgm.toString())
            index.append("qr$i.pgm\t$text\n")
        }
        File(dir, "expected.tsv").writeText(index.toString())
    }
}
