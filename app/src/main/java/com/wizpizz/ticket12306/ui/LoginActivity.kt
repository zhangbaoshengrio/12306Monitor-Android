package com.wizpizz.ticket12306.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

const val EXTRA_COOKIE = "cookie_result"

private const val LOGIN_URL = "https://kyfw.12306.cn/otn/resources/login.html"
private const val HOME_URL = "https://kyfw.12306.cn/otn/view/index.html"
private const val TICKET_INIT_URL = "https://kyfw.12306.cn/otn/leftTicket/init"

class LoginActivity : ComponentActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 清除旧 Cookie，确保重新登录
        CookieManager.getInstance().removeAllCookies(null)

        setContent {
            MaterialTheme {
                LoginScreen(
                    onClose = { finish() },
                    onLoginSuccess = { cookie ->
                        val intent = Intent().putExtra(EXTRA_COOKIE, cookie)
                        setResult(Activity.RESULT_OK, intent)
                        Toast.makeText(this, "登录成功，Cookie 已自动填入", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LoginScreen(
    onClose: () -> Unit,
    onLoginSuccess: (String) -> Unit
) {
    var pageTitle by remember { mutableStateOf("12306 登录") }
    var loggedIn by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pageTitle) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }
            )
        }
    ) { padding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = "Mozilla/5.0 (Linux; Android 12; Pixel 6) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/112.0.0.0 Mobile Safari/537.36"
                    }
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            pageTitle = view.title ?: "12306 登录"

                            if (!loggedIn) {
                                // 检测登录成功：跳转到首页
                                if (url.contains("index.html") ||
                                    url.contains("userLogin") && url.contains("welcome") ||
                                    url.contains("otn/view/index")
                                ) {
                                    loggedIn = true
                                    pageTitle = "正在准备查票环境..."
                                    // 登录后先访问查票页，让 12306 设置必要的 session cookie
                                    view.loadUrl(TICKET_INIT_URL)
                                }
                            } else {
                                // 已登录，等查票页加载完再提取 cookie
                                if (url.contains("leftTicket/init")) {
                                    CookieManager.getInstance().flush()
                                    val cookie = CookieManager.getInstance()
                                        .getCookie("kyfw.12306.cn") ?: ""
                                    if (cookie.isNotBlank()) {
                                        onLoginSuccess(cookie)
                                    }
                                }
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            // 所有 12306 域名内的跳转都在 WebView 内完成
                            val host = request.url.host ?: ""
                            return !host.contains("12306.cn")
                        }
                    }
                    loadUrl(LOGIN_URL)
                }
            }
        )
    }
}
