package cn.jiaoban.data

import cn.jiaoban.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class FollowUpCodecTest {
    private val now=Instant.parse("2026-10-13T03:30:00Z")
    private val task=Task("legacy","王总让周五交活动表","王总",LocalDate.of(2026,10,16),createdAt=now,
        expiryPolicy=ExpiryPolicy.MUST_FINISH)
    @Test fun roundTripKeepsStageAndAllProgressEvents() {
        val changed=task.withWorkStage(WorkStage.WORKING,now).withFollowUp("已发活动表\n等确认😀",now.plusSeconds(5))
            .withWorkStage(WorkStage.WAITING,now.plusSeconds(10)).withState(TaskState.DONE,now.plusSeconds(15))
        assertEquals(changed,TaskCodec.decode(TaskCodec.encode(listOf(changed))).single())
    }
    @Test fun oldJsonKeepsEveryOldFieldAndDefaultsOnlyNewFields() {
        val obj=JSONObject(TaskCodec.encode(listOf(task)).let { JSONArray(it).getJSONObject(0).toString() })
        obj.remove("workStage");obj.remove("followUps")
        assertEquals(task,TaskCodec.decode(JSONArray().put(obj).toString()).single())
    }
    @Test fun unknownStageFallsBackWithoutDroppingTheTask() {
        val obj=JSONArray(TaskCodec.encode(listOf(task))).getJSONObject(0)
        obj.put("workStage","UNKNOWN")
        assertEquals(WorkStage.RECEIVED,TaskCodec.decode(JSONArray().put(obj).toString()).single().workStage)
    }
    @Test fun brokenEventDoesNotDropGoodEventsOrTheRecord() {
        val changed=task.withFollowUp("价格已核对😀",now)
        val obj=JSONArray(TaskCodec.encode(listOf(changed))).getJSONObject(0)
        obj.getJSONArray("followUps").put(JSONObject().put("text","坏时间").put("createdAt","not-a-date").put("id","bad"))
        assertEquals(changed,TaskCodec.decode(JSONArray().put(obj).toString()).single())
    }
}
