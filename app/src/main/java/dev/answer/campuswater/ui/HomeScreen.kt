package dev.answer.campuswater.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.ui.unit.dp
import dev.answer.campuswater.data.WaterEngine
import dev.answer.campuswater.data.WaterEvent
import kotlinx.coroutines.flow.collectLatest
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.useful.Back
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 底部标签。顺序与 [HomeScreen] 里的 when 分支一一对应。 */
private val TABS = listOf(
    NavigationItem("用水", AppIcons.Water),
    NavigationItem("充值", AppIcons.Wallet),
    NavigationItem("账单", AppIcons.Receipt),
    NavigationItem("个人", AppIcons.Person),
)

private val TAB_TITLES = listOf("校园用水", "充值", "账单", "个人")

/**
 * 主界面外壳：顶栏 + 三个标签页。
 *
 * <p>用水页在"扫描选设备"和"用水控制"两个形态之间切换，不额外开 Activity ——
 * 会话状态本来就在进程级的 [WaterEngine] 里，开新页面反而会把状态所有权搅乱。
 */
@Composable
fun HomeScreen(startOnWaterPage: Boolean) {
    var tab by remember { mutableStateOf(0) }
    var showWaterPage by remember { mutableStateOf(startOnWaterPage && WaterEngine.state.value.device != null) }

    val waterState by WaterEngine.state.collectAsState()
    var banner by remember { mutableStateOf<WaterEvent.Banner?>(null) }
    var countdownDialog by remember { mutableStateOf<WaterEvent.CountdownElapsed?>(null) }

    // 事件只消费一次：界面不在前台时事件就丢掉（通知栏那条是服务发的，不受影响）
    LaunchedEffect(Unit) {
        WaterEngine.events.collectLatest { event ->
            when (event) {
                is WaterEvent.Banner -> banner = event
                is WaterEvent.Finished -> if (showWaterPage) banner = null
                is WaterEvent.CountdownElapsed -> countdownDialog = event
            }
        }
    }

    // 玻璃组件统一采样这块背景层（而不是滚动内容，否则卡片自己采自己）
    val glassBackdrop = rememberGlassBackdrop()
    val glass = glassMode()
    // 顶栏和底栏要糊/折的是"正在滚动的页面内容"，所以内容另外登记一层。
    // 底栏再把渐变背景叠上，内容没铺满时也有颜色可折。
    val contentBackdrop = rememberLayerBackdrop()
    val barBackdrop = if (glass) {
        rememberCombinedBackdrop(glassBackdrop, contentBackdrop)
    } else {
        glassBackdrop
    }

    Scaffold(
        topBar = {
            Box(Modifier.fillMaxWidth()) {
                if (glass) {
                    GlassToolbarLayer(contentBackdrop, Modifier.matchParentSize())
                }
                TopAppBar(
                    TAB_TITLES[tab],
                    // 玻璃风格下顶栏要完全隐形（内容从底下滚过去时被糊住）；
                    // Miuix 风格下交还它自己的底色和分割线。
                    Modifier,
                    if (glass) Color.Transparent else Color.Unspecified,
                    navigationIcon = {
                        if (showWaterPage && tab == 0) {
                            // 返回即断开：蓝牙连着不放会挡住别人（设备同一时刻只接受一个客户端）
                            GlassPill(Modifier.size(40.dp)) {
                                IconButton(
                                    onClick = {
                                        showWaterPage = false
                                        WaterEngine.disconnect()
                                    },
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Icon(
                                        imageVector = MiuixIcons.Useful.Back,
                                        contentDescription = "返回设备列表",
                                    )
                                }
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            // 玻璃模式：胶囊底栏 + 真模糊/折射（Kyant0 Backdrop）
            if (glass) {
                LiquidBottomBar(
                    items = TABS,
                    selected = tab,
                    onSelect = { tab = it },
                    backdrop = barBackdrop,
                )
            } else {
                NavigationBar(
                    items = TABS,
                    selected = tab,
                    onClick = { tab = it },
                )
            }
        },
    ) { outerPadding ->
        // 玻璃模式下内容要延伸到顶栏和胶囊底栏下方（否则玻璃后面没东西可糊/可折射），
        // 上下留白交给 Page 补进滚动内容里；普通模式仍按 Scaffold 给的边距走。
        val padding = if (glass) PaddingValues() else outerPadding
        val bottomExtra = if (glass) 130.dp else 0.dp
        CompositionLocalProvider(
            LocalGlassBackdrop provides if (glass) glassBackdrop else null,
            LocalContentBackdrop provides if (glass) contentBackdrop else null,
            LocalTopInset provides if (glass) outerPadding.calculateTopPadding() else 0.dp,
        ) {
        Box(Modifier.fillMaxSize()) {
        if (glass) {
            GlassBackdropLayer(glassBackdrop)
        }
        Column(Modifier.fillMaxSize()) {
            BannerHost(banner = banner, onDismiss = { banner = null })
            countdownDialog?.let {
                AutoCloseDialog(
                    onDismiss = { countdownDialog = null },
                    onContinue = {
                        countdownDialog = null
                        WaterEngine.extendDeadline()
                    },
                    onStop = {
                        countdownDialog = null
                        WaterEngine.requestEnd()
                    },
                )
            }
            when (tab) {
                0 -> if (showWaterPage) {
                    WaterPage(
                        state = waterState,
                        padding = padding,
                        bottomExtra = bottomExtra,
                        onOpenBills = { tab = 2 },
                    )
                } else {
                    ScanPage(
                        padding = padding,
                        bottomExtra = bottomExtra,
                        onOpenDevice = {
                            WaterEngine.connect(it)
                            showWaterPage = true
                        },
                    )
                }

                1 -> RechargePage(padding = padding, bottomExtra = bottomExtra)
                2 -> BillPage(padding = padding, bottomExtra = bottomExtra)
                3 -> ProfilePage(padding = padding, bottomExtra = bottomExtra)
            }
        }
        }
        }
    }
}
