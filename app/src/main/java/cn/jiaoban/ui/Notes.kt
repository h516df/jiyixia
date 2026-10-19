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
import androidx.compose.ui.platform.LocalDensity
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
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZoneId
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
@Composable internal fun NoteEditor(note:Task?,initial:String,initialDate:LocalDate?=null,session:String="editor",newId:String?=null,saving:Boolean=false,onDismiss:()->Unit,onSave:(String,LocalDate?)->Unit) {
    val context=LocalContext.current
    val persisted=remember{context.getSharedPreferences("editor_draft_v1",android.content.Context.MODE_PRIVATE)}
    val restoring=persisted.getString("session",null)==session
    val baseline=remember(session){note?.text.orEmpty()}
    val baselineDate=remember(session){note?.journalDate?.toString()}
    var value by rememberSaveable(session,stateSaver=TextFieldValue.Saver){mutableStateOf(TextFieldValue(if(restoring)persisted.getString("text","").orEmpty() else note?.text ?: initial))}
    var dateText by rememberSaveable(session){mutableStateOf(if(restoring)persisted.getString("date",null) else initialDate?.toString())}
    SideEffect {
        if(persisted.getString("completed",null)!=session) persisted.edit().putBoolean("active",true).putString("session",session)
            .putString("existingId",note?.id).putString("newId",newId)
            .putString("text",value.text).putString("date",dateText).apply()
    }
    val diary=dateText!=null
    var datePicker by remember{mutableStateOf(false)}
    var discard by rememberSaveable{mutableStateOf(false)}
    val dirty=if(note==null)value.text.isNotBlank() else value.text!=baseline || dateText!=baselineDate
    fun close(){if(dirty)discard=true else onDismiss()}
    val focus=remember{FocusRequester()}
    FullPage(::close) {
        LaunchedEffect(Unit){if(note==null && initial.isEmpty()){withFrameNanos{};focus.requestFocus()}}
        Scaffold(contentWindowInsets=WindowInsets.safeDrawing,containerColor=MaterialTheme.colorScheme.background,
            topBar={TopAppBar(title={Text(if(diary) "写日记" else if(note==null)"写一条记录" else "编辑记录",style=MaterialTheme.typography.titleMedium)},
                navigationIcon={IconButton(onClick={close()}){Icon(Icons.Default.ArrowBack,"关闭笔记")}},
                actions={TextButton(onClick={onSave(value.text,dateText?.let{LocalDate.parse(it)})},enabled=!saving && value.text.isNotBlank()){Text(if(saving)"保存中…" else "保存",fontWeight=FontWeight.SemiBold)}},
                colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background))}) {padding->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                if(diary) TextButton(onClick={datePicker=true},modifier=Modifier.padding(horizontal=16.dp)) {
                    Icon(Icons.Default.DateRange,null,Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(dateText.orEmpty()+" · 更改日期")
                }

                TextField(value,{value=it},enabled=!saving,placeholder={Text(if(diary) "今天有哪些想留住的细节？" else "现在的想法是……",lineHeight=32.sp)},
                    modifier=Modifier.weight(1f).fillMaxWidth().padding(horizontal=8.dp).focusRequester(focus),
                    textStyle=MaterialTheme.typography.bodyLarge.copy(fontSize=18.sp,lineHeight=30.sp),
                    colors=TextFieldDefaults.colors(focusedContainerColor=MaterialTheme.colorScheme.background,unfocusedContainerColor=MaterialTheme.colorScheme.background,
                        focusedIndicatorColor=androidx.compose.ui.graphics.Color.Transparent,unfocusedIndicatorColor=androidx.compose.ui.graphics.Color.Transparent))
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(if(diary) "这一页，只为自己" else "留一点空白给想法",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f));Text("${value.text.length} 字",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if(datePicker) {
            val picker=rememberDatePickerState(initialSelectedDateMillis=LocalDate.parse(dateText).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(onDismissRequest={datePicker=false},confirmButton={TextButton(onClick={picker.selectedDateMillis?.let{dateText=Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()};datePicker=false}){Text("确定")}},dismissButton={TextButton(onClick={datePicker=false}){Text("取消")}}) {DatePicker(picker)}
        }
        if(discard)AlertDialog(onDismissRequest={discard=false},title={Text("笔记还未保存")},text={Text("继续写，或放弃这次改动。")},
            confirmButton={TextButton(onClick={discard=false}){Text("继续编辑")}},dismissButton={TextButton(onClick={discard=false;onDismiss()}){Text("放弃改动")}})
    }
}

/** Body-first records; no title field, extraction, or prerequisite classification. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun PaperEntry(note:Task,open:(Task)->Unit,query:String="",onTag:((String)->Unit)?=null) {
    val c=MaterialTheme.colorScheme
    Surface(onClick={open(note)},shape=RoundedCornerShape(16.dp),color=c.surface) {
        Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(note.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日  HH:mm"))+if(note.state!=TaskState.OPEN)" · 已归档" else "",fontSize=12.sp,color=c.onSurfaceVariant)
            Text(note.text,fontSize=17.sp,lineHeight=28.sp,maxLines=7,overflow=TextOverflow.Ellipsis)
            val tags=writingTags(note.text)
            if(tags.isNotEmpty() && onTag!=null)FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                tags.forEach {tag->SuggestionChip(onClick={onTag(tag)},label={Text("#$tag",fontSize=12.sp)},border=null,colors=SuggestionChipDefaults.suggestionChipColors(containerColor=c.primaryContainer.copy(alpha=.5f)))}
            }
        }
    }
}

@Composable internal fun QuickComposer(text:String,onText:(String)->Unit,posting:Boolean,ready:Boolean,onSave:()->Unit) {
    val c=MaterialTheme.colorScheme
    Surface(color=c.background,tonalElevation=0.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp)) {
            OutlinedTextField(text,onText,enabled=ready && !posting,placeholder={Text("记下一点想法…",fontSize=16.sp)},
                minLines=1,maxLines=4,modifier=Modifier.fillMaxWidth().semantics{contentDescription="随手记录正文"},shape=RoundedCornerShape(18.dp),
                textStyle=MaterialTheme.typography.bodyLarge.copy(fontSize=17.sp,lineHeight=27.sp),
                colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=c.primary,unfocusedBorderColor=c.outlineVariant,focusedContainerColor=c.surface,unfocusedContainerColor=c.surface))
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={onText(text+if(text.isEmpty() || text.last().isWhitespace())"#" else " #")},enabled=ready && !posting){Text("# 标签")}
                Spacer(Modifier.weight(1f))
                TextButton(onClick=onSave,enabled=ready && !posting && text.isNotBlank()){Text(if(posting)"保存中…" else "记下",fontWeight=FontWeight.SemiBold)}
            }
        }
    }
}

@Composable internal fun WritingEmpty(title:String,subtitle:String) {
    val c=MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical=48.dp,horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(15.dp)) {
        Canvas(Modifier.size(64.dp)) {
            drawRoundRect(c.primaryContainer,cornerRadius=androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()))
            drawLine(c.primary,androidx.compose.ui.geometry.Offset(20.dp.toPx(),20.dp.toPx()),androidx.compose.ui.geometry.Offset(44.dp.toPx(),20.dp.toPx()),2.dp.toPx())
            drawLine(c.primary,androidx.compose.ui.geometry.Offset(20.dp.toPx(),30.dp.toPx()),androidx.compose.ui.geometry.Offset(40.dp.toPx(),30.dp.toPx()),2.dp.toPx())
            drawLine(c.primary,androidx.compose.ui.geometry.Offset(20.dp.toPx(),40.dp.toPx()),androidx.compose.ui.geometry.Offset(32.dp.toPx(),40.dp.toPx()),2.dp.toPx())
        }
        Text(title,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Medium)
        Text(subtitle,style=MaterialTheme.typography.bodyMedium,lineHeight=24.sp,color=c.onSurfaceVariant)
    }
}

@Composable internal fun NotesPage(notes:List<Task>,open:(Task)->Unit,query:String="",searching:Boolean=false,selectedTag:String?=null,onTag:(String?)->Unit={},savedGeneration:Int=0,onReview:()->Unit={}) {
    val c=MaterialTheme.colorScheme
    var archived by rememberSaveable{mutableStateOf(false)}
    val listState=androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(savedGeneration){if(savedGeneration>0){archived=false;listState.scrollToItem(0)}}
    val shown=notes.filter{searching || (it.state!=TaskState.OPEN)==archived}.sortedByDescending{it.createdAt}
    LazyColumn(Modifier.fillMaxSize(),state=listState,contentPadding=PaddingValues(start=18.dp,end=18.dp,top=4.dp,bottom=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(!searching)item {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick=onReview,modifier=Modifier.weight(1f)){Text("翻一条旧记录",fontSize=13.sp)}
                TextButton(onClick={archived=!archived}){Text(if(archived)"返回记录" else "归档",fontSize=13.sp)}
            }
        }
        if(selectedTag!=null)item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("#$selectedTag",color=c.primary,modifier=Modifier.weight(1f));TextButton(onClick={onTag(null)}){Text("清除筛选")}}}
        if(archived && !searching)item{Text("已归档 · 内容完整保留",style=MaterialTheme.typography.labelMedium,color=c.onSurfaceVariant)}
        if(shown.isEmpty())item{WritingEmpty(if(searching || selectedTag!=null)"没有找到这段文字" else if(archived)"还没有归档的记录" else "想到什么，就记下来",if(searching || selectedTag!=null)"换个关键词，或清除标签筛选。" else "不必起标题，也不必想好放在哪里。")}
        items(shown,key={it.id}){note->PaperEntry(note,open,query){onTag(it)}}
    }
}

@Composable internal fun JournalPage(notes:List<Task>,today:LocalDate,open:(Task)->Unit,searching:Boolean=false) {
    val c=MaterialTheme.colorScheme
    var archived by rememberSaveable{mutableStateOf(false)}
    val shown=notes.filter{searching || (it.state!=TaskState.OPEN)==archived}.sortedWith(compareByDescending<Task>{writingDay(it)}.thenByDescending{it.createdAt})
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=22.dp,end=22.dp,top=18.dp,bottom=110.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(!searching)item {
            Surface(shape=RoundedCornerShape(22.dp),color=c.primaryContainer) {
                if(LocalDensity.current.fontScale>1.3f) Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("${today.year} / ${today.monthValue} / ${today.dayOfMonth}",fontSize=20.sp,lineHeight=28.sp,color=c.onPrimaryContainer)
                    Text("今天",fontSize=13.sp,color=c.onPrimaryContainer)
                } else Row(Modifier.fillMaxWidth().padding(22.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                    Column {Text("${today.dayOfMonth}".padStart(2,'0'),fontSize=42.sp,lineHeight=48.sp,fontWeight=FontWeight.Light,color=c.onPrimaryContainer);Text("${today.monthValue}月 · ${today.year}",fontSize=12.sp,color=c.onPrimaryContainer)}
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {Text("日子有迹可循",fontSize=19.sp,fontWeight=FontWeight.Medium,color=c.onPrimaryContainer);Text("不必特别，也值得记下。",fontSize=13.sp,lineHeight=21.sp,color=c.onPrimaryContainer)}
                }
            }
            Row(Modifier.fillMaxWidth().padding(top=20.dp),verticalAlignment=Alignment.CenterVertically) {Text(if(archived)"归档日记" else "时光里的几页",fontSize=18.sp,fontWeight=FontWeight.Medium,modifier=Modifier.weight(1f));TextButton(onClick={archived=!archived}){Text(if(archived)"返回日记" else "归档",fontSize=13.sp)}}
        }
        if(shown.isEmpty())item{WritingEmpty(if(searching)"没有找到这段文字" else if(archived)"还没有归档的日记" else "今天，从这一页开始",if(searching)"换个关键词，再找找。" else "写下今天，也可以补记从前的一天。")}
        shown.groupBy{writingDay(it)}.forEach{(date,group)->
            item(key="date-$date"){Text(date?.format(DateTimeFormatter.ofPattern("yyyy年 M月d日"))?:"",style=MaterialTheme.typography.labelMedium,color=c.primary,modifier=Modifier.padding(top=12.dp,bottom=2.dp))}
            items(group,key={it.id}){note->PaperEntry(note,open)}
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun NoteDetail(note:Task,pin:()->Unit,archive:()->Unit,onUpdate:(Task)->Unit) {
    val context=LocalContext.current
    var confirm by remember{mutableStateOf(false)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(26.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item {Text(note.journalDate?.format(DateTimeFormatter.ofPattern("yyyy年 M月d日 · 日记")) ?: note.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy年 M月d日 · 笔记")),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)}
        item {FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton(onClick={context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,note.text),"分享文字"))}){Text("分享")}
            TextButton(onClick={confirm=true}){Text(if(note.state==TaskState.OPEN)"归档" else "恢复")}
        }}
        // Legacy checklist source remains literal, selectable text. No task controls.
        note.text.split('\n').forEachIndexed{index,line->item(key=index) {
            SelectionContainer {Text(line,style=MaterialTheme.typography.bodyLarge,
                fontWeight=FontWeight.Normal,lineHeight=30.sp)}
        }}
    }
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text(if(note.state==TaskState.OPEN)"归档这一页？" else "恢复这一页？")},text={Text("内容完整保留，可在对应页面的归档中找回。")},confirmButton={TextButton(onClick={confirm=false;archive()}){Text("确定")}},dismissButton={TextButton(onClick={confirm=false}){Text("取消")}})
}
