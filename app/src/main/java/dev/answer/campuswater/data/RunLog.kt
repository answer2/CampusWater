package dev.answer.campuswater.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局运行日志。
 *
 * <p>以前每个页面各自挂一块日志区，界面被一堆调试文字占着；现在只**记录**，
 * 查看与复制统一收到「个人 → 运行日志」。环形缓冲，超过上限丢最旧的。
 */
object RunLog {

    private const val MAX_LINES = 500

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private val lines = ArrayDeque<String>()
    private val _version = MutableStateFlow(0)

    /** 单调递增的版本号：日志页用它判断要不要重新取 [snapshot]。 */
    val version = _version.asStateFlow()

    /** 记录一行。[tag] 是来源模块（用水 / 扫描 / 充值 / 账单…）。 */
    fun add(tag: String, message: String) {
        val line = "%s [%s] %s".format(timeFormat.format(Date()), tag, message)
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) {
                lines.removeFirst()
            }
        }
        _version.value = _version.value + 1
    }

    /** 当前全部日志（新→旧顺序保持记录顺序）。 */
    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    /** 复制用的整段文本。 */
    fun text(): String = snapshot().joinToString("\n")

    fun clear() {
        synchronized(lines) { lines.clear() }
        _version.value = _version.value + 1
    }
}
