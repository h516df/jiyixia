package cn.jiaoban.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class DueParserTest {

    /** 2026-10-11 是周日。 */
    private val today = LocalDate.of(2026, 10, 11)

    @Test
    fun noTimeWordsMeansNoParse() {
        assertNull(DueParser.parse("王总要周报", today))
        assertNull(DueParser.parse("把周报发给客户", today))
        assertNull(DueParser.parse("", today))
    }

    @Test
    fun defaultLifeIsThreeDays() {
        assertEquals(today.plusDays(3), DueParser.withDefault("王总要周报", today))
    }

    @Test
    fun fixedDays() {
        assertEquals(today, DueParser.parse("今天下班前给你", today))
        assertEquals(today.plusDays(1), DueParser.parse("明天要", today))
        assertEquals(today.plusDays(2), DueParser.parse("后天中午之前回复客户", today))
        assertEquals(today.plusDays(3), DueParser.parse("大后天交", today))
        assertEquals(today.minusDays(1), DueParser.parse("昨天就该给的", today))
    }

    @Test
    fun morningPhraseStillMeansTheDate() {
        assertEquals(today.plusDays(1), DueParser.parse("明早十点开会", today))
        assertEquals(today, DueParser.parse("今天下午三点前", today))
    }

    @Test
    fun monthEnd() {
        assertEquals(LocalDate.of(2026, 10, 31), DueParser.parse("月底交报销单", today))
    }

    @Test
    fun nextMonthEndBeatsMonthEnd() {
        assertEquals(LocalDate.of(2026, 11, 30), DueParser.parse("下个月底前给我", today))
    }

    @Test
    fun nextMonthStart() {
        assertEquals(LocalDate.of(2026, 11, 1), DueParser.parse("下月初交", today))
    }

    @Test
    fun weekend() {
        assertEquals(today, DueParser.parse("这周末交", today))
        assertEquals(LocalDate.of(2026, 10, 18), DueParser.parse("下周末交", today))
    }

    @Test
    fun yearEnd() {
        assertEquals(LocalDate.of(2026, 12, 31), DueParser.parse("年底前结账", today))
    }

    @Test
    fun weekdayWithoutPrefixMeansTheComingOne() {
        assertEquals(LocalDate.of(2026, 10, 16), DueParser.parse("周五前给我", today))
        assertEquals(DayOfWeek.FRIDAY, DueParser.parse("周五前给我", today)!!.dayOfWeek)
    }

    @Test
    fun thisWeekFriday() {
        assertEquals(LocalDate.of(2026, 10, 16), DueParser.parse("这周五之前把预算表发我", today))
    }

    @Test
    fun nextWednesday() {
        assertEquals(LocalDate.of(2026, 10, 14), DueParser.parse("下周三开会", today))
        assertEquals(DayOfWeek.WEDNESDAY, DueParser.parse("下周三开会", today)!!.dayOfWeek)
    }

    @Test
    fun lastWednesdayIsInThePast() {
        val parsed = DueParser.parse("上周三要的报销单", today)
        assertEquals(LocalDate.of(2026, 9, 30), parsed)
        assertTrue(parsed!!.isBefore(today))
    }

    @Test
    fun dayOfMonthRollsForwardWhenAlreadyPassed() {
        val parsed = DueParser.parse("3号要", today)
        assertEquals(LocalDate.of(2026, 11, 3), parsed)
        assertTrue(parsed!!.isAfter(today))
    }

    @Test
    fun explicitMonthDay() {
        assertEquals(LocalDate.of(2026, 10, 20), DueParser.parse("10月20日交报表", today))
        assertEquals(LocalDate.of(2027, 1, 5), DueParser.parse("1月5日交报表", today))
    }

    @Test
    fun nextMonthDay() {
        assertEquals(LocalDate.of(2026, 11, 5), DueParser.parse("下个月5号交报表", today))
    }

    @Test
    fun offsets() {
        assertEquals(today.plusDays(3), DueParser.parse("三天后要", today))
        assertEquals(today.plusDays(3), DueParser.parse("3天后要", today))
        assertEquals(today.plusDays(7), DueParser.parse("一周后交", today))
        assertEquals(today.plusDays(14), DueParser.parse("两个星期后交", today))
        assertEquals(today.plusDays(15), DueParser.parse("半个月后交", today))
        assertEquals(today.plusMonths(2), DueParser.parse("2个月之后交", today))
    }

    @Test
    fun personAndTimeInOneSentence() {
        assertEquals(today.plusDays(1), DueParser.parse("王总说明天要周报", today))
        assertEquals(today.plusDays(5), DueParser.parse("李姐让下周五之前给她", today))
    }
}
