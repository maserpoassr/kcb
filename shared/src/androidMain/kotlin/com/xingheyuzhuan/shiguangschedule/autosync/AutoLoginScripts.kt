package com.xingheyuzhuan.shiguangschedule.autosync

/**
 * 自动登录 / 探测用 JS 片段。
 *
 * 兼容两类常见教务登录页：
 *  - 正方/金智统一身份认证（authserver）：存在多个登录面板
 *    （#pwdLoginDiv 账号密码 / #phoneLoginDiv 手机验证码 / #fidoLoginDiv / #qrLoginDiv），
 *    且各面板内 id 重复（#username、#login_submit 等），必须**限定在可见面板内**查找。
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
     * 公共 JS 工具：可见性判断 + 定位当前可见登录面板（优先账号密码面板）。
     */
    private val JS_HELPERS = """
        function __sgVisible(el){
          if(!el) return false;
          var st = getComputedStyle(el);
          if(st.display==='none'||st.visibility==='hidden'||st.opacity==='0') return false;
          if(el.offsetParent===null && st.position!=='fixed') return false;
          return true;
        }
        function __sgPanel(){
          var ids = ['#pwdLoginDiv','#phoneLoginDiv','#fidoLoginDiv','#qrLoginDiv'];
          for(var i=0;i<ids.length;i++){
            var el = document.querySelector(ids[i]);
            if(el && __sgVisible(el)) return el;
          }
          return document;
        }
    """.trimIndent()

    /** 探测当前页面状态（在可见面板内判断账号/密码/验证码/按钮）。 */
    val JS_PROBE: String = """
        (function(){
          try {
            $JS_HELPERS
            var root = __sgPanel();
            var q = function(s){ return root.querySelector(s); };
            var u = q('#username') || q('input[name="username"]') || q('#yhm') || q('input[name="yhm"]');
            var p = q('#password') || q('input[name="passwordText"]') || q('input[type="password"]') || q('#mm') || q('input[name="mm"]');
            var capDiv = q('#captchaDiv');
            var capImg = q('#captchaImg');
            var capInput = q('#captcha') || q('#yzm');
            var capVisible = (capDiv && __sgVisible(capDiv) && String(capDiv.className).indexOf('hide') < 0)
                          || (capImg && __sgVisible(capImg))
                          || (capInput && __sgVisible(capInput));
            var btn = q('#login_submit') || q('#loginButton') || q('#dl') || q('.login-btn')
                   || q('button[type="submit"]') || q('input[type="submit"]');
            return JSON.stringify({
              url: location.href,
              path: location.pathname,
              panel: (root && root.id) ? root.id : 'document',
              hasUser: !!(u && __sgVisible(u)),
              hasPwd: !!(p && __sgVisible(p)),
              captchaVisible: !!capVisible,
              hasButton: !!(btn && __sgVisible(btn))
            });
          } catch (e) { return JSON.stringify({ error: String(e) }); }
        })();
    """.trimIndent()

    /** 在可见面板内自动填充账号密码（去除 readonly），并触发页面的验证码检查。 */
    fun jsAutoFill(username: String, password: String): String = """
        (function(u, p){
          try {
            $JS_HELPERS
            function setVal(el, val){
              if (!el) return false;
              try { el.removeAttribute('readonly'); el.readOnly = false; } catch (e) {}
              var proto = (el.tagName === 'TEXTAREA') ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
              var setter = Object.getOwnPropertyDescriptor(proto, 'value');
              if (setter && setter.set) { setter.set.call(el, val); } else { el.value = val; }
              el.dispatchEvent(new Event('input', { bubbles: true }));
              el.dispatchEvent(new Event('change', { bubbles: true }));
              el.dispatchEvent(new Event('blur', { bubbles: true }));
              return true;
            }
            var root = __sgPanel();
            var users = [].slice.call(root.querySelectorAll('#username, input[name="username"], #yhm, input[name="yhm"]'));
            var pwds  = [].slice.call(root.querySelectorAll('#password, input[name="passwordText"], input[type="password"], #mm, input[name="mm"]'));
            var fu = 0, fp = 0;
            users.forEach(function(e){ if (setVal(e, u)) fu++; });
            pwds.forEach(function(e){ if (setVal(e, p)) fp++; });
            try { if (typeof checkUserCaptcha === 'function') { checkUserCaptcha(); } } catch (e) {}
            return JSON.stringify({ panel: (root && root.id) ? root.id : 'document', filledUser: fu, filledPwd: fp });
          } catch (e) { return JSON.stringify({ error: String(e) }); }
        })(${jsString(username)}, ${jsString(password)});
    """.trimIndent()

    /** 点击**可见面板内**的登录按钮 / 提交表单。 */
    val JS_SUBMIT: String = """
        (function(){
          try {
            $JS_HELPERS
            var root = __sgPanel();
            var q = function(s){ return root.querySelector(s); };
            var btn = q('#login_submit') || q('#loginButton') || q('#dl') || q('.login-btn')
                    || q('button[type="submit"]') || q('input[type="submit"]');
            if (btn) { btn.click(); return 'clicked:' + ((root && root.id) ? root.id : 'document'); }
            var form = root.querySelector('form');
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
