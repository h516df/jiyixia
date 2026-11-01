package cn.jiaoban.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCatalogTest {

    private val hex = "a".repeat(64)

    private fun notesAsset(version: String, id: Long) =
        """{"id":$id,"size":100,"name":"Jiaoban-v$version-release-notes.json","digest":"sha256:$hex"}"""

    private fun apkAsset(version: String, id: Long, variant: String) =
        """{"id":$id,"size":200,"name":"Jiaoban-v$version-$variant.apk","digest":"sha256:$hex"}"""

    private fun shaAsset(version: String, id: Long) =
        """{"id":$id,"size":50,"name":"Jiaoban-v$version-SHA256.txt"}"""

    private val releases = """
        [
          {"tag_name":"v0.3","assets":[
            ${notesAsset("0.3", 1)},
            ${apkAsset("0.3", 2, "universal")},
            ${shaAsset("0.3", 3)}
          ]},
          {"tag_name":"v0.2","assets":[${notesAsset("0.2", 4)}]},
          {"tag_name":"some-branch","assets":[${apkAsset("9.9", 5, "universal")}]}
        ]
    """.trimIndent()

    @Test
    fun versionOfOnlyAcceptsReleaseTags() {
        assertEquals("0.3", UpdateCatalog.versionOf("v0.3"))
        assertEquals("1.12", UpdateCatalog.versionOf(" v1.12 "))
        assertNull(UpdateCatalog.versionOf("some-branch"))
        assertNull(UpdateCatalog.versionOf("v0.3.1"))
    }

    @Test
    fun versionRankComparesNumerically() {
        assertEquals(3, UpdateCatalog.versionRank("0.3"))
        assertEquals(112, UpdateCatalog.versionRank("1.12"))
        assertTrue(UpdateCatalog.versionRank("1.12") > UpdateCatalog.versionRank("0.30"))
    }

    @Test
    fun parseKeepsOnlyReleasesWithNotes() {
        val published = UpdateCatalog.parse(releases)
        assertEquals(2, published.size)
        assertEquals(listOf("0.3", "0.2"), published.map { it.version })
    }

    @Test
    fun newestIsTheHighestVersion() {
        assertEquals("0.3", UpdateCatalog.newest(UpdateCatalog.parse(releases))?.version)
    }

    @Test
    fun installedSameOrNewerMeansNoUpdate() {
        val published = UpdateCatalog.parse(releases).first { it.version == "0.3" }
        val notes = ReleaseNotes("0.3", 3, listOf("新增" to listOf("x")))
        assertTrue(UpdateCatalog.decide(notes, published.assets, 3, listOf("arm64-v8a")) is UpdateCatalog.Result.Latest)
        assertTrue(UpdateCatalog.decide(notes, published.assets, 4, listOf("arm64-v8a")) is UpdateCatalog.Result.Latest)
    }

    @Test
    fun newerVersionOffersUniversalApkWhenNoArm64Asset() {
        val published = UpdateCatalog.parse(releases).first { it.version == "0.3" }
        val notes = ReleaseNotes("0.3", 3, listOf("新增" to listOf("x")))
        val result = UpdateCatalog.decide(notes, published.assets, 2, listOf("arm64-v8a"))
        assertTrue(result is UpdateCatalog.Result.Available)
        val candidate = (result as UpdateCatalog.Result.Available).candidate
        assertEquals("Jiaoban-v0.3-universal.apk", candidate.apk.name)
        assertEquals("Jiaoban-v0.3-SHA256.txt", candidate.shaFile?.name)
    }

    @Test
    fun arm64AssetWinsWhenBothExist() {
        val assets = listOf(
            UpdateAsset(1, 200, "Jiaoban-v0.3-arm64.apk", null),
            UpdateAsset(2, 200, "Jiaoban-v0.3-universal.apk", null),
        )
        val notes = ReleaseNotes("0.3", 3, listOf("新增" to listOf("x")))
        val result = UpdateCatalog.decide(notes, assets, 2, listOf("arm64-v8a"))
        assertEquals("Jiaoban-v0.3-arm64.apk", (result as UpdateCatalog.Result.Available).candidate.apk.name)
    }

    @Test
    fun missingApkForThisDeviceFailsLoudly() {
        val assets = listOf(UpdateAsset(1, 200, "Jiaoban-v0.3-arm64.apk", null))
        val notes = ReleaseNotes("0.3", 3, listOf("新增" to listOf("x")))
        val failed = runCatching { UpdateCatalog.decide(notes, assets, 2, listOf("x86_64")) }.exceptionOrNull()
        assertTrue(failed is UpdateFailure)
        assertTrue((failed as UpdateFailure).userMessage.contains("适合这台手机"))
    }

    @Test
    fun apkNamesPreferArm64ThenUniversal() {
        assertEquals(
            listOf("Jiyixia-v0.3-arm64.apk", "Jiyixia-v0.3-universal.apk",
                "Jiaoban-v0.3-arm64.apk", "Jiaoban-v0.3-universal.apk"),
            UpdateConfig.apkNames("0.3", listOf("arm64-v8a")),
        )
        assertEquals(
            listOf("Jiyixia-v0.3-universal.apk", "Jiaoban-v0.3-universal.apk"),
            UpdateConfig.apkNames("0.3", listOf("x86_64")),
        )
    }

    @Test fun currentUpdaterPrefersNewBrandWhileLegacyInstallersStillFindOldAsset() {
        val assets=listOf(
            UpdateAsset(1,200,"Jiaoban-v0.24-universal.apk",null),
            UpdateAsset(2,200,"Jiyixia-v0.24-universal.apk",null),
        )
        val notes=ReleaseNotes("0.24",24,listOf("改进" to listOf("新界面")))
        val choice=UpdateCatalog.decide(notes,assets,23,listOf("arm64-v8a")) as UpdateCatalog.Result.Available
        assertEquals("Jiyixia-v0.24-universal.apk",choice.candidate.apk.name)
        assertTrue(UpdateConfig.publicApkUrl("https://github.com/h516df/app-releases/releases/download/jiyixia-stable-v0.24/Jiaoban-v0.24-universal.apk",
            "jiyixia-stable-v0.24","Jiaoban-v0.24-universal.apk"))
        assertTrue(UpdateConfig.publicApkUrl("https://github.com/h516df/app-releases/releases/download/jiyixia-stable-v0.24/Jiyixia-v0.24-universal.apk",
            "jiyixia-stable-v0.24","Jiyixia-v0.24-universal.apk"))
    }

    @Test
    fun onlyOurRepoApiUrlsAreAccepted() {
        assertTrue(UpdateConfig.apiUrl("https://api.github.com/repos/h516df/jiyixia/releases"))
        assertTrue(UpdateConfig.apiUrl("https://api.github.com/repos/h516df/jiyixia/releases/assets/1"))
        assertTrue(!UpdateConfig.apiUrl("https://api.github.com/repos/other/repo/releases"))
        assertTrue(!UpdateConfig.apiUrl("https://api.github.com/repos/h516df/Jiaoban/releases"))
        assertEquals(1413866214L,UpdateConfig.REPO_ID)
        assertEquals("cn.jiaoban",UpdateConfig.PACKAGE)
        assertEquals("Jiaoban",UpdateConfig.APK_PREFIX)
        assertEquals("https://github.com/h516df/app-releases/releases?q=jiyixia-stable",UpdateConfig.RELEASES_PAGE)
        assertTrue(!UpdateConfig.apiUrl("https://evil.example.com/repos/h516df/jiyixia/releases"))
        assertTrue(!UpdateConfig.apiUrl("http://api.github.com/repos/h516df/jiyixia/releases"))
    }

    @Test
    fun onlyGithubAssetHostsAreAcceptedAfterRedirect() {
        assertTrue(UpdateConfig.assetRedirect("https://objects.githubusercontent.com/abc"))
        assertTrue(UpdateConfig.assetRedirect("https://release-assets.githubusercontent.com/abc"))
        assertTrue(!UpdateConfig.assetRedirect("https://evil.example.com/abc"))
        assertTrue(!UpdateConfig.assetRedirect("http://objects.githubusercontent.com/abc"))
    }
}

