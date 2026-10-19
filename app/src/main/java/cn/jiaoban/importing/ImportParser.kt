package cn.jiaoban.importing

import android.text.Html
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter

object ImportParser {
    fun parse(bundle:ImportBundle,format:ImportFormat,heading:String="Journal",manualDate:LocalDate?=null,now:Instant=Instant.now()):ImportPlan {
        if(format==ImportFormat.SHIYE_BACKUP)return BackupPackage.parse(bundle)
        val builder=PlanBuilder(bundle,format,now)
        when(format){
            ImportFormat.SHIYE_BACKUP->error("unreachable")
            ImportFormat.OBSIDIAN,ImportFormat.THINO_DAILY->{
                val files=bundle.files.filterKeys{it.endsWith(".md",true) && !it.split('/').any{part->part.startsWith('.')}}
                require(files.isNotEmpty()){ "请选择 Markdown 文件或包含 Markdown 的 vault ZIP" }
                for((path,bytes)in files){
                    if(format==ImportFormat.THINO_DAILY && path.substringAfterLast('/').lowercase() in setOf("query.md","delete.md")){builder.warnings+="查询/删除文件未导入：$path";continue}
                    val text=decodeText(bytes);if(text.isBlank()){builder.warnings+="空文件未导入：$path";continue}
                    val date=runCatching{LocalDate.parse(path.substringAfterLast('/').substringBeforeLast('.'))}.getOrNull()
                    if(format==ImportFormat.THINO_DAILY){require(date!=null){"Thino DAILY 目前要求 YYYY-MM-DD.md 日期文件名；未知模式未导入：$path"};parseDaily(builder,path,text,date,heading)}
                    else {if(date==null)builder.warnings+="未识别原日期，保留为普通记录：$path";builder.add(path,text,date?.toString(),date!=null,title=path.substringAfterLast('/').removeSuffix(".md"))}
                }
            }
            ImportFormat.DIARIUM->parseDiarium(builder)
            ImportFormat.MOJI_ORIGINAL->{
                require(manualDate!=null){"请为墨记原文件手动标注日期；不会推测原日期"}
                for((path,bytes)in bundle.files){require(path.substringAfterLast('.').lowercase() in setOf("pdf","png","jpg","jpeg","webp")){"墨记原件收存仅接受 PDF 或图片"}
                    require(bytes.isNotEmpty()){ "原文件为空" }
                    builder.add(path,"墨记原件：${path.substringAfterLast('/')}\n原文件已保留。正文、标签和原始日期未自动解析。",null,true,title=path.substringAfterLast('/'),metadata=JSONObject().put("manualDate",manualDate.toString()).toString(),manualDay=manualDate)
                }
                builder.warnings+="墨记仅收存原 PDF/图片；日期为手动标注，不代表已迁移可搜索正文、标签或原始时间。"
            }
        }
        return builder.finish()
    }
    private fun decodeText(bytes:ByteArray):String {
        require(bytes.size<=8*1024*1024){"单篇文字过大"}
        val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    }
    private fun parseDaily(builder:PlanBuilder,path:String,text:String,day:LocalDate,heading:String) {
        val lines=text.replace("\r\n","\n").split('\n')
        val headingPattern=Regex("^(#{1,6})\\s+(.+?)\\s*#*\\s*$")
        val outside=BooleanArray(lines.size)
        var fenceChar:Char?=null;var fenceLength=0
        val fencePattern=Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
        for(i in lines.indices){
            val match=fencePattern.matchEntire(lines[i])
            if(fenceChar!=null){
                if(match!=null && match.groupValues[1][0]==fenceChar && match.groupValues[1].length>=fenceLength && match.groupValues[2].isBlank()){fenceChar=null}
            }else if(match!=null){fenceChar=match.groupValues[1][0];fenceLength=match.groupValues[1].length}
            else outside[i]=true
        }
        var start=0;var end=lines.size
        if(heading.isNotBlank()){
            val found=lines.indices.filter{outside[it] && headingPattern.matchEntire(lines[it])?.groupValues?.get(2)==heading}
            require(found.size==1){"未找到唯一的 Thino 标题“$heading”：$path；请核对模式/标题"}
            start=found.single()+1;val level=headingPattern.matchEntire(lines[start-1])!!.groupValues[1].length
            end=(start until lines.size).firstOrNull{i->outside[i] && headingPattern.matchEntire(lines[i])?.groupValues?.get(1)?.length?.let{it<=level}==true} ?: lines.size
        }
        val marker=Regex("^[-*] (?:\\[(.)] )?(?:<time>)?(\\d{1,2}):(\\d{2})(?:</time>)?\\s+(.*)$")
        val starts=(start until end).filter{outside[it] && marker.matches(lines[it])}
        require(starts.isNotEmpty()){ "没有识别到 Thino DAILY 时间片段：$path；新模式或自定义模板尚不支持" }
        val consumed=BooleanArray(lines.size)
        for((position,index)in starts.withIndex()){
            val next=starts.getOrNull(position+1) ?: end;val match=marker.matchEntire(lines[index])!!
            val hour=match.groupValues[2].toInt();val minute=match.groupValues[3].toInt();require(hour in 0..23 && minute in 0..59){"无效时间：$path 第${index+1}行"}
            val checkbox=match.groupValues[1].takeIf{it.isNotEmpty()}?.let{"[$it] "}.orEmpty()
            val body=(listOf(checkbox+match.groupValues[4])+lines.subList(index+1,next)).joinToString("\n").trimEnd()
            val block=Regex("(?:^|\\s)\\^([A-Za-z0-9-]+)\\s*$").find(body)?.groupValues?.get(1)
            builder.add(path,body,"${day}T${"%02d:%02d".format(java.util.Locale.ROOT,hour,minute)}",false,externalId=block?.let{"$path#$it"},metadata=JSONObject().put("lineStart",index+1).put("lineEnd",next).put("timezoneKnown",false).toString())
            for(i in index until next)consumed[i]=true
        }
        val residual=lines.indices.filter{!consumed[it]}.map{lines[it]}.joinToString("\n").trim()
        if(residual.isNotBlank() && residual!="# $heading"){
            builder.add(path,residual,day.toString(),false,title="未拆分的原文",metadata=JSONObject().put("residual",true).toString())
            builder.warnings+="标题外或未识别文字另行完整保留：$path"
        }
    }
    private fun parseDiarium(builder:PlanBuilder) {
        val jsonFiles=builder.bundle.files.filterKeys{it.endsWith(".json",true) && !it.startsWith("media/") && !it.split('/').any{p->p.startsWith('.')}}
        require(jsonFiles.isNotEmpty()){ "请选择 Diarium JSON 导出或含 JSON 与独立附件的 ZIP；.diary 备份不支持" }
        val rows=mutableListOf<Pair<String,JSONObject>>()
        for((path,bytes)in jsonFiles){val raw=decodeText(bytes).trim();when{
            raw.startsWith("[")->{val array=JSONArray(raw);for(i in 0 until array.length()){require(rows.size<ImportBundle.MAX_RECORDS){"记录过多，请分批导入"};rows+=path to array.getJSONObject(i)}}
            raw.startsWith("{")->rows+=path to JSONObject(raw)
            else->error("无法识别 Diarium JSON：$path")
        }}
        val stamps=rows.map{(_,o)->parseDate(o.getString("date"))}
        for((index,row)in rows.withIndex()){
            val(path,o)=row;val dateRaw=o.getString("date");val local=stamps[index]
            require(o.has("heading") || o.has("html")){"JSON 缺少 Diarium heading/html，未导入未知结构"}
            val title=if(o.has("heading"))o.getString("heading") else ""
            val html=if(o.has("html"))o.getString("html") else ""
            val plainTitle=Html.fromHtml(title,Html.FROM_HTML_MODE_LEGACY).toString().trim()
            val plain=Html.fromHtml(html,Html.FROM_HTML_MODE_LEGACY).toString().trim()
            val body=listOf(plainTitle,plain).filter{it.isNotBlank()}.joinToString("\n\n")
            val mediaPrefix="media/"+local.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmssSSS"))+"/"
            val media=builder.bundle.files.keys.filter{it.startsWith(mediaPrefix)}
            val ambiguous=stamps.count{it.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmssSSS"))==local.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmssSSS"))}>1
            if(ambiguous && media.isNotEmpty())builder.warnings+="同时间多篇的附件无法唯一关联，未自动导入，请保留源文件：$dateRaw"
            val tags=o.optJSONArray("tags")?.let{a->(0 until a.length()).map{a.getString(it)}} ?: emptyList()
            builder.add(path,body,dateRaw,true,plainTitle,tags,externalId=if(o.has("id"))o.get("id").toString() else null,metadata=o.toString(),extra=if(ambiguous)emptyList() else media)
        }
    }
    private fun parseDate(raw:String):LocalDateTime = runCatching{OffsetDateTime.parse(raw).toLocalDateTime()}.getOrElse{
        runCatching{LocalDateTime.parse(raw)}.getOrElse{LocalDate.parse(raw).atStartOfDay()}
    }
}
