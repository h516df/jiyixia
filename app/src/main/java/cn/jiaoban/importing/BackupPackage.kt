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
    const val MAX_ASSET=1024L*1024*1024
    const val MAX_TOTAL=4L*1024*1024*1024
    const val MAX_METADATA=32L*1024*1024
    // Independently cap compressed input/output too, including ZIP overhead.
    const val MAX_ARCHIVE=MAX_TOTAL+16L*1024*1024
    internal data class Limits(val asset:Long=MAX_ASSET,val total:Long=MAX_TOTAL,
        val metadata:Long=MAX_METADATA,val entries:Int=ImportBundle.MAX_ENTRIES,val archive:Long=MAX_ARCHIVE)

    fun export(records:List<Task>,filesDir:File,output:OutputStream) {
        require(records.size<=ImportBundle.MAX_RECORDS){"记录超过当前完整备份的 5000 条上限，导出未完成"}
        val metadata=linkedMapOf(
            "manifest.json" to JSONObject().put("format","shiye-backup").put("version",2).toString().toByteArray(),
            "records.json" to TaskCodec.encode(records).toByteArray())
        require(metadata.values.all{it.size<=MAX_METADATA}){"备份记录文件过大"}
        val attachments=records.flatMap{it.attachments}
        require(attachments.map{it.relativePath}.distinct().size+2<=ImportBundle.MAX_ENTRIES){"备份附件条目过多"}
        val files=linkedMapOf<String,File>();val hashes=mutableMapOf<String,String>()
        var total=metadata.values.sumOf{it.size.toLong()}
        for(a in attachments){
            val file=checkedAsset(filesDir,a.relativePath)
            require(file.isFile && file.length()<=MAX_ASSET){"备份附件缺失或过大"}
            require(a.sha256==a.relativePath.substringAfter('/').substringBefore('.')){"附件路径与校验值不符"}
            val hash=hashes.getOrPut(a.relativePath){
                val result=file.inputStream().use{streamDigest(it)}
                total+=result.second;require(total<=MAX_TOTAL){"备份超出安全恢复容量"}
                files[a.relativePath]=file;result.first
            }
            require(hash==a.sha256){"附件校验失败，导出未完成"}
        }
        // Validate lossless metadata before touching the destination.
        parseMetadata(metadata.getValue("manifest.json"),metadata.getValue("records.json"))
        var written=0L
        val bounded=object:java.io.FilterOutputStream(output){
            override fun write(b:Int){require(++written<=MAX_ARCHIVE){"备份压缩包过大"};out.write(b)}
            override fun write(b:ByteArray,off:Int,len:Int){require(written<=MAX_ARCHIVE-len){"备份压缩包过大"};written+=len;out.write(b,off,len)}
        }
        ZipOutputStream(bounded).use{zip->
            metadata.forEach{(path,bytes)->zip.putNextEntry(ZipEntry(path));zip.write(bytes);zip.closeEntry()}
            var copied=metadata.values.sumOf{it.size.toLong()}
            for((path,file) in files){
                checkedAsset(filesDir,path)
                zip.putNextEntry(ZipEntry(path))
                val result=file.inputStream().use{streamDigest(it,zip,minOf(MAX_ASSET,MAX_TOTAL-copied))}
                require(result.first==hashes[path]){"附件在导出期间改变，备份未完成"}
                copied+=result.second;zip.closeEntry()
            }
        }
    }

    /** Disk-backed preview. Caller owns and must close the returned plan. */
    fun read(input:java.io.InputStream,cacheDir:File):ImportPlan = read(input,cacheDir,Limits())
    internal fun read(input:java.io.InputStream,cacheDir:File,limits:Limits):ImportPlan {
        require(cacheDir.isDirectory || cacheDir.mkdirs()){"无法创建备份临时目录"}
        val stage=java.nio.file.Files.createTempDirectory(cacheDir.toPath(),"backup-preview-").toFile()
        try {
            val files=linkedMapOf<String,File>();val paths=mutableSetOf<String>();var total=0L;var compressed=0L
            val bounded=object:java.io.FilterInputStream(input){
                override fun read():Int {val b=super.read();if(b>=0){compressed++;require(compressed<=limits.archive){"备份压缩包过大"}};return b}
                override fun read(b:ByteArray,off:Int,len:Int):Int {val n=super.read(b,off,len);if(n>0){compressed+=n;require(compressed<=limits.archive){"备份压缩包过大"}};return n}
            }
            java.util.zip.ZipInputStream(bounded).use{zip->
                while(true){
                    val entry=zip.nextEntry ?: break
                    require(paths.size<limits.entries){"备份条目过多"}
                    val path=entry.name
                    require(path=="manifest.json" || path=="records.json" || path.matches(assetPathPattern)){"备份含非规范路径"}
                    require(!entry.isDirectory && paths.add(path)){"备份含重复路径或目录"}
                    val limit=if(path.startsWith("imports/"))limits.asset else limits.metadata
                    require(entry.size<0 || entry.size<=limit){"备份条目过大"}
                    val target=File(stage,path);target.parentFile!!.mkdirs()
                    val result=target.outputStream().use{streamDigest(zip,it,minOf(limit,limits.total-total))}
                    total+=result.second
                    if(path.startsWith("imports/"))require(result.first==path.substringAfter('/').substringBefore('.')){"备份附件校验失败"}
                    files[path]=target;zip.closeEntry()
                }
            }
            val manifest=requireNotNull(files["manifest.json"]){"缺少记一下备份标识"}.readBytes()
            val raw=requireNotNull(files["records.json"]){"缺少记录文件"}.readBytes()
            val records=parseMetadata(manifest,raw)
            val assets=linkedMapOf<String,File>()
            for(a in records.flatMap{it.attachments}){
                require(a.relativePath.matches(assetPathPattern) && a.sha256==a.relativePath.substringAfter('/').substringBefore('.')){"备份附件路径或校验值无效"}
                assets[a.relativePath]=requireNotNull(files[a.relativePath]){"备份附件缺失"}
            }
            // Do not retain unreferenced files from an external package.
            files.filterKeys{it !in assets}.values.forEach{it.delete()}
            return ImportPlan(records,emptyMap(),emptyList(),"SHIYE_BACKUP",assets,stage)
        } catch(error:Throwable){stage.deleteRecursively();throw error}
    }
    private fun sameJson(a:Any?,b:Any?):Boolean = when {
        a is JSONObject && b is JSONObject -> a.keys().asSequence().toSet()==b.keys().asSequence().toSet() && a.keys().asSequence().all{sameJson(a.get(it),b.get(it))}
        a is JSONArray && b is JSONArray -> a.length()==b.length() && (0 until a.length()).all{sameJson(a.get(it),b.get(it))}
        else -> a==b
    }

    fun parse(bundle:ImportBundle):ImportPlan {
        val records=parseMetadata(requireNotNull(bundle.files["manifest.json"]){"缺少记一下备份标识"},requireNotNull(bundle.files["records.json"]){"缺少记录文件"})
        val assets=linkedMapOf<String,ByteArray>()
        val verifiedHashes=mutableMapOf<String,String>()
        for(a in records.flatMap{it.attachments}){require(a.relativePath.matches(Regex("imports/[a-f0-9]{64}(?:\\.[a-z0-9]{1,8})?"))){"附件路径无效"};val bytes=requireNotNull(bundle.files[a.relativePath]){"备份附件缺失"};require(verifiedHashes.getOrPut(a.relativePath){digest(bytes)}==a.sha256 && a.relativePath.substringAfter('/').substringBefore('.')==a.sha256){"备份附件校验失败"};assets[a.relativePath]=bytes}
        return ImportPlan(records,assets,emptyList(),"SHIYE_BACKUP")
    }
    private fun parseMetadata(manifestBytes:ByteArray,recordBytes:ByteArray):List<Task> {
        require(manifestBytes.size<=MAX_METADATA && recordBytes.size<=MAX_METADATA){"备份记录文件过大"}
        val manifest=JSONObject(manifestBytes.toString(Charsets.UTF_8))
        require(manifest.getString("format")=="shiye-backup" && manifest.getInt("version") in 1..2){"不支持的记一下备份版本"}
        val raw=recordBytes.toString(Charsets.UTF_8)
        val rows=JSONArray(raw);require(rows.length()<=ImportBundle.MAX_RECORDS){"备份条目过多"};val ids=mutableSetOf<String>()
        for(i in 0 until rows.length()){val o=rows.getJSONObject(i);require(o.getString("id").isNotBlank() && ids.add(o.getString("id"))){"记录ID缺失或重复"};o.getString("text");Instant.parse(o.getString("createdAt"));cn.jiaoban.EntryKind.valueOf(o.getString("kind"));cn.jiaoban.TaskState.valueOf(o.getString("state"));if(!o.isNull("journalDate"))java.time.LocalDate.parse(o.getString("journalDate"));if(o.has("deletedAt") && !o.isNull("deletedAt"))Instant.parse(o.getString("deletedAt"))}
        val records=TaskCodec.decode(raw);require(records.size==rows.length()){ "备份条目不完整" }
        require(sameJson(rows,JSONArray(TaskCodec.encode(records)))){"备份字段损坏或版本不兼容，未恢复任何记录"}
        return records
    }

}

