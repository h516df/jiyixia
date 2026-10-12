package cn.jiaoban.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLStreamHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class UpdateCancellationTest {
    companion object {
        init {
            // 真实请求边界的离线夹具，测试不访问 GitHub、不读取授权。
            URL.setURLStreamHandlerFactory { protocol ->
                if (protocol != "https") null else object : URLStreamHandler() {
                    override fun openConnection(url: URL) = object : HttpURLConnection(url) {
                        override fun connect() {}
                        override fun disconnect() {}
                        override fun usingProxy() = false
                        override fun getResponseCode() = 200
                        override fun getInputStream() = ByteArrayInputStream("[]".toByteArray())
                        override fun getOutputStream() = ByteArrayOutputStream()
                    }
                }
            }
        }
    }

    @Test fun cancelStopsCurrentRequestButFreshSessionCanReadTheCatalog() {
        val old = UpdateHttp()
        old.cancel()
        assertThrows(InterruptedException::class.java) { old.json("/releases?per_page=20", "fixture") }
        val next = UpdateHttp()
        assertEquals("[]", next.json("/releases?per_page=20", "fixture"))
        // 不能靠复位旧对象解除正在取消的请求。
        assertThrows(InterruptedException::class.java) { old.assetText(1, "fixture") }
    }

    @Test fun cancelAlsoStopsDeviceAuthorizationBeforeAnyRequest() {
        val http = UpdateHttp()
        http.cancel()
        assertThrows(InterruptedException::class.java) { http.oauth("device/code", emptyMap()) }
    }
}