package cn.jiaoban.ui

import android.content.Intent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import cn.jiaoban.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable internal fun FullPage(onDismiss:()->Unit,content:@Composable ()->Unit) {
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        val view=LocalView.current
        val dark=isSystemInDarkTheme()
        SideEffect {(view.parent as? DialogWindowProvider)?.window?.let { window ->
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            WindowCompat.setDecorFitsSystemWindows(window,false)
            view.post {WindowCompat.getInsetsController(window,view).apply{isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark}}
        }}
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {content()}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun NoteEditor(note:Task?,initial:String,onDismiss:()->Unit,onSave:(String)->Unit) {
    var value by rememberSaveable(note?.id,initial,stateSaver=TextFieldValue.Saver){mutableStateOf(TextFieldValue(note?.text ?: initial))}
    var discard by rememberSaveable{mutableStateOf(false)}
    val dirty=if(note==null)value.text.isNotBlank() else value.text!=note.text
    fun close(){if(dirty)discard=true else onDismiss()}
    val focus=remember{FocusRequester()}
    FullPage(::close) {
        Scaffold(contentWindowInsets=WindowInsets.safeDrawing,containerColor=MaterialTheme.colorScheme.background,
            topBar={TopAppBar(title={Text(if(note==null)"新建笔记" else "编辑笔记",style=MaterialTheme.typography.titleMedium)},
                navigationIcon={IconButton(onClick={close()}){Icon(Icons.Default.ArrowBack,"关闭笔记")}},
                actions={TextButton(onClick={onSave(value.text)},enabled=value.text.isNotBlank()){Text("保存笔记",fontWeight=FontWeight.SemiBold)}},
                colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background))}) {padding->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                TextField(value,{value=it},placeholder={Text("标题\n\n写下想法、素材或一段日常。",lineHeight=32.sp)},
                    modifier=Modifier.weight(1f).fillMaxWidth().padding(horizontal=8.dp).focusRequester(focus),
                    textStyle=MaterialTheme.typography.bodyLarge.copy(fontSize=18.sp,lineHeight=30.sp),
                    colors=TextFieldDefaults.colors(focusedContainerColor=MaterialTheme.colorScheme.background,unfocusedContainerColor=MaterialTheme.colorScheme.background,
                        focusedIndicatorColor=androidx.compose.ui.graphics.Color.Transparent,unfocusedIndicatorColor=androidx.compose.ui.graphics.Color.Transparent))
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={
                        val edit=insertChecklist(value.text,value.selection.start,value.selection.end)
                        value=TextFieldValue(edit.text,TextRange(edit.cursor));focus.requestFocus()
                    }){Icon(Icons.Default.Check,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("清单")}
                    Spacer(Modifier.weight(1f));Text("${value.text.length} 字",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if(discard)AlertDialog(onDismissRequest={discard=false},title={Text("笔记还未保存")},text={Text("继续写，或放弃这次改动。")},
            confirmButton={TextButton(onClick={discard=false}){Text("继续编辑")}},dismissButton={TextButton(onClick={discard=false;onDismiss()}){Text("放弃改动")}})
    }
    LaunchedEffect(Unit){if(note==null && initial.isEmpty())focus.requestFocus()}
}

@Composable internal fun NotesPage(notes:List<Task>,open:(Task)->Unit,query:String="",searching:Boolean=false) {
    val colors=MaterialTheme.colorScheme
    var archived by rememberSaveable{mutableStateOf(false)}
    val shown=notes.filter{searching || (it.state!=TaskState.OPEN)==archived}.sortedWith(compareByDescending<Task>{it.pinned}.thenByDescending{it.createdAt})
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=24.dp,end=24.dp,bottom=100.dp)) {
        if(!searching)item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(!archived,{archived=false},label={Text("全部笔记")})
            FilterChip(archived,{archived=true},label={Text("已归档")})
        }}
        if(shown.isEmpty())item {Column(Modifier.padding(vertical=56.dp)) {
            Text(if(searching)"没有找到这段文字" else if(archived)"还没有归档的笔记" else "给想法留个位置",style=MaterialTheme.typography.headlineSmall)
            Text(if(searching)"换个关键词试试" else "文字留在这里，需要时随时找回。",Modifier.padding(top=12.dp),color=colors.onSurfaceVariant)
        }}
        items(shown,key={it.id}) {note->
            Column(Modifier.fillMaxWidth().animateItem().clickable{open(note)}.padding(vertical=22.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                if(note.pinned)Text("置顶",style=MaterialTheme.typography.labelSmall,color=colors.primary)
                Text(noteTitle(note.text),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                val body=noteExcerpt(note.text,query)
                if(body.isNotBlank())Text(body,style=MaterialTheme.typography.bodyMedium,color=colors.onSurfaceVariant,maxLines=3,overflow=TextOverflow.Ellipsis)
                Text(note.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日"))+if(note.state!=TaskState.OPEN)" · 已归档" else "",style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
            }
            HorizontalDivider(color=colors.outlineVariant.copy(alpha=.6f))
        }
    }
}

@Composable internal fun NoteDetail(note:Task,pin:()->Unit,archive:()->Unit,onUpdate:(Task)->Unit) {
    val context=LocalContext.current
    var confirm by remember{mutableStateOf(false)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(note.pinned,pin,label={Text(if(note.pinned)"已置顶" else "置顶")})
            TextButton(onClick={context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,note.text),"分享笔记"))}){Text("分享")}
            TextButton(onClick={confirm=true}){Text(if(note.state==TaskState.OPEN)"归档" else "恢复笔记")}
        }}
        val lines=note.text.split('\n')
        lines.forEachIndexed{index,line->item(key=index) {
            if(line.startsWith("- [ ] ") || line.startsWith("- [x] ")) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Checkbox(line.startsWith("- [x] "),{onUpdate(note.copy(text=toggleNoteLine(note.text,index)))},
                        modifier=Modifier.semantics{contentDescription="清单第${index+1}行：${line.drop(6)}"})
                    SelectionContainer {Text(line.drop(6),style=MaterialTheme.typography.bodyLarge)}
                }
            } else SelectionContainer {Text(line,style=if(index==0)MaterialTheme.typography.headlineMedium else MaterialTheme.typography.bodyLarge,
                fontWeight=if(index==0)FontWeight.SemiBold else FontWeight.Normal,lineHeight=if(index==0)36.sp else 28.sp)}
        }}
    }
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text(if(note.state==TaskState.OPEN)"归档这篇笔记？" else "恢复这篇笔记？")},text={Text("内容完整保留，可在笔记的已归档中找回。")},confirmButton={TextButton(onClick={confirm=false;archive()}){Text("确定")}},dismissButton={TextButton(onClick={confirm=false}){Text("取消")}})
}
