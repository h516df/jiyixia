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
            val stamp=note.importInfo?.originalDate?.replace('T',' ') ?: (if(note.importInfo!=null)"导入于 " else "")+note.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日  HH:mm"))
            Text(stamp+(if(note.state!=TaskState.OPEN)" · 已归档" else ""),fontSize=12.sp,color=c.onSurfaceVariant)
            Text(note.text,fontSize=17.sp,lineHeight=28.sp,maxLines=7,overflow=TextOverflow.Ellipsis)
            val tags=(writingTags(note.text)+note.importedTags).distinct()
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
    val shown=notes.filter{searching || (it.state!=TaskState.OPEN)==archived}.sortedByDescending{writingSortKey(it)}
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun JournalPage(notes:List<Task>,today:LocalDate,open:(Task)->Unit,searching:Boolean=false,onWrite:(LocalDate)->Unit={}) {
    val c=MaterialTheme.colorScheme
    var archived by rememberSaveable{mutableStateOf(false)}
    var selectedDay by rememberSaveable{mutableStateOf(today.toString())}
    val day=LocalDate.parse(selectedDay)
    var pickerVisible by remember{mutableStateOf(false)}
    var showFragments by rememberSaveable(selectedDay){mutableStateOf(false)}
    val diaries=notes.filter{it.journalDate!=null && (searching || (it.state!=TaskState.OPEN)==archived)}
    val shown=diaries.filter{searching || it.journalDate==day}.sortedByDescending{writingSortKey(it)}
    val fragments=notes.filter{it.journalDate==null && it.state==TaskState.OPEN && writingDay(it)==day}.sortedBy{it.createdAt}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=22.dp,end=22.dp,top=12.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        if(!searching)item {
            Text("把一天，留成一页",fontSize=24.sp,lineHeight=34.sp,fontWeight=FontWeight.Medium)
            Text("写下自己的回想，也可以补记从前。",fontSize=13.sp,lineHeight=22.sp,color=c.onSurfaceVariant)
            Surface(shape=RoundedCornerShape(20.dp),color=c.primaryContainer,modifier=Modifier.padding(top=18.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick={pickerVisible=true},modifier=Modifier.align(Alignment.CenterHorizontally)){
                        Text(day.format(DateTimeFormatter.ofPattern("yyyy年 M月d日")),fontSize=18.sp,color=c.onPrimaryContainer)
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                        IconButton(onClick={selectedDay=day.minusDays(1).toString()}){Icon(Icons.Default.KeyboardArrowLeft,"上一天")}
                        TextButton(onClick={selectedDay=today.toString()}){Text("今天")}
                        IconButton(onClick={selectedDay=day.plusDays(1).toString()}){Icon(Icons.Default.KeyboardArrowRight,"下一天")}
                    }
                }
            }
        }
        if(!searching)item {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text(if(archived)"归档日记" else if(day==today)"今日小结" else "这一天的小结",fontSize=18.sp,modifier=Modifier.weight(1f))
                TextButton(onClick={archived=!archived}){Text(if(archived)"返回日记" else "归档")}
            }
            if(!archived) FilledTonalButton(onClick={onWrite(day)},modifier=Modifier.fillMaxWidth().semantics{contentDescription="写日记"}){
                Icon(Icons.Default.Add,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(if(shown.isEmpty())"写下这一天" else "再写一篇")
            }
        }
        if(shown.isEmpty())item {
            Text(if(searching)"没有找到日记，换个关键词试试。" else if(archived)"这一天没有归档日记。" else "还没写也没关系。一两句话，就能留下今天的感受。",fontSize=14.sp,lineHeight=25.sp,color=c.onSurfaceVariant,modifier=Modifier.padding(vertical=14.dp))
        }
        items(shown,key={it.id}){PaperEntry(it,open)}
        if(!searching && !archived)item {
            HorizontalDivider(color=c.outlineVariant)
            TextButton(onClick={showFragments=!showFragments},modifier=Modifier.fillMaxWidth()){
                Text("这天 ${fragments.size} 条记录 · ${if(showFragments)"收起参考" else "展开参考"}",fontSize=13.sp)
            }
            if(showFragments && fragments.isEmpty())Text("这天没有碎片记录，也可以直接写日记。",fontSize=13.sp,lineHeight=23.sp,color=c.onSurfaceVariant)
        }
        if(!searching && !archived && showFragments)items(fragments,key={"fragment-${it.id}"}){PaperEntry(it,open)}
    }
    if(pickerVisible) {
        val picker=rememberDatePickerState(initialSelectedDateMillis=day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest={pickerVisible=false},confirmButton={TextButton(onClick={picker.selectedDateMillis?.let{selectedDay=Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()};pickerVisible=false}){Text("确定")}},dismissButton={TextButton(onClick={pickerVisible=false}){Text("取消")}}){DatePicker(picker)}
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
        note.importInfo?.let{source->item{Text("来源：${source.format}\n${source.originalPath}"+(source.originalDate?.let{"\n原日期：$it"} ?: "\n原日期未提供"),fontSize=12.sp,lineHeight=21.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
        if(note.importedTags.isNotEmpty())item{Text(note.importedTags.joinToString("  "){"#$it"},fontSize=13.sp,color=MaterialTheme.colorScheme.primary)}
        if(note.attachments.isNotEmpty())item{Text("附件与原文件",style=MaterialTheme.typography.titleSmall)}
        items(note.attachments,key={it.relativePath}){attachment->
            OutlinedButton(onClick={
                runCatching {
                    require(attachment.relativePath.matches(Regex("imports/[a-f0-9]{64}(?:\\.[a-z0-9]{1,8})?")))
                    val file=java.io.File(context.filesDir,attachment.relativePath)
                    val uri=androidx.core.content.FileProvider.getUriForFile(context,"cn.jiaoban.imports",file)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri,attachment.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"打开文件"))
                }.onFailure{android.widget.Toast.makeText(context,"无法打开附件，请先导出完整资料检查原文件",android.widget.Toast.LENGTH_LONG).show()}
            },modifier=Modifier.fillMaxWidth()){Text((if(attachment.sourceFile)"原文件 · " else "附件 · ")+attachment.name,maxLines=2)}
        }
        // Legacy checklist source remains literal, selectable text. No task controls.
        note.text.split('\n').forEachIndexed{index,line->item(key=index) {
            SelectionContainer {Text(line,style=MaterialTheme.typography.bodyLarge,
                fontWeight=FontWeight.Normal,lineHeight=30.sp)}
        }}
    }
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text(if(note.state==TaskState.OPEN)"归档这一页？" else "恢复这一页？")},text={Text("内容完整保留，可在对应页面的归档中找回。")},confirmButton={TextButton(onClick={confirm=false;archive()}){Text("确定")}},dismissButton={TextButton(onClick={confirm=false}){Text("取消")}})
}
