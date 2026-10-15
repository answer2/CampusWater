package dev.answer.campuswater.ui

import androidx.compose.ui.graphics.Color
import java.util.Locale

/**
 * 可选主题色。
 *
 * <p>这些值只作为 Miuix Monet 的**种子色**，整套配色由它推导 —— 所以选色会影响按钮、
 * 链接、卡片高亮等所有地方，而不是只换个按钮颜色。
 */
data class ThemeColor(val name: String, val argb: Long)

object ThemePalette {

    /** 种子色 0 = 跟随壁纸动态取色。 */
    val wallpaper = ThemeColor("跟随壁纸", 0L)

    val all: List<ThemeColor> = listOf(
        wallpaper,
        // 苹果系统蓝（iOS 浅色 #007AFF / 深色 #0A84FF 的折中），配液态玻璃最接近新设计语言
        ThemeColor("苹果蓝", 0xFF0A84FF),
        ThemeColor("海蓝", 0xFF2F6FE0),
        ThemeColor("靛蓝", 0xFF3B4FC0),
        ThemeColor("天青", 0xFF0E91B5),
        ThemeColor("薄荷", 0xFF12A38A),
        ThemeColor("松绿", 0xFF1E8F55),
        ThemeColor("青柠", 0xFF6A8E12),
        ThemeColor("金黄", 0xFFC89412),
        ThemeColor("暖橙", 0xFFD2761B),
        ThemeColor("砖红", 0xFFC2453D),
        ThemeColor("桃粉", 0xFFD2618F),
        ThemeColor("紫罗兰", 0xFF6F52D6),
        ThemeColor("石墨", 0xFF5A6272),
    )

    /** 用户自选色不在预设里时，界面用这个名字。 */
    const val CUSTOM_NAME = "自定义"

    /**
     * 解析用户输入的十六进制色值。
     *
     * <p>允许 `#RRGGBB`、`RRGGBB`、`#AARRGGBB`；解析不出来返回 null（让界面提示而不是崩）。
     */
    fun parseHex(input: String): Long? {
        val raw = input.trim().removePrefix("#").uppercase(Locale.ROOT)
        if (raw.length != 6 && raw.length != 8) {
            return null
        }
        if (!raw.all { c -> c in "0123456789ABCDEF" }) {
            return null
        }
        val value = raw.toLong(16)
        return if (raw.length == 6) 0xFF000000L or value else value
    }

    /** 反过来说给用户看：#RRGGBB（丢掉 alpha=FF）。 */
    fun toHex(argb: Long): String {
        val rgb = argb and 0xFFFFFFL
        return String.format(Locale.US, "#%06X", rgb)
    }

    fun colorOf(argb: Long): Color = Color(argb.toInt())
}
