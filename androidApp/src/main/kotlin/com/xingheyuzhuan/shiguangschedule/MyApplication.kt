package com.xingheyuzhuan.shiguangschedule

import android.app.Application
import androidx.work.Configuration
import com.xingheyuzhuan.shiguangschedule.autosync.AutoSyncScheduler
import com.xingheyuzhuan.shiguangschedule.autosync.AutoSyncSchedulerBridge
import com.xingheyuzhuan.shiguangschedule.data.di.SharedModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module
import org.koin.plugin.module.dsl.startKoin

@Module(includes = [
    SharedModule::class
])
@ComponentScan("com.xingheyuzhuan.shiguangschedule")
class AppModule

@KoinApplication(modules = [AppModule::class])
class ScheduleAppConfig

class MyApplication : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()

        startKoin<ScheduleAppConfig> {
            androidLogger()
            androidContext(this@MyApplication)
            workManagerFactory()
        }

        // 注册自动同步调度桥接，并按当前配置恢复周期任务
        AutoSyncSchedulerBridge.onReschedule = { AutoSyncScheduler.schedule(this@MyApplication) }
        AutoSyncScheduler.schedule(this@MyApplication)
    }
}