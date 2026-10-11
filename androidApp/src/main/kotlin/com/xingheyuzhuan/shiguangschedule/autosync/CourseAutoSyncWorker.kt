package com.xingheyuzhuan.shiguangschedule.autosync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.koin.android.annotation.KoinWorker

/**
 * 教务课表自动同步的周期任务。
 *
 * 由于登录验证码的存在，无法保证完全无人值守，因此该任务在到期时**发出通知**，
 * 用户点击后进入 [AutoSyncActivity] 自动完成登录与导入（如遇验证码仅需手动输入一次）。
 */
@KoinWorker
class CourseAutoSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val config = AutoSyncStore(applicationContext).load()
            if (!config.enabled || !config.isConfigured) {
                Log.d("CourseAutoSyncWorker", "自动同步未启用或未配置，跳过")
                return Result.success()
            }
            AutoSyncNotifier.showSyncNotification(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.e("CourseAutoSyncWorker", "自动同步任务失败", e)
            Result.retry()
        }
    }
}
