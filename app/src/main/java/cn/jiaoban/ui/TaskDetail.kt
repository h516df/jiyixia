package cn.jiaoban.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.jiaoban.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TaskDetail(task: Task, today: LocalDate, onStage: (WorkStage) -> Unit,
                        onNote: (String) -> Unit, onState: (TaskState) -> Unit, onNoteEditing: (Boolean) -> Unit) {
    var addingNote by rememberSaveable(task.id) { mutableStateOf(false) }
    var draft by rememberSaveable(task.id) { mutableStateOf("") }
    var discard by remember { mutableStateOf(false) }
    var voidConfirm by remember { mutableStateOf(false) }
    var metadata by rememberSaveable(task.id){mutableStateOf(false)}
    LaunchedEffect(addingNote) { onNoteEditing(addingNote) }
    DisposableEffect(task.id) { onDispose { onNoteEditing(false) } }
    val colors = MaterialTheme.colorScheme
    val open = task.state == TaskState.OPEN
    val status = if(open) task.workStage.label else if(task.state == TaskState.DONE) "已办完" else "已作废"
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(color = colors.primaryContainer,shape = RoundedCornerShape(6.dp)) {
                        Text(status,Modifier.padding(horizontal = 10.dp,vertical = 6.dp),style = MaterialTheme.typography.labelMedium,color = colors.primary)
                    }
                    if(isExpired(task,today)) Text("到期失效",Modifier.padding(vertical = 6.dp),style = MaterialTheme.typography.labelMedium,color = colors.onSurfaceVariant)
                }
                Text(task.text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text((if(task.kind==EntryKind.DELEGATED)personLabel(task.from)+" · " else "")+recordMeta(task,today),style=MaterialTheme.typography.bodyMedium,color=if(open && bucketOf(task,today)==Bucket.OVERDUE)colors.error else colors.onSurfaceVariant)
                TextButton(onClick={metadata=!metadata},contentPadding=PaddingValues(0.dp)){Text(if(metadata)"收起详情" else "期限与规则")}
                AnimatedVisibility(metadata) {Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    if(task.kind==EntryKind.DELEGATED)DetailField("交代人",personLabel(task.from),Icons.Default.Person)
                    DetailField("期限",task.due?.let{"${it.year}年${it.monthValue}月${it.dayOfMonth}日"} ?: "没有期限",Icons.Default.DateRange)
                    DetailField("到期规则",if(task.expiryPolicy==ExpiryPolicy.MUST_FINISH)"逾期继续跟进" else "到期自动收起",Icons.Default.Info)
                }}
                HorizontalDivider(color=colors.outlineVariant)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("办理进展", style = MaterialTheme.typography.titleMedium,fontWeight = FontWeight.SemiBold)
                if(open) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if(task.workStage != WorkStage.WORKING) OutlinedButton(onClick = { onStage(WorkStage.WORKING) }) {
                            Text(if(task.workStage == WorkStage.WAITING) "继续办理" else "开始办")
                        }
                        if(task.workStage != WorkStage.WAITING) OutlinedButton(onClick = { onStage(WorkStage.WAITING) }) { Text("等回复") }
                        Button(onClick = { onState(TaskState.DONE) }) { Text("办完了") }
                    }

                } else OutlinedButton(onClick = { onState(TaskState.OPEN) }, modifier = Modifier.fillMaxWidth()) { Text("恢复为待办") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("跟进记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { addingNote = true }) { Icon(Icons.Default.Add,null,Modifier.size(18.dp)); Text("记进展") }
            }
            if(task.followUps.none { it.kind == FollowUpKind.NOTE }) Text("还没有跟进记录",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        items(task.followUps.asReversed(),key = { it.id }) { event ->
            TimelineEntry(event.text, event.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm")),
                event.kind == FollowUpKind.NOTE)
        }
        item { TimelineEntry(if(task.kind==EntryKind.TODO)"创建待办" else "收到交代",task.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")),false) }
        if(open) item { TextButton(onClick = { voidConfirm = true }) { Text("作废这件事",color = colors.error) } }
    }
    if(addingNote) ModalBottomSheet(onDismissRequest = { if(draft.isNotBlank()) discard = true else addingNote = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = {
            if(it == SheetValue.Hidden && draft.isNotBlank()) { discard = true; false } else true
        })) {
        Column(Modifier.fillMaxWidth().imePadding().padding(20.dp),verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("记一条进展",style = MaterialTheme.typography.titleLarge,fontWeight = FontWeight.SemiBold)
            OutlinedTextField(draft,{ draft = it }, label = { Text("这次办到哪里了") },
                placeholder = { Text("例如：活动表已发给王总，等确认") },minLines = 3,maxLines = 7,modifier = Modifier.fillMaxWidth())
            Button(onClick = { onNote(draft); draft = ""; addingNote = false },enabled = draft.isNotBlank(),modifier = Modifier.fillMaxWidth()) { Text("保存进展") }
        }
    }
    if(discard) AlertDialog(onDismissRequest = { discard = false },title = { Text("进展还未保存") },
        text = { Text("继续填写，或放弃这条进展。") },
        confirmButton = { TextButton(onClick = { discard = false }) { Text("继续填写") } },
        dismissButton = { TextButton(onClick = { discard = false; draft = ""; addingNote = false }) { Text("放弃进展") } })
    if(voidConfirm) AlertDialog(onDismissRequest = { voidConfirm = false },title = { Text("作废这件事？") },
        text = { Text("会收进历史，跟进记录仍保留，之后可以恢复。") },
        confirmButton = { TextButton(onClick = { voidConfirm = false; onState(TaskState.VOID) }) { Text("确认作废") } },
        dismissButton = { TextButton(onClick = { voidConfirm = false }) { Text("继续保留") } })
}

@Composable private fun DetailField(label: String,value: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon,null,Modifier.size(20.dp),tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label,style = MaterialTheme.typography.labelMedium,color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value,style = MaterialTheme.typography.bodyMedium)
        }
    }
}
@Composable private fun TimelineEntry(text: String,time: String,note: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(if(note) Icons.Default.Edit else Icons.Default.Check,null,Modifier.size(18.dp),tint = MaterialTheme.colorScheme.primary)
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(text,style = if(note) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium)
            Text(time,style = MaterialTheme.typography.labelSmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
