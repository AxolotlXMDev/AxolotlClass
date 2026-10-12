package github.axolotl.aclass.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class SchoolClientTest {
    private val account = AccountSettings("student", "密码&+", "202601")
    private fun warmup() = MockResponse().setBody("").addHeader("Set-Cookie", "session=abc; Path=/")
    private fun login() = MockResponse().setBody("{\"status\":\"y\"}")
    private fun table() = MockResponse().setBody("[[${testRaw()}]]")

    @Test fun loginUsesCookieAndEncodedBase64ThenQueriesTerm() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(warmup()); server.enqueue(login()); server.enqueue(table())
            val courses = SchoolClient(server.url("/").toString()).fetch(account)
            assertEquals(1, courses.size)
            assertEquals("POST", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/login!doLogin.action", request.path)
            assertEquals("session=abc", request.getHeader("Cookie"))
            val body = request.body.readUtf8()
            assertTrue(body.startsWith("account=student&pwd="))
            assertFalse(body.contains(account.password))
            assertEquals("/xsgrkbcx!getKbRq.action?xnxqdm=202601", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
        }
    }

    @Test fun expiredSessionLogsInOnceAgain() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(warmup()); server.enqueue(login()); server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/login"))
            server.enqueue(warmup()); server.enqueue(login()); server.enqueue(table())
            assertEquals(1, SchoolClient(server.url("/").toString()).fetch(account).size)
            assertEquals(6, server.requestCount)
        }
    }

    @Test fun badPasswordAndMalformedTableFail() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(warmup()); server.enqueue(MockResponse().setBody("{\"status\":\"n\",\"msg\":\"密码错误\"}"))
            assertEquals("密码错误", runCatching { SchoolClient(server.url("/").toString()).fetch(account) }.exceptionOrNull()?.message)
            server.enqueue(warmup()); server.enqueue(login()); server.enqueue(MockResponse().setBody("{\"status\":\"n\"}"))
            assertTrue(runCatching { SchoolClient(server.url("/").toString()).fetch(account) }.isFailure)
        }
    }

    @Test fun repeatedSessionFailureStopsAfterOneRetry() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(warmup()); server.enqueue(login()); server.enqueue(MockResponse().setResponseCode(403)) }
            assertTrue(runCatching { SchoolClient(server.url("/").toString()).fetch(account) }.isFailure)
            assertEquals(6, server.requestCount)
        }
    }
}
