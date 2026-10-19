package cn.jiaoban.update

import android.content.Context
import java.io.File
import org.json.JSONObject

/** Only public release metadata, never credentials. Cached bytes are always reverified. */
internal data class ReadyUpdate(val name:String,val size:Long,val sha256:String,val apiDigest:String?,val code:Int) {
    fun encode()=JSONObject().put("name",name).put("size",size).put("sha256",sha256).put("apiDigest",apiDigest ?: JSONObject.NULL).put("code",code).toString()
    companion object {
        fun decode(raw:String):ReadyUpdate {
            val o=JSONObject(raw)
            val value=ReadyUpdate(o.getString("name"),o.getLong("size"),o.getString("sha256"),if(o.isNull("apiDigest"))null else o.getString("apiDigest"),o.getInt("code"))
            requireUpdate(value.name.matches(Regex("Jiaoban-v[0-9]+(?:\\.[0-9]+)*-(?:arm64|universal)\\.apk")),"本机更新文件名无效，请重新检查")
            requireUpdate(value.size in 1..UpdateConfig.MAX_APK && value.code>0,"本机更新资料无效，请重新检查")
            requireUpdate(value.sha256.matches(Regex("[a-f0-9]{64}")) && (value.apiDigest==null || value.apiDigest==value.sha256),"本机更新校验资料无效，请重新检查")
            return value
        }
    }
}
internal class ReadyUpdateStore(context:Context) {
    private val app=context.applicationContext
    private val prefs=app.getSharedPreferences("update_ready_v1",Context.MODE_PRIVATE)
    fun load():ReadyUpdate?=prefs.getString("ready",null)?.let{ReadyUpdate.decode(it)}
    fun wasInterrupted()=prefs.getBoolean("downloading",false)
    fun beginDownload(){check(prefs.edit().remove("ready").putBoolean("downloading",true).commit()){ "无法保留更新进度，请稍后重试" }}
    fun save(value:ReadyUpdate){ReadyUpdate.decode(value.encode());check(prefs.edit().putString("ready",value.encode()).putBoolean("downloading",false).commit()){ "无法保留安装包信息，请重新下载" }}
    fun clear(){prefs.edit().clear().commit()}
    fun file(value:ReadyUpdate):File {
        ReadyUpdate.decode(value.encode())
        val expectedRoot=File(app.cacheDir.canonicalFile,"updates")
        val root=expectedRoot.canonicalFile
        requireUpdate(root==expectedRoot,"本机更新目录路径无效")
        val file=File(root,value.name)
        requireUpdate(file.canonicalFile.parentFile==root,"本机更新文件路径无效")
        return file
    }
    fun verifyBytes(value:ReadyUpdate):File=file(value).also{UpdateVerify.verifyHash(it,value.size,value.sha256,value.apiDigest)}
}
