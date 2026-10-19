package cn.jiaoban.importing

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.security.MessageDigest

fun digest(bytes:ByteArray):String=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
data class ImportBundle(val files:Map<String,ByteArray>,val originalName:String,val originalBytes:ByteArray) {
    companion object {
        const val MAX_FILE=32*1024*1024
        const val MAX_TOTAL=64*1024*1024
        const val MAX_ENTRIES=10000
        const val MAX_RECORDS=5000
        private fun readBounded(input:InputStream,limit:Int):ByteArray {
            val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){val count=input.read(buffer);if(count<0)break;require(out.size().toLong()+count<=limit){"文件超出安全大小限制"};out.write(buffer,0,count)}
            return out.toByteArray()
        }
        fun read(name:String,input:InputStream):ImportBundle {
            val original=readBounded(input,MAX_TOTAL)
            val safeName=name.substringAfterLast('/').substringAfterLast('\\').ifBlank{"import"}
            if(!safeName.endsWith(".zip",true)){require(original.size<=MAX_FILE){"文件超出安全大小限制"};return ImportBundle(mapOf(safeName to original),safeName,original)}
            val files=linkedMapOf<String,ByteArray>();var total=0;var entries=0;val paths=mutableSetOf<String>()
            ZipInputStream(original.inputStream()).use{zip->
                while(true){val entry=zip.nextEntry ?: break
                    require(++entries<=MAX_ENTRIES){"压缩包条目过多"}
                    val path=safeArchivePath(entry.name)
                    require(paths.add(path)){"压缩包含重复路径"}
                    val bytes=readBounded(zip,if(entry.isDirectory)0 else minOf(MAX_FILE,MAX_TOTAL-total))
                    total+=bytes.size
                    if(!entry.isDirectory)files[path]=bytes
                    zip.closeEntry()
                }
            }
            require(files.isNotEmpty()){ "压缩包没有可读取文件" }
            return ImportBundle(files,safeName,original)
        }
        fun safeArchivePath(raw:String):String {
            val path=raw.replace('\\','/');require(!path.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(path) && '\u0000' !in path){"拒绝绝对路径"}
            val parts=path.split('/').filter{it.isNotEmpty() && it!="."};require(parts.none{it==".."}){"拒绝越界路径"}
            require(parts.isNotEmpty()){ "空路径" };return parts.joinToString("/")
        }
    }
}
