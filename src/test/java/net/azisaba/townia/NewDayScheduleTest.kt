package net.azisaba.townia

import net.azisaba.townia.manager.DailyTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Towny's new_day_time: one new day per day, even when the timer fires a moment early or late. */
class NewDayScheduleTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private val five = LocalTime.of(5, 0)
    private fun at(day: Int, h: Int, m: Int, s: Int, ms: Int = 0) = ZonedDateTime.of(2026, 11, day, h, m, s, ms * 1_000_000, zone)

    @Test
    fun `the next new day is today when its time is still ahead, otherwise tomorrow`() {
        assertEquals(at(27, 5, 0, 0), DailyTask.nextNewDay(five, at(27, 4, 0, 0)))
        assertEquals(at(28, 5, 0, 0), DailyTask.nextNewDay(five, at(27, 6, 0, 0)))
        assertEquals(at(28, 5, 0, 0), DailyTask.nextNewDay(five, at(27, 5, 0, 0)), "exactly at the time: that day is under way")
    }

    @Test
    fun `counting on from the day just run never gives the same day again`() {
        // The timer for 05:00 fired at 04:59:59.95 (ticks are 50 ms; it may be up to a second early).
        val ran = at(27, 5, 0, 0)
        val firedAt = at(27, 4, 59, 59, 950)
        assertEquals(ran, DailyTask.nextNewDay(five, firedAt), "counted from the moment it fired, today's 05:00 is still ahead: the old way ran it again")
        assertEquals(at(28, 5, 0, 0), DailyTask.nextNewDay(five, ran), "counted from the day that ran: tomorrow")
    }

    @Test
    fun `a day with a clock change still has one new day`() {
        val ny = ZoneId.of("America/New_York")   // 2026-11-01 02:00 -> 01:00
        val day = ZonedDateTime.of(2026, 10, 31, 1, 30, 0, 0, ny)
        val next = DailyTask.nextNewDay(LocalTime.of(1, 30), day)
        assertEquals(1, next.dayOfMonth)
        assertEquals(1, java.time.Duration.between(day.toLocalDate().atStartOfDay(ny), next.toLocalDate().atStartOfDay(ny)).toDays())
    }
}
