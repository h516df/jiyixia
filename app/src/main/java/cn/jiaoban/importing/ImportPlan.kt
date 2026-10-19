package cn.jiaoban.importing

import cn.jiaoban.*
import java.time.Instant
import java.time.LocalDate
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Parsed only in memory. No database or permanent attachment writes before confirmation. */
data class ImportPlan(val records:List<Task>,val assets:Map<String,ByteArray>,val warnings:List<String>,val format:String) {
    fun additions(existing:List<Task>)=records.filter{candidate->existing.none{it.id==candidate.id}}
    fun conflictCount(existing:List<Task>)=records.count{candidate->existing.any{it.id==candidate.id && (it.text!=candidate.text || it.journalDate!=candidate.journalDate || it.importedTags!=candidate.importedTags || semanticAssets(it)!=semanticAssets(candidate) || it.importInfo?.originalDate!=candidate.importInfo?.originalDate || it.importInfo?.originalTitle!=candidate.importInfo?.originalTitle || (candidate.importInfo?.format=="DIARIUM" && it.importInfo?.metadata!=candidate.importInfo?.metadata))}}
    fun duplicateCount(existing:List<Task>)=records.size-additions(existing).size-conflictCount(existing)
}
internal fun safeSourcePath(path:String):Boolean {
    val parts=path.replace('\\','/').split('/')
    val name=parts.last().lowercase()
    return parts.none{it.startsWith('.')} && name !in setOf("credentials.json","credential.json","tokens.json","token.json","secrets.json","id_rsa","id_ed25519") && !name.endsWith(".pem") && !name.endsWith(".key")
}
private fun semanticAssets(task:Task)=task.attachments.filter{!it.sourceFile || task.importInfo?.format=="MOJI_ORIGINAL"}.map{it.sha256}.sorted()

enum class ImportFormat(val label:String){OBSIDIAN("Obsidian Markdown"),THINO_DAILY("Thino DAILY"),DIARIUM("Diarium JSON"),MOJI_ORIGINAL("墨记 PDF/图片原件"),SHIYE_BACKUP("拾页完整备份")}

internal class PlanBuilder(val bundle:ImportBundle,val format:ImportFormat,val importedAt:Instant) {
    val records=mutableListOf<Task>();val warnings=mutableListOf<String>();val assets=linkedMapOf<String,ByteArray>()
    private val attachmentCache=mutableMapOf<Pair<String,Boolean>,ImportedAttachment>()
    private var textBytes=0L
    private val occurrences=mutableMapOf<String,Int>()
    fun attachment(path:String,bytes:ByteArray,source:Boolean=false):ImportedAttachment {
        require(safeSourcePath(path)){"隐藏配置或凭据类文件不导入"}
        attachmentCache[path to source]?.let{return it}
        val hash=digest(bytes);val extension=path.substringAfterLast('.',"").lowercase().takeIf{it.matches(Regex("[a-z0-9]{1,8}"))}.orEmpty()
        val relative="imports/$hash${if(extension.isBlank())"" else ".$extension"}";assets[relative]=bytes
        val mime=when(extension){"png"->"image/png";"jpg","jpeg"->"image/jpeg";"webp"->"image/webp";"gif"->"image/gif";"pdf"->"application/pdf";"mp3"->"audio/mpeg";"m4a"->"audio/mp4";"mp4"->"video/mp4";"md","txt"->"text/plain";"json"->"application/json";"zip"->"application/zip";else->"application/octet-stream"}
        return ImportedAttachment(hash,path.substringAfterLast('/'),relative,mime,source).also{attachmentCache[path to source]=it}
    }
    fun add(path:String,body:String,dateRaw:String?,diary:Boolean,title:String?=null,tags:List<String> = emptyList(),externalId:String?=null,metadata:String?=null,extra:List<String> = emptyList(),manualDay:LocalDate?=null) {
        require(body.isNotBlank() || extra.isNotEmpty()){ "发现没有正文与附件的条目" }
        require(records.size<ImportBundle.MAX_RECORDS){"记录过多，请分批导入"}
        textBytes+=body.toByteArray().size;require(textBytes<=16*1024*1024){"总正文过大，请分批导入"}
        val original=attachment(path,bundle.files.getValue(path),true)
        val media=(resolveLinks(path,body)+extra).distinct().filter{p->safeSourcePath(p).also{if(!it)warnings+="隐藏配置或凭据类附件未读取：$path"}}.mapNotNull{p->bundle.files[p]?.let{attachment(p,it)}}
        val semantic=(media.map{it.sha256}+if(format==ImportFormat.MOJI_ORIGINAL)listOf(original.sha256) else emptyList()).sorted().joinToString(",")
        val canonical=body.replace("\r\n","\n").trimEnd()
        val baseKey=externalId?.takeIf{it.isNotBlank()}?.let{"id:$it"} ?: "$path\u0000$dateRaw\u0000${digest(canonical.toByteArray())}\u0000$semantic\u0000$manualDay"
        val occurrence=occurrences.getOrDefault(baseKey,0);require(externalId==null || occurrence==0){"源文件含重复稳定ID，未导入"};occurrences[baseKey]=occurrence+1
        val key=digest("${format.name}\u0000$baseKey\u0000$occurrence".toByteArray())
        val day=manualDay ?: dateRaw?.take(10)?.let{runCatching{LocalDate.parse(it)}.getOrNull()}
        if(diary)require(day!=null){"日记日期无法识别：$path"}
        records+=Task(id="import-$key",text=body,createdAt=importedAt,kind=EntryKind.NOTE,expiryPolicy=ExpiryPolicy.MUST_FINISH,
            journalDate=if(diary)day else null,importInfo=ImportInfo(format.name,key,path,dateRaw,title?.takeIf{it.isNotBlank()},metadata?.takeIf{it.isNotBlank()}),importedTags=tags.filter{it.isNotBlank()}.distinct(),attachments=(listOf(original)+media).distinctBy{it.relativePath})
    }
    private fun resolveLinks(notePath:String,body:String):List<String> {
        val refs=Regex("!?\\[\\[([^]\\n]+)]]").findAll(body).map{it.groupValues[1].substringBefore('|').substringBefore('#')}.toList()+
            Regex("!?\\[[^]\\n]*]\\(([^)\\n]+)\\)").findAll(body).map{it.groupValues[1].trim().removeSurrounding("<",">").substringBefore(" \"").substringBefore('#')}.toList()
        return refs.filter{it.isNotBlank()}.mapNotNull{raw->
            if(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(raw)||raw.startsWith("//")){warnings+="外部链接仅保留原文，不下载：$notePath";return@mapNotNull null}
            val decoded=runCatching{URLDecoder.decode(raw.replace("+","%2B"),StandardCharsets.UTF_8.name())}.getOrElse{raw}.replace('\\','/')
            if(decoded.startsWith('/')){warnings+="附件绝对路径未读取：$notePath";return@mapNotNull null}
            fun normalize(p:String):String?{val parts=mutableListOf<String>();for(part in p.split('/'))when(part){"","."->Unit;".."->{if(parts.isEmpty())return null;parts.removeAt(parts.lastIndex)};else->parts+=part};return parts.joinToString("/")}
            val relative=normalize(notePath.substringBeforeLast('/',"")+"/"+decoded)
            val rooted=normalize(decoded)
            val exact=listOfNotNull(relative,rooted).distinct().filter{it in bundle.files}
            val basename=bundle.files.keys.filter{it.substringAfterLast('/')==decoded}
            val matches=if(exact.isNotEmpty())exact else basename
            when(matches.size){1->matches.single();0->{if(!decoded.endsWith(".md") && decoded.substringAfterLast('/').contains('.'))warnings+="附件未找到：$notePath → $raw";null};else->{warnings+="附件名称有歧义，未自动关联：$notePath → $raw";null}}
        }
    }
    fun finish():ImportPlan {
        require(records.isNotEmpty()){ "没有识别到可导入记录；原文件未改动" }
        val used=attachmentCache.keys.map{it.first}.toSet()
        val omitted=bundle.files.keys.count{it !in used}
        if(omitted>0)warnings+="$omitted 个未引用或隐藏配置文件未导入；完整原 ZIP 不会复制进备份，请保留源文件。"
        return ImportPlan(records,assets,warnings.distinct(),format.name)
    }
}
