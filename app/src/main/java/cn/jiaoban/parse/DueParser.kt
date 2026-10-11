package cn.jiaoban.parse

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 从一句话里认期限。纯本地规则，不引第三方库。
 *
 * 为什么不用现成的：GitHub 上那个 Java 中文时间解析库（xk-time，Time-NLP 的改良版）
 * 在安卓上根本跑不起来。它的预编译正则表是 JDK 序列化的 java.util.regex.Pattern，
 * 安卓读不了；退回自己编译时又撞上安卓正则不接受 `{0,1}\d+` 这种写法，每次解析都抛
 * PatternSyntaxException（实测堆栈见 verification/）。所以自己写，全部单测覆盖。
 *
 * 只认到「哪一天」，不认具体几点：我们记的是有效期，不是日程。
 */
object DueParser {

    /** 一句话里没提期限时，默认给它 3 天寿命。 */
    const val DEFAULT_DAYS = 3L

    private val WEEKDAYS = mapOf(
        '一' to DayOfWeek.MONDAY,
        '二' to DayOfWeek.TUESDAY,
        '三' to DayOfWeek.WEDNESDAY,
        '四' to DayOfWeek.THURSDAY,
        '五' to DayOfWeek.FRIDAY,
        '六' to DayOfWeek.SATURDAY,
        '日' to DayOfWeek.SUNDAY,
        '天' to DayOfWeek.SUNDAY,
    )

    private val CN_DIGITS = mapOf(
        '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )

    /** 顺序有讲究：越具体的说法越靠前，别让「月底」抢走「下个月底」。 */
    private val FIXED = listOf(
        "大后天" to 3L, "大前天" to -3L,
        "后天" to 2L, "前天" to -2L,
        "明天" to 1L, "明日" to 1L, "明早" to 1L, "明晚" to 1L, "明晨" to 1L,
        "今天" to 0L, "今日" to 0L, "本日" to 0L, "今早" to 0L, "今晚" to 0L, "今晨" to 0L,
        "昨天" to -1L, "昨日" to -1L,
    )

    private val MONTH_DAY = Regex("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]")
    private val MONTH_DAY_CN = Regex("([零一二两三四五六七八九十]{1,3})\\s*月\\s*([零一二两三四五六七八九十]{1,3})\\s*[日号]")
    private val DAY_ONLY = Regex("(下个月|下月|这个月|本月)?\\s*(\\d{1,2}|[零一二两三四五六七八九十]{1,3})\\s*[日号]")
    private val WEEKDAY = Regex("(下下|上上|下|上|这|本)?\\s*(?:周|星期|礼拜)\\s*([一二三四五六日天])")
    private val OFFSET_MONTHS = Regex("(\\d{1,2}|[零一二两三四五六七八九十]{1,3}|半)\\s*个?月\\s*(?:之?后|以后)")
    private val OFFSET_DAYS = Regex("(\\d{1,3}|[零一二两三四五六七八九十]{1,3})\\s*(天|日|个?星期|个?礼拜|个?周)\\s*(?:之?后|以后)")

    /** 认得出就返回那一天，认不出返回 null。 */
    fun parse(text: String, today: LocalDate): LocalDate? {
        val t = text.trim()
        if (t.isEmpty()) return null
        return fixedDay(t, today)
            ?: phraseDay(t, today)
            ?: monthDay(t, today)
            ?: weekdayDay(t, today)
            ?: offsetDay(t, today)
            ?: bareDay(t, today)
    }

    /** 认不出就用默认寿命。 */
    fun withDefault(text: String, today: LocalDate): LocalDate =
        parse(text, today) ?: today.plusDays(DEFAULT_DAYS)

    private fun fixedDay(text: String, today: LocalDate): LocalDate? {
        for ((word, offset) in FIXED) {
            if (text.contains(word)) return today.plusDays(offset)
        }
        return null
    }

    private fun phraseDay(text: String, today: LocalDate): LocalDate? {
        if (text.contains("下个月底") || text.contains("下月底")) {
            val next = today.plusMonths(1)
            return next.withDayOfMonth(next.lengthOfMonth())
        }
        if (text.contains("月底")) return today.withDayOfMonth(today.lengthOfMonth())
        if (text.contains("下个月初") || text.contains("下月初")) return today.plusMonths(1).withDayOfMonth(1)
        if (text.contains("下周末")) return weekStart(today).plusWeeks(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        if (text.contains("本周末") || text.contains("这周末")) {
            return today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        }
        if (text.contains("年底")) return LocalDate.of(today.year, 12, 31)
        return null
    }

    private fun monthDay(text: String, today: LocalDate): LocalDate? {
        MONTH_DAY.find(text)?.let { m ->
            return safeDate(today.year, m.groupValues[1].toInt(), m.groupValues[2].toInt(), today)
        }
        MONTH_DAY_CN.find(text)?.let { m ->
            val month = cnNumber(m.groupValues[1]) ?: return null
            val day = cnNumber(m.groupValues[2]) ?: return null
            return safeDate(today.year, month, day, today)
        }
        DAY_ONLY.find(text)?.let { m ->
            val prefix = m.groupValues[1]
            val day = cnNumber(m.groupValues[2]) ?: return null
            if (day !in 1..31) return null
            return if (prefix == "下个月" || prefix == "下月") nextMonthDay(today, day) else thisOrNextMonthDay(today, day)
        }
        return null
    }

    private fun weekdayDay(text: String, today: LocalDate): LocalDate? {
        val m = WEEKDAY.find(text) ?: return null
        val target = WEEKDAYS[m.groupValues[2][0]] ?: return null
        val start = weekStart(today)
        return when (m.groupValues[1]) {
            "下" -> start.plusWeeks(1).with(TemporalAdjusters.nextOrSame(target))
            "下下" -> start.plusWeeks(2).with(TemporalAdjusters.nextOrSame(target))
            "上" -> start.minusWeeks(1).with(TemporalAdjusters.nextOrSame(target))
            "上上" -> start.minusWeeks(2).with(TemporalAdjusters.nextOrSame(target))
            else -> today.with(TemporalAdjusters.nextOrSame(target))
        }
    }

    private fun offsetDay(text: String, today: LocalDate): LocalDate? {
        OFFSET_MONTHS.find(text)?.let { m ->
            val raw = m.groupValues[1]
            if (raw == "半") return today.plusDays(15)
            val months = cnNumber(raw) ?: return null
            return today.plusMonths(months.toLong())
        }
        OFFSET_DAYS.find(text)?.let { m ->
            val n = cnNumber(m.groupValues[1]) ?: return null
            val unit = m.groupValues[2]
            val days = if (unit.contains("天") || unit.contains("日")) n.toLong() else n.toLong() * 7
            return today.plusDays(days)
        }
        return null
    }

    /** 兜底：只说「下周」「这周」没带星期几。 */
    private fun bareDay(text: String, today: LocalDate): LocalDate? {
        if (text.contains("下个月") || text.contains("下月")) return today.plusMonths(1)
        if (text.contains("下周")) return weekStart(today).plusWeeks(1)
        if (text.contains("这周") || text.contains("本周")) {
            return today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        }
        return null
    }

    private fun weekStart(today: LocalDate): LocalDate =
        today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun safeDate(year: Int, month: Int, day: Int, today: LocalDate): LocalDate? {
        if (month !in 1..12 || day !in 1..31) return null
        val date = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null
        return if (date.isBefore(today)) date.plusYears(1) else date
    }

    private fun thisOrNextMonthDay(today: LocalDate, day: Int): LocalDate? {
        val thisMonth = runCatching { today.withDayOfMonth(day) }.getOrNull()
        if (thisMonth != null && !thisMonth.isBefore(today)) return thisMonth
        return nextMonthDay(today, day)
    }

    private fun nextMonthDay(today: LocalDate, day: Int): LocalDate? =
        runCatching { today.plusMonths(1).withDayOfMonth(day) }.getOrNull()

    /** 认中文数字和阿拉伯数字，够用到 31。 */
    private fun cnNumber(raw: String): Int? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.all { it.isDigit() }) return s.toIntOrNull()
        val tenIndex = s.indexOf('十')
        if (tenIndex >= 0) {
            val tens = if (tenIndex == 0) 1 else CN_DIGITS[s[0]] ?: return null
            val ones = if (tenIndex == s.length - 1) 0 else CN_DIGITS[s[tenIndex + 1]] ?: return null
            return tens * 10 + ones
        }
        if (s.length == 1) return CN_DIGITS[s[0]]
        return null
    }
}
