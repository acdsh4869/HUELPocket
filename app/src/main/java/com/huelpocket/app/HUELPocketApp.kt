package com.huelpocket.app

import android.app.Application
import com.huelpocket.app.core.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class HUELPocketApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        AppGraph.pomodoroNotifications.ensureChannels()

        // 番茄钟可能在上一轮进程里就被杀了 —— 启动时把状态接回来。
        // 剩余时间是用墙钟算的，所以"接回来"之后不用做任何补偿。
        appScope.launch {
            runCatching { AppGraph.pomodoroEngine.restore() }
        }
    }
}
