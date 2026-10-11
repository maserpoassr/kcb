package com.xingheyuzhuan.shiguangschedule.tool

import android.content.Context
import com.xingheyuzhuan.shiguangschedule.autosync.AutoSyncActivity
import org.koin.core.context.GlobalContext

actual fun openAutoSyncSettings() {
    val context: Context = GlobalContext.get().get()
    context.startActivity(AutoSyncActivity.newIntent(context, autoStart = false))
}
