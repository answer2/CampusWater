package dev.answer.campuswater

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import dev.answer.campuswater.data.AuthState
import dev.answer.campuswater.data.AppSettings
import dev.answer.campuswater.ui.HomeScreen
import dev.answer.campuswater.ui.LoginScreen
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 内容延伸到状态栏/导航栏下方，系统栏图标对比度交给 Activity 库按主题亮度处理，
        // 具体边距由 Miuix 组件自己按 inset 补
        enableEdgeToEdge()
        val showShower = intent?.getBooleanExtra("show_shower", false) == true
        setContent {
            AppTheme {
                AppRoot(showShower)
            }
        }
    }
}

/**
 * 主题。
 *
 * <p>Miuix 的 Monet* 模式有两条路：给了种子色就从种子推导整套配色（任何机型都一致），
 * 没给就去系统动态取色（安卓 12+ 取壁纸，更低版本退回 Miuix 默认配色）。
 * 所以"跟随壁纸"和"自选主题色"共用同一套模式，只差一个 keyColor。
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    // 订阅设置变更信号：不 collect 的话改了颜色要重启应用才生效
    val revision by AppSettings.revision.collectAsState()
    val seed = AppSettings.seedColor
    revision
    val mode = if (AppSettings.followSystemTheme) {
        ColorSchemeMode.MonetSystem
    } else if (AppSettings.darkMode) {
        ColorSchemeMode.MonetDark
    } else {
        ColorSchemeMode.MonetLight
    }
    val keyColor = if (seed == 0L) null else Color(seed.toInt())
    val controller = remember(mode, keyColor) { ThemeController(mode, keyColor) }
    val baseColors = controller.currentColors()
    val pureWhite = AppSettings.whiteBackground && mode != ColorSchemeMode.MonetDark
    // Monet 从种子推导出来的"浅色背景"其实带一层灰紫，Apple 的浅色是干净的白。
    // 这里只换中性色，强调色继续跟着主题色，否则换成白底按钮就变灰了。
    val colors = remember(baseColors, pureWhite) {
        if (!pureWhite) {
            baseColors
        } else {
            baseColors.copy(
                background = Color.White,
                surface = Color.White,
                surfaceVariant = Color(0xFFF2F2F4),
                surfaceContainer = Color(0xFFF7F7F9),
                surfaceContainerHigh = Color(0xFFF1F1F4),
                surfaceContainerHighest = Color(0xFFEAEAEE),
                outline = Color(0xFFD9D9DE),
                dividerLine = Color(0xFFE4E4E8),
            )
        }
    }
    MiuixTheme(colors) {
        content()
    }
}

/**
 * 登录态决定看到哪一面。
 *
 * <p>界面状态由 [AuthState] 驱动而不是本地 remember：会话中途失效时（服务端 errorCode 8），
 * 引擎也能把用户退回登录页，而不是卡在一个人工校验的表单上。
 */
@Composable
fun AppRoot(showShower: Boolean) {
    val loggedIn by AuthState.loggedIn.collectAsState()
    if (loggedIn) {
        HomeScreen(startOnWaterPage = showShower)
    } else {
        LoginScreen(onLoggedIn = { AuthState.markLoggedIn() })
    }
}
