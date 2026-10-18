package cn.jiaoban

import java.time.LocalDate
import java.time.ZoneId

fun actionGrowth(tasks:List<Task>,today:LocalDate):GrowthStats {
    val done=tasks.filter{it.kind!=EntryKind.NOTE && it.state==TaskState.DONE}
    val from=GrowthRules.weekStart(today)
    val week=done.count{task->task.closedAt?.atZone(ZoneId.systemDefault())?.toLocalDate()?.let{it>=from && it<=today}==true}
    return GrowthStats("行动成长","把每一件小事，做成向前的一步",listOf(
        GrowthMetric("done","完成事项",done.size.toLong(),listOf(1,10,50,100),listOf("第一步完成","十事有成","行动派","百事达成"),"件"),
        GrowthMetric("notes","保存笔记",tasks.count{it.kind==EntryKind.NOTE}.toLong(),listOf(1,10,30),listOf("灵感落纸","随手记作者","自己的小书"),"篇"),
        GrowthMetric("followup","主动跟进",tasks.sumOf{it.followUps.count{f->f.kind in listOf(FollowUpKind.NOTE,FollowUpKind.PROGRESS)}}.toLong(),listOf(3,10,30),listOf("有始有终","跟进有方","可靠的伙伴"),"次"),
        GrowthMetric("personal","完成个人待办",done.count{it.kind==EntryKind.TODO}.toLong(),listOf(3,10,30),listOf("为自己行动","计划践行者","日常掌舵人"),"件")
    ),week.toLong(),"件完成",listOf(3,7,15),done.filter{it.closedAt!=null}.sortedByDescending{it.closedAt}.take(3).map{it.text})
}
