package github.axolotl.aclass.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface SchoolGateway { suspend fun fetch(account: AccountSettings): List<CourseRecord> }

class SchoolClient(private val baseUrl: String = "https://jwxt.gdupt.edu.cn") : SchoolGateway {
    private val baseClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
    override suspend fun fetch(account: AccountSettings): List<CourseRecord> {
        // One client and cookie jar per refresh isolate credentials even when accounts change.
        val cookieJar = object : CookieJar {
            private val storedCookies = mutableListOf<Cookie>()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                synchronized(storedCookies) {
                    cookies.forEach { cookie ->
                        storedCookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                        storedCookies += cookie
                    }
                }
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(storedCookies) {
                storedCookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
            }
        }
        val client = baseClient.newBuilder().cookieJar(cookieJar).build()
        login(client, account)
        return try { timetable(client, account.term) } catch (_: SessionExpired) {
            login(client, account)
            timetable(client, account.term)
        }
    }

    private fun request(path: String): Request.Builder = Request.Builder().url(baseUrl.trimEnd('/') + path)
        .header("User-Agent", "Mozilla/5.0 (Android) AxolotlClass/1.0")
        .header("Accept", "application/json, text/javascript, */*; q=0.01")
        .header("X-Requested-With", "XMLHttpRequest")

    private suspend fun login(client: OkHttpClient, account: AccountSettings) {
        client.await(request("/").post(FormBody.Builder().build()).build()).use { /* obtains initial cookies */ }
        val form = FormBody.Builder().add("account", account.studentId)
            .add("pwd", Base64.getEncoder().encodeToString(account.password.toByteArray(Charsets.UTF_8))).build()
        client.await(request("/login!doLogin.action").post(form).build()).use { response ->
            if (!response.isSuccessful) throw IOException("登录请求失败（HTTP ${response.code}）")
            val text = response.body?.string().orEmpty()
            val result = runCatching { TimetableParser.json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject }.getOrNull()
                ?: throw IOException("登录响应异常，请确认教务系统可以访问")
            if (!result.text("status").equals("y", true)) throw IOException(result.text("msg").ifBlank { "学号或密码错误" })
        }
    }

    private suspend fun timetable(client: OkHttpClient, term: String): List<CourseRecord> {
        client.await(request("/xsgrkbcx!getKbRq.action?xnxqdm=$term").get().build()).use { response ->
            if (response.code in listOf(301, 302, 303, 307, 308, 401, 403)) throw SessionExpired()
            if (!response.isSuccessful) throw IOException("获取课表失败（HTTP ${response.code}）")
            val text = response.body?.string().orEmpty()
            if (text.trimStart().startsWith("<")) throw SessionExpired()
            return TimetableParser.parse(text)
        }
    }

    private class SessionExpired : IOException("登录状态已失效，请重新登录")
}

private suspend fun OkHttpClient.await(request: Request): Response = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}
