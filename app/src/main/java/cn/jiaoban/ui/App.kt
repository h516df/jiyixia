package cn.jiaoban.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import cn.jiaoban.*
import cn.jiaoban.data.*
import cn.jiaoban.parse.*
import cn.jiaoban.update.Updater
import kotlinx.coroutines.launch
import java.time.*

private enum class Screen(val label: String, val icon: ImageVector) {
    NOTES("记录", Icons.Default.Edit), JOURNAL("日记", Icons.Default.DateRange),
    IMPORT("导入记录", Icons.Default.Info), LEGACY("旧版资料", Icons.Default.Info), SETTINGS("设置", Icons.Default.Settings), UPDATE("检查更新", Icons.Default.Refresh)
}

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable
fun App(store: TaskStore, today: LocalDate, sharedText: String? = null, onSharedHandled: () -> Unit = {}) {
    val holder = remember { TasksHolder.forStore(store) }
    val context = LocalContext.current
    val updater = remember { Updater.forApplication(context) }
    val focus = LocalFocusManager.current
    var screen by rememberSaveable { mutableStateOf(Screen.NOTES) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorSession by rememberSaveable {mutableStateOf(java.util.UUID.randomUUID().toString())}
    var editorPosting by remember {mutableStateOf(false)}
    var newEditorId by rememberSaveable {mutableStateOf<String?>(null)}
    var creating by rememberSaveable { mutableStateOf(false) }
    var newDiaryDay by rememberSaveable {mutableStateOf(today.toString())}
    var createDiary by rememberSaveable { mutableStateOf(false) }
    var sharedDraft by rememberSaveable { mutableStateOf("") }
    var incomingShare by rememberSaveable { mutableStateOf<String?>(null) }
    val editorDraft=remember{context.getSharedPreferences("editor_draft_v1",android.content.Context.MODE_PRIVATE).also {
        if(it.getString("savingProcess",null)!=TasksHolder.processId)it.edit().remove("saving").apply()
    }}
    var editorInFlight by remember {mutableStateOf(editorDraft.getString("saving",null))}
    val editorBusy=editorPosting || editorInFlight==editorSession
    var editorRestored by rememberSaveable {mutableStateOf(false)}
    LaunchedEffect(holder.loaded) {
        if(holder.loaded && !editorRestored) {
            editorRestored=true
            if(editorDraft.getBoolean("active",false) && !creating && editId==null) {
                editorSession=editorDraft.getString("session",null) ?: java.util.UUID.randomUUID().toString()
                editId=editorDraft.getString("existingId",null)
                creating=editId==null
                newEditorId=editorDraft.getString("newId",null)
                sharedDraft=editorDraft.getString("text","") ?: ""
                createDiary=editorDraft.getString("date",null)!=null
            }
        }
    }
    DisposableEffect(editorDraft) {
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener{_,_->
            editorInFlight=editorDraft.getString("saving",null)
            if(editorDraft.getString("completed",null)==editorSession) {
                creating=false;editId=null;newEditorId=null;sharedDraft="";editorPosting=false
            }
        }
        editorDraft.registerOnSharedPreferenceChangeListener(listener)
        onDispose{editorDraft.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    LaunchedEffect(editorSession) {
        if(editorDraft.getString("completed",null)==editorSession){creating=false;editId=null;newEditorId=null;sharedDraft="";editorPosting=false}
    }
    val draft=remember{WritingDraft(context.getSharedPreferences("writing_draft_v1",android.content.Context.MODE_PRIVATE))}
    var quickText by remember {mutableStateOf(draft.text)}
    var posting by remember {mutableStateOf(false)}
    DisposableEffect(draft) {
        val preferences=context.getSharedPreferences("writing_draft_v1",android.content.Context.MODE_PRIVATE)
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener{_,_->quickText=draft.text}
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose{preferences.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    fun retainQuick(value:String){quickText=value;draft.retain(value)}
    LaunchedEffect(holder.loaded) {
        if(holder.loaded && draft.reconcile(holder.savedTasks))quickText=""
    }
    var selectedTag by rememberSaveable {mutableStateOf<String?>(null)}
    var savedGeneration by remember {mutableStateOf(0)}
    var lastReviewed by rememberSaveable {mutableStateOf<String?>(null)}
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun notify(message: String) { scope.launch { snack.showSnackbar(message) } }
    fun submitQuick() {
        if(posting || !holder.loaded || quickText.isBlank()) return
        val request=draft.begin() ?: return
        posting=true
        holder.saveWriting(request.text,id=request.id) {success->
            posting=false
            if(draft.finish(request,success)) {
                quickText="";selectedTag=null;query="";savedGeneration++;notify("已记下")
            }
        }
    }

    val export = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if(uri != null) {
            val snapshot = holder.savedTasks.toList()
            scope.launch {
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { requireNotNull(context.contentResolver.openOutputStream(uri)).use { cn.jiaoban.importing.BackupPackage.export(snapshot,context.filesDir,it) } }
                }
                notify(if(result.isSuccess) "完整资料已导出" else "导出未完成：${result.exceptionOrNull()?.message ?: "请检查保存位置后重试"}。原资料仍保留。")
            }
        }
    }
    LaunchedEffect(sharedText, holder.loaded) {
        if(holder.loaded && !sharedText.isNullOrBlank()) {
            if(creating || editId != null) incomingShare = sharedText.trim()
            else { sharedDraft = sharedText.trim(); createDiary=false; creating=true; newEditorId=null;editorSession=java.util.UUID.randomUUID().toString(); searching=false; screen=Screen.NOTES }
            onSharedHandled()
        }
    }
    BackHandler(enabled = detailId != null || searching || screen != Screen.NOTES) {
        when {
            detailId != null -> detailId = null
            searching -> { searching=false; query=""; focus.clearFocus() }
            screen == Screen.UPDATE || screen == Screen.LEGACY || screen == Screen.IMPORT -> screen=Screen.SETTINGS
            else -> screen=Screen.NOTES
        }
    }
    val detail = holder.savedTasks.firstOrNull { it.id == detailId }
    val isMain = screen in listOf(Screen.NOTES, Screen.JOURNAL)
    Scaffold(contentWindowInsets=WindowInsets.safeDrawing,containerColor=MaterialTheme.colorScheme.background,
        snackbarHost={SnackbarHost(snack)},
        topBar={TopAppBar(expandedHeight=if(LocalDensity.current.fontScale>1.3f)112.dp else 82.dp,title={
            if(detail != null) Text(if(detail.kind!=EntryKind.NOTE) "旧版资料" else if(detail.journalDate!=null) "日记" else "笔记",style=MaterialTheme.typography.titleMedium)
            else if(searching) OutlinedTextField(query,{query=it},singleLine=true,placeholder={Text("搜索记录") },modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp))
            else Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(if(isMain) "拾页" else screen.label,fontSize=27.sp,fontWeight=FontWeight.SemiBold)
                if(isMain && LocalDensity.current.fontScale<=1.3f) Text(if(screen==Screen.NOTES) "随手记下，慢慢回看" else "按日翻看，想起更多",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },navigationIcon={
            if(detail!=null || !isMain) IconButton(onClick={if(detail!=null)detailId=null else screen=if(screen==Screen.UPDATE || screen==Screen.LEGACY || screen==Screen.IMPORT)Screen.SETTINGS else Screen.NOTES}) {Icon(Icons.Default.ArrowBack,"返回")}
        },actions={
            if(detail?.kind==EntryKind.NOTE) TextButton(onClick={editId=detail.id;newEditorId=null;editorSession=java.util.UUID.randomUUID().toString()}){Text("编辑")}
            else if(detail==null && searching) TextButton(onClick={searching=false;query="";focus.clearFocus()}){Text("取消")}
            else if(detail==null && isMain) {
                IconButton(onClick={searching=true}){Icon(Icons.Default.Search,"搜索" )}
                IconButton(onClick={screen=Screen.SETTINGS}){Icon(Icons.Default.Settings,"设置")}
            }
        },colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background))},
        bottomBar={if(detail==null && !searching && isMain) Column(Modifier.imePadding()) {
            if(screen==Screen.NOTES) QuickComposer(quickText,::retainQuick,posting,holder.loaded,::submitQuick)
            if(!WindowInsets.isImeVisible) NavigationBar(containerColor=MaterialTheme.colorScheme.background,tonalElevation=0.dp) {
            listOf(Screen.NOTES,Screen.JOURNAL).forEach {item->NavigationBarItem(selected=screen==item,onClick={screen=item;focus.clearFocus()},icon={Icon(item.icon,null,Modifier.size(22.dp))},label={Text(item.label)},
                colors=NavigationBarItemDefaults.colors(indicatorColor=MaterialTheme.colorScheme.primaryContainer))}
        }}},

    ) {padding->Column(Modifier.fillMaxSize().padding(padding)) {
        holder.saveError?.let {message->Surface(color=MaterialTheme.colorScheme.errorContainer) {Row(Modifier.fillMaxWidth().padding(start=20.dp),verticalAlignment=Alignment.CenterVertically){Text(message,Modifier.weight(1f));TextButton(onClick={if(quickText.isNotBlank())submitQuick() else holder.retrySave()}){Text("重试保存")}}}}
        when {
            detail?.kind==EntryKind.NOTE -> NoteDetail(detail,{holder.update(detail.copy(pinned=!detail.pinned))},{holder.setState(detail.id,if(detail.state==TaskState.OPEN)TaskState.VOID else TaskState.OPEN)},{holder.update(it)})
            detail!=null -> LegacyDetail(detail,today)
            !holder.loaded -> Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
            screen==Screen.NOTES -> NotesPage(holder.savedTasks.filter{it.kind==EntryKind.NOTE && it.journalDate==null && writingMatches(it,if(searching)query else "",selectedTag)},{detailId=it.id},query,searching,selectedTag,{selectedTag=it},savedGeneration=savedGeneration,onReview={
                val candidates=reviewEntries(holder.savedTasks,today)
                val next=candidates.filter{it.id!=lastReviewed}.randomOrNull() ?: candidates.firstOrNull()
                if(next==null)notify("还没有更早的记录。今天写下的，以后再回来看看。")
                else {lastReviewed=next.id;detailId=next.id}
            })
            screen==Screen.JOURNAL -> JournalPage(searchAll(holder.savedTasks.filter{it.kind==EntryKind.NOTE},if(searching)query else ""),today,{detailId=it.id},searching,onWrite={date->
                createDiary=true;newDiaryDay=date.toString();sharedDraft="";creating=true;newEditorId=null;editorSession=java.util.UUID.randomUUID().toString()
            })
            screen==Screen.LEGACY -> LegacyPage(holder.savedTasks.filter{it.kind!=EntryKind.NOTE},{detailId=it.id})
            screen==Screen.IMPORT -> ImportPage(holder)
            screen==Screen.UPDATE -> UpdateScreen(updater,PaddingValues(0.dp))
            else -> WritingSettings(holder.savedTasks.count{it.kind!=EntryKind.NOTE},{screen=Screen.LEGACY},{export.launch("拾页-完整资料-$today.zip")},{screen=Screen.UPDATE},{screen=Screen.IMPORT})
        }
    }}
    if(holder.loaded && (creating || editId!=null)) {
        val task=holder.savedTasks.firstOrNull{it.id==editId}
        val closeEditor={editorDraft.edit().clear().apply();creating=false;editId=null;newEditorId=null;sharedDraft="";focus.clearFocus()}
        NoteEditor(task,if(creating)sharedDraft else "",if(task!=null)task.journalDate else if(createDiary)LocalDate.parse(newDiaryDay) else null,session=editorSession,newId=newEditorId,saving=editorBusy,onDismiss={if(!editorBusy)closeEditor()},onSave={text,date->
            if(!editorBusy) {
                editorPosting=true
                val submittedSession=editorSession
                editorDraft.edit().putString("saving",submittedSession).putString("savingProcess",TasksHolder.processId).apply()
                val id=task?.id ?: newEditorId ?: java.util.UUID.randomUUID().toString().also{newEditorId=it;editorDraft.edit().putString("newId",it).apply()}
                holder.saveWriting(text,date,id) {success->
                    if(editorDraft.getString("session",null)==submittedSession) {
                        val unchanged=editorDraft.getString("text","")==text && editorDraft.getString("date",null)==date?.toString()
                        editorDraft.edit().remove("saving").apply()
                        if(success && unchanged) editorDraft.edit().putString("completed",submittedSession).putBoolean("active",false).remove("text").apply()
                        if(editorSession==submittedSession) {
                            editorPosting=false
                            notify(if(success)"已保存" else "保存失败，文字仍在，请重试")
                        }
                    }
                }
            }
        })
    }
    if(incomingShare!=null) AlertDialog(onDismissRequest={incomingShare=null},title={Text("收到新的分享")},text={Text("当前文字还未保存。继续编辑，或放弃当前改动并打开分享内容。")},
        confirmButton={TextButton(onClick={incomingShare=null}){Text("继续编辑")}},dismissButton={TextButton(enabled=!editorBusy,onClick={sharedDraft=incomingShare?:"";incomingShare=null;editId=null;detailId=null;createDiary=false;creating=true;newEditorId=null;editorSession=java.util.UUID.randomUUID().toString();searching=false;screen=Screen.NOTES}){Text("打开分享")}})
}
