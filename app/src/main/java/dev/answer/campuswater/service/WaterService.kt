package dev.answer.campuswater.service

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.answer.campuswater.data.AppSettings
import dev.answer.campuswater.data.EnginePhase
import dev.answer.campuswater.data.WaterEngine
import dev.answer.campuswater.data.WaterStore
import dev.answer.campuswater.notify.Notifier

/**
 * 后台用水监控前台服务。
 *
 * <p>它存在的唯一理由：**划掉最近任务之后，倒计时和自动结算还得继续跑**。
 * 蓝牙链路和状态都在 [WaterEngine] 里，服务只是那个"不让进程死掉"的壳，
 * 外加通知栏的常驻状态条与轮询节拍。
 */
class WaterService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                attachForeground()
                WaterEngine.requestEnd()
                return START_STICKY
            }

            ACTION_DISMISS -> {
                stopEverything()
                return START_NOT_STICKY
            }
        }

        attachForeground()
        // 进程被系统回收后重新拉起：本地还记着"正在用水"，就把蓝牙重连、继续盯
        if (!WaterEngine.state.value.inUse && WaterStore.isActive &&
            WaterEngine.state.value.phase == EnginePhase.DISCONNECTED
        ) {
            WaterStore.device()?.let { WaterEngine.connect(it) }
        }
        if (!AppSettings.monitorEnabled) {
            stopEverything()
            return START_NOT_STICKY
        }
        scheduleTick(0L)
        return START_STICKY
    }

    /**
     * 挂上前台通知。
     *
     * <p>Android 8+ 要求 startForeground 在 startForegroundService 之后 5 秒内完成，
     * 而且**任何情况下都要有一条通知**；用水已经结束时挂一条"监控中"的最小通知。
     */
    private fun attachForeground() {
        val state = WaterEngine.state.value
        val device = state.device ?: WaterStore.device()
        val label = device?.let { WaterEngine.deviceLabel(it) } ?: "用水设备"
        val notification = Notifier.buildInUse(this, label, state.startedAtMs, state.inUse)
        try {
            startForeground(Notifier.ID_IN_USE, notification)
        } catch (e: Exception) {
            // 通知权限被收回时会抛，进程不该因此崩
            stopSelf()
        }
    }

    private fun scheduleTick(delayMs: Long) {
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, delayMs)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!AppSettings.monitorEnabled) {
                stopEverything()
                return
            }
            val state = WaterEngine.state.value
            if (!state.inUse && !WaterStore.isActive) {
                stopEverything()
                return
            }
            // 到点与否由状态回调判断：先标记归零（触发确认框事件 + 让引擎查状态补结算）
            if (state.inUse && state.deadlineMs > 0 &&
                WaterEngine.countdownRemainSec() == 0
            ) {
                WaterEngine.markDeadlineReached()
            }
            WaterEngine.queryStatus()
            scheduleTick(WaterEngine.monitorDelayMs() + POLL_SETTLE_GAP_MS)
        }
    }

    private fun stopEverything() {
        handler.removeCallbacks(tick)
        Notifier.cancelInUse(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    companion object {
        /** 启动/维持用水监控（开阀成功、恢复现场时都会发）。 */
        const val ACTION_MONITOR = "dev.answer.campuswater.action.MONITOR_WATER"

        /** 通知栏「结束用水」。 */
        const val ACTION_STOP = "dev.answer.campuswater.action.STOP_WATER"

        /** 用户主动停掉后台监控。 */
        const val ACTION_DISMISS = "dev.answer.campuswater.action.DISMISS_MONITOR"

        /** 查询之后留一点时间让设备回帧，再排下一次。 */
        private const val POLL_SETTLE_GAP_MS = 2500L
    }
}
