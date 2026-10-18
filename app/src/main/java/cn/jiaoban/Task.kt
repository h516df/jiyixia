package cn.jiaoban

import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 一条事的状态。作废不是删除，只是为了能反悔，记录还留着。 */
enum class TaskState { OPEN, DONE, VOID }

/** 必须办完的事逾期后继续跟进；有时效的事到期后收起。 */
enum class ExpiryPolicy { MUST_FINISH, EXPIRES }

enum class EntryKind(val label:String) { TODO("待办"), DELEGATED("交办"), NOTE("笔记") }

/** 文字记录；旧记录始终保留交办类型。 */
data class Task(
    val id: String,
    val text: String,
    val from: String = "",
    val due: LocalDate? = null,
    val state: TaskState = TaskState.OPEN,
    val createdAt: Instant,
    val closedAt: Instant? = null,
    val expiryPolicy: ExpiryPolicy = ExpiryPolicy.EXPIRES,
    val workStage: WorkStage = WorkStage.RECEIVED,
    val followUps: List<FollowUp> = emptyList(),
    val kind: EntryKind = EntryKind.DELEGATED,
    val pinned: Boolean = false,
)

/**
 * 一件事此刻落在有效期的哪一段。
 * 是否在期限过后收起，由每条记录的 ExpiryPolicy 决定；日期分档本身不改变记录状态。
 */
enum class Bucket(val label: String) {
    OVERDUE("已过期"),
    TODAY("今天"),
    LATER("以后"),
    NO_DUE("没定期限"),
    CLOSED("已办/作废"),
}

fun bucketOf(task: Task, today: LocalDate): Bucket {
    if (task.state != TaskState.OPEN) return Bucket.CLOSED
    val due = task.due ?: return Bucket.NO_DUE
    return when {
        due.isBefore(today) -> Bucket.OVERDUE
        due == today -> Bucket.TODAY
        else -> Bucket.LATER
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
            days < 0 -> if (task.expiryPolicy == ExpiryPolicy.MUST_FINISH) "逾期 ${-days} 天" else "过期 ${-days} 天"
            days == 0L -> "今天要"
            days == 1L -> "明天要"
            else -> "${due.monthValue}月${due.dayOfMonth}日 · 还有 $days 天"
        }
    }
    return if (who.isEmpty()) whenText else "$who · $whenText"
}

/** 流水：最近记的在最上面。 */
fun sortForTimeline(tasks: List<Task>): List<Task> = tasks.sortedByDescending { it.createdAt }

/** 今天就要交的，单独拎到最上面，免得被后来的流水压下去。 */
fun dueToday(tasks: List<Task>, today: LocalDate): List<Task> =
    tasks.filter { bucketOf(it, today) == Bucket.TODAY }.sortedBy { it.createdAt }

/** 流水里其余的部分：有效期内、今天不到期、也没办完的，按记录时间倒序。 */
fun timelineRest(tasks: List<Task>, today: LocalDate): List<Task> =
    tasks.filter {
        it.state == TaskState.OPEN &&
            bucketOf(it, today) != Bucket.TODAY &&
            bucketOf(it, today) != Bucket.OVERDUE
    }.sortedByDescending { it.createdAt }

/** 过期区：越早过期的排越前。 */
fun sortForOverdue(tasks: List<Task>, today: LocalDate): List<Task> =
    tasks.filter { bucketOf(it, today) == Bucket.OVERDUE }.sortedBy { it.due }

/** 历史：办完和作废的，最近处理的在前。 */
fun sortForHistory(tasks: List<Task>): List<Task> =
    tasks.filter { it.state != TaskState.OPEN }.sortedByDescending { it.closedAt ?: it.createdAt }

fun matches(task: Task, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return task.text.contains(q, ignoreCase = true) || task.from.contains(q, ignoreCase = true) ||
        task.followUps.any { it.text.contains(q, ignoreCase = true) }
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

/** 一个人名下有多少事。按人看是记录本的主线：回头能说清谁在什么时候交代了什么。 */
data class PersonStat(val name: String, val total: Int, val open: Int, val overdue: Int)

fun peopleStats(tasks: List<Task>, today: LocalDate): List<PersonStat> =
    tasks.groupBy { it.from.trim() }
        .map { (name, list) ->
            PersonStat(
                name = name,
                total = list.size,
                open = list.count { it.state == TaskState.OPEN },
                overdue = list.count { bucketOf(it, today) == Bucket.OVERDUE },
            )
        }
        .sortedWith(
            compareByDescending<PersonStat> { it.open }
                .thenByDescending { it.total }
                // 有名字的排前面，「没记是谁」放最后
                .thenByDescending { it.name.isNotEmpty() }
                .thenBy { it.name }
        )

/** 某个人的事：还在办的排前面（按到期日），办完作废的排后面（按处理时间）。 */
fun sortForPerson(tasks: List<Task>, name: String): List<Task> {
    val mine = tasks.filter { it.from.trim() == name.trim() }
    val open = mine.filter { it.state == TaskState.OPEN }.sortedBy { it.due ?: LocalDate.MAX }
    val closed = mine.filter { it.state != TaskState.OPEN }.sortedByDescending { it.closedAt ?: it.createdAt }
    return open + closed
}

/** 人名那一栏怎么显示：没记是谁的单独归一类。 */
fun personLabel(name: String): String = name.trim().ifEmpty { "没记是谁" }

/** 每日首页只把主动选择到期失效的记录收起；未办完的工作不能自动消失。 */
fun isExpired(task: Task, today: LocalDate): Boolean =
    bucketOf(task, today) == Bucket.OVERDUE && task.expiryPolicy == ExpiryPolicy.EXPIRES

fun activeTasks(tasks: List<Task>, today: LocalDate): List<Task> =
    tasks.filter { it.state == TaskState.OPEN && !isExpired(it, today) }
        .sortedWith(compareBy<Task> { it.due ?: LocalDate.MAX }.thenByDescending { it.createdAt })

fun expiredTasks(tasks: List<Task>, today: LocalDate): List<Task> =
    sortForOverdue(tasks, today).filter { isExpired(it, today) }

fun followUpOverdue(tasks: List<Task>, today: LocalDate): List<Task> =
    activeTasks(tasks, today).filter { bucketOf(it, today) == Bucket.OVERDUE }
