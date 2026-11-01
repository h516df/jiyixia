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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.abs

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
@Composable internal fun NoteEditor(note:Task?,initial:String,initialDate:LocalDate?=null,session:String="editor",newId:String?=null,saving:Boolean=false,onDismiss:()->Unit,onSave:(String,LocalDate?,List<ImportedAttachment>)->Unit,openMediaPickerOnLaunch:Boolean=false) {
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
    var selectedPhoto by remember { mutableStateOf<String?>(null) }
    var showRecordingFiles by rememberSaveable(session) { mutableStateOf(false) }
    fun retainMedia(added:List<ImportedAttachment>) {
        attachments=AttachmentDraft.append(persisted,session,added)
    }
    val voice=remember(session) { VoiceCapture(context,session) { file ->
        // Finalized originals stay in the recording journal even if copying or draft persistence fails.
        val attachment=file.inputStream().use { MediaStorage.copy(context.filesDir,it,file.name,"audio/mp4") }
        retainMedia(listOf(attachment))
    } }
    val voiceState by voice.state.collectAsState()
    val globalVoiceState by VoiceSessionController.state.collectAsState()
    // A foreground voice session owns the microphone until its durable note has been saved.
    // The legacy in-editor recorder remains available for old drafts, but never competes with it.
    val globalVoicePending = globalVoiceState.sessionId != null && globalVoiceState.phase != VoiceCapturePhase.IDLE
    val latestGlobalVoicePending = rememberUpdatedState(globalVoicePending)
    val recording=voiceState.phase==VoiceCapturePhase.RECORDING
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val microphoneRequest=remember(session) { MicrophoneStartRequest() }
    DisposableEffect(voice,lifecycle) {
        voice.recover()
        val observer=LifecycleEventObserver { _,event ->
            if(event==Lifecycle.Event.ON_STOP) { microphoneRequest.cancel(); voice.interrupt("已离开录音页，录音已暂停；请手动继续") }
            if(event==Lifecycle.Event.ON_RESUME && microphoneRequest.resumed(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) && !latestGlobalVoicePending.value) { mediaError=null; playing=null; voice.start() }
        }
        lifecycle.addObserver(observer)
        onDispose { microphoneRequest.cancel(); lifecycle.removeObserver(observer); voice.close() }
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) {
            mediaError=null
            if(microphoneRequest.granted(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) && !globalVoicePending) { playing=null; voice.start() }
        } else { microphoneRequest.cancel(); mediaError="未开启麦克风，可重试授权或到系统设置开启；文字和照片仍可保存。" }
    }
    fun record() {
        if(globalVoicePending) { mediaError="有一条语音录音或草稿尚未保存，请先完成它。"; return }
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
    var launchedMediaPicker by rememberSaveable(session) { mutableStateOf(false) }
    LaunchedEffect(session,openMediaPickerOnLaunch) {
        if(openMediaPickerOnLaunch && !launchedMediaPicker) {
            launchedMediaPicker=true
            choose.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }
    }
    val diary=dateText!=null
    var datePicker by remember{mutableStateOf(false)}
    var discard by rememberSaveable{mutableStateOf(false)}
    val dirty=if(note==null)value.text.isNotBlank() || attachments.isNotEmpty() else value.text!=baseline || dateText!=baselineDate || attachments!=note.attachments
    fun close(){if(copying || saving)return; voice.stop(); if(dirty || recording)discard=true else onDismiss()}
    val focus=remember{FocusRequester()}
    FullPage(::close) {
        val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        LaunchedEffect(session){if(!openMediaPickerOnLaunch && note==null){withFrameNanos{};focus.requestFocus();keyboard?.show()}}
        Scaffold(contentWindowInsets=WindowInsets.safeDrawing,containerColor=MaterialTheme.colorScheme.background,
            topBar={TopAppBar(title={Text(if(diary) dateText.orEmpty() else java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日")),style=MaterialTheme.typography.titleMedium)},
                navigationIcon={IconButton(onClick={close()}){Icon(Icons.Default.ArrowBack,"关闭笔记")}},
                actions={TextButton(onClick={onSave(value.text,dateText?.let{LocalDate.parse(it)},attachments)},enabled=!saving && !copying && !recording && !voiceState.attachmentSaveFailed && !draftWriteFailed && mediaError!="附件草稿无法读取，请保留资料并重试" && (value.text.isNotBlank() || attachments.isNotEmpty())){Text(if(saving)"保存中…" else "完成",fontWeight=FontWeight.SemiBold)}},
                colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background))}) {padding->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                if(diary) TextButton(onClick={datePicker=true},modifier=Modifier.padding(horizontal=16.dp)) {
                    Icon(Icons.Default.DateRange,null,Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(dateText.orEmpty()+" · 更改日期")
                }

                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                TextField(value,{value=it},enabled=!saving,placeholder={Text(if(diary) "写下今天…" else "开始记录…")},
                    modifier=Modifier.fillMaxWidth().heightIn(min=160.dp).padding(horizontal=8.dp).focusRequester(focus).semantics { contentDescription="记录正文" },
                    textStyle=MaterialTheme.typography.bodyLarge.copy(fontSize=17.sp,lineHeight=26.sp),
                    colors=TextFieldDefaults.colors(focusedContainerColor=MaterialTheme.colorScheme.background,unfocusedContainerColor=MaterialTheme.colorScheme.background,
                        focusedIndicatorColor=androidx.compose.ui.graphics.Color.Transparent,unfocusedIndicatorColor=androidx.compose.ui.graphics.Color.Transparent))
                val audioAttachments=attachments.filter{it.mimeType.startsWith("audio/")}
                if(audioAttachments.isNotEmpty())Column(Modifier.padding(horizontal=20.dp,vertical=8.dp)) {
                    AudioRecordingGroup(audioAttachments,playing=="editor-audio-group",{if(!recording)playing="editor-audio-group"},{playing=null})
                    TextButton(onClick={showRecordingFiles=!showRecordingFiles}) {
                        Text(if(showRecordingFiles)"收起录音原始文件" else "管理录音原始文件（${audioAttachments.size}）")
                    }
                    if(showRecordingFiles)audioAttachments.forEach { attachment ->
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            Text(attachment.name,style=MaterialTheme.typography.labelMedium,modifier=Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis)
                            TextButton(onClick={
                                playing=null
                                runCatching { val next=attachments.filterNot{it.relativePath==attachment.relativePath}; AttachmentDraft.replace(persisted,session,next);attachments=next }.onFailure{mediaError=it.message}
                            },enabled=!saving && !copying && !recording){Text("移除")}
                        }
                    }
                }
                attachments.filterNot{it.mimeType.startsWith("audio/")}.forEach { attachment ->
                    Column(Modifier.padding(horizontal=20.dp,vertical=8.dp)) {
                        MediaAttachment(attachment,playing==attachment.relativePath,{ if(!recording)playing=attachment.relativePath },{playing=null},
                            onImageTap=if(attachment.mimeType.startsWith("image/")) { { selectedPhoto=attachment.relativePath } } else null)
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
                Row(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=3.dp),verticalAlignment=Alignment.CenterVertically) {
                    if(recording) {
                        TextButton(onClick={voice.pause()}){Text("暂停")}
                        TextButton(onClick={voice.stop()}){Text("结束")}
                    } else {
                        IconButton(onClick={choose.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))},enabled=!saving && !copying) {
                            Icon(Icons.Default.Add,"添加照片或视频")
                        }
                        IconButton(onClick={record()},enabled=!saving && !copying && !globalVoicePending,
                            modifier=Modifier.semantics{contentDescription=if(voiceState.phase==VoiceCapturePhase.PAUSED || voiceState.phase==VoiceCapturePhase.INTERRUPTED)"继续录音" else "录音"}) {
                            MicGlyph()
                        }
                    }
                }
                if(globalVoicePending && !recording) Text("录音进行中或有待保存的语音，请先结束并保存；当前文字与照片仍可编辑。",Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if(datePicker) {
            val picker=rememberDatePickerState(initialSelectedDateMillis=LocalDate.parse(dateText).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(onDismissRequest={datePicker=false},confirmButton={TextButton(onClick={picker.selectedDateMillis?.let{dateText=Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()};datePicker=false}){Text("确定")}},dismissButton={TextButton(onClick={datePicker=false}){Text("取消")}}) {DatePicker(picker)}
        }
        if(discard)AlertDialog(onDismissRequest={discard=false},title={Text("笔记还未保存")},text={Text("继续写，或放弃这次改动。")},
            confirmButton={TextButton(onClick={discard=false}){Text("继续编辑")}},dismissButton={TextButton(onClick={discard=false;onDismiss()}){Text("放弃改动")}})
        selectedPhoto?.let { path -> PhotoViewer(attachments.filter{it.mimeType.startsWith("image/")},path){selectedPhoto=null} }
    }
}

/** Body-first records; no title field, extraction, or prerequisite classification. */
@OptIn(ExperimentalLayoutApi::class,ExperimentalFoundationApi::class,ExperimentalMaterial3Api::class)
@Composable internal fun PaperEntry(note:Task,open:(Task)->Unit,query:String="",onTag:((String)->Unit)?=null,
    selected:Boolean=false,selectionMode:Boolean=false,onLongPress:((Task)->Unit)?=null,onSelect:((Task)->Unit)?=null,
    onSwipeDelete:((Task)->Unit)?=null) {
    val c=MaterialTheme.colorScheme
    val haptic=LocalHapticFeedback.current
    val swipeState=rememberSwipeToDismissBoxState(
        confirmValueChange={ destination ->
            if(destination==SwipeToDismissBoxValue.EndToStart && !selectionMode && onSwipeDelete!=null && note.deletedAt==null) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onSwipeDelete(note)
            }
            // The storage write is asynchronous. Keep the item until it actually succeeds;
            // failed saves must not remove it from the screen.
            false
        },
        positionalThreshold={distance->distance*.38f}
    )
    SwipeToDismissBox(state=swipeState,modifier=Modifier.testTag("entry-${note.id}"),
        enableDismissFromStartToEnd=false,
        enableDismissFromEndToStart=onSwipeDelete!=null && !selectionMode && note.deletedAt==null,
        backgroundContent={
            Box(Modifier.fillMaxSize().padding(vertical=4.dp)
                .background(c.errorContainer.copy(alpha=.65f),RoundedCornerShape(14.dp))
                .padding(end=22.dp),contentAlignment=Alignment.CenterEnd) {
                Icon(Icons.Default.Delete,"松手移入回收站",tint=c.error)
            }
        }) {
    Surface(modifier=Modifier.fillMaxWidth().combinedClickable(
        onClick={if(selectionMode)onSelect?.invoke(note) else open(note)},
        onLongClick=if(onLongPress!=null)({onLongPress(note)}) else null
    ),shape=RoundedCornerShape(8.dp),color=if(selected)c.primaryContainer.copy(alpha=.65f) else c.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            val stamp=note.importInfo?.originalDate?.replace('T',' ') ?: (if(note.importInfo!=null)"导入于 " else "")+note.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                if(selectionMode) {
                    if(selected) Icon(Icons.Default.CheckCircle,"已选择",tint=c.primary,
                        modifier=Modifier.padding(end=12.dp).size(22.dp))
                    else Text("○",color=c.onSurfaceVariant,modifier=Modifier.padding(end=12.dp),fontSize=24.sp)
                }
                Text(stamp,style=MaterialTheme.typography.labelMedium,color=c.onSurfaceVariant,modifier=Modifier.weight(1f))
                if(note.pinned && note.deletedAt==null)Text("置顶",style=MaterialTheme.typography.labelSmall,color=c.primary)
                if(note.journalDate!=null)Text("日记",style=MaterialTheme.typography.labelSmall,color=c.primary,modifier=Modifier.padding(start=8.dp,end=8.dp))
                if(note.state!=TaskState.OPEN)Text("已归档",style=MaterialTheme.typography.labelSmall,color=c.onSurfaceVariant)
            }
            if(note.text.isNotBlank())Text(note.text,style=MaterialTheme.typography.bodyLarge,maxLines=4,overflow=TextOverflow.Ellipsis)
            val voiceCount=note.attachments.count{it.mimeType.startsWith("audio/")}
            TimelineMediaPreviews(note.attachments)
            if(voiceCount>0)TimelineAudioSummary(note.attachments.filter{it.mimeType.startsWith("audio/")})
            val tags=(writingTags(note.text)+note.importedTags).distinct()
            if(!selectionMode && tags.isNotEmpty() && onTag!=null)FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                tags.forEach {tag->SuggestionChip(onClick={onTag(tag)},label={Text("#$tag",style=MaterialTheme.typography.labelMedium)},border=null,colors=SuggestionChipDefaults.suggestionChipColors(containerColor=c.primaryContainer.copy(alpha=.5f)))}
            }
            HorizontalDivider(color=c.outlineVariant.copy(alpha=.7f))
        }
    }
    }
}

@Composable internal fun QuickComposer(
    text:String,
    onText:(String)->Unit,
    posting:Boolean,
    ready:Boolean,
    onSave:()->Unit,
    onRecord:()->Unit={},
    onMedia:()->Unit={},
) {
    val c=MaterialTheme.colorScheme
    Surface(color=c.background,tonalElevation=0.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                IconButton(onClick=onMedia,enabled=ready && !posting,modifier=Modifier.size(48.dp).semantics { contentDescription="添加照片或视频" }) {
                    Icon(Icons.Default.Add,null,tint=c.primary)
                }
                OutlinedTextField(text,onText,enabled=ready && !posting,placeholder={Text(if(LocalDensity.current.fontScale>1.3f) "记点什么…" else "记下一点想法…",fontSize=15.sp)},
                    minLines=1,maxLines=3,modifier=Modifier.weight(1f).semantics{contentDescription="随手记录正文"},shape=RoundedCornerShape(18.dp),
                    textStyle=MaterialTheme.typography.bodyLarge.copy(fontSize=16.sp,lineHeight=25.sp),
                    colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=c.primary,unfocusedBorderColor=c.outlineVariant,focusedContainerColor=c.surface,unfocusedContainerColor=c.surface))
                IconButton(onClick=onRecord,enabled=ready && !posting,modifier=Modifier.size(48.dp).semantics { contentDescription="开始录音" }) {
                    MicGlyph(color=c.primary)
                }
            }
            if(text.isNotBlank()) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={onText(text+if(text.lastOrNull()?.isWhitespace()!=false)"#" else " #")},enabled=ready && !posting){Text("# 标签",fontSize=13.sp)}
                Spacer(Modifier.weight(1f))
                Button(onClick=onSave,enabled=ready && !posting){Text(if(posting)"保存中…" else "记下",fontWeight=FontWeight.Medium)}
            }
        }
    }
}

@Composable internal fun WritingEmpty(title:String,subtitle:String) {
    val c=MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical=58.dp,horizontal=18.dp),
        verticalArrangement=Arrangement.spacedBy(6.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Text(title,style=MaterialTheme.typography.bodyLarge,color=c.onSurfaceVariant)
        if(subtitle.isNotBlank())Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=c.onSurfaceVariant)
    }
}

@Composable internal fun NotesPage(notes:List<Task>,open:(Task)->Unit,query:String="",searching:Boolean=false,selectedTag:String?=null,onTag:(String?)->Unit={},savedGeneration:Int=0,onReview:()->Unit={},
    selectedIds:Set<String> = emptySet(),onSelect:(Task)->Unit={},onSwipeDelete:((Task)->Unit)?=null) {
    val c=MaterialTheme.colorScheme
    var archived by rememberSaveable{mutableStateOf(false)}
    var actionMenu by remember {mutableStateOf(false)}
    var searchType by rememberSaveable { mutableStateOf("全部") }
    val listState=androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(savedGeneration){if(savedGeneration>0){archived=false;listState.scrollToItem(0)}}
    val shown=notes.filter{entry ->
        (searching || (entry.state!=TaskState.OPEN)==archived) &&
        (!searching || when(searchType) {
            "记录" -> entry.journalDate==null
            "日记" -> entry.journalDate!=null
            "图片" -> entry.attachments.any{it.mimeType.startsWith("image/")}
            "视频" -> entry.attachments.any{it.mimeType.startsWith("video/")}
            "录音" -> entry.attachments.any{it.mimeType.startsWith("audio/")}
            else -> true
        })
    }.sortedByDescending{writingSortKey(it)}
    val pinned=if(archived)emptyList() else shown.filter{it.pinned}
    val regular=shown.filterNot{it in pinned}
    LazyColumn(Modifier.fillMaxSize(),state=listState,contentPadding=PaddingValues(start=18.dp,end=18.dp,top=4.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        if(searching)item {
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(4.dp),
                modifier=Modifier.padding(bottom=12.dp)) {
                listOf("全部","记录","日记","图片","视频","录音").forEach{type->
                    FilterChip(selected=searchType==type,onClick={searchType=type},label={Text(type)})
                }
            }
        }
        if(!searching)item {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                if(archived)Text("已归档",style=MaterialTheme.typography.labelLarge,color=c.onSurfaceVariant,modifier=Modifier.weight(1f))
                else Spacer(Modifier.weight(1f))
                if(archived) TextButton(onClick={archived=false}) {Text("返回记录")}
                else Box {
                    IconButton(onClick={actionMenu=true}) {Icon(Icons.Default.MoreVert,"更多记录操作")}
                    DropdownMenu(expanded=actionMenu,onDismissRequest={actionMenu=false}) {
                        DropdownMenuItem(text={Text("随机回看")},onClick={actionMenu=false;onReview()})
                        DropdownMenuItem(text={Text("查看归档")},onClick={actionMenu=false;archived=true})
                    }
                }
            }
        }
        if(selectedTag!=null)item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("#$selectedTag",color=c.primary,modifier=Modifier.weight(1f));TextButton(onClick={onTag(null)}){Text("清除筛选")}}}
        if(shown.isEmpty())item{WritingEmpty(if(searching || selectedTag!=null)"没有找到记录" else if(archived)"暂无归档" else "写下第一条记录","")}
        if(pinned.isNotEmpty()) {
            item {Text("置顶",style=MaterialTheme.typography.labelLarge,color=c.primary,modifier=Modifier.padding(start=4.dp,top=10.dp))}
            items(pinned,key={it.id}){note->PaperEntry(note,open,query,{onTag(it)},note.id in selectedIds,selectedIds.isNotEmpty(),onSelect,onSelect,onSwipeDelete)}
        }
        regular.groupBy { writingDay(it) }.forEach { (day,entries) ->
            item(key="date-$day") {
                Text(when(day) {
                    LocalDate.now() -> "今天"
                    LocalDate.now().minusDays(1) -> "昨天"
                    else -> day.format(DateTimeFormatter.ofPattern("M月d日 · EEEE"))
                },style=MaterialTheme.typography.labelLarge,color=c.onSurfaceVariant,modifier=Modifier.padding(start=4.dp,top=14.dp,bottom=3.dp))
            }
            items(entries,key={it.id}){note->PaperEntry(note,open,query,{onTag(it)},note.id in selectedIds,selectedIds.isNotEmpty(),onSelect,onSelect,onSwipeDelete)}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("UNUSED_PARAMETER") // The primary diary write action is anchored in App's bottom bar.
@Composable internal fun JournalPage(
    notes:List<Task>,today:LocalDate,open:(Task)->Unit,searching:Boolean=false,
    onWrite:(LocalDate)->Unit={},selectedDay:LocalDate=today,onSelectDay:(LocalDate)->Unit={},
    archived:Boolean=false,onArchivedChange:(Boolean)->Unit={}
    ,selectedIds:Set<String> = emptySet(),onSelect:(Task)->Unit={},onSwipeDelete:((Task)->Unit)?=null
) {
    val c=MaterialTheme.colorScheme
    var pickerVisible by remember{mutableStateOf(false)}
    var showFragments by rememberSaveable(selectedDay.toString()){mutableStateOf(false)}
    val diaries=notes.filter{it.journalDate!=null && (searching || (it.state!=TaskState.OPEN)==archived)}
    val shown=diaries.filter{searching || it.journalDate==selectedDay}.sortedByDescending{writingSortKey(it)}
    val fragments=notes.filter{it.journalDate==null && it.state==TaskState.OPEN && writingDay(it)==selectedDay}.sortedBy{it.createdAt}
    val startOfWeek=selectedDay.minusDays((selectedDay.dayOfWeek.value-1).toLong())
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=18.dp,end=18.dp,top=12.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        if(!searching)item {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={pickerVisible=true},modifier=Modifier.weight(1f)) {
                        Text(selectedDay.format(DateTimeFormatter.ofPattern("yyyy年M月")),style=MaterialTheme.typography.titleMedium,color=c.onSurface)
                        Icon(Icons.Default.KeyboardArrowDown,"选择日期",Modifier.size(18.dp))
                    }
                    IconButton(onClick={onSelectDay(selectedDay.minusDays(1))}) {Icon(Icons.Default.KeyboardArrowLeft,"上一天")}
                    IconButton(onClick={onSelectDay(selectedDay.plusDays(1))}) {Icon(Icons.Default.KeyboardArrowRight,"下一天")}
                }
                var weekDrag by remember { mutableFloatStateOf(0f) }
                Row(Modifier.fillMaxWidth().pointerInput(selectedDay) {
                    detectHorizontalDragGestures(onDragStart={weekDrag=0f},onDragEnd={
                        if(abs(weekDrag)>48.dp.toPx()) onSelectDay(selectedDay.plusDays(if(weekDrag<0)7 else -7))
                        weekDrag=0f
                    },onHorizontalDrag={change,dragAmount->change.consume();weekDrag+=dragAmount})
                },horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                    val weekdays=listOf("一","二","三","四","五","六","日")
                    repeat(7){index->
                        val day=startOfWeek.plusDays(index.toLong())
                        val hasDiary=notes.any{it.journalDate==day && it.state==TaskState.OPEN}
                        val selected=day==selectedDay
                        Surface(onClick={onSelectDay(day)},modifier=Modifier.weight(1f),
                            shape=RoundedCornerShape(12.dp),color=if(selected)c.primaryContainer else c.background) {
                            Column(Modifier.padding(vertical=7.dp),horizontalAlignment=Alignment.CenterHorizontally,
                                verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Text(weekdays[index],style=MaterialTheme.typography.labelSmall,color=c.onSurfaceVariant)
                                Text(day.dayOfMonth.toString(),style=MaterialTheme.typography.bodyMedium,color=if(selected)c.primary else c.onSurface)
                                Box(Modifier.size(4.dp).background(if(hasDiary)c.primary else Color.Transparent,
                                    RoundedCornerShape(50)))
                            }
                        }
                    }
                }
            }
            if(selectedDay!=today)TextButton(onClick={onSelectDay(today)}){Text("回到今天")}
        }
        if(!searching)item {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(if(archived)"已归档日记" else "${shown.size} 篇日记",style=MaterialTheme.typography.labelLarge,color=c.onSurfaceVariant,modifier=Modifier.weight(1f))
                TextButton(onClick={onArchivedChange(!archived)}) {Text(if(archived)"返回日记" else "查看归档")}
            }
        }
        if(shown.isEmpty())item {
            Surface(shape=RoundedCornerShape(18.dp),color=c.surface) {
                Column(Modifier.fillMaxWidth().padding(22.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(if(searching)"没有找到日记" else if(archived)"这一天没有归档日记" else "这一天还没写日记",style=MaterialTheme.typography.titleMedium)
                    Text(if(searching)"换个关键词试试。" else if(archived)"归档的内容仍会保留在本机。" else "写几句话，留住今天。",style=MaterialTheme.typography.bodyMedium,lineHeight=23.sp,color=c.onSurfaceVariant)
                }
            }
        }
        items(shown,key={it.id}){PaperEntry(it,open,selected=it.id in selectedIds,selectionMode=selectedIds.isNotEmpty(),onLongPress=onSelect,onSelect=onSelect,onSwipeDelete=onSwipeDelete)}
        if(!searching && !archived && fragments.isNotEmpty())item {
            HorizontalDivider(color=c.outlineVariant)
            TextButton(onClick={showFragments=!showFragments},modifier=Modifier.fillMaxWidth().semantics {
                contentDescription="同日随记 ${fragments.size} 条，${if(showFragments)"收起" else "展开"}"
            }) {
                Icon(if(showFragments)Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,null,Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if(LocalDensity.current.fontScale>1.3f) "随记 · ${fragments.size} 条" else "同日随记 · ${fragments.size} 条 · ${if(showFragments)"收起" else "展开"}",style=MaterialTheme.typography.labelLarge)
            }
        }
        if(!searching && !archived && showFragments)items(fragments,key={"fragment-${it.id}"}){PaperEntry(it,open,selected=it.id in selectedIds,selectionMode=selectedIds.isNotEmpty(),onLongPress=onSelect,onSelect=onSelect,onSwipeDelete=onSwipeDelete)}
    }
    if(pickerVisible) {
        val picker=rememberDatePickerState(initialSelectedDateMillis=selectedDay.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest={pickerVisible=false},confirmButton={TextButton(onClick={picker.selectedDateMillis?.let{onSelectDay(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())};pickerVisible=false}){Text("确定")}},dismissButton={TextButton(onClick={pickerVisible=false}){Text("取消")}}){DatePicker(picker)}
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun NoteDetail(note:Task,pin:()->Unit,archive:()->Unit,onUpdate:(Task)->Unit,allowPlayback:Boolean=true) {
    val context=LocalContext.current
    var confirm by remember{mutableStateOf(false)}
    var playing by remember{mutableStateOf<String?>(null)}
    var selectedPhoto by remember {mutableStateOf<String?>(null)}
    var showAudioFiles by rememberSaveable(note.id){mutableStateOf(false)}
    val audios=note.attachments.filter{it.mimeType.startsWith("audio/")}
    val otherMedia=note.attachments.filterNot{it.mimeType.startsWith("audio/")}
    fun openAttachment(attachment:ImportedAttachment) {
        runCatching {
            require(attachment.relativePath.matches(Regex("imports/[a-f0-9]{64}(?:\\.[a-z0-9]{1,8})?")))
            val file=java.io.File(context.filesDir,attachment.relativePath)
            val uri=androidx.core.content.FileProvider.getUriForFile(context,"cn.jiaoban.imports",file)
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri,attachment.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"打开文件"))
        }.onFailure{android.widget.Toast.makeText(context,"无法打开附件，请先导出完整资料检查原文件",android.widget.Toast.LENGTH_LONG).show()}
    }
    LaunchedEffect(allowPlayback){if(!allowPlayback)playing=null}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=22.dp,vertical=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {Text(note.journalDate?.format(DateTimeFormatter.ofPattern("yyyy年 M月d日 · 日记")) ?: note.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy年 M月d日 · 记录")),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)}
        // Preserve all legacy newlines and checklist characters, but render one continuous
        // selectable body; a separate LazyColumn item per line made ordinary line breaks huge.
        if(note.text.isNotEmpty())item(key="continuous-body") {
            SelectionContainer {
                Text(note.text,style=MaterialTheme.typography.bodyLarge.copy(fontSize=17.sp,lineHeight=26.sp),fontWeight=FontWeight.Normal)
            }
        }
        if(note.deletedAt==null)item {FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton(onClick={context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,note.text),"分享文字"))}){Text("分享")}
            TextButton(onClick={confirm=true}){Text(if(note.state==TaskState.OPEN)"归档" else "恢复")}
        }}
        else item {Text("此内容在回收站中，只能恢复或彻底删除。",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.labelMedium)}
        note.importInfo?.let{source->item{Text("来源：${source.format}\n${source.originalPath}"+(source.originalDate?.let{"\n原日期：$it"} ?: "\n原日期未提供"),fontSize=12.sp,lineHeight=21.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
        if(note.importedTags.isNotEmpty())item{Text(note.importedTags.joinToString("  "){"#$it"},fontSize=13.sp,color=MaterialTheme.colorScheme.primary)}
        if(note.attachments.isNotEmpty())item{Text("声音与照片",style=MaterialTheme.typography.titleMedium)}
        if(audios.isNotEmpty())item(key="audio-group") {
            AudioRecordingGroup(audios,allowPlayback && playing=="audio-group",{if(allowPlayback)playing="audio-group"},{playing=null})
        }
        if(audios.isNotEmpty())item(key="audio-originals-button") {
            TextButton(onClick={showAudioFiles=!showAudioFiles}) {
                Text(if(showAudioFiles)"收起录音原始文件" else "查看录音原始文件（${audios.size}）",style=MaterialTheme.typography.labelLarge)
            }
        }
        if(showAudioFiles)items(audios,key={"audio-file-${it.relativePath}"}) { attachment ->
            OutlinedButton(onClick={openAttachment(attachment)},modifier=Modifier.fillMaxWidth()) {
                Text(attachment.name,maxLines=2)
            }
        }
        items(otherMedia,key={it.relativePath}){attachment->
            MediaAttachment(attachment,allowPlayback && playing==attachment.relativePath,{if(allowPlayback)playing=attachment.relativePath},{playing=null},
                onImageTap=if(attachment.mimeType.startsWith("image/")) { {selectedPhoto=attachment.relativePath} } else null)
            OutlinedButton(onClick={openAttachment(attachment)},modifier=Modifier.fillMaxWidth()){Text((if(attachment.sourceFile)"原文件 · " else "附件 · ")+attachment.name,maxLines=2)}
        }
    }
    selectedPhoto?.let {path->PhotoViewer(otherMedia.filter{it.mimeType.startsWith("image/")},path){selectedPhoto=null}}
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text(if(note.state==TaskState.OPEN)"归档这一页？" else "恢复这一页？")},text={Text("内容完整保留，可在对应页面的归档中找回。")},confirmButton={TextButton(onClick={confirm=false;archive()}){Text("确定")}},dismissButton={TextButton(onClick={confirm=false}){Text("取消")}})
}

