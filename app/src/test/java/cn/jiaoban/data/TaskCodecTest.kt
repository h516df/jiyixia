package cn.jiaoban.data

import cn.jiaoban.Task
import cn.jiaoban.TaskState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class TaskCodecTest {

    private val sample = Task(
        id = "a1",
        text = "把周报发给客户",
        from = "王总",
        due = LocalDate.of(2026, 10, 12),
        state = TaskState.OPEN,
        createdAt = Instant.parse("2026-10-11T02:00:00Z"),
        closedAt = null,
    )

    @Test
    fun roundTripKeepsEveryField() {
        val decoded = TaskCodec.decode(TaskCodec.encode(listOf(sample)))
        assertEquals(1, decoded.size)
        assertEquals(sample, decoded[0])
    }

    @Test
    fun roundTripKeepsClosedState() {
        val done = sample.copy(state = TaskState.DONE, closedAt = Instant.parse("2026-10-11T03:00:00Z"))
        assertEquals(done, TaskCodec.decode(TaskCodec.encode(listOf(done)))[0])
    }

    @Test
    fun roundTripKeepsNoDueDate() {
        val noDue = sample.copy(due = null, from = "")
        val decoded = TaskCodec.decode(TaskCodec.encode(listOf(noDue)))[0]
        assertEquals(null, decoded.due)
        assertEquals("", decoded.from)
    }

    @Test
    fun blankTextIsEmptyList() {
        assertEquals(0, TaskCodec.decode("").size)
        assertEquals(0, TaskCodec.decode("   ").size)
    }

    @Test
    fun unknownStateFallsBackToOpen() {
        val raw = JSONArray().put(
            JSONObject()
                .put("id", "x")
                .put("text", "来路不明的事")
                .put("state", "SOMETHING_NEW")
                .put("createdAt", "2026-10-11T02:00:00Z")
        ).toString()
        assertEquals(TaskState.OPEN, TaskCodec.decode(raw)[0].state)
    }

    @Test
    fun brokenEntryDoesNotKillTheRest() {
        val arr = JSONArray()
        arr.put(JSONObject().put("text", "没有 id 的一条").put("createdAt", "2026-10-11T02:00:00Z"))
        arr.put(
            JSONObject()
                .put("id", "ok")
                .put("text", "好的那条")
                .put("due", "不是日期")
                .put("createdAt", "2026-10-11T02:00:00Z")
        )
        val decoded = TaskCodec.decode(arr.toString())
        assertEquals(1, decoded.size)
        assertEquals("好的那条", decoded[0].text)
        assertEquals(null, decoded[0].due)
    }

    @Test
    fun garbageThrowsSoCallerCanFallBackToBackup() {
        try {
            TaskCodec.decode("{ 这不是 JSON")
            fail("坏数据应该抛异常，交给 TaskStore 退回备份")
        } catch (expected: Exception) {
            // 预期如此
        }
    }
}
