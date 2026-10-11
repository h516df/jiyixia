package cn.jiaoban

import com.xkzhangsan.time.nlp.TimeNLPUtil
import org.junit.Test

/**
 * 探针：先看清 xk-time 对我们会说的那些话到底认成什么，再决定怎么用。
 * 这个类只打印，不做断言。
 */
class ChineseTimeParseProbeTest {

    @Test
    fun probe() {
        val phrases = listOf(
            "王总说明天要周报",
            "周五前给我",
            "月底交",
            "3号要",
            "三天后要",
            "下周三开会",
            "今天下班前",
            "明早十点",
            "王总要周报",
            "这周五之前把预算表发我",
            "后天中午之前回复客户",
            "下个月5号交报表",
        )
        for (p in phrases) {
            val hits = runCatching { TimeNLPUtil.parse(p) }
            val line = hits.map { list ->
                list.joinToString(" | ") { "${it.timeExpression} => ${it.localDateTime} allDay=${it.isAllDayTime}" }
            }.getOrElse { "解析抛异常：" + it.message }
            println("PROBE [$p] -> $line")
        }
    }
}
