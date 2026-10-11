package com.xingheyuzhuan.shiguangschedule.autosync

/**
 * 调度桥接对象。
 *
 * 由于自动同步页面位于 shared/androidMain，而 WorkManager 调度器位于 androidApp，
 * 这里提供一个可注入的回调钩子：androidApp 启动时注册真实调度逻辑，
 * 页面保存配置后可触发重新调度，避免模块间直接依赖。
 */
object AutoSyncSchedulerBridge {
    /** 由 androidApp 侧注册：重新按当前配置调度周期任务。 */
    var onReschedule: (() -> Unit)? = null

    /** 由 androidApp 侧注册：立即执行一次同步（可选）。 */
    var onSyncNow: (() -> Unit)? = null
}
