package cn.jiaoban.update

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors

/** 更新界面要显示的状态。 */
sealed class UpdateState {
    object Idle : UpdateState()
    object Restoring : UpdateState()
    object Verifying : UpdateState()
    object NeedInstallPermission : UpdateState()
    object Checking : UpdateState()
    class NeedAuth(val userCode: String, val verifyUri: String) : UpdateState()
    class Latest(val notes: ReleaseNotes?) : UpdateState()
    class Available(val candidate: UpdateCandidate) : UpdateState()
    class Downloading(val percent: Int) : UpdateState()
    class ReadyToInstall(val file: File) : UpdateState()
    class Failed(val message: String) : UpdateState()
}

/**
 * 检查更新 → 下载 → 校验 → 交给系统安装。
 *
 * 联网、授权、下载都在后台线程，状态回主线程；任何一步失败都只影响这次更新，旧版本继续用。
 */
class Updater internal constructor(
    context:Context,
    private val archiveVerifier:(Context,File,Int)->Unit=UpdateVerify::verifyArchive,
    private val installAllowed:(Context)->Boolean={it.packageManager.canRequestPackageInstalls()},
    private val installer:(Context,File)->Unit={ctx,file->
        val uri=FileProvider.getUriForFile(ctx,"${ctx.packageName}.updates",file)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    },
) {
    private val context=context.applicationContext
    companion object {
        @Volatile private var instance:Updater?=null
        fun forApplication(context:Context):Updater = instance ?: synchronized(this){instance ?: Updater(context.applicationContext).also{instance=it}}
    }
    private val readyStore=ReadyUpdateStore(this.context)
    @Volatile private var ready:ReadyUpdate?=null

    @Volatile private var http = UpdateHttp()
    private val tokens = UpdateTokenStore(this.context)
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jiaoban-update").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    var state by mutableStateOf<UpdateState>(UpdateState.Restoring)
        private set

    val busy:Boolean get()=state is UpdateState.Restoring || state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Verifying || state is UpdateState.NeedAuth

    private var candidate: UpdateCandidate? = null
    @Volatile private var cancelled = false
    private val authorizationLock=Any()

    init {
        // Disk-only recovery; opening the app never checks GitHub or downloads.
        io.execute {
            val restored=runCatching {
                val saved=readyStore.load()
                if(saved==null){
                    if(readyStore.wasInterrupted())throw UpdateFailure("上次下载已中断，请点击检查更新后重试")
                    UpdateState.Idle
                }else if(saved.code<=UpdateVerify.installedVersionCode(this.context)){
                    readyStore.clear();UpdateState.Idle
                }else{
                    val file=readyStore.verifyBytes(saved)
                    archiveVerifier(this.context,file,saved.code)
                    ready=saved;UpdateState.ReadyToInstall(file)
                }
            }
            restored.onSuccess{set(it)}.onFailure{readyStore.clear();set(UpdateState.Failed((it as? UpdateFailure)?.userMessage ?: "本机安装包不可用，请重新检查更新"))}
        }
    }

    /** 当前版本的更新说明，打进 App 里，没网也能看。 */
    fun currentNotes(): ReleaseNotes? = runCatching {
        context.assets.open("release-notes.json").use {
            ReleaseNotes.decode(String(it.readBytes(), Charsets.UTF_8))
        }
    }.getOrNull()

    fun installedVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(UpdateConfig.PACKAGE, 0).versionName.orEmpty()
    }.getOrDefault("")

    fun installedVersionCode(): Int = runCatching { UpdateVerify.installedVersionCode(context) }.getOrDefault(0)

    fun check() {
        if (busy) return
        cancelled = false
        candidate = null
        set(UpdateState.Checking)
        io.execute {
            // 上次取消过的请求对象不能复用；单线程队列保证旧任务已退出。
            http = UpdateHttp()
            try {
                val token = tokenOrAuthorize()
                val releases = http.json("/releases?per_page=20", token)
                val published = UpdateCatalog.parse(releases)
                val newest = UpdateCatalog.newest(published)
                    ?: throw UpdateFailure("还没发布过带更新说明的版本，请稍后再试")
                val notes = ReleaseNotes.decode(http.assetText(newest.notesAsset.id, token))
                val installed = UpdateVerify.installedVersionCode(context)
                when (val result = UpdateCatalog.decide(notes, newest.assets, installed, UpdateVerify.deviceAbis())) {
                    is UpdateCatalog.Result.Latest -> set(UpdateState.Latest(result.notes))
                    is UpdateCatalog.Result.Available -> {
                        candidate = result.candidate
                        set(UpdateState.Available(result.candidate))
                    }
                }
            } catch (e: UpdateFailure) {
                set(UpdateState.Failed(e.userMessage))
            } catch (e: InterruptedException) {
                set(UpdateState.Idle)
            } catch (e: Exception) {
                set(UpdateState.Failed("检查更新失败，请稍后重试"))
            }
        }
    }

    fun download() {
        val target = candidate ?: return
        if (state !is UpdateState.Available) return
        cancelled = false
        set(UpdateState.Downloading(0))
        io.execute {
            // 上次取消过的请求对象不能复用；单线程队列保证旧任务已退出。
            http = UpdateHttp()
            try {
                val token = tokens.load()?.access ?: throw UpdateFailure("授权失效了，请重新检查更新")
                readyStore.beginDownload();ready=null
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val dest = File(dir, target.apk.name)
                http.downloadApk(target.apk.id, token, target.apk.size, dest) { percent ->
                    set(UpdateState.Downloading(percent))
                }
                val expectedSha = target.shaFile?.let { UpdateVerify.parseShaFile(http.assetText(it.id, token)) }
                    ?: target.apk.apiDigest
                    ?: throw UpdateFailure("发布资料里缺校验值，请稍后再试")
                UpdateVerify.verifyHash(dest, target.apk.size, expectedSha, target.apk.apiDigest)
                archiveVerifier(context, dest, target.notes.code)
                val verified=ReadyUpdate(target.apk.name,target.apk.size,expectedSha,target.apk.apiDigest,target.notes.code)
                readyStore.save(verified);ready=verified
                set(UpdateState.ReadyToInstall(dest))
            } catch (e: UpdateFailure) {
                readyStore.clear()
                set(UpdateState.Failed(e.userMessage))
            } catch (e: InterruptedException) {
                readyStore.clear()
                set(UpdateState.Idle)
            } catch (e: Exception) {
                readyStore.clear()
                set(UpdateState.Failed("下载或校验失败，请稍后重试"))
            }
        }
    }

    /** 交给系统安装器；装不装由你在系统界面上确认，App 不会偷偷装。 */
    fun install() {
        if(state !is UpdateState.ReadyToInstall && state !is UpdateState.NeedInstallPermission)return
        val expected=ready ?: return
        if(android.os.Build.VERSION.SDK_INT>=26 && !installAllowed(context)){
            state=UpdateState.NeedInstallPermission;return
        }
        state=UpdateState.Verifying
        io.execute {
            try {
                val file=readyStore.verifyBytes(expected)
                archiveVerifier(context,file,expected.code)
                main.post {
                    try {
                        installer(context,file)
                        state=UpdateState.ReadyToInstall(file)
                    }catch(e:Exception){state=UpdateState.Failed("打不开系统安装界面，请稍后重试")}
                }
            }catch(e:Exception){ready=null;readyStore.clear();set(UpdateState.Failed((e as? UpdateFailure)?.userMessage ?: "安装前复核失败，请重新检查更新"))}
        }
    }
    fun openInstallPermissionSettings(){
        if(state !is UpdateState.NeedInstallPermission)return
        try{context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,android.net.Uri.parse("package:${UpdateConfig.PACKAGE}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
        catch(e:Exception){set(UpdateState.Failed("无法打开安装权限设置，请在系统设置中允许此应用安装后重试"))}
    }

    /** 忘掉这台手机上的授权（令牌存在手机里，随时可以清掉）。 */
    fun forgetAuthorization() {
        if(busy)return
        tokens.clear()
        candidate = null
        val saved=ready
        set(if(saved!=null)UpdateState.ReadyToInstall(readyStore.file(saved)) else UpdateState.Idle)
    }

    fun hasAuthorization(): Boolean = tokens.load() != null

    fun cancel() {
        synchronized(authorizationLock){cancelled=true;if(state is UpdateState.NeedAuth)tokens.clear()}
        http.cancel()
        // 发现新版本后点“稍后”时没有后台任务负责回到首页。
        if (state is UpdateState.Available) set(UpdateState.Idle)
    }

    private fun set(value: UpdateState) {
        if(Looper.myLooper()==Looper.getMainLooper())state=value else main.post { state = value }
    }

    private fun tokenOrAuthorize(): String {
        val now = System.currentTimeMillis()
        val stored = tokens.load()
        if (stored != null) {
            if (!stored.expired(now)) return stored.access
            if (!stored.refreshExpired(now)) {
                runCatching { return refresh(stored.refresh) }
            }
            tokens.clear()
        }
        return authorize()
    }

    private fun refresh(refreshToken: String): String {
        val json = http.oauth(
            "oauth/access_token",
            mapOf(
                "client_id" to UpdateConfig.CLIENT_ID,
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
            ),
        )
        val authorization = UpdateAuthorization.fromResponse(json, System.currentTimeMillis())
        persistAuthorization(authorization)
        return authorization.access
    }

    private fun persistAuthorization(value:UpdateAuthorization){
        synchronized(authorizationLock){if(cancelled)throw InterruptedException();tokens.save(value)}
    }

    /** 设备授权：给你一个码，你在浏览器里输一次，并勾选 Jiaoban 这个仓库。 */
    private fun authorize(): String {
        val grant = UpdateGrant.decode(
            http.oauth("device/code", mapOf("client_id" to UpdateConfig.CLIENT_ID)),
            System.currentTimeMillis(),
        )
        set(UpdateState.NeedAuth(grant.userCode, UpdateConfig.VERIFY_URI))
        var interval = grant.intervalSeconds
        while (System.currentTimeMillis() < grant.expiresAt) {
            if (cancelled) throw InterruptedException()
            Thread.sleep(interval * 1000L)
            val poll = UpdatePoll.decode(
                http.oauth(
                    "oauth/access_token",
                    mapOf(
                        "client_id" to UpdateConfig.CLIENT_ID,
                        "device_code" to grant.deviceCode,
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    ),
                ),
                interval,
                System.currentTimeMillis(),
            )
            when (poll) {
                is UpdatePoll.Pending -> interval = poll.nextIntervalSeconds
                is UpdatePoll.Approved -> {
                    persistAuthorization(poll.authorization)
                    return poll.authorization.access
                }
            }
        }
        throw UpdateFailure("授权超时了，请重新点检查更新")
    }
}
