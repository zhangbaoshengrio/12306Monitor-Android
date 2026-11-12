package com.wizpizz.ticket12306

import com.wizpizz.ticket12306.api.MemoryCookieJar
import com.wizpizz.ticket12306.api.TicketApi
import com.wizpizz.ticket12306.api.TicketApiException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 不连网，用假的 12306 响应测试客户端的会话和接口名处理。 */
class TicketApiTest {

    private val sample = javaClass.classLoader!!.getResource("left_ticket_sample.json")!!.readText()
    private val renameHint = """{"c_url":"leftTicket/queryH","c_name":"CLeftTicketUrl","status":false}"""

    private fun api(paths: MutableList<String>, handler: (path: String, callIndex: Int) -> Pair<Int, String>): TicketApi {
        val counts = HashMap<String, Int>()
        val interceptor = Interceptor { chain ->
            val path = chain.request().url.encodedPath.substringAfter("/otn/")
            paths += path
            val n = (counts[path] ?: 0) + 1
            counts[path] = n
            val (code, body) = handler(path, n)
            Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("x")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        return TicketApi(OkHttpClient.Builder().cookieJar(MemoryCookieJar()).addInterceptor(interceptor).build())
    }

    @Test
    fun followsTheEndpointNameHintAndRemembersIt() {
        val paths = mutableListOf<String>()
        val api = api(paths) { path, _ ->
            when (path) {
                "leftTicket/init" -> 200 to "<html></html>"
                "leftTicket/queryG" -> 302 to renameHint      // 12306 用 302 状态码返回这个 JSON
                "leftTicket/queryH" -> 200 to sample
                else -> 404 to ""
            }
        }
        val first = api.queryTickets("ENH", "HAN", "2026-11-13")
        assertEquals(3, first.trains.size)
        assertEquals(listOf("leftTicket/init", "leftTicket/queryG", "leftTicket/queryH"), paths)

        paths.clear()
        api.queryTickets("ENH", "HAN", "2026-11-13")
        assertEquals("第二次直接用新接口名、不再重新初始化", listOf("leftTicket/queryH"), paths)
    }

    @Test
    fun reopensTheSessionWhenTheReplyIsNotJson() {
        val paths = mutableListOf<String>()
        val api = api(paths) { path, n ->
            when {
                path == "leftTicket/init" -> 200 to "<html></html>"
                path == "leftTicket/queryG" && n == 1 -> 200 to "<html>会话过期</html>"
                path == "leftTicket/queryG" -> 200 to sample
                else -> 404 to ""
            }
        }
        val resp = api.queryTickets("ENH", "HAN", "2026-11-13")
        assertEquals(3, resp.trains.size)
        assertEquals(
            listOf("leftTicket/init", "leftTicket/queryG", "leftTicket/init", "leftTicket/queryG"),
            paths
        )
    }

    @Test
    fun reportsTheServerMessageWithoutRetrying() {
        val paths = mutableListOf<String>()
        val api = api(paths) { path, _ ->
            if (path == "leftTicket/init") 200 to "<html></html>"
            else 200 to """{"status":false,"messages":["系统繁忙，请稍后重试"]}"""
        }
        try {
            api.queryTickets("ENH", "HAN", "2026-11-13")
            fail("应该抛出异常")
        } catch (e: TicketApiException) {
            assertTrue(e.message!!.contains("系统繁忙"))
        }
        assertEquals(listOf("leftTicket/init", "leftTicket/queryG"), paths)
    }

    @Test
    fun ignoresAHintThatIsNotAPlainName() {
        val paths = mutableListOf<String>()
        val api = api(paths) { path, _ ->
            if (path == "leftTicket/init") 200 to "<html></html>"
            else 200 to """{"c_url":"../evil?x=1","status":false,"messages":"bad"}"""
        }
        try {
            api.queryTickets("ENH", "HAN", "2026-11-13")
            fail("应该抛出异常")
        } catch (e: TicketApiException) {
            // 不会拿它拼出新地址去请求
        }
        assertEquals(listOf("leftTicket/init", "leftTicket/queryG"), paths)
    }
}
