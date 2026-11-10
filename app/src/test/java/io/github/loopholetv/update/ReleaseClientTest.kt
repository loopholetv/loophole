package io.github.loopholetv.update

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ReleaseClientTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun client() = ReleaseClient(http, { server.url("/repos/loopholetv/loophole/releases/latest").toString() }, "Loophole/0.4.1")

    private fun respond(code: Int, body: String) =
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    @Test
    fun parsesTagAndTheFirstApkAsset() = runTest {
        respond(
            200,
            """
            {"tag_name":"v0.4.2","name":"Loophole 0.4.2","draft":false,"prerelease":false,
             "assets":[
               {"name":"checksums.txt","browser_download_url":"https://example.org/checksums.txt","size":120},
               {"name":"loophole-0.4.2.apk","browser_download_url":"https://example.org/loophole-0.4.2.apk","size":12345678,
                "uploader":{"login":"x"}},
               {"name":"other.apk","browser_download_url":"https://example.org/other.apk","size":1}
             ],
             "body":"Notes"}
            """.trimIndent(),
        )
        val release = client().latest()
        assertEquals(Release(SemVer(0, 4, 2), "https://example.org/loophole-0.4.2.apk", 12345678L), release)
    }

    @Test
    fun sendsTheGithubHeaders() = runTest {
        respond(200, """{"tag_name":"v0.4.2","assets":[]}""")
        client().latest()
        val request = server.takeRequest()
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        assertEquals("Loophole/0.4.1", request.getHeader("User-Agent"))
        assertEquals("/repos/loopholetv/loophole/releases/latest", request.path)
    }

    @Test
    fun noApkAssetGivesNull() = runTest {
        respond(200, """{"tag_name":"v0.4.2","assets":[{"name":"src.zip","browser_download_url":"https://e/x.zip","size":3}]}""")
        assertNull(client().latest())
    }

    @Test
    fun anUnreadableTagGivesNull() = runTest {
        respond(200, """{"tag_name":"nightly","assets":[{"name":"a.apk","browser_download_url":"https://e/a.apk","size":3}]}""")
        assertNull(client().latest())
    }

    @Test
    fun httpErrorsGiveNull() = runTest {
        respond(404, """{"message":"Not Found"}""")
        assertNull(client().latest())
        respond(403, """{"message":"API rate limit exceeded"}""")
        assertNull(client().latest())
    }

    @Test
    fun malformedJsonGivesNull() = runTest {
        respond(200, """{"tag_name":"v0.4.2","assets":[{""")
        assertNull(client().latest())
        respond(200, "<html>not json</html>")
        assertNull(client().latest())
        respond(200, "[]")
        assertNull(client().latest())
    }

    @Test
    fun aNonHttpsAssetUrlOrMissingSizeGivesNull() = runTest {
        respond(200, """{"tag_name":"v0.4.2","assets":[{"name":"a.apk","browser_download_url":"ftp://e/a.apk","size":3}]}""")
        assertNull(client().latest())
        respond(200, """{"tag_name":"v0.4.2","assets":[{"name":"a.apk","browser_download_url":"https://e/a.apk"}]}""")
        assertNull(client().latest())
    }

    @Test
    fun networkFailureGivesNull() = runTest {
        server.shutdown()
        assertNull(client().latest())
    }
}
