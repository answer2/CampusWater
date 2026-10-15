package dev.answer.campuswater

import android.app.Application
import dev.answer.campuswater.api.Session
import dev.answer.campuswater.data.ScanEngine
import dev.answer.campuswater.data.WaterEngine

/**
 * 进程级初始化。
 *
 * <p>这些单例原来是在各个 Activity 里分别 init 的；现在蓝牙会话搬到了进程级
 * （前台服务可能在没有 Activity 的情况下被拉起），必须保证先于任何界面就绪。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Session.init(this)
        WaterEngine.init(this)
        ScanEngine.init(this)
    }
}
