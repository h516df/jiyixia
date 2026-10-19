package cn.jiaoban.importing

import java.io.File
import java.util.UUID

/** Installs content-addressed attachments only. Never overwrites existing files. */
object ImportStorage {
    fun install(root:File,assets:Map<String,ByteArray>,needed:Set<String>):List<File> {
        val imports=File(root,"imports")
        require(imports.canonicalFile==File(root.canonicalFile,"imports")){"附件目录路径无效"}
        val created=mutableListOf<File>()
        try {
            for(relative in needed){
                require(relative.matches(Regex("imports/[a-f0-9]{64}(?:\\.[a-z0-9]{1,8})?"))){"附件路径无效"}
                val bytes=requireNotNull(assets[relative]){"附件数据缺失"}
                require(relative.substringAfter('/').substringBefore('.')==digest(bytes)){"附件校验失败"}
                val file=File(root,relative);require(file.canonicalPath.startsWith(File(root,"imports").canonicalPath+File.separator)){"附件路径越界"}
                if(file.exists()){require(file.isFile && digest(file.readBytes())==digest(bytes)){"已有附件校验失败，未覆盖"};continue}
                file.parentFile?.mkdirs();val temp=File(file.parentFile,".${UUID.randomUUID()}.tmp")
                try{temp.outputStream().use{it.write(bytes);it.fd.sync()};check(temp.renameTo(file)){"附件保存失败"};created+=file}finally{temp.delete()}
            }
            return created
        }catch(error:Throwable){created.forEach{it.delete()};throw error}
    }
}
