package cn.jiaoban.data

import cn.jiaoban.EntryKind
import cn.jiaoban.Task
import cn.jiaoban.TaskStep
import cn.jiaoban.TaskState
import cn.jiaoban.ExpiryPolicy
import cn.jiaoban.WorkStage
import cn.jiaoban.FollowUp
import cn.jiaoban.FollowUpKind
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

/**
 * 手写 JSON 编解码：不引第三方序列化库，单条坏掉只丢那一条，不让整个列表陪葬。
 */
object TaskCodec {

    fun encode(tasks: List<Task>): String {
        val arr = JSONArray()
        for (t in tasks) {
            val o = JSONObject()
            o.put("id", t.id)
            o.put("text", t.text)
            o.put("from", t.from)
            o.put("due", if (t.due == null) JSONObject.NULL else t.due.toString())
            o.put("state", t.state.name)
            o.put("expiryPolicy", t.expiryPolicy.name)
            o.put("kind",t.kind.name)
            o.put("pinned",t.pinned)
            o.put("important", t.important)
            o.put("steps", JSONArray().apply { t.steps.forEach { step ->
                put(JSONObject().put("id",step.id).put("text",step.text).put("done",step.done))
            } })
            o.put("workStage", t.workStage.name)
            val events = JSONArray()
            for(event in t.followUps) events.put(JSONObject().put("id",event.id).put("text",event.text)
                .put("createdAt",event.createdAt.toString()).put("kind",event.kind.name))
            o.put("followUps",events)
            o.put("createdAt", t.createdAt.toString())
            o.put("closedAt", if (t.closedAt == null) JSONObject.NULL else t.closedAt.toString())
            arr.put(o)
        }
        return arr.toString()
    }

    /** 整体不是合法 JSON 时抛异常，由调用方决定退回备份还是当空列表。 */
    fun decode(raw: String): List<Task> {
        if (raw.isBlank()) return emptyList()
        val arr = JSONArray(raw)
        val out = ArrayList<Task>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            val dueText = o.optString("due")
            val closedText = o.optString("closedAt")
            out += Task(
                id = id,
                text = o.optString("text"),
                from = o.optString("from", ""),
                due = if (dueText.isBlank()) null else runCatching { LocalDate.parse(dueText) }.getOrNull(),
                state = runCatching { TaskState.valueOf(o.optString("state", TaskState.OPEN.name)) }
                    .getOrDefault(TaskState.OPEN),
                createdAt = runCatching { Instant.parse(o.optString("createdAt")) }.getOrNull() ?: Instant.EPOCH,
                closedAt = if (closedText.isBlank()) null else runCatching { Instant.parse(closedText) }.getOrNull(),
                // 旧记录无字段时，保留原来的到期收起行为，不能替用户改决定。
                expiryPolicy = runCatching { ExpiryPolicy.valueOf(o.optString("expiryPolicy", "EXPIRES")) }
                    .getOrDefault(ExpiryPolicy.EXPIRES),
                workStage = runCatching { WorkStage.valueOf(o.optString("workStage", "RECEIVED")) }
                    .getOrDefault(WorkStage.RECEIVED),
                kind = runCatching { EntryKind.valueOf(o.optString("kind","DELEGATED")) }.getOrDefault(EntryKind.DELEGATED),
                pinned = o.optBoolean("pinned",false),
                important = o.optBoolean("important",false),
                steps = decodeSteps(o.optJSONArray("steps")),
                followUps = decodeEvents(o.optJSONArray("followUps")),
            )
        }
        return out
    }

    private fun decodeSteps(array: JSONArray?): List<TaskStep> = buildList {
        if(array != null) for(i in 0 until array.length()) {
            val step = array.optJSONObject(i) ?: continue
            val id = step.optString("id")
            val text = step.optString("text")
            if(id.isNotBlank() && text.isNotBlank() && none { it.id == id })
                add(TaskStep(id,text,step.optBoolean("done",false)))
        }
    }

    private fun decodeEvents(array: JSONArray?): List<FollowUp> {
        if(array == null) return emptyList()
        val out = ArrayList<FollowUp>()
        for(i in 0 until array.length()) {
            val event = array.optJSONObject(i) ?: continue
            val id = event.optString("id")
            val text = event.optString("text")
            val at = runCatching { Instant.parse(event.optString("createdAt")) }.getOrNull() ?: continue
            if(id.isBlank() || text.isBlank()) continue
            val kind = runCatching { FollowUpKind.valueOf(event.optString("kind","NOTE")) }.getOrDefault(FollowUpKind.NOTE)
            out += FollowUp(id,text,at,kind)
        }
        return out
    }
}
