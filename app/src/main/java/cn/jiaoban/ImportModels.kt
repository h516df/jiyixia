package cn.jiaoban

/** Optional metadata; old JSON remains valid and original source files stay local. */
data class ImportInfo(
    val format:String,
    val key:String,
    val originalPath:String,
    val originalDate:String?=null,
    val originalTitle:String?=null,
    val metadata:String?=null,
)
data class ImportedAttachment(
    val sha256:String,
    val name:String,
    val relativePath:String,
    val mimeType:String,
    val sourceFile:Boolean=false,
)
