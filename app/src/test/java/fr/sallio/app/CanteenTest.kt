package fr.sallio.app

import fr.sallio.turboself.Turboself
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class CanteenTest {

    private fun day(year: Int, week: Int, dayOfWeek: Int) = Turboself.BookingDay("w", "Self", dayOfWeek, "", 0, true, null, year, week)

    @Test
    fun bookingDates() {
        // ISO week 42 of 2026 starts on Monday 12 October.
        assertEquals(LocalDate.of(2026, 10, 12), Canteen.dateOf(day(2026, 42, 1)))
        assertEquals(LocalDate.of(2026, 10, 16), Canteen.dateOf(day(2026, 42, 5)))
        // Week 1 of 2027 starts on Monday 4 January.
        assertEquals(LocalDate.of(2027, 1, 4), Canteen.dateOf(day(2027, 1, 1)))
    }

    @Test
    fun money() {
        assertEquals("12,40 €", Canteen.money(12.4).replace(' ', ' '))
    }

    private fun data(estimated: Double, negativeAllowed: Boolean = false) = Canteen.Data("", "Argent", 4.66, null, true, negativeAllowed,
        listOf(Turboself.Balance("Self", 4.20, estimated, null)), emptyList(), emptyList(), 0)

    @Test
    fun noBookingThatGoesNegative() {
        // 2026-10-15: 4,20 € left, Wednesday booked (estimate 4,20), a 4,66 € lunch must not go through.
        org.junit.Assert.assertFalse(Canteen.affords(data(4.20)))
        org.junit.Assert.assertTrue(Canteen.affords(data(4.66)))
        org.junit.Assert.assertTrue(Canteen.affords(data(10.0)))
        // Schools that allow it on TurboSelf (autoriseReservSoldeIns).
        org.junit.Assert.assertTrue(Canteen.affords(data(0.0, negativeAllowed = true)))
    }
}
