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
}
