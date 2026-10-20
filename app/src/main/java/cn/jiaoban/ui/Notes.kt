package cn.jiaoban.ui

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.jiaoban.media.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

internal fun mediaFailureMessage(error: Throwable): String = when(error) {
    is java.io.IOException, is SecurityException -> "附件未能保存，请检查可用空间后重试；原文件未改动。"
    else -> error.message?.takeIf { !it.contains('/') && !it.contains('\\') && it.length <= 100 }
        ?: "附件添加失败，请重试；原文件未改动。"
}

internal val mediaCopySessions = mutableStateMapOf<String,Boolean>()

@Composable internal fun FullPage(onDismiss:()->Unit,content:@Composable ()->Unit) {
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        val view=LocalView.current
        val dark=isSystemInDarkTheme()
        SideEffect {(view.parent as? DialogWindowProvider)?.window?.let { window ->
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            WindowCompat.setDecorFitsSystemWindows(window,false)
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            view.post {WindowCompat.getInsetsController(window,view).apply{isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark}}
        }}
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {content()}
    }
}

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable internal fun NoteEditor(note:Task?,initial:String,initialDate:LocalDate?=null,session:String="editor",newId:String?=null,saving:Boolean=false,onDismiss:()->Unit,onSave:(String,LocalDate?,List<ImportedAttachment>)->Unit) {
    val context=LocalContext.current
    val persisted=remember{context.getSharedPreferences("editor_draft_v1",android.content.Context.MODE_PRIVATE)}
    val restoring=persisted.getString("session",null)==session
    val baseline=remember(session){note?.text.orEmpty()}
    val baselineDate=remember(session){note?.journalDate?.toString()}
    var value by rememberSaveable(session,stateSaver=TextFieldValue.Saver){mutableStateOf(TextFieldValue(if(restoring)persisted.getString("text","").orEmpty() else note?.text ?: initial))}
    var dateText by rememberSaveable(session){mutableStateOf(if(restoring)persisted.getString("date",null) else initialDate?.toString())}
    val scope=rememberCoroutineScope()
    var mediaError by remember { mutableStateOf<String?>(null) }
    var draftWriteFailed by remember(session) { mutableStateOf(false) }
    val copying=mediaCopySessions[session]==true
    var attachments by remember(session) { mutableStateOf(if(restoring) runCatching { AttachmentDraft.decode(persisted.getString("attachments",null)) }.getOrElse { mediaError="附件草稿无法读取，请保留资料并重试"; note?.attachments.orEmpty() } else note?.attachments.orEmpty()) }
    fun persistEditor() {
        runCatching { AttachmentDraft.open(persisted,session,note?.id,newId,value.text,dateText,attachments) }
            .onSuccess { draftWriteFailed=false }
            .onFailure { draftWriteFailed=true; mediaError=it.message }
    }
    SideEffect { persistEditor() }
    DisposableEffect(persisted,session) {
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
            if(key=="attachments" && persisted.getString("session",null)==session) runCatching { AttachmentDraft.decode(persisted.getString("attachments",null)) }.onSuccess { attachments=it }
        }
        persisted.registerOnSharedPreferenceChangeListener(listener)
        onDispose { persisted.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var playing by remember { mutableStateOf<String?>(null) }
    fun retainMedia(added:List<ImportedAttachment>) {
        attachments=AttachmentDraft.append(persisted,session,added)
    }
    val voice=remember(session) { VoiceCapture(context,session) { file ->
        // Finalized originals stay in the recording journal even if copying or draft persistence fails.
        val attachment=file.inputStream().use { MediaStorage.copy(context.filesDir,it,file.name,"audio/mp4") }
        retainMedia(listOf(attachment))
    } }
    val voiceState by voice.state.collectAsState()
    val recording=voiceState.phase==VoiceCapturePhase.RECORDING
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val microphoneRequest=remember(session) { MicrophoneStartRequest() }
    DisposableEffect(voice,lifecycle) {
        voice.recover()
        val observer=LifecycleEventObserver { _,event ->
            if(event==Lifecycle.Event.ON_STOP) { microphoneRequest.cancel(); voice.interrupt("已离开录音页，录音已暂停；请手动继续") }
            if(event==Lifecycle.Event.ON_RESUME && microphoneRequest.resumed(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)) { mediaError=null; playing=null; voice.start() }
        }
        lifecycle.addObserver(observer)
        onDispose { microphoneRequest.cancel(); lifecycle.removeObserver(observer); voice.close() }
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) {
            mediaError=null
            if(microphoneRequest.granted(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))) { playing=null; voice.start() }
        } else { microphoneRequest.cancel(); mediaError="未开启麦克风，可重试授权或到系统设置开启；文字和照片仍可保存。" }
    }
    fun record() {
        playing=null; mediaError=null
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) voice.start()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    val choose=rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if(uris.isNotEmpty()) {
            mediaCopySessions[session]=true; mediaError=null
            scope.launch { withContext(NonCancellable) {
                try {
                    // Commit each finished copy so a later failed selection never loses earlier items.
                    for(uri in uris) {
                        val result=withContext(NonCancellable+Dispatchers.IO) { runCatching {
                            val resolver=context.contentResolver
                            val name=resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst())it.getString(0) else null } ?: "媒体附件"
                            val mime=resolver.getType(uri) ?: throw IllegalArgumentException("无法识别媒体格式")
                            val added=resolver.openInputStream(uri)?.use { MediaStorage.copy(context.filesDir,it,name,mime) } ?: throw IllegalStateException("无法打开媒体")
                            // Finish the current file and commit its ID even across Activity recreation.
                            // An explicitly discarded/replaced session must never receive a late attachment.
                            AttachmentDraft.append(persisted,session,listOf(added))
                            added
                        } }
                        result.onFailure { mediaError=mediaFailureMessage(it) }
                    }
                } finally { mediaCopySessions.remove(session) }
            } }
        }
    }
    val diary=dateText!=null
    var datePicker by remember{mutableStateOf(false)}
    var discard by rememberSaveable{mutableStateOf(false)}
    val dirty=if(note==null)value.text.isNotBlank() || attachments.isNotEmpty() else value.text!=baseline || dateText!=baselineDate || attachments!=note.attachments
    fun close(){if(copying || saving)return; voice.stop(); if(dirty || recording)discard=true else onDismiss()}
    val focus=remember{FocusRequester()}
    FullPage(::close) {
        LaunchedEffect(Unit){if(note==null && initial.isEmpty()){withFrameNanos{};focus.requestFocus()}}
        Scaffold(contentWindowInsets=WindowInsets.safeDrawing,containerColor=MaterialTheme.colorScheme.background,
            topBar={TopAppBar(title={Text(if(diary) "写日记" else if(note==null)"写一条记录" else "编辑记录",style=MaterialTheme.typography.titleMedium)},
                navigationIcon={IconButton(onClick={close()}){Icon(Icons.Default.ArrowBack,"关闭笔记")}},
                actions={TextButton(onClick={onSave(value.text,dateText?.let{LocalDate.parse(it)},attachments)},enabled=!saving && !copying && !recording && !voiceState.attachmentSaveFailed && !draftWriteFailed && mediaError!="附件草稿无法读取，请保留资料并重试" && (value.text.isNotBlank() || attachments.isNotEmpty())){Text(if(saving)"保存中…" else "保存",fontWeight=FontWeight.SemiBold)}},
                colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background))}) {padding->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                if(diary) TextButton(onClick={datePicker=true},modifier=Modifier.padding(horizontal=16.dp)) {
                    Icon(Icons.Default.DateRange,null,Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(dateText.orEmpty()+" · 更改日期")
                }

                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                TextField(value,{value=it},enabled=!saving,placeholder={Text(if(diary) "今天有哪些想留住的细节？" else "现在的想法是……",lineHeight=32.sp)},
                    modifier=Modifier.fillMaxWidth().heightIn(min=160.dp).padding(horizontal=8.dp).focusRequester(focus).semantics { contentDescription="记录正文" },
                    textStyle=MaterialTheme.typography.bodyLarge.copy(fontSize=18.sp,lineHeight=30.sp),
                    colors=TextFieldDefaults.colors(focusedContainerColor=MaterialTheme.colorScheme.background,unfocusedContainerColor=MaterialTheme.colorScheme.background,
                        focusedIndicatorColor=androidx.compose.ui.graphics.Color.Transparent,unfocusedIndicatorColor=androidx.compose.ui.graphics.Color.Transparent))
                attachments.forEach { attachment ->
                    Column(Modifier.padding(horizontal=20.dp,vertical=8.dp)) {
                        MediaAttachment(attachment,playing==attachment.relativePath,{ if(!recording)playing=attachment.relativePath },{playing=null})
                        TextButton(onClick={runCatching { val next=attachments.filterNot{it.relativePath==attachment.relativePath}; AttachmentDraft.replace(persisted,session,next);attachments=next }.onFailure{mediaError=it.message}},enabled=!saving && !copying && !recording) { Text("从这一页移除") }
                    }
                }
                if(copying)LinearProgressIndicator(Modifier.fillMaxWidth())
                mediaError?.let { Text(it,Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) }
                if(mediaError?.startsWith("未开启麦克风")==true) TextButton(onClick={
                    context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:"+context.packageName)))
                }) { Text("打开权限设置") }
                voiceState.message?.let { Text(it,Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.bodySmall) }
                if(draftWriteFailed)TextButton(onClick={persistEditor()}){Text("重试保存草稿")}
                if(voiceState.attachmentSaveFailed)TextButton(onClick={voice.recover()}){Text("恢复已完成录音")}
                }
                FlowRow(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(0.dp)) {
                    if(recording) {
                        TextButton(onClick={voice.pause()}){Text("暂停录音")}
                        TextButton(onClick={voice.stop()}){Text("结束录音")}
                    } else {
                        TextButton(onClick={record()},enabled=!saving && !copying){Text(if(voiceState.phase==VoiceCapturePhase.PAUSED || voiceState.phase==VoiceCapturePhase.INTERRUPTED)"继续录音" else "录音")}
                        TextButton(onClick={choose.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))},enabled=!saving && !copying){Text("照片 / 视频")}
                    }
                }
                if(WindowInsets.ime.getBottom(LocalDensity.current)==0) {
                Text(if(recording)"正在录音 · 每30秒保留一段，离开或锁屏会暂停" else "原媒体仅存本机 · 录音离开页面会暂停",Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(if(diary) "这一页，只为自己" else "留一点空白给想法",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f));Text("${value.text.length} 字",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
            if(note.attachments.isNotEmpty()) Text("${note.attachments.count{it.mimeType.startsWith("audio/")}} 段录音 · ${note.attachments.count{it.mimeType.startsWith("image/")}} 张照片 · ${note.attachments.count{it.mimeType.startsWith("video/")}} 个视频",style=MaterialTheme.typography.labelMedium,color=c.primary)
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
@Composable internal fun NoteDetail(note:Task,pin:()->Unit,archive:()->Unit,onUpdate:(Task)->Unit,allowPlayback:Boolean=true) {
    val context=LocalContext.current
    var confirm by remember{mutableStateOf(false)}
    var playing by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(allowPlayback){if(!allowPlayback)playing=null}
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
            MediaAttachment(attachment,allowPlayback && playing==attachment.relativePath,{if(allowPlayback)playing=attachment.relativePath},{playing=null})
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

