package com.xingheyuzhuan.shiguangschedule.autosync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.xingheyuzhuan.shiguangschedule.R

/** 自动同步通知工具。 */
object AutoSyncNotifier {

    private const val CHANNEL_ID = "auto_sync_channel"
    private const val NOTIFICATION_ID = 0x53474E // "SGN"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "教务课表自动同步",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "提醒同步教务系统最新课表"
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    /** 发出“点击同步课表”的通知。 */
    fun showSyncNotification(context: Context) {
        ensureChannel(context)

        val intent = AutoSyncActivity.newIntent(context, autoStart = true)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getActivity(context, 1001, intent, flags)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("教务课表自动同步")
            .setContentText("点击以同步最新课表（如遇验证码需手动输入）")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // 缺少 POST_NOTIFICATIONS 权限时静默失败
        }
    }
}
