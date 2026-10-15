package dev.answer.campuswater.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import dev.answer.campuswater.R
import dev.answer.campuswater.data.AppSettings
import dev.answer.campuswater.data.SettlementInfo
import dev.answer.campuswater.service.WaterService

/**
 * 系统通知。
 *
 * <p>渠道的 importance 一旦创建就改不了，所以"横幅提醒"这个开关不能靠改渠道重要性实现，
 * 而是**准备两条渠道二选一**：需要弹横幅的走 HIGH（会 heads-up），
 * 关掉后走 DEFAULT/MIN（只进通知列表，不打扰）。
 */
object Notifier {

    private const val CHANNEL_IN_USE = "water_in_use"
    private const val CHANNEL_EVENTS = "water_events"
    private const val CHANNEL_ALERT = "water_alert"
    private const val CHANNEL_ALERT_QUIET = "water_alert_quiet"

    const val ID_IN_USE = 1001
    const val ID_FINISHED = 1002
    const val ID_AUTO_CLOSED = 1003
    const val ID_BANNER = 1004

    /** 通知栏"结束用水"按钮的 requestCode，与 Activity 的跳转码分开，避免互相覆盖 extra。 */
    private const val REQ_STOP = 7101

    private const val GROUP = "water_group"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = context.getSystemService<NotificationManager>() ?: return
        // 渠道引用了 group 就必须先有 group，否则 createNotificationChannel 直接抛
        // IllegalArgumentException: NotificationChannelGroup doesn't exist（真机实测崩在启动阶段）
        manager.createNotificationChannelGroup(
            NotificationChannelGroup(GROUP, "用水")
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_IN_USE, "用水中", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "用水期间的常驻状态条"; group = GROUP }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, "用水事件", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { group = GROUP }
        )
        // heads-up 必须是 HIGH；DEFAULT 在国产 ROM 上基本不弹横幅
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "用水提醒", NotificationManager.IMPORTANCE_HIGH)
                .apply { group = GROUP }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT_QUIET, "用水提醒（不弹横幅）",
                NotificationManager.IMPORTANCE_DEFAULT).apply { group = GROUP }
        )
    }

    // -------------------------------------------------------------- 常驻状态条

    /**
     * 用水期间的常驻通知。
     *
     * @param startedAt 开始用水时刻，交给系统用 chronometer 走秒，省掉每秒刷新通知
     */
    fun showInUse(context: Context, deviceLabel: String, startedAt: Long, using: Boolean) {
        if (!AppSettings.inUseNotificationEnabled) {
            return
        }
        notify(context, ID_IN_USE, buildInUse(context, deviceLabel, startedAt, using))
    }

    /**
     * 常驻状态条通知。
     *
     * <p>单独暴露给 [dev.answer.campuswater.service.WaterService]：前台服务必须在
     * `startForeground` 里传一条通知，不能只靠 notify()。
     */
    fun buildInUse(context: Context, deviceLabel: String, startedAt: Long, using: Boolean): Notification {
        val builder = baseBuilder(context, CHANNEL_IN_USE, ID_IN_USE)
            .setSmallIcon(R.drawable.ic_stat_water)
            .setContentTitle(if (using) "正在用水" else "用水监控中")
            .setContentText(deviceLabel)
            .setContentIntent(openAppIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(if (startedAt > 0) startedAt else System.currentTimeMillis())
            .setUsesChronometer(using && startedAt > 0)
            .setChronometerCountDown(using && startedAt > 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (using) {
            builder.addAction(0, "结束用水", stopIntent(context))
        }
        return builder.build()
    }

    fun cancelInUse(context: Context) {
        cancel(context, ID_IN_USE)
    }

    // -------------------------------------------------------------- 结束 / 自动关停

    /**
     * 结算通知：给出**本次消费金额和用水时长**。
     *
     * <p>"结算中 → ¥0.41"是同一条通知的两次形态（同一个 id 原地更新），
     * 不像现在这样每步都甩一条新的。
     */
    fun showFinished(context: Context, info: SettlementInfo) {
        val autoClosed = info.autoClosed
        val id = if (autoClosed) ID_AUTO_CLOSED else ID_FINISHED
        val title = when {
            autoClosed && info.pending -> "已自动关停，结算中"
            autoClosed -> "闲置超时，已自动关停"
            info.pending -> "用水已结束，结算中"
            else -> "用水已结束"
        }
        val builder = baseBuilder(context, eventChannel(autoClosed), id)
            .setSmallIcon(R.drawable.ic_stat_water)
            .setContentTitle(title)
            .setContentText(resultBody(info))
            .setStyle(NotificationCompat.BigTextStyle().bigText("$title\n${resultBody(info)}"))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
        // 自动关停是可感知的事件，允许用横幅；普通结束不打扰
        if (autoClosed && AppSettings.bannerEnabled) {
            builder.setPriority(NotificationCompat.PRIORITY_HIGH)
        } else {
            builder.setPriority(NotificationCompat.PRIORITY_DEFAULT)
        }
        notify(context, id, builder.build())
    }

    /** 金额与文案：null = 结算中，0 = 无消费，这是两个结论。 */
    private fun resultBody(info: SettlementInfo): String {
        val duration = dev.answer.campuswater.api.Settlement.formatDuration(info.durationMs)
        val money = if (info.pending) {
            "结算中"
        } else {
            "消费 ¥" + String.format(java.util.Locale.US, "%.2f", info.moneyYuan ?: 0.0)
        }
        return "用时 $duration · $money"
    }

    // -------------------------------------------------------------- 横幅类事件

    /**
     * 开阀失败 / 设备被占用 / 关阀失败之类需要用户马上知道的事。
     *
     * <p>[bannerable] 为真时受「横幅提醒」开关控制；关掉开关后只静默进通知列表，
     * 界面里仍然会显示顶部横幅（那条由 Compose 侧的 flow 驱动）。
     */
    fun showEvent(
        context: Context,
        title: String,
        message: String,
        bannerable: Boolean = true,
    ) {
        val alert = bannerable && AppSettings.bannerEnabled
        val n = baseBuilder(context, if (alert) CHANNEL_ALERT else CHANNEL_ALERT_QUIET, ID_BANNER)
            .setSmallIcon(R.drawable.ic_stat_water)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .setPriority(if (alert) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notify(context, ID_BANNER, n)
    }

    /** 事件渠道：允许弹横幅的走 HIGH，否则走 DEFAULT。 */
    private fun eventChannel(alert: Boolean): String =
        if (alert && AppSettings.bannerEnabled) CHANNEL_ALERT else CHANNEL_EVENTS

    // -------------------------------------------------------------- 内部

    private fun baseBuilder(context: Context, channel: String, id: Int): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channel)
            .setGroup(GROUP)

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, dev.answer.campuswater.MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .putExtra("show_shower", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, 7000, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun stopIntent(context: Context): PendingIntent {
        val intent = Intent(context, WaterService::class.java)
            .setAction(WaterService.ACTION_STOP)
        return PendingIntent.getService(
            context, REQ_STOP, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notify(context: Context, id: Int, notification: Notification) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        manager.notify(id, notification)
    }

    private fun cancel(context: Context, id: Int) {
        context.getSystemService<NotificationManager>()?.cancel(id)
    }
}
