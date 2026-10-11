package com.xingheyuzhuan.shiguangschedule.autosync

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.xingheyuzhuan.shiguangschedule.ui.schoolselection.web.JS_BRIDGE_INIT
import com.xingheyuzhuan.shiguangschedule.ui.schoolselection.web.NativeBridge
import com.xingheyuzhuan.shiguangschedule.ui.schoolselection.web.WebBridgeHandler
import com.xingheyuzhuan.shiguangschedule.ui.schoolselection.web.WebCompatDelegate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 自动同步运行状态。 */
enum class AutoSyncState { IDLE, LOGGING_IN, NEED_CAPTCHA, IMPORTING, DONE, ERROR }

/** 页面探测结果（对应 [AutoLoginScripts.JS_PROBE] 的返回值）。 */
@Serializable
private data class PageProbe(
    val url: String = "",
    val path: String = "",
    @SerialName("hasUser") val hasUser: Boolean = false,
    @SerialName("hasPwd") val hasPwd: Boolean = false,
    @SerialName("captchaVisible") val captchaVisible: Boolean = false,
    @SerialName("hasButton") val hasButton: Boolean = false,
    val error: String? = null
)

/**
 * 教务自动同步引擎：驱动一个 WebView 完成
 * 加载登录页 → 自动填充账号密码 →（如需要）等待人工输入验证码 → 登录 → 执行适配脚本导入课表。
 *
 * 该引擎仅负责流程编排，课程数据落库由 [WebBridgeHandler] 完成。
 */
class AutoSyncController(
    private val config: AutoSyncConfig,
    private val plainPassword: String,
    private val adapterJs: String,
    private val bridgeHandler: WebBridgeHandler,
    private val onFinished: (success: Boolean, message: String) -> Unit
) {
    companion object {
        private const val TAG = "AutoSyncController"
        private const val MAX_AUTOFILL_ATTEMPTS = 3
        private const val OVERALL_TIMEOUT_MS = 180_000L
        private const val IMPORT_TIMEOUT_MS = 90_000L
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private var webView: WebView? = null
    private var autofillAttempts = 0
    private var submitted = false
    private var adapterRan = false
    private var finished = false
    private var timeoutPosted = false

    private val _state = MutableStateFlow(AutoSyncState.IDLE)
    val state: StateFlow<AutoSyncState> = _state.asStateFlow()

    private val _status = MutableStateFlow("准备中…")
    val status: StateFlow<String> = _status.asStateFlow()

    /** 由 Compose 的 AndroidView 工厂回调，绑定 WebView 并启动流程。 */
    @SuppressLint("JavascriptInterface", "SetJavaScriptEnabled")
    fun attach(wv: WebView) {
        if (webView === wv) return
        webView = wv

        val delegate = WebCompatDelegate(wv)
        delegate.enhanceSettings(isDesktopMode = false)

        wv.addJavascriptInterface(NativeBridge(bridgeHandler), "_shiguangNativeBridge")

        val client = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {}

            override fun onPageFinished(view: WebView?, url: String?) {
                val realUrl = url ?: view?.url ?: ""
                Log.d(TAG, "onPageFinished: $realUrl")
                postPageFinished(realUrl)
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                // 教务系统常见自签/链不全证书，直接放行
                handler?.proceed()
            }

            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                finish(false, "WebView 渲染进程崩溃")
                return true
            }
        }
        wv.webViewClient = delegate.wrapWebViewClient(client) { false }
        wv.webChromeClient = delegate.wrapWebChromeClient(object : WebChromeClient() {}, { _ -> })

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(wv, true)

        startOverallTimeout()
        _state.value = AutoSyncState.LOGGING_IN
        _status.value = "正在打开教务系统…"
        wv.loadUrl(config.importUrl)
    }

    private fun startOverallTimeout() {
        if (timeoutPosted) return
        timeoutPosted = true
        webView?.postDelayed({
            if (!finished) finish(false, "同步超时（超过 ${OVERALL_TIMEOUT_MS / 1000} 秒）")
        }, OVERALL_TIMEOUT_MS)
    }

    private fun postPageFinished(url: String) {
        inject(AutoLoginScripts.JS_PROBE) { raw ->
            val probe = parseProbe(raw)
            if (probe == null) {
                Log.w(TAG, "探测结果解析失败: $raw")
                return@inject
            }
            Log.d(TAG, "probe=$probe")

            when {
                isLoggedIn(url, probe) -> runAdapter()
                probe.hasUser && probe.hasPwd -> handleLoginPage(probe)
                submitted && !adapterRan -> {
                    // 已提交且不再是登录页，视为登录成功
                    runAdapter()
                }
                else -> {
                    // 未知页面，稍作等待
                    _status.value = "正在等待页面加载…"
                }
            }
        }
    }

    private fun handleLoginPage(probe: PageProbe) {
        if (_state.value == AutoSyncState.NEED_CAPTCHA) {
            // 已提示人工输入验证码，等待用户操作
            return
        }
        if (submitted) return

        if (autofillAttempts >= MAX_AUTOFILL_ATTEMPTS) {
            finish(false, "多次尝试登录失败，请检查账号密码或手动登录")
            return
        }
        autofillAttempts++
        _state.value = AutoSyncState.LOGGING_IN
        _status.value = "正在自动填写账号密码…"

        inject(AutoLoginScripts.jsAutoFill(config.username, plainPassword)) {
            // 填充后重新探测是否需要验证码
            inject(AutoLoginScripts.JS_PROBE) { raw2 ->
                val probe2 = parseProbe(raw2)
                if (probe2?.captchaVisible == true) {
                    _state.value = AutoSyncState.NEED_CAPTCHA
                    _status.value = "该账号需要验证码：请在上方页面输入验证码并点击登录，完成后将自动导入课表。"
                } else {
                    _status.value = "正在登录…"
                    inject(AutoLoginScripts.JS_SUBMIT) { submitted = true }
                }
            }
        }
    }

    private fun runAdapter() {
        if (adapterRan || finished) return
        adapterRan = true
        _state.value = AutoSyncState.IMPORTING
        _status.value = "登录成功，正在导入课表…"

        // 1. 确保桥接已注入（幂等）
        inject(JS_BRIDGE_INIT)
        // 2. 自动应答脚本内的确认/单选弹窗（无需人工干预）
        inject(JS_AUTO_ANSWER)
        // 3. 执行适配脚本
        inject(AutoLoginScripts.buildAdapterScript(config.targetTableId, adapterJs))

        // 导入超时兜底
        webView?.postDelayed({
            if (!finished && _state.value != AutoSyncState.DONE) {
                finish(false, "导入超时，未能获取课表数据")
            }
        }, IMPORT_TIMEOUT_MS)
    }

    /** 适配脚本内部触发 notifyTaskCompletion 时由桥接回调。 */
    fun onBridgeTaskCompleted() {
        finish(true, "课表同步完成")
    }

    /** 供桥接（如 Prompt 校验、Promise 回调）执行的 JS。 */
    fun evaluateJs(script: String, callback: ((String?) -> Unit)? = null) {
        inject(script, callback)
    }

    fun finish(success: Boolean, message: String) {
        if (finished) return
        finished = true
        _state.value = if (success) AutoSyncState.DONE else AutoSyncState.ERROR
        _status.value = message
        webView?.post {
            try {
                webView?.stopLoading()
            } catch (_: Exception) {
            }
        }
        onFinished(success, message)
    }

    fun destroy() {
        try {
            val wv = webView
            if (wv != null) {
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.stopLoading()
                wv.webChromeClient = null
                wv.webViewClient = WebViewClient()
                wv.destroy()
            }
        } catch (_: Exception) {
        }
        webView = null
    }

    // ---- 工具方法 ----

    private fun inject(script: String, callback: ((String?) -> Unit)? = null) {
        webView?.post {
            try {
                webView?.evaluateJavascript(script) { res -> callback?.invoke(res) }
            } catch (e: Exception) {
                Log.e(TAG, "注入脚本失败", e)
                callback?.invoke(null)
            }
        }
    }

    private fun parseProbe(raw: String?): PageProbe? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return try {
            // evaluateJavascript 返回的是 JSON 编码后的字符串，需先解一层
            val inner = json.decodeFromString<String>(raw)
            json.decodeFromString<PageProbe>(inner)
        } catch (e: Exception) {
            null
        }
    }

    private fun isLoggedIn(url: String, probe: PageProbe): Boolean {
        val lower = url.lowercase()
        val onJwglxt = lower.contains("/jwglxt/") &&
                !lower.contains("login") &&
                !lower.contains("authserver")
        val noLoginForm = !probe.hasUser && !probe.hasPwd
        return onJwglxt || (noLoginForm && submitted && lower.contains("/jwglxt"))
    }

    private val JS_AUTO_ANSWER: String = """
        (function(){
          try {
            var p = window.shiguangBridgePromise;
            if (!p) return;
            p.showAlert = function(){ return Promise.resolve(true); };
            p.showSingleSelection = function(title, items, def){
              var idx = (def === undefined || def === null || def < 0) ? 0 : def;
              return Promise.resolve(idx);
            };
          } catch (e) {}
        })();
    """.trimIndent()
}
