package cn.jiaoban.update

import java.net.URI

/** 更新路上出问题时给用户看的话：全中文，不带术语，不出现仓库、token、API 这些词。 */
class UpdateFailure(val userMessage: String) : Exception(userMessage)

internal fun requireUpdate(value: Boolean, message: String = "更新资料不完整，请稍后重试") {
    if (!value) throw UpdateFailure(message)
}

/**
 * 一个项目的更新配置。
 *
 * 新项目接入只改这六行（仓库、仓库 id、包名、安装包前缀、当前版本说明文件）。
 * 仓库地址和下载地址都是写死的常量：不接受外部传进来的地址，跳转也只能去 GitHub 的资产域名，
 * 这样即使有人伪造了发布资料，也带不走你的授权令牌。
 */
object UpdateConfig {

    const val REPO = "h516df/Jiaoban"
    const val REPO_ID = 1413866214L

    /** 授权页上要勾选的仓库名，界面上会念给用户听。 */
    const val REPO_NAME = "Jiaoban"

    /**
     * 公开的 GitHub App client id。它不是一个密钥，写进 APK 是安全的，所有项目可以共用同一个。
     * 设备授权时你只要在 GitHub 上勾选对应仓库（比如 Jiaoban），App 拿到的令牌就只有这个仓库的只读权限。
     * 任何情况下都不要把 client secret、PAT、登录 cookie、签名私钥放进这个文件。
     */
    const val CLIENT_ID = "Iv23liQM3N2avCTc0QAd"

    const val PACKAGE = "cn.jiaoban"
    const val APK_PREFIX = "Jiaoban"
    const val API = "https://api.github.com/repos/$REPO"
    const val VERIFY_URI = "https://github.com/login/device"
    const val MAX_APK = 120L * 1024 * 1024

    /** 只允许访问这个仓库自己的 API 路径。 */
    fun apiUrl(value: String): Boolean = runCatching {
        val u = URI(value)
        u.scheme == "https" && u.host == "api.github.com" && u.port == -1 &&
            u.userInfo == null && u.fragment == null &&
            (u.rawPath == "/repos/$REPO" || u.rawPath.startsWith("/repos/$REPO/")) &&
            !u.rawPath.contains("%") && !u.rawPath.contains("..")
    }.getOrDefault(false)

    /** 下载安装包时 GitHub 会跳一次，只认它自己的资产域名，且跳转后不再带令牌。 */
    fun assetRedirect(value: String): Boolean = runCatching {
        val u = URI(value)
        u.scheme == "https" && u.port == -1 && u.userInfo == null && u.fragment == null &&
            u.host in setOf(
                "release-assets.githubusercontent.com",
                "objects.githubusercontent.com",
                "github-releases.githubusercontent.com",
            )
    }.getOrDefault(false)

    /**
     * 发布时的安装包命名：`Jiaoban-v0.3-arm64.apk` 或 `Jiaoban-v0.3-universal.apk`。
     * 优先拿对应 CPU 架构的那个，没有再退到通用包。
     */
    fun apkNames(version: String, abis: List<String>): List<String> {
        val preferred = if (abis.firstOrNull() == "arm64-v8a") "arm64" else "universal"
        return listOf("$APK_PREFIX-v$version-$preferred.apk", "$APK_PREFIX-v$version-universal.apk").distinct()
    }

    fun notesName(version: String): String = "$APK_PREFIX-v$version-release-notes.json"

    fun shaName(version: String): String = "$APK_PREFIX-v$version-SHA256.txt"
}
