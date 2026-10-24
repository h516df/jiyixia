package cn.jiaoban.update

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class PublicUpdateTest {
    private fun fixture() = javaClass.getResource("/jiyixia-stable.json")!!.readText()
    @After fun reset() { UpdateHttp.publicRetryAt = 0 }
    @Test fun realPublishedContractAndFinalNotesAreRuntimeInputs() {
        val available = PublicUpdateCatalog.decide(fixture(), 17, 26, listOf("arm64-v8a")) as UpdateCatalog.Result.Available
        assertEquals(18, available.candidate.notes.code)
        assertEquals(2928598L, available.candidate.apk.size)
        assertTrue(available.candidate.notes.sections.any { it.first == "说明" })
        assertNotNull(available.candidate.publicUrl)
        assertTrue(PublicUpdateCatalog.decide(fixture(), 18, 26, listOf("x86_64")) is UpdateCatalog.Result.Latest)
        assertTrue(PublicUpdateCatalog.decide(fixture(), 19, 26, listOf("x86")) is UpdateCatalog.Result.Latest)
        val bundled = ReleaseNotes.decode(File("src/main/assets/release-notes.json").readText())
        val build = File("build.gradle.kts").readText()
        val packageCode = Regex("""versionCode\s*=\s*(\d+)""").find(build)!!.groupValues[1].toInt()
        val packageVersion = Regex("""versionName\s*=\s*["]([^"]+)["]""").find(build)!!.groupValues[1]
        assertEquals(packageCode, bundled.code)
        assertEquals(packageVersion, bundled.version)
        assertTrue(bundled.sections.isNotEmpty())
    }
    @Test fun malformedWrongIdentityAndUnsupportedDeviceAreRecoverable() {
        val bad = listOf<(JSONObject)->Unit>(
            { it.put("appId", "other") }, { it.put("channel", "beta") },
            { it.put("repositoryId", 1) }, { it.put("applicationId", "wrong") },
            { it.put("status", "unavailable") }, { it.put("schemaVersion", 2) },
            { it.getJSONObject("release").put("signingCertificateSha256", "0".repeat(64)) },
            { it.getJSONObject("release").getJSONObject("apk").put("url", "https://evil.invalid/a.apk") },
            { it.getJSONObject("release").getJSONObject("apk").put("sha256", "invalid") },
            { it.getJSONObject("release").getJSONObject("apk").put("size", 0) },
            { it.getJSONObject("release").put("notes", JSONObject()) },
        )
        bad.forEach { mutate ->
            val json = JSONObject(fixture()); mutate(json)
            assertTrue(runCatching { PublicUpdateCatalog.decide(json.toString(), 17, 26, listOf("arm64-v8a")) }.exceptionOrNull() is UpdateFailure)
        }
        assertTrue(runCatching { PublicUpdateCatalog.decide("oops", 17, 26, listOf("x86")) }.exceptionOrNull() is UpdateFailure)
        assertTrue(runCatching { PublicUpdateCatalog.decide(fixture(), 17, 25, listOf("x86")) }.exceptionOrNull() is UpdateFailure)
        assertTrue(runCatching { PublicUpdateCatalog.decide(fixture(), 17, 26, listOf("riscv")) }.exceptionOrNull() is UpdateFailure)
    }
    private class Fake(val code: Int, val data: ByteArray = byteArrayOf(), val headers: Map<String,String> = emptyMap()): HttpURLConnection(URL("https://api.github.com")) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getHeaderField(name: String) = headers[name]
        override fun getInputStream() = ByteArrayInputStream(data)
    }
    @Test fun anonymousRawManifestAndRateLimitDoNotAuthorizeOrLoop() {
        val ok = Fake(200, fixture().toByteArray())
        val seen = mutableListOf<String>()
        val http = UpdateHttp({ seen.add(it); ok }, { 1000L })
        assertEquals(fixture(), http.publicManifest())
        assertEquals(listOf(UpdateConfig.PUBLIC_MANIFEST), seen)
        assertEquals("application/vnd.github.raw+json", ok.getRequestProperty("Accept"))
        assertNull(ok.getRequestProperty("Authorization"))
        var calls = 0
        val limited = UpdateHttp({ calls++; Fake(429, headers = mapOf("Retry-After" to "120")) }, { 1000L })
        repeat(2) { assertTrue(runCatching { limited.publicManifest() }.exceptionOrNull() is UpdateFailure) }
        assertEquals(1, calls)
        assertEquals(121000L, UpdateHttp.publicRetryAt)
    }
    @Test fun downloadRedirectHasNoCredentialsAndRejectsBadBytesOrDestinations() {
        val candidate = (PublicUpdateCatalog.decide(fixture(), 17, 26, listOf("x86")) as UpdateCatalog.Result.Available).candidate
        val requests = mutableListOf<Fake>()
        val http = UpdateHttp({
            (if(requests.isEmpty()) Fake(302, headers = mapOf("Location" to "https://release-assets.githubusercontent.com/a")) else Fake(200, byteArrayOf(1,2,3))).also { requests.add(it) }
        })
        val file = File.createTempFile("public-update", ".apk")
        try {
            http.downloadPublicApk(candidate.publicUrl!!, 3, file) {}
            assertArrayEquals(byteArrayOf(1,2,3), file.readBytes())
            assertTrue(requests.all { it.getRequestProperty("Authorization") == null })
            assertTrue(runCatching { UpdateVerify.verifyHash(file,3,"0".repeat(64),null) }.exceptionOrNull() is UpdateFailure)
            val wrong = UpdateHttp({ Fake(302, headers = mapOf("Location" to "https://evil.invalid/a")) })
            assertTrue(runCatching { wrong.downloadPublicApk(candidate.publicUrl,3,file) {} }.exceptionOrNull() is UpdateFailure)
            listOf(2L,4L).forEach { size ->
                val short = UpdateHttp({ Fake(200, byteArrayOf(1,2,3)) })
                assertTrue(runCatching { short.downloadPublicApk(candidate.publicUrl,size,file) {} }.exceptionOrNull() is UpdateFailure)
            }
        } finally { file.delete() }
    }
    @Test fun errorsAndCancelAreRecoverable() {
        listOf(404,500,403).forEach { code ->
            UpdateHttp.publicRetryAt = 0
            assertTrue(runCatching { UpdateHttp({Fake(code)}).publicManifest() }.exceptionOrNull() is UpdateFailure)
        }
        UpdateHttp.publicRetryAt = 0
        val cancelled = UpdateHttp({ error("Must not connect") }); cancelled.cancel()
        assertTrue(runCatching { cancelled.publicManifest() }.exceptionOrNull() is InterruptedException)
    }
}
