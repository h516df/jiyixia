package cn.jiaoban.importing

import cn.jiaoban.Task
import cn.jiaoban.data.TaskCodec
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

object BackupPackage {
    fun export(records:List<Task>,filesDir:File,output:OutputStream) {
        require(records.size<=ImportBundle.MAX_RECORDS){"记录超过当前完整备份的 5000 条上限，导出未完成"}
        val entries=linkedMapOf<String,ByteArray>()
        entries["manifest.json"]=JSONObject().put("format","shiye-backup").put("version",1).toString().toByteArray()
        entries["records.json"]=TaskCodec.encode(records).toByteArray()
        var total=entries.values.sumOf{it.size.toLong()}
        for(a in records.flatMap{it.attachments}.distinctBy{it.relativePath}){
            require(a.relativePath.matches(Regex("imports/[a-f0-9]{64}(?:\\.[a-z0-9]{1,8})?"))){"附件路径无效"}
            val file=File(filesDir,a.relativePath)
            require(file.length()<=ImportBundle.MAX_FILE){"备份附件过大，无法生成可恢复备份"}
            total+=file.length();require(total<=ImportBundle.MAX_TOTAL){"备份超出安全恢复容量，未导出"}
            val bytes=file.readBytes();require(digest(bytes)==a.sha256){"附件校验失败，导出未完成"};entries[a.relativePath]=bytes
        }
        require(entries.size<=ImportBundle.MAX_ENTRIES && entries.values.all{it.size<=ImportBundle.MAX_FILE} && total<=ImportBundle.MAX_TOTAL){"备份超出安全恢复容量，未导出"}
        // Validate exactly the package we are about to produce before touching output.
        parse(ImportBundle(entries,"backup.zip",byteArrayOf()))
        var compressed=0L
        val bounded=object:java.io.FilterOutputStream(output){
            override fun write(b:Int){require(++compressed<=ImportBundle.MAX_TOTAL){"备份压缩包过大，导出未完成"};out.write(b)}
            override fun write(b:ByteArray,off:Int,len:Int){compressed+=len;require(compressed<=ImportBundle.MAX_TOTAL){"备份压缩包过大，导出未完成"};out.write(b,off,len)}
        }
        ZipOutputStream(bounded).use{zip->for((path,bytes)in entries){zip.putNextEntry(ZipEntry(path));zip.write(bytes);zip.closeEntry()}}
    }
    private fun sameJson(a:Any?,b:Any?):Boolean = when {
        a is JSONObject && b is JSONObject -> a.keys().asSequence().toSet()==b.keys().asSequence().toSet() && a.keys().asSequence().all{sameJson(a.get(it),b.get(it))}
        a is JSONArray && b is JSONArray -> a.length()==b.length() && (0 until a.length()).all{sameJson(a.get(it),b.get(it))}
        else -> a==b
    }

    fun parse(bundle:ImportBundle):ImportPlan {
        val manifest=JSONObject(requireNotNull(bundle.files["manifest.json"]){"缺少拾页备份标识"}.toString(Charsets.UTF_8))
        require(manifest.getString("format")=="shiye-backup" && manifest.getInt("version")==1){"不支持的拾页备份版本"}
        val raw=requireNotNull(bundle.files["records.json"]){"缺少记录文件"}.toString(Charsets.UTF_8)
        val rows=JSONArray(raw);require(rows.length()<=ImportBundle.MAX_RECORDS){"备份条目过多"};val ids=mutableSetOf<String>()
        for(i in 0 until rows.length()){val o=rows.getJSONObject(i);require(o.getString("id").isNotBlank() && ids.add(o.getString("id"))){"记录ID缺失或重复"};o.getString("text");Instant.parse(o.getString("createdAt"));cn.jiaoban.EntryKind.valueOf(o.getString("kind"));cn.jiaoban.TaskState.valueOf(o.getString("state"));if(!o.isNull("journalDate"))java.time.LocalDate.parse(o.getString("journalDate"))}
        val records=TaskCodec.decode(raw);require(records.size==rows.length()){ "备份条目不完整" }
        require(sameJson(rows,JSONArray(TaskCodec.encode(records)))){"备份字段损坏或版本不兼容，未恢复任何记录"}
        val assets=linkedMapOf<String,ByteArray>()
        val verifiedHashes=mutableMapOf<String,String>()
        for(a in records.flatMap{it.attachments}){require(a.relativePath.matches(Regex("imports/[a-f0-9]{64}(?:\\.[a-z0-9]{1,8})?"))){"附件路径无效"};val bytes=requireNotNull(bundle.files[a.relativePath]){"备份附件缺失"};require(verifiedHashes.getOrPut(a.relativePath){digest(bytes)}==a.sha256 && a.relativePath.substringAfter('/').substringBefore('.')==a.sha256){"备份附件校验失败"};assets[a.relativePath]=bytes}
        return ImportPlan(records,assets,emptyList(),"SHIYE_BACKUP")
    }
}
