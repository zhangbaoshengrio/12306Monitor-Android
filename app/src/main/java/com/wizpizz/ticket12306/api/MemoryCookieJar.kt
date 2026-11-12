package com.wizpizz.ticket12306.api

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** 只存在内存里的 cookie 罐：打开一次 12306 查询页拿到匿名会话，后面的请求带着它。 */
class MemoryCookieJar : CookieJar {
    private val store = ArrayList<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        for (c in cookies) {
            store.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
            if (c.expiresAt > now) store.add(c)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        store.removeAll { it.expiresAt <= now }
        return store.filter { it.matches(url) }
    }

    @Synchronized
    fun clear() = store.clear()
}
