package cn.jiaoban

import java.time.LocalDate

/** UI projections leave legacy kind, expiry and source data untouched. */
fun todoRecords(tasks: List<Task>) = tasks.filter { it.kind != EntryKind.NOTE }
fun orderedTodos(tasks: List<Task>, today: LocalDate) = activeTasks(todoRecords(tasks),today)
    .sortedWith(compareByDescending<Task>{it.important}.thenBy{it.due ?: LocalDate.MAX}.thenByDescending{it.createdAt})
fun completedRecords(tasks: List<Task>) = todoRecords(tasks).filter{it.state==TaskState.DONE}.sortedByDescending{it.closedAt}
fun Task.addStep(text: String, id: String): Task = if(text.isBlank() || steps.any{it.id==id})this
    else copy(steps=steps+TaskStep(id,text.trim()))
fun Task.toggleStep(id: String) = copy(steps=steps.map{if(it.id==id)it.copy(done=!it.done) else it})
fun noteTitle(text: String) = text.lineSequence().firstOrNull{it.isNotBlank()}?.trim().orEmpty()
fun noteExcerpt(text: String, query: String = ""): String {
    if(query.isBlank()) return text.substringAfter('\n',"").trim()
    val start=text.indexOf(query.trim(),ignoreCase=true)
    return if(start<0)text else text.substring((start-28).coerceAtLeast(0)).take(180)
}
fun toggleNoteLine(text: String, index: Int): String = text.split('\n').mapIndexed { i,line ->
    if(i!=index)line else when {
        line.startsWith("- [ ] ")->line.replaceFirst("- [ ] ","- [x] ")
        line.startsWith("- [x] ")->line.replaceFirst("- [x] ","- [ ] ")
        else->line
    }
}.joinToString("\n")

data class ChecklistEdit(val text: String, val cursor: Int)

/** Transform whole selected lines; never replace or split selected content. */
fun insertChecklist(text: String, selectionStart: Int, selectionEnd: Int): ChecklistEdit {
    val start=minOf(selectionStart,selectionEnd).coerceIn(0,text.length)
    val end=maxOf(selectionStart,selectionEnd).coerceIn(start,text.length)
    val lineStart=if(start==0)0 else text.lastIndexOf('\n',start-1)+1
    val lastSelected=if(end>start)end-1 else end
    val lineEnd=text.indexOf('\n',lastSelected).let{if(it<0)text.length else it}
    val block=text.substring(lineStart,lineEnd).split('\n').joinToString("\n") {line ->
        if(line.startsWith("- [ ] ") || line.startsWith("- [x] "))line else "- [ ] $line"
    }
    return ChecklistEdit(text.substring(0,lineStart)+block+text.substring(lineEnd),lineStart+block.length)
}
