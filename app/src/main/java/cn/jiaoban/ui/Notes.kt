package cn.jiaoban.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.jiaoban.Task
import cn.jiaoban.TaskState
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable internal fun NotesPage(notes:List<Task>,open:(Task)->Unit) {
    val colors=MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=24.dp,end=24.dp,bottom=100.dp)) {
        item {Text("${notes.size} 篇笔记",Modifier.padding(vertical=16.dp),style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)}
        if(notes.isEmpty()) item {Column(Modifier.padding(vertical=60.dp)) {Text("给想法留个位置",style=MaterialTheme.typography.headlineSmall);Text("灵感、素材、复盘，都可以从第一行开始。",Modifier.padding(top=12.dp),color=colors.onSurfaceVariant)}}
        items(notes.sortedWith(compareByDescending<Task>{it.pinned}.thenByDescending{it.createdAt}),key={it.id}) {note->
            Column(Modifier.fillMaxWidth().animateItem().clickable{open(note)}.padding(vertical=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if(note.pinned) Text("置顶",style=MaterialTheme.typography.labelSmall,color=colors.primary)
                Text(note.text.lineSequence().first(),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                val body=note.text.substringAfter('\n',"")
                if(body.isNotBlank())Text(body,style=MaterialTheme.typography.bodyMedium,color=colors.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(note.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日"))+" · ${note.text.length} 字",style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
            }
            HorizontalDivider(color=colors.outlineVariant)
        }
    }
}

@Composable internal fun NoteDetail(note:Task,pin:()->Unit,archive:()->Unit) {
    val context=LocalContext.current
    var confirm by remember {mutableStateOf(false)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(note.pinned,pin,label={Text(if(note.pinned)"已置顶" else "置顶")})
            TextButton(onClick={context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,note.text),"分享笔记"))}){Text("分享")}
            TextButton(onClick={confirm=true}){Text(if(note.state==TaskState.OPEN) "归档" else "恢复笔记")}
        }}
        item {SelectionContainer {Column(verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Text(note.text.lineSequence().first(),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold)
            val body=note.text.substringAfter('\n',"")
            if(body.isNotBlank())Text(body,style=MaterialTheme.typography.bodyLarge)
        }}}
        item {Text("${note.text.length} 字",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text(if(note.state==TaskState.OPEN)"归档这篇笔记？" else "恢复这篇笔记？")},text={Text("内容会完整保留，可在历史与归档中找回。")},confirmButton={TextButton(onClick={confirm=false;archive()}){Text("确定")}},dismissButton={TextButton(onClick={confirm=false}){Text("取消")}})
}
