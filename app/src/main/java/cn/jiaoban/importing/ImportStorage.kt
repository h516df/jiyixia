package cn.jiaoban.importing

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import cn.jiaoban.media.AttachmentPublisher
import java.util.UUID

internal val assetPathPattern=Regex("imports/[a-f0-9]{64}(?:\\.[a-z0-9]{1,8})?")
internal fun checkedAsset(root:File,relative:String):File {
    require(relative.matches(assetPathPattern)){"附件路径无效"}
    val expected=File(root.canonicalFile,relative)
    require(expected.canonicalFile==expected){"附件路径越界"}
    return expected
}
internal fun streamDigest(input:InputStream,output:OutputStream?=null,limit:Long=BackupPackage.MAX_ASSET):Pair<String,Long> {
    val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024);var size=0L
    while(true){
        if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException("操作已取消")
        val count=input.read(buffer);if(count<0)break;if(count==0)continue
        require(size<=limit-count){"附件超出安全大小限制"};size+=count
        hash.update(buffer,0,count);output?.write(buffer,0,count)
    }
    return hash.digest().joinToString(""){"%02x".format(it)} to size
}

/** Installs content-addressed attachments only. Never overwrites existing files. */
object ImportStorage {
    fun install(root:File,assets:Map<String,ByteArray>,needed:Set<String>):List<File> =
        install(root,ImportPlan(emptyList(),assets,emptyList(),"ASSETS"),needed)

    fun install(root:File,plan:ImportPlan,needed:Set<String>):List<File> {
        val created=mutableListOf<File>()
        try {
            for(relative in needed){
                val file=checkedAsset(root,relative)
                val expected=relative.substringAfter('/').substringBefore('.')
                fun source():InputStream = plan.assets[relative]?.inputStream()
                    ?: plan.stagedAssets[relative]?.inputStream() ?: error("附件数据缺失")
                if(file.exists()){
                    require(file.isFile && file.inputStream().use{streamDigest(it).first}==expected){"已有附件校验失败，未覆盖"}
                    source().use{require(streamDigest(it).first==expected){"附件校验失败"}}
                    continue
                }
                check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory){"附件目录创建失败"}
                checkedAsset(root,relative)
                val temp=File(file.parentFile,".${UUID.randomUUID()}.tmp")
                try {
                    temp.outputStream().use{out->source().use{input->require(streamDigest(input,out).first==expected){"附件校验失败"}};out.fd.sync()}
                    if (AttachmentPublisher.publish(temp,file,expected)) created+=file
                } finally {temp.delete()}
            }
            return created
        }catch(error:Throwable){
            // Published hashes can already be referenced by another editor. Retain them on failure;
            // only this operation's unpublished temporary files may be cleaned up.
            throw error
        }
    }
}
