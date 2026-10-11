package com.xingheyuzhuan.shiguangschedule.autosync

import android.content.Context
import com.xingheyuzhuan.shiguangschedule.tool.SecureCrypto

/**
 * 教务自动同步配置。
 *
 * @property enabled        是否启用每周自动同步
 * @property importUrl      教务系统入口/登录页地址（会在此页面自动登录）
 * @property username       账号（学号/工号）
 * @property passwordCipher 密码密文（Android Keystore AES/GCM 加密）
 * @property passwordIv     密码加密 IV
 * @property adapterJsPath  适配脚本相对路径（相对 repo/schools/resources，例如 "PEU/peu_01.js"）
 * @property targetTableId  导入目标课表 ID
 * @property intervalDays   同步周期（天），默认 7
 * @property lastSyncAt     上次同步时间戳
 * @property lastResult     上次同步结果描述
 */
data class AutoSyncConfig(
    val enabled: Boolean = false,
    val importUrl: String = "",
    val username: String = "",
    val passwordCipher: String = "",
    val passwordIv: String = "",
    val adapterJsPath: String = "",
    val targetTableId: String = "",
    val intervalDays: Int = 7,
    val lastSyncAt: Long = 0L,
    val lastResult: String = ""
) {
    /** 是否已完成最基本的配置（可执行同步） */
    val isConfigured: Boolean
        get() = importUrl.isNotBlank() && username.isNotBlank() &&
                passwordCipher.isNotBlank() && adapterJsPath.isNotBlank() && targetTableId.isNotBlank()
}

/**
 * 基于 SharedPreferences 的自动同步配置存储。
 * 密码字段以密文形式保存，明文仅在需要时通过 [SecureCrypto] 解密。
 */
class AutoSyncStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AutoSyncConfig = AutoSyncConfig(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        importUrl = prefs.getString(KEY_IMPORT_URL, "").orEmpty(),
        username = prefs.getString(KEY_USERNAME, "").orEmpty(),
        passwordCipher = prefs.getString(KEY_PWD_CIPHER, "").orEmpty(),
        passwordIv = prefs.getString(KEY_PWD_IV, "").orEmpty(),
        adapterJsPath = prefs.getString(KEY_ADAPTER, "").orEmpty(),
        targetTableId = prefs.getString(KEY_TABLE_ID, "").orEmpty(),
        intervalDays = prefs.getInt(KEY_INTERVAL, 7),
        lastSyncAt = prefs.getLong(KEY_LAST_SYNC, 0L),
        lastResult = prefs.getString(KEY_LAST_RESULT, "").orEmpty()
    )

    fun save(config: AutoSyncConfig) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_IMPORT_URL, config.importUrl)
            .putString(KEY_USERNAME, config.username)
            .putString(KEY_PWD_CIPHER, config.passwordCipher)
            .putString(KEY_PWD_IV, config.passwordIv)
            .putString(KEY_ADAPTER, config.adapterJsPath)
            .putString(KEY_TABLE_ID, config.targetTableId)
            .putInt(KEY_INTERVAL, config.intervalDays)
            .putLong(KEY_LAST_SYNC, config.lastSyncAt)
            .putString(KEY_LAST_RESULT, config.lastResult)
            .apply()
    }

    fun updateLastResult(result: String, timestamp: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString(KEY_LAST_RESULT, result)
            .putLong(KEY_LAST_SYNC, timestamp)
            .apply()
    }

    /** 用明文密码加密后写回配置。若明文为空则清空密码字段。 */
    fun saveWithPassword(config: AutoSyncConfig, plainPassword: String, crypto: SecureCrypto): AutoSyncConfig {
        val updated = if (plainPassword.isEmpty()) {
            config.copy(passwordCipher = "", passwordIv = "")
        } else {
            val result = crypto.encrypt(plainPassword)
            if (result != null) {
                config.copy(passwordCipher = result.encryptedData, passwordIv = result.iv)
            } else {
                config
            }
        }
        save(updated)
        return updated
    }

    /** 解密出明文密码；失败返回 null。 */
    fun getPlainPassword(crypto: SecureCrypto): String? {
        val config = load()
        if (config.passwordCipher.isEmpty() || config.passwordIv.isEmpty()) return null
        return crypto.decrypt(config.passwordCipher, config.passwordIv)
    }

    companion object {
        private const val PREFS_NAME = "auto_sync_config"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_IMPORT_URL = "import_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PWD_CIPHER = "password_cipher"
        private const val KEY_PWD_IV = "password_iv"
        private const val KEY_ADAPTER = "adapter_js_path"
        private const val KEY_TABLE_ID = "target_table_id"
        private const val KEY_INTERVAL = "interval_days"
        private const val KEY_LAST_SYNC = "last_sync_at"
        private const val KEY_LAST_RESULT = "last_result"
    }
}
