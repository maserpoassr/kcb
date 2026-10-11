package com.xingheyuzhuan.shiguangschedule.autosync

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xingheyuzhuan.shiguangschedule.data.repository.CourseConversionRepository
import com.xingheyuzhuan.shiguangschedule.tool.SecureCrypto
import com.xingheyuzhuan.shiguangschedule.ui.components.CourseTablePickerDialog
import com.xingheyuzhuan.shiguangschedule.ui.schoolselection.web.WebBridgeHandler
import com.xingheyuzhuan.shiguangschedule.ui.schoolselection.web.WebDialogHost
import com.xingheyuzhuan.shiguangschedule.ui.schoolselection.web.WebUiEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named

/**
 * 教务课表自动同步页面（Android 端）。
 *
 * 同一页面承担两件事：
 *  1. 配置界面：教务入口、账号、密码、适配脚本、目标课表、同步周期、开关；
 *  2. 自动化执行：加载登录页 → 自动填充 →（如需要）人工输入验证码 → 执行适配脚本导入课表。
 *
 * 通过 Intent 额外参数 [EXTRA_AUTO_START] 可让页面打开后立即开始同步（供定时通知调用）。
 */
class AutoSyncActivity : ComponentActivity(), KoinComponent {

    private val crypto: SecureCrypto by inject()
    private val courseConversionRepository: CourseConversionRepository by inject()
    private val fileSystem: FileSystem by inject()
    private val filesDir: Path by inject(named("FilesDir"))

    private lateinit var store: AutoSyncStore

    companion object {
        const val EXTRA_AUTO_START = "auto_start"

        fun newIntent(context: Context, autoStart: Boolean): Intent {
            return Intent(context, AutoSyncActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_AUTO_START, autoStart)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AutoSyncStore(this)
        val autoStart = intent?.getBooleanExtra(EXTRA_AUTO_START, false) == true

        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                AutoSyncRoot(autoStart = autoStart)
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AutoSyncRoot(autoStart: Boolean) {
        val scope = rememberCoroutineScope()

        var config by remember { mutableStateOf(store.load()) }
        var editing by remember { mutableStateOf(!config.isConfigured) }
        var started by remember { mutableStateOf(false) }
        var statusText by remember { mutableStateOf("") }
        var running by remember { mutableStateOf(false) }
        var showTablePicker by remember { mutableStateOf(false) }

        // 表单字段
        var url by remember { mutableStateOf(config.importUrl.ifBlank { DEFAULT_IMPORT_URL }) }
        var username by remember { mutableStateOf(config.username) }
        var password by remember { mutableStateOf("") }
        var adapterPath by remember { mutableStateOf(config.adapterJsPath.ifBlank { DEFAULT_ADAPTER }) }
        var interval by remember { mutableStateOf(config.intervalDays.toString()) }
        var enabled by remember { mutableStateOf(config.enabled) }

        val uiEventChannel = remember { Channel<WebUiEvent>(Channel.UNLIMITED) }
        val uiEvents = remember(uiEventChannel) { uiEventChannel.receiveAsFlow() }

        var controller by remember { mutableStateOf<AutoSyncController?>(null) }

        val bridgeHandler = remember {
            WebBridgeHandler(
                coroutineScope = scope,
                uiEventChannel = uiEventChannel,
                courseConversionRepository = courseConversionRepository,
                onTaskCompleted = {
                    controller?.onBridgeTaskCompleted()
                },
                evaluateJs = { script, cb -> controller?.evaluateJs(script, cb) }
            )
        }

        val startSync: () -> Unit = {
            val tableId = config.targetTableId
            if (tableId.isBlank()) {
                statusText = "请先选择目标课表"
            } else {
                scope.launch {
                    val plainPwd = withContext(Dispatchers.IO) { store.getPlainPassword(crypto) }
                    if (plainPwd.isNullOrBlank()) {
                        statusText = "密码解密失败，请重新填写密码后保存"
                        return@launch
                    }
                    val adapterJs = withContext(Dispatchers.IO) {
                        runCatching {
                            val p = filesDir / "repo" / "schools" / "resources" / config.adapterJsPath
                            if (fileSystem.exists(p)) fileSystem.read(p) { readUtf8() } else null
                        }.getOrNull()
                    }
                    if (adapterJs.isNullOrBlank()) {
                        statusText = "未找到适配脚本：${config.adapterJsPath}（请先在“我的-更多-更新仓库”中同步适配仓库）"
                        return@launch
                    }

                    bridgeHandler.setImportTableId(tableId)
                    val ctrl = AutoSyncController(
                        config = config,
                        plainPassword = plainPwd,
                        adapterJs = adapterJs,
                        bridgeHandler = bridgeHandler,
                        onFinished = { success, message ->
                            store.updateLastResult(message)
                            statusText = message
                            running = false
                        }
                    )
                    controller = ctrl
                    // 将引擎状态回传到界面
                    scope.launch {
                        ctrl.status.collect { statusText = it }
                    }
                    statusText = "正在启动…"
                    running = true
                    started = true
                }
            }
        }

        LaunchedEffect(autoStart) {
            if (autoStart && config.isConfigured) {
                startSync()
            }
        }

        Scaffold(
            topBar = { TopAppBar(title = { Text("教务自动同步") }) }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // WebView 背景（运行时可见，用于展示登录/验证码）
                if (started) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).also { wv ->
                                controller?.attach(wv)
                            }
                        },
                        update = { wv ->
                            controller?.attach(wv)
                        }
                    )
                }

                if (editing) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        ConfigForm(
                            url = url, onUrl = { url = it },
                            username = username, onUsername = { username = it },
                            password = password, onPassword = { password = it },
                            adapterPath = adapterPath, onAdapterPath = { adapterPath = it },
                            interval = interval, onInterval = { interval = it },
                            enabled = enabled, onEnabled = { enabled = it },
                            tableId = config.targetTableId,
                            lastResult = config.lastResult,
                            statusText = statusText,
                            onPickTable = { showTablePicker = true },
                            onSave = {
                                val base = config.copy(
                                    enabled = enabled,
                                    importUrl = url.trim(),
                                    username = username.trim(),
                                    adapterJsPath = adapterPath.trim(),
                                    intervalDays = interval.toIntOrNull() ?: 7
                                )
                                config = store.saveWithPassword(base, password, crypto)
                                AutoSyncSchedulerBridge.onReschedule?.invoke()
                                statusText = "配置已保存"
                                editing = false
                            },
                            onStart = {
                                val base = config.copy(
                                    enabled = enabled,
                                    importUrl = url.trim(),
                                    username = username.trim(),
                                    adapterJsPath = adapterPath.trim(),
                                    intervalDays = interval.toIntOrNull() ?: 7
                                )
                                config = store.saveWithPassword(base, password, crypto)
                                AutoSyncSchedulerBridge.onReschedule?.invoke()
                                editing = false
                                startSync()
                            }
                        )
                    }
                } else {
                    // 运行态：顶部状态条 + 返回配置按钮
                    Column(modifier = Modifier.fillMaxSize()) {
                        Surface(tonalElevation = 3.dp) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (running) {
                                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                                    Spacer(Modifier.padding(horizontal = 6.dp))
                                }
                                Text(
                                    text = statusText.ifBlank { "准备中…" },
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                OutlinedButton(onClick = {
                                    started = false
                                    running = false
                                    controller?.destroy()
                                    controller = null
                                    editing = true
                                }) { Text("配置") }
                            }
                        }
                    }
                }

                WebDialogHost(uiEvents = uiEvents)
            }
        }

        if (showTablePicker) {
            CourseTablePickerDialog(
                title = "选择导入目标课表",
                onDismissRequest = { showTablePicker = false },
                onTableSelected = { table ->
                    config = config.copy(targetTableId = table.id)
                    store.save(config)
                    showTablePicker = false
                }
            )
        }
    }

    @Composable
    private fun ConfigForm(
        url: String, onUrl: (String) -> Unit,
        username: String, onUsername: (String) -> Unit,
        password: String, onPassword: (String) -> Unit,
        adapterPath: String, onAdapterPath: (String) -> Unit,
        interval: String, onInterval: (String) -> Unit,
        enabled: Boolean, onEnabled: (Boolean) -> Unit,
        tableId: String,
        lastResult: String,
        statusText: String,
        onPickTable: () -> Unit,
        onSave: () -> Unit,
        onStart: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("配置教务自动同步", style = MaterialTheme.typography.titleLarge)

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "自动同步会每周自动登录教务系统并导入最新课表。若该次登录需要验证码，" +
                                "会停留在登录页等待你输入验证码，完成后自动导入。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            OutlinedTextField(
                value = url, onValueChange = onUrl,
                label = { Text("教务系统入口网址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = username, onValueChange = onUsername,
                label = { Text("账号（学号/工号）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password, onValueChange = onPassword,
                label = { Text("密码（仅首次填写，留空表示不修改）") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = adapterPath, onValueChange = onAdapterPath,
                label = { Text("适配脚本路径") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = interval, onValueChange = onInterval,
                    label = { Text("同步周期（天）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.padding(horizontal = 8.dp))
                Switch(checked = enabled, onCheckedChange = onEnabled)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (tableId.isBlank()) "目标课表：未选择" else "目标课表 ID：$tableId",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedButton(onClick = onPickTable) { Text("选择课表") }
            }

            if (lastResult.isNotBlank()) {
                Text("上次同步：$lastResult", style = MaterialTheme.typography.bodySmall)
            }
            if (statusText.isNotBlank()) {
                Text(statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onSave) { Text("保存") }
                Button(onClick = onStart) { Text("保存并立即同步") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private const val DEFAULT_IMPORT_URL =
    "https://jwxt.peu.edu.cn/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N253508&layout=default"
private const val DEFAULT_ADAPTER = "PEU/peu_01.js"
