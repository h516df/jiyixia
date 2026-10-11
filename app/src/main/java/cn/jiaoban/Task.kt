package cn.jiaoban

import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 一条事的状态。作废不是删除，只是为了能反悔，记录还留着。 */
enum class TaskState { OPEN, DONE, VOID }

/** 别人交代的一件事。 */
data class Task(
    val id: String,
    val text: String,
    val from: String = "",
    val due: LocalDate? = null,
    val state: TaskState = TaskState.OPEN,
    val createdAt: Instant,
    val closedAt: Instant? = null,
)

/** 一件事在界面上落到哪一档。到期没办就自动落到 OVERDUE，不用用户动手。 */
enum class Bucket(val label: String) {
    TODAY("今天"),
    TOMORROW("明天"),
    THIS_WEEK("这周"),
    LATER("以后"),
    NO_DUE("没定期限"),
    OVERDUE("已过期"),
    CLOSED("已办/作废"),
}

/** 主列表的档位顺序。OVERDUE 单独折在底部，CLOSED 只在历史里出现。 */
val MAIN_BUCKETS = listOf(Bucket.TODAY, Bucket.TOMORROW, Bucket.THIS_WEEK, Bucket.LATER, Bucket.NO_DUE)

fun bucketOf(task: Task, today: LocalDate): Bucket {
    if (task.state != TaskState.OPEN) return Bucket.CLOSED
    val due = task.due ?: return Bucket.NO_DUE
    return when {
        due.isBefore(today) -> Bucket.OVERDUE
        due == today -> Bucket.TODAY
        due == today.plusDays(1) -> Bucket.TOMORROW
        due.isAfter(today.plusDays(7)) -> Bucket.LATER
        else -> Bucket.THIS_WEEK
    }
}

fun daysLeft(task: Task, today: LocalDate): Long? = task.due?.let { ChronoUnit.DAYS.between(today, it) }

/** 一行小字：谁交代的 + 什么时候要。 */
fun metaLine(task: Task, today: LocalDate): String {
    val who = task.from.trim()
    val due = task.due
    val whenText = if (due == null) {
        "没定期限"
    } else {
        val days = ChronoUnit.DAYS.between(today, due)
        when {
            days < 0 -> "过期 ${-days} 天"
            days == 0L -> "今天要"
            days == 1L -> "明天要"
            else -> "${due.monthValue}月${due.dayOfMonth}日 · 还有 $days 天"
        }
    }
    return if (who.isEmpty()) whenText else "$who · $whenText"
}

/** 主列表：先按档位，档内按到期日，最后按记下来的时间。 */
fun sortForMain(tasks: List<Task>, today: LocalDate): List<Task> = tasks.sortedWith(
    compareBy(
        { MAIN_BUCKETS.indexOf(bucketOf(it, today)).let { i -> if (i < 0) Int.MAX_VALUE else i } },
        { it.due ?: LocalDate.MAX },
        { it.createdAt },
    )
)

/** 过期区：越早过期的排越前。 */
fun sortForOverdue(tasks: List<Task>, today: LocalDate): List<Task> =
    tasks.filter { bucketOf(it, today) == Bucket.OVERDUE }.sortedBy { it.due }

/** 历史：办完/作废的，最近处理的在前。 */
fun sortForHistory(tasks: List<Task>): List<Task> =
    tasks.filter { it.state != TaskState.OPEN }.sortedByDescending { it.closedAt ?: it.createdAt }

fun matches(task: Task, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return task.text.contains(q, ignoreCase = true) || task.from.contains(q, ignoreCase = true)
}

/** 搜索是全局的：过期、办完、作废的都能翻出来。 */
fun searchAll(tasks: List<Task>, query: String): List<Task> =
    tasks.filter { matches(it, query) }.sortedByDescending { it.createdAt }

/** 顶部一行概况。 */
fun summaryLine(tasks: List<Task>, today: LocalDate): String {
    val open = tasks.count { it.state == TaskState.OPEN }
    if (open == 0) return if (tasks.isEmpty()) "还没记过事" else "手上没有活的事"
    val todayCount = tasks.count { bucketOf(it, today) == Bucket.TODAY }
    val overdue = tasks.count { bucketOf(it, today) == Bucket.OVERDUE }
    val parts = mutableListOf("手上 $open 件")
    if (todayCount > 0) parts += "今天 $todayCount 件"
    if (overdue > 0) parts += "过期 $overdue 件"
    return parts.joinToString("，")
}
