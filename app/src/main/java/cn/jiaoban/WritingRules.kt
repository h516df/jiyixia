package cn.jiaoban

import java.time.LocalDate
import java.time.ZoneId

/** Single-level tags must start at the beginning or after whitespace; URL fragments stay text. */
private val writingTagPattern = Regex("(?:^|\\s)#([\\p{L}\\p{N}_]+)(?![/\\p{L}\\p{N}_])")
fun writingTags(text:String):List<String> = writingTagPattern.findAll(text).map{it.groupValues[1]}.distinct().toList()
fun writingDay(entry:Task,zone:ZoneId=ZoneId.systemDefault()):LocalDate = entry.journalDate ?: entry.importInfo?.originalDate?.take(10)?.let{runCatching{LocalDate.parse(it)}.getOrNull()} ?: entry.createdAt.atZone(zone).toLocalDate()
fun writingMatches(entry:Task,query:String,tag:String?):Boolean =
    matches(entry,query) && (tag==null || tag in (writingTags(entry.text)+entry.importedTags))
fun reviewEntries(entries:List<Task>,today:LocalDate,zone:ZoneId=ZoneId.systemDefault()):List<Task> =
    entries.filter{it.kind==EntryKind.NOTE && it.deletedAt==null && it.state==TaskState.OPEN && writingDay(it,zone).isBefore(today)}.sortedBy{it.createdAt}

fun writingSortKey(entry:Task):String = entry.importInfo?.originalDate ?: entry.createdAt.atZone(ZoneId.systemDefault()).toLocalDateTime().toString()
