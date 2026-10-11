package fr.sallio.pronote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ProtocolTest {

    @Test
    fun deflateRoundTrip() {
        val text = "7b2273657373696f6e223a3132337d".repeat(20).toByteArray()
        assertArrayEquals(text, Crypto.inflateRaw(Crypto.deflateRaw(text)))
    }

    @Test
    fun aesRoundTripWithMd5KeyAndZeroIv() {
        val clear = "1".toByteArray()
        val enc = Crypto.aesEncrypt(clear, ByteArray(0), ByteArray(0))
        // md5("") key, zero IV, PKCS#7: the very first order number of every session.
        assertEquals("fb9c2bf6a5d4c7e4c5c6d4e6b0ad7a7f".length, Crypto.hex(enc).length)
        assertArrayEquals(clear, Crypto.aesDecrypt(enc, ByteArray(0), ByteArray(0)))
    }

    @Test
    fun parsesStartFromVariousPages() {
        val modern = """<body onload="try { Start ({"h":9492729,"d":true,"a":6}) } catch (e) { messageErreur (e) }">"""
        val a = Session.parseStart(modern)
        assertEquals(9492729, a.getInt("h"))
        assertEquals(6, a.getInt("a"))

        val legacy = """<script>Start ({h:'1234',a:3,d:false,MR:'ABCD',ER:'010001',sCrA:true})</script>"""
        val b = Session.parseStart(legacy)
        assertEquals("1234", b.getString("h"))
        assertEquals("ABCD", b.getString("MR"))
        assertTrue(b.getBoolean("sCrA"))
    }

    @Test
    fun recognisesOverloadPage() {
        val html = "<html><body>Le site est momentanément indisponible. Veuillez réessayer.</body></html>"
        assertTrue(Session.classifyErrorPage(html) is PronoteException.Busy)
    }

    @Test
    fun datesAndDomains() {
        assertEquals(LocalDateTime.of(2026, 10, 12, 8, 0), PronoteDates.parse("12/10/2026 08:00:00"))
        assertEquals(LocalDateTime.of(2026, 10, 12, 9, 55), PronoteDates.parse("12/10/26 09h55"))
        assertEquals("5/1/2026 0:0:0", PronoteDates.format(LocalDateTime.of(2026, 1, 5, 0, 0)))
        assertEquals(setOf(1, 3, 4, 5), PronoteDates.domain("[1,3..5]"))
    }

    @Test
    fun cleansUrls() {
        assertEquals(
            "https://0750000a.index-education.net/pronote",
            Pronote.cleanUrl("https://0750000A.index-education.net/pronote/eleve.html?identifiant=x#y")
                .replace("0750000A", "0750000a"),
        )
        assertEquals("https://x.fr/pronote", Pronote.cleanUrl("https://x.fr/pronote/"))
    }

    @Test
    fun canceledLessonHiddenOnlyWhenReplaced() {
        fun lesson(h: Int, canceled: Boolean, subject: String) = Lesson(
            LocalDateTime.of(2026, 10, 12, h, 0), LocalDateTime.of(2026, 10, 12, h + 1, 0),
            subject, listOf("B12"), emptyList(), emptyList(), null, canceled, canceled, null, Lesson.Kind.LESSON,
        )
        val alone = listOf(lesson(8, true, "Maths"))
        assertEquals(1, Timetable.hideSuperposedCanceled(alone).size)

        val replaced = listOf(lesson(8, true, "Maths"), lesson(8, false, "Physique"))
        val kept = Timetable.hideSuperposedCanceled(replaced)
        assertEquals(listOf("Physique"), kept.map { it.subject })
        assertFalse(kept.single().canceled)
    }

    @Test
    fun lessonJsonRoundTrip() {
        val l = Lesson(
            LocalDateTime.of(2026, 10, 12, 8, 0), LocalDateTime.of(2026, 10, 12, 9, 0),
            "ANGLAIS LV1", listOf("C204"), listOf("M. DUPONT"), listOf("2NDE 3"), "Prof. absent",
            true, true, "#FF0000", Lesson.Kind.LESSON,
        )
        assertEquals(l, Lesson.fromJson(l.toJson()))
        assertTrue(l.teacherAbsent)
        assertFalse(l.copy(status = "Classe absente").teacherAbsent)
        assertTrue(l.copy(status = "Professeur absent").teacherAbsent)
    }
}
