package dev.answer.campuswater.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 用户可改的开关。
 *
 * <p>和 [WaterStore] 分开存：这里是"偏好"，跨会话生效；那里是"这一次用水的现场"，
 * 用完就该清掉。
 */
object AppSettings {

    private const val PREF = "campuswater_settings"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        }
    }

    /**
     * 设置变更信号。
     *
     * <p>为什么需要它：SharedPreferences 对 Compose 不可观察，主题在 `AppTheme` 里读
     * [seedColor]，而 `AppTheme` 位于界面树上层 —— 在个人页 bump 一个本地状态
     * 根本不会让它重组，于是"点了颜色没反应"（真机实测）。
     * 现在所有影响外观的写入都 [bump]，外观层 collect 这个流即可即时生效。
     */
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()

    private fun bump() {
        _revision.value = System.nanoTime()
    }

    /** 横幅提醒：开阀失败 / 设备被占用 / 超时关停是否用 heads-up 横幅弹出。 */
    var bannerEnabled: Boolean
        get() = prefs.getBoolean("banner", true)
        set(v) = prefs.edit().putBoolean("banner", v).apply()

    /** 后台用水监控：关掉后退出应用就不再计时、不再自动结算。 */
    var monitorEnabled: Boolean
        get() = prefs.getBoolean("monitor", true)
        set(v) = prefs.edit().putBoolean("monitor", v).apply()

    /** 用水期间的常驻状态条通知。 */
    var inUseNotificationEnabled: Boolean
        get() = prefs.getBoolean("in_use_notification", true)
        set(v) = prefs.edit().putBoolean("in_use_notification", v).apply()

    // ---------------------------------------------------------------- 超时设置

    /**
     * 闲置自动关停时长是否用服务端下发的值。
     *
     * <p>关掉后改用 [timeoutMinutes]。注意这只是**客户端的计时基准**：
     * 设备自己多久关阀由服务端下发给设备的费率包决定，改这里改不动设备，
     * 影响的只是"什么时候提醒 / 要不要到点主动关阀"。
     */
    var timeoutFollowServer: Boolean
        get() = prefs.getBoolean("timeout_follow", true)
        set(v) = prefs.edit().putBoolean("timeout_follow", v).apply()

    /** 自定义超时分钟数（1–60）。 */
    var timeoutMinutes: Int
        get() = prefs.getInt("timeout_minutes", 10).coerceIn(1, 60)
        set(v) = prefs.edit().putInt("timeout_minutes", v.coerceIn(1, 60)).apply()

    /** 到点是否主动发关阀指令；关掉就只提醒。 */
    var autoEndAtDeadline: Boolean
        get() = prefs.getBoolean("timeout_auto_end", false)
        set(v) = prefs.edit().putBoolean("timeout_auto_end", v).apply()

    // ---------------------------------------------------------------- 外观

    /** 深浅色跟随系统；关掉后由 [darkMode] 决定。 */
    var followSystemTheme: Boolean
        get() = prefs.getBoolean("theme_follow", true)
        set(v) {
            prefs.edit().putBoolean("theme_follow", v).apply()
            bump()
        }

    var darkMode: Boolean
        get() = prefs.getBoolean("theme_dark", false)
        set(v) {
            prefs.edit().putBoolean("theme_dark", v).apply()
            bump()
        }

    /**
     * 主题种子色（ARGB）。
     *
     * <p>{@code 0} 表示"跟随壁纸动态取色"（拿不到壁纸色时退回 Miuix 默认配色）；
     * 其它值走 Miuix 的 Monet 种子色通道，全站配色由它推导，
     * 所以选完颜色按钮、卡片、链接都会一起变。
     */
    var seedColor: Long
        get() = prefs.getLong("theme_seed", 0L)
        set(v) {
            prefs.edit().putLong("theme_seed", v).apply()
            bump()
        }

    /**
     * 液态玻璃：底栏浮成胶囊，并用 Backdrop 采样下方内容做真模糊与边缘折射。
     *
     * <p>能力分级由库自己判断：模糊要安卓 12+，折射要安卓 13+；
     * 更低版本 effect 直接跳过，界面退回半透明底，不会变成看不见的透明条。
     */
    var glassEffect: Boolean
        get() = prefs.getBoolean("glass", false)
        set(v) {
            prefs.edit().putBoolean("glass", v).apply()
            bump()
        }

    /** 用户协议是否已同意。只影响登录页的勾选，不影响外观，所以不用 bump。 */
    var termsAccepted: Boolean
        get() = prefs.getBoolean("terms", false)
        set(v) = prefs.edit().putBoolean("terms", v).apply()

    /**
     * 纯白浅色主题：把背景/表面从 Monet 推导出来的灰紫色换成白与近白，
     * 强调色仍然跟着主题色走。只作用于浅色，深色模式下这个开关没有意义。
     */
    var whiteBackground: Boolean
        get() = prefs.getBoolean("white_bg", false)
        set(v) {
            prefs.edit().putBoolean("white_bg", v).apply()
            bump()
        }
}
