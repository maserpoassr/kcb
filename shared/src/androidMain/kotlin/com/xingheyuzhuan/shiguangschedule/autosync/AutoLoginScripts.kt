package com.xingheyuzhuan.shiguangschedule.autosync

/**
 * 自动登录 / 探测用 JS 片段。
 *
 * 兼容两类常见教务登录页：
 *  - 正方/金智统一身份认证（authserver）：#username / #password / #captcha / #captchaDiv
 *  - 正方经典登录页（jwglxt/xtgl/login_slogin）：#yhm / #mm / #yzm
 *
 * 所有片段均返回 JSON 字符串，由 Kotlin 侧解析。
 */
object AutoLoginScripts {

    /** 将任意字符串安全编码为 JS 字符串字面量（含引号）。 */
    private fun jsString(value: String): String {
        val sb = StringBuilder("\"")
        for (c in value) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '<' -> sb.append("\\u003c")
                '>' -> sb.append("\\u003e")
                '&' -> sb.append("\\u0026")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    /**
     * 探测当前页面状态：是否登录页、是否存在账号/密码框、是否显示验证码。
     */
    val JS_PROBE: String = """
        (function(){
          try {
            var q = function(s){ return document.querySelector(s); };
            var vis = function(el){
              if (!el) return false;
              var st = getComputedStyle(el);
              if (st.display === 'none' || st.visibility === 'hidden' || st.opacity === '0') return false;
              if (el.offsetParent === null && st.position !== 'fixed') return false;
              return true;
            };
            var u = q('#username') || q('input[name="username"]') || q('#yhm') || q('input[name="yhm"]');
            var p = q('#password') || q('input[name="password"]') || q('#mm') || q('input[name="mm"]');
            var capDiv = q('#captchaDiv');
            var capInput = q('#captcha') || q('input[name="captcha"]') || q('#yzm');
            var capImg = q('#captchaImg');
            var capVisible = (capDiv && vis(capDiv) && capDiv.className.indexOf('hide') < 0)
                           || (capImg && vis(capImg))
                           || (capInput && vis(capInput));
            var btn = q('#loginButton') || q('#dl') || q('#login') || q('.login-btn')
                    || q('button[type="submit"]') || q('input[type="submit"]');
            return JSON.stringify({
              url: location.href,
              path: location.pathname,
              hasUser: !!u,
              hasPwd: !!p,
              captchaVisible: !!capVisible,
              hasButton: !!btn
            });
          } catch (e) { return JSON.stringify({ error: String(e) }); }
        })();
    """.trimIndent()

    /**
     * 自动填充账号密码，并触发页面的“检查是否需要验证码”逻辑。
     */
    fun jsAutoFill(username: String, password: String): String = """
        (function(u, p){
          try {
            function setVal(el, val){
              if (!el) return false;
              var proto = (el.tagName === 'TEXTAREA') ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
              var setter = Object.getOwnPropertyDescriptor(proto, 'value');
              if (setter && setter.set) { setter.set.call(el, val); } else { el.value = val; }
              el.dispatchEvent(new Event('input', { bubbles: true }));
              el.dispatchEvent(new Event('change', { bubbles: true }));
              el.dispatchEvent(new Event('blur', { bubbles: true }));
              return true;
            }
            var q = function(s){ return document.querySelector(s); };
            var ue = q('#username') || q('input[name="username"]') || q('#yhm') || q('input[name="yhm"]');
            var pe = q('#password') || q('input[name="password"]') || q('#mm') || q('input[name="mm"]');
            setVal(ue, u);
            setVal(pe, p);
            try { if (typeof checkUserCaptcha === 'function') { checkUserCaptcha(); } } catch (e) {}
            return JSON.stringify({ filledUser: !!ue, filledPwd: !!pe });
          } catch (e) { return JSON.stringify({ error: String(e) }); }
        })(${jsString(username)}, ${jsString(password)});
    """.trimIndent()

    /** 点击登录按钮 / 提交表单。 */
    val JS_SUBMIT: String = """
        (function(){
          try {
            var q = function(s){ return document.querySelector(s); };
            var btn = q('#loginButton') || q('#dl') || q('#login') || q('.login-btn')
                    || q('button[type="submit"]') || q('input[type="submit"]');
            if (btn) { btn.click(); return 'clicked'; }
            var form = q('#loginForm') || q('form');
            if (form) { form.submit(); return 'submitted'; }
            return 'no-button';
          } catch (e) { return 'error:' + e; }
        })();
    """.trimIndent()

    /** 将适配脚本与目标课表 ID 拼接为可执行脚本。 */
    fun buildAdapterScript(tableId: String, adapterJs: String): String {
        return "window.currentTableId = ${jsString(tableId)};\n$adapterJs"
    }
}
