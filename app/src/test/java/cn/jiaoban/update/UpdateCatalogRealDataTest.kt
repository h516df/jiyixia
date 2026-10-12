package cn.jiaoban.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 拿真实的发布数据（快照）喂给挑选逻辑，确认「App 里检查更新」真的会认出最新版。
 * 快照由 `gh api repos/h516df/Jiaoban/releases` 生成，只用于测试，不含任何凭据。
 */
class UpdateCatalogRealDataTest {

    private fun snapshot(): String {
        val candidates = listOf(
            "src/test/resources/releases-snapshot.json",
            "app/src/test/resources/releases-snapshot.json",
        )
        for (path in candidates) {
            val file = File(path)
            if (file.isFile) return file.readText()
        }
        throw AssertionError("找不到发布快照：" + candidates.joinToString())
    }

    @Test
    fun realReleasesPassTheFilters() {
        val published = UpdateCatalog.parse(snapshot())
        assertTrue("真实发布数据里一个可用版本都没有", published.isNotEmpty())
        assertTrue(
            "每个留下来的版本都必须挂说明文件",
            published.all { it.notesAsset.name.endsWith("-release-notes.json") },
        )
    }

    @Test
    fun newestReleaseIsOfferedToAnOlderInstall() {
        val newest = UpdateCatalog.newest(UpdateCatalog.parse(snapshot()))!!
        // 约定：versionCode = major*100 + minor，和发布脚本一致
        val code = UpdateCatalog.versionRank(newest.version)
        val notes = ReleaseNotes(newest.version, code, listOf("说明" to listOf("测试用")))

        val offered = UpdateCatalog.decide(notes, newest.assets, installedCode = code - 1, abis = listOf("arm64-v8a"))
        assertTrue("装了旧版却没给出新版：" + newest.version, offered is UpdateCatalog.Result.Available)
        val apk = (offered as UpdateCatalog.Result.Available).candidate.apk.name
        assertTrue("挑出来的安装包名字不对：$apk", apk.startsWith(UpdateConfig.APK_PREFIX + "-v" + newest.version))

        val upToDate = UpdateCatalog.decide(notes, newest.assets, installedCode = code, abis = listOf("arm64-v8a"))
        assertTrue("已经是最新版却还提示更新", upToDate is UpdateCatalog.Result.Latest)
    }

    @Test
    fun everyReleaseCarriesItsThreeAssets() {
        val published = UpdateCatalog.parse(snapshot())
        for (item in published) {
            val names = item.assets.map { it.name }
            assertTrue("${item.version} 缺说明文件", names.contains(UpdateConfig.notesName(item.version)))
            assertTrue("${item.version} 缺 SHA256 文件", names.contains(UpdateConfig.shaName(item.version)))
            assertTrue(
                "${item.version} 缺安装包",
                UpdateConfig.apkNames(item.version, listOf("arm64-v8a")).any { names.contains(it) },
            )
        }
    }
}
