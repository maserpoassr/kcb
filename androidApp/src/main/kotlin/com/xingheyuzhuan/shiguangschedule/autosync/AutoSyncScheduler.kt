package com.xingheyuzhuan.shiguangschedule.autosync

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 教务课表自动同步的 WorkManager 调度器。
 */
object AutoSyncScheduler {

    private const val WORK_NAME = "CourseAutoSyncWorker_Periodic"

    /** 根据当前配置调度/更新周期任务；未启用则取消。 */
    fun schedule(context: Context) {
        val appContext = context.applicationContext
        val config = AutoSyncStore(appContext).load()

        if (!config.enabled || !config.isConfigured) {
            WorkManager.getInstance(appContext).cancelUniqueWork(WORK_NAME)
            Log.d("AutoSyncScheduler", "自动同步未启用，已取消周期任务")
            return
        }

        val days = config.intervalDays.coerceAtLeast(1).toLong()
        val request = PeriodicWorkRequestBuilder<CourseAutoSyncWorker>(days, TimeUnit.DAYS).build()

        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        Log.d("AutoSyncScheduler", "已调度自动同步周期任务：每 $days 天")
    }
}
