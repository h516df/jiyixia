package cn.jiaoban.data

import cn.jiaoban.Task
import cn.jiaoban.TaskState
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
            )
        }
        return out
    }
}
