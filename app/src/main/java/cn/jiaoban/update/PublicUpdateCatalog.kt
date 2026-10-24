package cn.jiaoban.update

import org.json.JSONObject

/** Adapter for the shared public release contract; uses the existing verified installer. */
internal object PublicUpdateCatalog {
    fun decide(text: String, installedCode: Int, sdk: Int, abis: List<String>): UpdateCatalog.Result {
        try {
            val root = JSONObject(text)
            requireUpdate(root.getInt("schemaVersion") == 1 &&
                root.getLong("repositoryId") == UpdateConfig.PUBLIC_REPO_ID &&
                root.getString("appId") == "jiyixia" && root.getString("channel") == "stable" &&
                root.getString("applicationId") == UpdateConfig.PACKAGE)
            requireUpdate(root.getString("status") == "published", "正式更新暂时不可用，请稍后重试")
            val release = root.getJSONObject("release")
            val version = release.getString("versionName")
            val code = release.getInt("versionCode")
            requireUpdate(release.getString("signingCertificateSha256") == UpdateConfig.CERTIFICATE_SHA256,
                "更新签名资料不可信，已停止更新")
            val minSdk = release.getInt("minSdk")
            requireUpdate(minSdk >= 26)
            val supported = release.getJSONArray("abis")
            requireUpdate(supported.length() in 1..4)
            val supportedAbis = (0 until supported.length()).map { supported.getString(it) }
            requireUpdate(supportedAbis.all { it in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") })
            val notes = ReleaseNotes.decode(release.getJSONObject("notes").toString().let {
                JSONObject(it).put("versionName", version).put("versionCode", code).toString()
            })
            val tag = release.getString("tag")
            requireUpdate(tag == "jiyixia-stable-v$version" && release.getLong("releaseId") > 0)
            val source = release.getJSONObject("source")
            requireUpdate(source.getLong("repositoryId") == UpdateConfig.REPO_ID &&
                source.getString("tag") == "v$version" &&
                source.getString("commit").matches(Regex("[a-f0-9]{40}")))
            val apk = release.getJSONObject("apk")
            val name = apk.getString("fileName")
            val url = apk.getString("url")
            val size = apk.getLong("size")
            val sha = apk.getString("sha256")
            val id = apk.getLong("assetId")
            requireUpdate(id > 0 && size in 1..UpdateConfig.MAX_APK && sha.matches(Regex("[a-f0-9]{64}")))
            requireUpdate(UpdateConfig.publicApkUrl(url, tag, name), "更新下载地址不可信，已停止")
            requireUpdate(name in UpdateConfig.apkNames(version, abis), "新版还没提供适合这台手机的安装包，请稍后再试")
            if (code <= installedCode) return UpdateCatalog.Result.Latest(notes)
            requireUpdate(sdk >= minSdk, "新版需要更高的安卓版本，当前版本仍可继续使用")
            requireUpdate(abis.any { it in supportedAbis }, "新版还没提供适合这台手机的安装包，请稍后再试")
            return UpdateCatalog.Result.Available(UpdateCandidate(notes, UpdateAsset(id, size, name, sha), null, url))
        } catch (e: UpdateFailure) {
            throw e
        } catch (e: Exception) {
            throw UpdateFailure("更新资料不完整，请稍后重试")
        }
    }
}
