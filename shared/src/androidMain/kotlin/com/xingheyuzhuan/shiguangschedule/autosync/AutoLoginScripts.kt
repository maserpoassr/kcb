package com.xingheyuzhuan.shiguangschedule.autosync

/**
 * 自动登录 / 探测用 JS 片段。
 *
 * 兼容两类常见教务登录页：
 *  - 正方/金智统一身份认证（authserver，实际渲染于 authserver.<域名>）：
 *    页面里存在多个同名元素（#username / #password / #login_submit 各有多份），
 *    其中一部分处于 display:none 的隐藏面板内。因此统一采用「优先可见元素」策略。
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
     * 公共 JS 工具：
     *  - __sgVisible(el)      可见性判断
     *  - __sgPanel()          当前可见的登录面板（无则返回 document）
     *  - __sgFirst(root, sels) 按选择器顺序取「第一个可见」元素，找不到再退回首元素
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
        function __sgFirst(root, sels){
          var i, j, list;
          for(i=0;i<sels.length;i++){
            list = root.querySelectorAll(sels[i]);
            for(j=0;j<list.length;j++){ if(__sgVisible(list[j])) return list[j]; }
          }
          for(i=0;i<sels.length;i++){
            list = root.querySelectorAll(sels[i]);
            if(list.length) return list[0];
          }
          return null;
        }
    """.trimIndent()

    /** 探测当前页面状态（优先可见元素）。 */
    val JS_PROBE: String = """
        (function(){
          try {
            $JS_HELPERS
            var root = __sgPanel();
            var u = __sgFirst(root, ['#username','input[name="username"]','#yhm','input[name="yhm"]']);
            var p = __sgFirst(root, ['#password','input[name="passwordText"]','input[type="password"]','#mm','input[name="mm"]']);
            var capDiv = __sgFirst(root, ['#captchaDiv']);
            var capImg = __sgFirst(root, ['#captchaImg']);
            var capInput = __sgFirst(root, ['#captcha','#yzm']);
            var capVisible = (capDiv && __sgVisible(capDiv) && String(capDiv.className).indexOf('hide') < 0)
                          || (capImg && __sgVisible(capImg))
                          || (capInput && __sgVisible(capInput));
            var btn = __sgFirst(root, ['#login_submit','#loginButton','#dl','.login-btn','button[type="submit"]','input[type="submit"]']);
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

    /** 自动填充账号密码（填所有匹配项，并去除 readonly），再触发页面的验证码检查。 */
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

    /** 点击**可见**的登录按钮 / 提交表单。 */
    val JS_SUBMIT: String = """
        (function(){
          try {
            $JS_HELPERS
            var root = __sgPanel();
            var btn = __sgFirst(root, ['#login_submit','#loginButton','#dl','.login-btn','button[type="submit"]','input[type="submit"]']);
            if (btn) { btn.click(); return 'clicked:' + (btn.id || btn.className || 'button'); }
            var form = __sgFirst(root, ['form']);
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
