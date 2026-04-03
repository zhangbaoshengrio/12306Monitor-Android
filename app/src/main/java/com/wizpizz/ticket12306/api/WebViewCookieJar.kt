package com.wizpizz.ticket12306.api

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 让 OkHttp 直接使用 WebView 的 CookieManager，
 * 这样 WebView 登录后的完整 session 可以无缝用于 API 请求。
 */
object WebViewCookieJar : CookieJar {

    private val wvCookieManager = CookieManager.getInstance()

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val cookieStr = wvCookieManager.getCookie(url.toString()) ?: return emptyList()
        return cookieStr.split(";").mapNotNull { part ->
            Cookie.parse(url, part.trim())
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { cookie ->
            wvCookieManager.setCookie(url.toString(), cookie.toString())
        }
    }
}
