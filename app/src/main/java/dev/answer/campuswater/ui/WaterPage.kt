package dev.answer.campuswater.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.answer.campuswater.api.ApiConfig
import dev.answer.campuswater.api.Money
import dev.answer.campuswater.api.Session
import dev.answer.campuswater.api.Settlement
import dev.answer.campuswater.api.UseTimeSlot
import dev.answer.campuswater.data.AppSettings
import dev.answer.campuswater.data.BannerKind
import dev.answer.campuswater.data.EnginePhase
import dev.answer.campuswater.data.Tone
import dev.answer.campuswater.data.UseTimeRepo
import dev.answer.campuswater.data.WaterEngine
import dev.answer.campuswater.data.WaterEvent
import dev.answer.campuswater.data.WaterStore
import dev.answer.campuswater.data.WaterUiState
import kotlinx.coroutines.delay
import java.util.Locale
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 用水控制页。
 *
 * <p>保留小程序"一个按钮两用"的语义：空闲即开始用水，使用中即结束用水。
 * 另外把闲置倒计时、本单结算、后台监控与提醒开关收进同一屏。
 */
@Composable
fun WaterPage(
    state: WaterUiState,
    padding: PaddingValues,
    bottomExtra: Dp = 0.dp,
    onOpenBills: () -> Unit = {},
) {
    val useTime by UseTimeRepo.state.collectAsState()
    // tick 只为触发每秒重组；剩余时间一律从截止时间现算，切后台回来也不会漂
    var tick by remember { mutableIntStateOf(0) }
    var confirmOutsideSlot by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }
    refreshTick
    var banner by remember { mutableStateOf<WaterEvent.Banner?>(null) }

    LaunchedEffect(state.inUse) {
        while (state.inUse) {
            tick++
            delay(1000L)
        }
    }
    LaunchedEffect(state.device?.bigTypeId) {
        state.device?.let { UseTimeRepo.load(ApiConfig.moduleIdFor(it.bigTypeId)) }
    }

    val now = System.currentTimeMillis()
    val elapsedMs = if (state.inUse && state.startedAtMs > 0) now - state.startedAtMs else 0L
    // 单价（元/分钟）来自上一单：用水过程中服务端不推实时扣费，只能本地估
    val unitPrice = WaterStore.lastMoney?.let { m ->
        val minutes = WaterStore.lastDurationMs / 60000.0
        if (minutes >= 0.5 && m > 0) m / minutes else null
    }
    val remainSec = state.countdownRemainSec(now)
    val countdownVisible = state.inUse && state.autoDisconSec > 0

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            banner = WaterEvent.Banner(
                BannerKind.INFO, "通知权限未授予",
                "没有通知权限时，常驻状态条与「结束用水」按钮都不会出现"
            )
        }
    }

    // 倒计时到点：交给引擎统一发事件（确认框由外层弹，后台服务也会收到同一条）
    LaunchedEffect(remainSec, state.inUse) {
        if (tick > 0 && state.inUse && state.deadlineMs in 1..now) {
            WaterEngine.markDeadlineReached()
        }
    }

    Page(padding = padding, bottomExtra = bottomExtra) {
        BannerHost(banner = banner, onDismiss = { banner = null })

        // ------------------------------------------------------ 当前状态
        Section {
            Stacked(Modifier.padding(20.dp)) {
                Text(
                    text = state.device?.let { WaterEngine.deviceLabel(it) } ?: "设备",
                    style = MiuixTheme.textStyles.title1,
                )
                Footnote(text = state.device?.bleAddress?.let { "蓝牙地址 $it" } ?: "")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatusDot(state.statusTone)
                    Text(
                        text = state.statusText,
                        style = MiuixTheme.textStyles.body1,
                        color = toneColor(state.statusTone),
                    )
                }
                if (state.inUse) {
                    Text(
                        text = "已用水 " + Settlement.formatDuration(elapsedMs),
                        style = MiuixTheme.textStyles.headline2,
                        color = MiuixTheme.colorScheme.primary,
                    )
                    // 单价取自上一单：用水过程中服务端不推实时扣费，只能本地估
                    unitPrice?.let { price ->
                        Footnote(
                            text = "预估本单 ¥%.2f（按上一单 ¥%.2f/分钟）· 余额 ¥%s".format(
                                price * (elapsedMs / 60000.0),
                                price,
                                Money.display(Session.get().accountMoney),
                            )
                        )
                    }
                }
            }
        }

        // ------------------------------------------------------ 闲置自动关停倒计时
        if (countdownVisible) {
            Section(title = "闲置自动关停倒计时") {
                Stacked(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "剩余时间",
                            style = MiuixTheme.textStyles.body2,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = formatClock(remainSec),
                            style = MiuixTheme.textStyles.headline2,
                            color = if (remainSec <= 30) {
                                MiuixTheme.colorScheme.error
                            } else {
                                MiuixTheme.colorScheme.primary
                            },
                        )
                    }
                    LinearProgressIndicator(
                        progress = (remainSec.toFloat() / state.autoDisconSec.coerceAtLeast(1))
                            .coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Footnote(
                        text = "停止出水超过这个时长，设备会自动关阀。到点时这里会弹确认框；" +
                            "退到后台则由通知提醒，后台监控照常结算。"
                    )
                }
            }
        }

        // ------------------------------------------------------ 本单消费
        state.lastSettlement?.let { info ->
            Section(title = "本次消费") {
                Stacked(Modifier.padding(20.dp)) {
                    Text(
                        text = if (info.pending) "结算中…"
                        else "¥" + String.format(Locale.US, "%.2f", info.moneyYuan),
                        style = MiuixTheme.textStyles.headline1,
                        color = MiuixTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Footnote(
                        text = ("用时 ${Settlement.formatDuration(info.durationMs)}" +
                            (if (info.autoClosed) " · 超时自动关停" else "") +
                            (if (info.source.isNotEmpty()) " · ${info.source}" else "")),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // ------------------------------------------------------ 主操作
        if (state.inUse) {
            DangerButton(
                text = "结束用水",
                onClick = { WaterEngine.requestEnd() },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            PrimaryButton(
                text = state.actionLabel,
                onClick = {
                    askNotificationPermission(notificationLauncher)
                    val slots = useTime.slots
                    if (!state.inUse && slots.isNotEmpty() && !UseTimeSlot.isNowAllowed(slots)) {
                        confirmOutsideSlot = true
                    } else {
                        WaterEngine.toggle()
                    }
                },
                enabled = !state.busy && state.phase != EnginePhase.CONNECTING,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LinkButton(
                text = "刷新状态",
                onClick = { WaterEngine.queryStatus() },
                enabled = state.phase == EnginePhase.READY && !state.busy,
                modifier = Modifier.weight(1f),
            )
            LinkButton(
                text = "重新连接",
                onClick = { state.device?.let { WaterEngine.connect(it) } },
                enabled = !state.busy && state.phase != EnginePhase.CONNECTING,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LinkButton(
                text = "断开连接",
                onClick = { WaterEngine.disconnect() },
                enabled = state.phase != EnginePhase.DISCONNECTED,
                modifier = Modifier.weight(1f),
            )
            LinkButton(
                text = "查看账单",
                onClick = onOpenBills,
                modifier = Modifier.weight(1f),
            )
        }
        if (state.busy) {
            LinearProgressIndicator(progress = null, modifier = Modifier.fillMaxWidth())
        }

        // ------------------------------------------------------ 上次消费
        WaterStore.lastMoney?.let { money ->
            Section(title = "上次消费") {
                CardBody {
                    InfoLine("金额", "¥%.2f".format(money))
                    InfoLine("用时", Settlement.formatDuration(WaterStore.lastDurationMs))
                    InfoLine("账单时间", WaterStore.lastConsumeTime.ifEmpty { "（服务端未给）" })
                }
            }
        }

        // ------------------------------------------------------ 设备与账户
        state.device?.let { device ->
            Section(title = "设备与账户") {
                CardBody {
                    InfoLine("设备名称", device.deviceName.ifEmpty { "设备 ${device.devId}" })
                    InfoLine("设备编号", device.devId)
                    InfoLine("类型", ApiConfig.bigTypeName(device.bigTypeId))
                    InfoLine("蓝牙地址", device.bleAddress)
                    InfoLine("项目编号", Session.get().projectId.ifEmpty { "未绑定" })
                    InfoLine(
                        "连接状态",
                        when (state.phase) {
                            EnginePhase.CONNECTING -> "连接中"
                            EnginePhase.READY -> "已连接"
                            EnginePhase.CONNECT_FAILED -> "连接失败"
                            EnginePhase.DISCONNECTED -> "未连接"
                        },
                    )
                }
            }
        }

        // ------------------------------------------------------ 使用时段
        Section(
            title = "使用时段",
            summary = if (useTime.allowedNow) null else "当前不在时段内，设备可能不出水。",
        ) {
            CardBody {
                UseTimeList(slots = useTime.slots, loading = useTime.loading)
            }
        }

        // ------------------------------------------------------ 超时设置
        Section(
            title = "超时设置",
            summary = "这里改的是本应用的计时与提醒基准；设备自己多久关阀由服务端下发给它的费率决定，" +
                "客户端改不动它。选「到点自动结束用水」才会真的下发关阀指令。",
        ) {
            BasicComponent(
                title = "跟随服务端下发的时长",
                summary = if (AppSettings.timeoutFollowServer) {
                    "当前用服务端给的值" +
                        (if (state.autoDisconSec > 0) "（本次 ${state.autoDisconSec / 60} 分钟）" else "（本次未下发）")
                } else {
                    "已改为手动指定，下面滑条生效"
                },
                rightActions = {
                    AppSwitch(
                        checked = AppSettings.timeoutFollowServer,
                        onCheckedChange = {
                            AppSettings.timeoutFollowServer = it
                            refreshTick++
                        },
                    )
                },
            )
            if (!AppSettings.timeoutFollowServer) {
                HorizontalDivider()
                CardBody {
                    Text(
                        text = "超时时间：${AppSettings.timeoutMinutes} 分钟",
                        style = MiuixTheme.textStyles.body1,
                    )
                    Slider(
                        value = AppSettings.timeoutMinutes.toFloat(),
                        onValueChange = {
                            AppSettings.timeoutMinutes = it.toInt()
                            refreshTick++
                        },
                        valueRange = 1f..60f,
                        steps = 58,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Footnote(text = "拖动设置，1 到 60 分钟。下一次开始用水时生效。")
                }
            }
            HorizontalDivider()
            BasicComponent(
                title = "到点自动结束用水",
                summary = if (AppSettings.autoEndAtDeadline) {
                    "到点直接下发关阀指令并结算，不再弹确认框"
                } else {
                    "只提醒不关阀：界面弹确认框，后台发通知"
                },
                rightActions = {
                    AppSwitch(
                        checked = AppSettings.autoEndAtDeadline,
                        onCheckedChange = { AppSettings.autoEndAtDeadline = it },
                    )
                },
            )
        }

        // ------------------------------------------------------ 提醒与后台监控
        Section(
            title = "提醒与后台监控",
            summary = "这三项只影响提醒方式，不影响计费；关阀与结算仍以服务端为准。",
        ) {
            BasicComponent(
                title = "后台用水监控",
                summary = "监控跑在前台服务里，退出应用、划掉最近任务都不影响：超时自动关停照常生效",
                rightActions = {
                    AppSwitch(
                        checked = AppSettings.monitorEnabled,
                        onCheckedChange = { enabled ->
                            AppSettings.monitorEnabled = enabled
                            if (enabled) {
                                WaterEngine.startMonitorIfNeeded()
                            } else {
                                banner = WaterEvent.Banner(
                                    BannerKind.INFO, "后台监控已关闭",
                                    "退到后台后不再计时与自动结算，请留意"
                                )
                            }
                        },
                    )
                },
            )
            BasicComponent(
                title = "用水期间通知",
                summary = "通知栏常驻状态条并带「结束用水」按钮；结束、超时自动关停、开阀失败都有提醒",
                rightActions = {
                    AppSwitch(
                        checked = AppSettings.inUseNotificationEnabled,
                        onCheckedChange = { AppSettings.inUseNotificationEnabled = it },
                    )
                },
            )
            BasicComponent(
                title = "横幅提醒",
                summary = "开阀失败 / 设备被占用 / 超时关停会弹横幅；关掉后只静默进通知列表",
                rightActions = {
                    AppSwitch(
                        checked = AppSettings.bannerEnabled,
                        onCheckedChange = { AppSettings.bannerEnabled = it },
                    )
                },
            )
        }

    }

    // 不在使用时段：提醒一次，但不强制拦截（和小程序一致）
    if (confirmOutsideSlot) {
        AppDialog(
            title = "不在使用时段",
            summary = "该类设备的使用时段为：${UseTimeSlot.summary(useTime.slots)}\n" +
                "当前不在时段内，设备可能不出水。仍要继续吗？",
            onDismiss = { confirmOutsideSlot = false },
        ) {
            PrimaryButton(
                text = "继续用水",
                onClick = { confirmOutsideSlot = false; WaterEngine.toggle() },
                modifier = Modifier.fillMaxWidth(),
            )
            LinkButton(
                text = "取消",
                onClick = { confirmOutsideSlot = false },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 倒计时归零的确认框。
 *
 * <p>由外层统一弹：事件既可能来自界面轮询，也可能来自后台服务。
 */
@Composable
fun AutoCloseDialog(
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
    onStop: () -> Unit,
) {
    AppDialog(
        title = "闲置自动关停时间已到",
        summary = "设备可能已经自己关阀并结算。要继续用水就重新计时，否则现在结束这一单。",
        onDismiss = onDismiss,
    ) {
        PrimaryButton(
            text = "继续用水",
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        )
        DangerButton(
            text = "结束用水",
            onClick = onStop,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StatusDot(tone: Tone) {
    val color = toneColor(tone)
    Canvas(Modifier.size(10.dp)) {
        drawCircle(color)
    }
}

private fun formatClock(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

/** 缺通知权限就没有常驻状态条与横幅，第一次用水时申请一次。 */
private fun askNotificationPermission(
    launcher: androidx.activity.result.ActivityResultLauncher<String>,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
