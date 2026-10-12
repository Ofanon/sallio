package fr.sallio.app

import fr.sallio.pronote.Grade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ColorsAndCurvesTest {

    private fun grade(day: Int, points: Double, classAverage: Double?, coefficient: Double = 1.0, outOf: Double = 20.0) =
        Grade("id$day", "MATHÉMATIQUES", LocalDate.of(2026, 10, day), points.toString(), points, outOf, classAverage, coefficient, null)

    @Test
    fun classCurveFollowsTheSameTests() {
        val curve = GradesSheets.runningAverage(listOf(
            grade(3, 12.0, null),
            grade(1, 10.0, 8.0),
            grade(5, 8.0, 5.0, coefficient = 2.0, outOf = 10.0),
        ))
        assertEquals(listOf(10.0, 11.0), curve.take(2).map { it.value })
        assertEquals(8.0, curve[0].classValue!!, 1e-9)
        // A test without class average leaves the class curve where it was.
        assertEquals(8.0, curve[1].classValue!!, 1e-9)
        // 8/10 = 16/20 and 5/10 = 10/20, both with coefficient 2.
        assertEquals((10.0 + 12.0 + 16.0 * 2) / 4, curve[2].value, 1e-9)
        assertEquals((8.0 + 10.0 * 2) / 3, curve[2].classValue!!, 1e-9)
    }

    @Test
    fun noClassAverageNoClassCurve() {
        assertNull(GradesSheets.runningAverage(listOf(grade(1, 10.0, null))).single().classValue)
    }

    @Test
    fun pronoteHexColours() {
        assertEquals(0xFF1E90FF.toInt(), Subjects.parseHex("#1E90FF"))
        assertEquals(0xFF1E90FF.toInt(), Subjects.parseHex("1e90ff"))
        assertNull(Subjects.parseHex("rouge"))
        assertNull(Subjects.parseHex("#FFF"))
    }

    private fun hue(c: Long): Int {
        val r = ((c shr 16) and 0xFF).toInt(); val g = ((c shr 8) and 0xFF).toInt(); val b = (c and 0xFF).toInt()
        return listOf(r, g, b).indexOf(maxOf(r, g, b))
    }

    @Test
    fun pronoteColourKeepsItsHueAtSallioSoftness() {
        // A harsh pure red becomes a soft red; a blue stays blue.
        val red = Subjects.familyFrom(0xFFFF0000.toInt())
        assertEquals(0, hue(red.solid))
        assertTrue("not harsh", ((red.solid shr 8) and 0xFF) > 0x40)
        assertEquals(2, hue(Subjects.familyFrom(0xFF1E40C0.toInt()).solid))
        // Grey stays grey.
        val grey = Subjects.familyFrom(0xFF808080.toInt()).solid
        val channels = listOf((grey shr 16) and 0xFF, (grey shr 8) and 0xFF, grey and 0xFF)
        assertTrue(channels.max() - channels.min() < 0x20)
    }

    @Test
    fun learnedColourWinsAndCanBeTurnedOff() {
        Subjects.learn(mapOf("MATHÉMATIQUES" to "#1E40C0"), use = true)
        assertEquals(2, hue(Subjects.style("Mathématiques").family.solid.let { it }))
        assertEquals(R.drawable.ic_maths, Subjects.style("MATHÉMATIQUES").icon)
        Subjects.learn(mapOf("MATHÉMATIQUES" to "#1E40C0"), use = false)
        assertEquals(Subjects.CORAL, Subjects.style("MATHÉMATIQUES").family)
        Subjects.learn(emptyMap(), use = true)
    }
}
