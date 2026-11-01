package cn.jiaoban.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.jiaoban.Task
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A secondary data-management page, never a third main tab. */
@Composable
internal fun TrashPage(
    entries:List<Task>,
    open:(Task)->Unit,
    restore:(Set<String>)->Unit,
    purge:(Set<String>)->Unit,
) {
    var chosen by remember { mutableStateOf<Set<String>>(emptySet()) }
    val available=entries.filter { it.deletedAt!=null }.sortedByDescending { it.deletedAt }
    LaunchedEffect(available.map { it.id }) { chosen=chosen.intersect(available.map { it.id }.toSet()) }
    fun toggle(entry:Task) { chosen=if(entry.id in chosen)chosen-entry.id else chosen+entry.id }
    Column(Modifier.fillMaxSize()) {
        Text("删除的内容保留30天，到期后会在下次打开应用时清理。",
            Modifier.padding(horizontal=22.dp,vertical=16.dp),
            style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(available.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
            WritingEmpty("回收站是空的","删除的记录和日记会先保存在这里。")
        } else LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(horizontal=16.dp,vertical=8.dp),
            verticalArrangement=Arrangement.spacedBy(6.dp)) {
            items(available,key={it.id}) { item ->
                Column {
                    Text("删除于 "+item.deletedAt!!.atZone(ZoneId.systemDefault()).format(
                        DateTimeFormatter.ofPattern("M月d日 HH:mm")),
                        Modifier.padding(horizontal=12.dp,vertical=4.dp),
                        style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    PaperEntry(item,open,selected=item.id in chosen,selectionMode=chosen.isNotEmpty(),
                        onLongPress=::toggle,onSelect=::toggle)
                }
            }
        }
        if(chosen.isNotEmpty()) {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("已选 ${chosen.size}",Modifier.weight(1f),style=MaterialTheme.typography.labelMedium)
                TextButton(onClick={chosen=emptySet()}) { Text("取消") }
                Button(onClick={restore(chosen);chosen=emptySet()}) { Text("恢复") }
                OutlinedButton(onClick={purge(chosen);chosen=emptySet()}) { Text("彻底删除") }
            }
        }
    }
}
