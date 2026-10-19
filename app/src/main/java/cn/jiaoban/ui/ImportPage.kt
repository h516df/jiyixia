package cn.jiaoban.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.jiaoban.data.TasksHolder
import cn.jiaoban.importing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ImportPage(holder:TasksHolder,initialPreview:ImportPlan?=null,initialFilename:String="") {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var format by rememberSaveable{mutableStateOf(ImportFormat.OBSIDIAN)}
    var menu by remember{mutableStateOf(false)}
    var heading by rememberSaveable{mutableStateOf("Journal")}
    var manualDate by rememberSaveable{mutableStateOf<String?>(null)}
    var datePicker by remember{mutableStateOf(false)}
    var plan by remember{mutableStateOf(initialPreview)}
    var loading by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(null)}
    var filename by remember{mutableStateOf(initialFilename)}
    val choose=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null){val selectedFormat=format;val selectedHeading=heading;val selectedDate=manualDate
            loading=true;plan=null;error=null
            scope.launch{
                val result=withContext(Dispatchers.IO){runCatching{
                    val name=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{cursor->if(cursor.moveToFirst())cursor.getString(0) else null} ?: "import"
                    val bundle=context.contentResolver.openInputStream(uri)?.use{ImportBundle.read(name,it)} ?: throw IllegalStateException("无法打开选择的文件")
                    name to ImportParser.parse(bundle,selectedFormat,selectedHeading,selectedDate?.let{LocalDate.parse(it)})
                }}
                loading=false
                result.onSuccess{filename=it.first;plan=it.second}.onFailure{error=it.message ?: "无法识别文件；原资料未改动"}
            }
        }
    }
    val current=plan
    val additions=current?.additions(holder.savedTasks)?.size ?: 0
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(22.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item{Text("把旧记录带过来",fontSize=24.sp);Text("仅在本机读取。先预览，再确认；不改原文件，不覆盖已有记录。",fontSize=14.sp,lineHeight=24.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item{Box{OutlinedButton(onClick={menu=true},enabled=!loading && !holder.importing,modifier=Modifier.fillMaxWidth()){Text(format.label)}
            DropdownMenu(expanded=menu,onDismissRequest={menu=false}){ImportFormat.entries.forEach{option->DropdownMenuItem(text={Text(option.label)},onClick={format=option;menu=false;plan=null;error=null})}}}}
        item{Text(when(format){
            ImportFormat.OBSIDIAN->"选择 .md 或 vault ZIP。YYYY-MM-DD.md 作为日记，其余为记录。保留原 Markdown 和已引用附件，跳过隐藏配置与未引用文件，不复制完整原 ZIP；不执行插件或下载远程图片。"
            ImportFormat.THINO_DAILY->"仅支持 DAILY 日期文件的时间列表，一天可拆多条。当前不承诺 JOURNAL、FILE、MULTI、Canvas 或自定义模板。未知结构不会写入。"
            ImportFormat.DIARIUM->"在 Diarium 导出 JSON；有附件请勾选独立附件并选择 ZIP。支持单条对象或数组，不读取 .diary 专有备份。"
            ImportFormat.MOJI_ORIGINAL->"只收存墨记导出的 PDF/图片原件，供打开查看。不会自动解析成完整日记；正文、标签与原日期不保证可搜索或迁移。"
            ImportFormat.SHIYE_BACKUP->"选择拾页导出的完整 ZIP 备份。恢复缺少的记录与附件，重复跳过，冲突保留现有内容，包中未包含的旧附件不作修复。损坏的现存附件会明确报错，不会覆盖。"
        },fontSize=14.sp,lineHeight=24.sp)}
        item{Text("本轮安全上限：单文件 32 MiB，ZIP 解压总计 64 MiB，最多 5000 条；超限不写入。",fontSize=12.sp,lineHeight=20.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        if(format==ImportFormat.THINO_DAILY)item{OutlinedTextField(heading,{heading=it;plan=null},label={Text("精确标题，留空读取整篇")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!loading && !holder.importing)}
        if(format==ImportFormat.MOJI_ORIGINAL)item{OutlinedButton(onClick={datePicker=true},enabled=!loading && !holder.importing){Text(manualDate?.let{"手动标注日期：$it"} ?: "选择手动标注日期")}}
        item{Button(onClick={choose.launch(arrayOf("*/*"))},enabled=!loading && !holder.importing && (format!=ImportFormat.MOJI_ORIGINAL || manualDate!=null),modifier=Modifier.fillMaxWidth()){Text(if(loading)"正在本机解析…" else "选择文件并预览")}}
        error?.let{message->item{Text(message,color=MaterialTheme.colorScheme.error,fontSize=14.sp,lineHeight=23.sp)}}
        if(loading)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
        if(current!=null){
            item{HorizontalDivider();Text(filename,fontSize=14.sp);Text("新增 $additions 条 · 重复 ${current.duplicateCount(holder.savedTasks)} 条 · 冲突 ${current.conflictCount(holder.savedTasks)} 条",fontSize=15.sp,lineHeight=25.sp)}
            item{Text("冲突不会覆盖。没有稳定源 ID 时，修改过或改名后的原文件可能被识别为新记录，请核对预览。原日期缺少时区时保留原值，不转换成 UTC。",fontSize=13.sp,lineHeight=23.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            items(current.warnings){warning->Text(warning,fontSize=13.sp,lineHeight=23.sp,color=MaterialTheme.colorScheme.error)}
            items(current.records.take(8),key={it.id}){record->Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surface){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text(record.importInfo?.originalDate ?: record.journalDate?.let{"手动日期 $it"} ?: "原日期未提供",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(record.text,maxLines=5,fontSize=15.sp,lineHeight=24.sp)
                Text("${record.attachments.count{!it.sourceFile}} 个关联附件 · 原文件保留",fontSize=12.sp)
            }}}
            if(current.records.size>8)item{Text("预览前8条，共 ${current.records.size} 条。",fontSize=13.sp)}
            item{Button(onClick={holder.importWriting(current,context.filesDir){}},enabled=(additions>0 || format==ImportFormat.SHIYE_BACKUP) && !holder.importing && !loading,modifier=Modifier.fillMaxWidth()){Text(if(holder.importing)"正在保存…" else if(format==ImportFormat.SHIYE_BACKUP)"恢复缺少的记录与附件" else "确认导入 $additions 条")}}
        }
        holder.lastImportResult?.let{result->item{Text(result.error ?: "已导入 ${result.added} 条，跳过 ${result.skipped} 条已有记录。",color=if(result.error==null)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,fontSize=14.sp,lineHeight=24.sp)}}
    }
    if(datePicker){val state=rememberDatePickerState(initialSelectedDateMillis=manualDate?.let{LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()})
        DatePickerDialog(onDismissRequest={datePicker=false},confirmButton={TextButton(onClick={state.selectedDateMillis?.let{manualDate=Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString();plan=null};datePicker=false}){Text("确定")}},dismissButton={TextButton(onClick={datePicker=false}){Text("取消")}}){DatePicker(state)}}
}
