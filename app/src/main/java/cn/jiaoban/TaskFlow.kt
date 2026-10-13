package cn.jiaoban

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 办理阶段只描述未关闭事项的过程，不改变期限或原 OPEN/DONE/VOID 状态。 */
enum class WorkStage(val label: String) { RECEIVED("未开始"), WORKING("进行中"), WAITING("等回复") }
enum class FollowUpKind { NOTE, PROGRESS, DONE, VOID, RESTORED }
data class FollowUp(val id: String, val text: String, val createdAt: Instant, val kind: FollowUpKind = FollowUpKind.NOTE)

private fun Task.appendEvent(text: String, kind: FollowUpKind, at: Instant): Task =
    copy(followUps = followUps + FollowUp(UUID.randomUUID().toString(), text, at, kind))

fun Task.withWorkStage(stage: WorkStage, at: Instant = Instant.now()): Task {
    if (state != TaskState.OPEN || workStage == stage) return this
    val text = when(stage) {
        WorkStage.RECEIVED -> "改为未开始"
        WorkStage.WORKING -> if(workStage == WorkStage.WAITING) "继续办理" else "开始办理"
        WorkStage.WAITING -> "改为等回复"
    }
    return copy(workStage = stage).appendEvent(text, FollowUpKind.PROGRESS, at)
}

fun Task.withFollowUp(text: String, at: Instant = Instant.now()): Task {
    val clean = text.trim()
    return if(clean.isEmpty()) this else appendEvent(clean, FollowUpKind.NOTE, at)
}

fun Task.withState(next: TaskState, at: Instant = Instant.now()): Task {
    if(state == next) return this
    val (text, kind) = when(next) {
        TaskState.OPEN -> "恢复为待办" to FollowUpKind.RESTORED
        TaskState.DONE -> "办完这件事" to FollowUpKind.DONE
        TaskState.VOID -> "作废这件事" to FollowUpKind.VOID
    }
    return copy(state = next, closedAt = if(next == TaskState.OPEN) null else at).appendEvent(text,kind,at)
}

/** 历史展示真实处理时间，不能继续使用“正在逾期”的待办文案。 */
fun recordMeta(task: Task, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
    if(task.state == TaskState.OPEN) return metaLine(task.copy(from = ""),today)
    val label = if(task.state == TaskState.DONE) "办完" else "作废"
    val date = task.closedAt?.atZone(zone)?.toLocalDate() ?: return "已$label · 未记录处理时间"
    return "${date.monthValue}月${date.dayOfMonth}日$label"
}
