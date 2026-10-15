package dev.answer.campuswater.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.answer.campuswater.api.ApiConfig
import dev.answer.campuswater.api.ApiException
import dev.answer.campuswater.api.Money
import dev.answer.campuswater.api.Session
import dev.answer.campuswater.api.WaterApi
import dev.answer.campuswater.data.AppSettings
import dev.answer.campuswater.data.AuthState
import dev.answer.campuswater.data.BannerKind
import dev.answer.campuswater.data.RunLog
import dev.answer.campuswater.data.WaterEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.useful.Confirm
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 个人页：账号信息、外观、日志与关于、退出登录。
 *
 * <p>运行日志只在这里看：平时各模块只往 [RunLog] 写，界面上不再摆一块调试文字，
 * 免得正常用个应用还要看协议帧。
 */
@Composable
fun ProfilePage(padding: PaddingValues, bottomExtra: Dp = 0.dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshTick by remember { mutableIntStateOf(0) }

    var banner by remember { mutableStateOf<WaterEvent.Banner?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val session = Session.get()
    refreshTick

    Page(padding = padding, bottomExtra = bottomExtra) {
        BannerHost(banner = banner, onDismiss = { banner = null })

        Section(title = "账号信息") {
            BasicComponent(
                title = session.telephone.ifEmpty { "（未获取手机号）" },
                summary = "余额 ¥${Money.display(session.accountMoney)} · 账号编号 ${
                session.accountId.ifEmpty { "未获取" }
                }",
            )
            HorizontalDivider()
            BasicComponent(
                title = "学校项目编号：${session.projectId.ifEmpty { "未获取" }}",
                summary = "设备和账号都挂在项目下，缺少它就查不到任何设备。点此填写",
                onClick = { dialog = "project" },
            )
            HorizontalDivider()
            BasicComponent(
                title = "接入身份：${ApiConfig.platformLabel(session.platform)}",
                summary = "部分学校只允许支付宝端接入，点此切换",
                onClick = { dialog = "platform" },
            )
            HorizontalDivider()
            BasicComponent(
                title = if (busy) "正在校验…" else "校验登录状态",
                summary = "向服务端确认登录凭据是否仍然有效",
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { WaterApi.assertSessionValid() }
                            banner = WaterEvent.Banner(
                                BannerKind.INFO, "登录状态有效",
                                "凭据可用，当前余额 ¥${Money.display(Session.get().accountMoney)}"
                            )
                        } catch (e: Exception) {
                            if (e is ApiException && e.isSessionExpired) {
                                AuthState.markExpired()
                            } else {
                                banner = WaterEvent.Banner(
                                    BannerKind.INFO, "校验失败", e.message ?: "未知错误"
                                )
                            }
                        } finally {
                            busy = false
                        }
                    }
                },
            )
        }

        // ------------------------------------------------------ 外观
        Section(title = "外观") {
            BasicComponent(
                title = "跟随系统深色",
                summary = "打开后深浅色由系统决定，下面那个开关交还给系统",
                rightActions = {
                    AppSwitch(
                        checked = AppSettings.followSystemTheme,
                        onCheckedChange = {
                            AppSettings.followSystemTheme = it
                            refreshTick++
                        },
                    )
                },
            )
            HorizontalDivider()
            BasicComponent(
                title = "深色模式",
                summary = if (AppSettings.followSystemTheme) {
                    "当前跟随系统，先关掉上面的开关再手动指定"
                } else {
                    "关掉就是浅色"
                },
                enabled = !AppSettings.followSystemTheme,
                rightActions = {
                    AppSwitch(
                        checked = AppSettings.darkMode,
                        enabled = !AppSettings.followSystemTheme,
                        onCheckedChange = {
                            AppSettings.darkMode = it
                            refreshTick++
                        },
                    )
                },
            )
            HorizontalDivider()
            BasicComponent(
                title = "纯白浅色主题",
                summary = "把灰紫调的背景换成干净的白，强调色仍然跟着主题色；深色模式下不起作用",
                enabled = !AppSettings.darkMode || AppSettings.followSystemTheme,
                rightActions = {
                    AppSwitch(
                        checked = AppSettings.whiteBackground,
                        onCheckedChange = {
                            AppSettings.whiteBackground = it
                            refreshTick++
                        },
                    )
                },
            )
            HorizontalDivider()
            BasicComponent(
                title = "液态玻璃界面",
                summary = "打开切到 Apple 那套：底部导航浮成玻璃胶囊、顶栏透明模糊、弹窗与开关换样式、" +
                    "分组留白拉开。关掉就整个回到 Miuix（HyperOS）自己的控件与排布。" +
                    "模糊需安卓 12+，边缘折射需安卓 13+，更低版本退回半透明。",
                rightActions = {
                    AppSwitch(
                        checked = AppSettings.glassEffect,
                        onCheckedChange = {
                            AppSettings.glassEffect = it
                            refreshTick++
                        },
                    )
                },
            )
            HorizontalDivider()
            CardBody {
                Text(text = "主题色", style = MiuixTheme.textStyles.body1)
                ColorSwatchRow(
                    selectedArgb = AppSettings.seedColor,
                    onPick = {
                        AppSettings.seedColor = it
                        refreshTick++
                    },
                )
                LinkButton(
                    text = "自定义颜色（色板）",
                    onClick = { dialog = "color" },
                )
            }
        }

        // ------------------------------------------------------ 帮助
        Section(title = "帮助与信息") {
            BasicComponent(
                title = "使用说明",
                summary = "登录、项目编号、开始用水、充值、扫不到设备的排查步骤",
                onClick = { dialog = "guide" },
            )
            HorizontalDivider()
            BasicComponent(
                title = "运行日志",
                summary = "平时只记录不打扰；这里可以看全文、复制或清空",
                onClick = { dialog = "log" },
            )
            HorizontalDivider()
            BasicComponent(
                title = "关于",
                summary = "版本、服务端与本应用的来历",
                onClick = { dialog = "about" },
            )
            HorizontalDivider()
            BasicComponent(
                title = "许可证与鸣谢",
                summary = "界面组件、支付 SDK 的开源许可，以及本应用的来历说明",
                onClick = { dialog = "license" },
            )
        }

        DangerButton(
            text = "退出登录",
            onClick = { dialog = "logout" },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    when (dialog) {
        "project" -> ProjectIdDialog(onDismiss = { dialog = null }, onSaved = { dialog = null; refreshTick++ })
        "platform" -> PlatformDialog(onDismiss = { dialog = null }, onPicked = {
            session.platform = it
            session.save()
            dialog = null
            refreshTick++
        })
        "guide" -> GuideDialog(onDismiss = { dialog = null })
        "log" -> LogDialog(context, onDismiss = { dialog = null })
        "about" -> AboutDialog(context, onDismiss = { dialog = null })
        "license" -> LicenseDialog(onDismiss = { dialog = null })
        "color" -> ColorPickerDialog(onDismiss = { dialog = null })
        "logout" -> AppDialog(
            title = "退出登录",
            summary = "将清除本机保存的登录凭据，需要重新用短信验证码登录。",
            onDismiss = { dialog = null },
        ) {
            PrimaryButton(
                text = "退出",
                onClick = {
                    dialog = null
                    AuthState.markExpired()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            LinkButton(
                text = "取消",
                onClick = { dialog = null },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ProjectIdDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
    var value by remember { mutableStateOf(Session.get().projectId) }
    AppDialog(
        title = "学校项目编号",
        summary = "可以在「趣智校园」小程序里查看学校对应的项目编号。",
        onDismiss = onDismiss,
    ) {
        TextField(
            value = value,
            onValueChange = { raw -> value = raw.filter { c -> c.isDigit() }.take(10) },
            label = "项目编号",
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        PrimaryButton(
            text = "保存",
            onClick = {
                Session.get().projectId = value.trim()
                Session.get().save()
                onSaved()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PlatformDialog(onPicked: (String) -> Unit, onDismiss: () -> Unit) {
    val current = Session.get().platform
    AppDialog(
        title = "接入身份",
        summary = "服务端靠这个字段判断当前项目允许哪个端接入；" +
            "被提示「当前项目不支持该应用」时换一个再登录。",
        onDismiss = onDismiss,
    ) {
        Footnote(text = "当前生效：${ApiConfig.platformLabel(current)}")
        ApiConfig.CLIENT_PLATFORMS.forEach { item ->
            BasicComponent(
                title = ApiConfig.platformLabel(item),
                summary = "参数值 $item · 接入端编号 ${ApiConfig.clientTypeFor(item)}",
                rightActions = {
                    if (item == current) {
                        Icon(
                            imageVector = MiuixIcons.Useful.Confirm,
                            contentDescription = "当前使用",
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                onClick = { onPicked(item) },
            )
            HorizontalDivider()
        }
    }
}

/** 自定义主题色：整片 HSV 取色面 + 色相条，十六进制输入只是辅助。 */
@Composable
private fun ColorPickerDialog(onDismiss: () -> Unit) {
    val seed = if (AppSettings.seedColor == 0L) 0xFF2F6FE0L else AppSettings.seedColor
    val components = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(seed.toInt(), it) } }
    var hue by remember { mutableFloatStateOf(components[0]) }
    var saturation by remember { mutableFloatStateOf(components[1]) }
    var brightness by remember { mutableFloatStateOf(components[2]) }
    var input by remember { mutableStateOf(hexOf(hue, saturation, brightness)) }
    val parsed = ThemePalette.parseHex(input)

    // 色板动了就回填输入框；输入框解析成功也会反过来挪色板（见 onValueChange）
    LaunchedEffect(hue, saturation, brightness) { input = hexOf(hue, saturation, brightness) }

    AppDialog(
        title = "自定义主题色",
        summary = "在色板上直接挑，整套配色由它推导。也可以在下面填十六进制色值。",
        onDismiss = onDismiss,
    ) {
        ColorPreviewBar(hex = input, color = hsvColor(hue, saturation, brightness))
        SaturationValueSquare(
            hue = hue,
            saturation = saturation,
            brightness = brightness,
            onPicked = { s, v -> saturation = s; brightness = v },
        )
        HueSlider(hue = hue, onHue = { hue = it })
        TextField(
            value = input,
            onValueChange = { raw ->
                input = raw.filter { c -> !c.isWhitespace() }.take(9)
                ThemePalette.parseHex(input)?.let { argb ->
                    android.graphics.Color.colorToHSV(argb.toInt(), components)
                    hue = components[0]
                    saturation = components[1]
                    brightness = components[2]
                }
            },
            label = "色值",
            singleLine = true,
        )
        if (input.isNotBlank() && parsed == null) {
            Footnote(text = "格式不对：需要 6 位或 8 位十六进制，如 #2F6FE0。")
        }
        PrimaryButton(
            text = "应用",
            onClick = {
                AppSettings.seedColor = 0xFF000000L or
                    (android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness)).toLong() and 0xFFFFFFL)
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        LinkButton(
            text = "改回跟随壁纸",
            onClick = {
                AppSettings.seedColor = 0L
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 许可证与鸣谢。 */
@Composable
private fun LicenseDialog(onDismiss: () -> Unit) {
    AppDialog(title = "许可证与鸣谢", onDismiss = onDismiss, scrollable = true) {
        LicenseBlock(
            "本应用 CampusWater",
            "Copyright (C) 2026 AnswerDev · GNU 通用公共许可证 GPLv3 或更高版本。" +
                "你可以自由运行、研究、修改和再分发本程序；如果你发布修改版，" +
                "必须以同样的许可证公开你的源代码。完整条款见仓库里的 LICENSE 文件。"
        )
        LicenseBlock(
            "液态玻璃效果 Backdrop",
            "Apache License 2.0 · Kyant0/AndroidLiquidGlass。" +
                "底栏的模糊、色彩增强与边缘折射来自它；形状库 shapes 同为该作者。"
        )
        LicenseBlock(
            "界面组件 Miuix",
            "Apache License 2.0 · compose-miuix-ui。本应用所有可见控件（导航栏、卡片、开关、滑条、弹窗）都来自它。"
        )
        LicenseBlock(
            "Jetpack / JetBrains Compose、Material Color Utilities",
            "Apache License 2.0 · 界面运行时与配色推导。"
        )
        LicenseBlock(
            "支付宝应用支付 SDK",
            "由支付宝官方发布，许可与隐私条款见支付宝开放平台。本应用只用它拉起收银台，不经手支付数据。"
        )
        LicenseBlock(
            "服务端与设备协议",
            "归属趣智校园（运营方）。本应用按公开可复现的方式复用小程序的接口与签名规则，" +
                "继续使用即表示你已了解这可能与其服务条款冲突。"
        )
        LicenseBlock(
            "鸣谢",
            "机制与接口口径参考了开源第三方客户端「淋浴 (LinYu, MIT)」及其贡献者；" +
                "逆向依据为小程序的可读产物。计费、开阀、结算均由服务端决定，本应用不做任何绕过。"
        )
        PrimaryButton(
            text = "知道了",
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LicenseBlock(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        // 之前标题用 title2（20sp），一整屏许可证看下来像在念一堆大标题；
        // 收成 15sp 半粗，正文 12sp / 17sp 行高，密度才像一份条款。
        Text(
            text = title,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = body,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** 日志查看：等宽、可滚动、能一键复制。 */
@Composable
private fun LogDialog(context: Context, onDismiss: () -> Unit) {
    val version by RunLog.version.collectAsState()
    val lines = remember(version) { RunLog.snapshot() }
    AppDialog(
        title = "运行日志",
        summary = "共 ${lines.size} 条，只保留最近 500 条",
        onDismiss = onDismiss,
        scrollable = true,
    ) {
        if (lines.isEmpty()) {
            Footnote(text = "还没有记录。用水、扫描、充值出问题时会自动写进来。")
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            lines.forEach {
                Text(
                    text = it,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    lineHeight = 15.sp,
                )
            }
        }
        PrimaryButton(
            text = "复制全部",
            onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("运行日志", RunLog.text()))
                onDismiss()
            },
            enabled = lines.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LinkButton(
                text = "清空",
                onClick = { RunLog.clear() },
                modifier = Modifier.weight(1f),
            )
            LinkButton(
                text = "关闭",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AboutDialog(context: Context, onDismiss: () -> Unit) {
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "未知"
        }
    }
    AppDialog(
        title = "关于",
        summary = "校园用水 $versionName · 第三方逆向客户端",
        onDismiss = onDismiss,
        scrollable = true,
    ) {
        DetailLine("服务端", ApiConfig.BASE_V3)
        DetailLine("小程序版本口径", ApiConfig.VERSION)
        DetailLine("系统", "安卓 ${Build.VERSION.RELEASE} · 型号 ${Build.MODEL}")
        Footnote(
            text = "本应用不是官方应用，它按公开可复现的方式复用「趣智校园」小程序的接口与签名规则，" +
                "用来控制同一套校园水控设备。计费、开阀、结算全部由服务端决定，" +
                "本应用只负责与设备通信并原样上报消费记录。"
        )
        Footnote(
            text = "界面组件来自开源库 Miuix（Apache-2.0）；登录凭据与账号信息只保存在本机，" +
                "不上传任何与用水无关的个人数据。如与官方小程序表现不一致，以官方服务端为准。"
        )
        PrimaryButton(
            text = "知道了",
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun GuideDialog(onDismiss: () -> Unit) {
    AppDialog(title = "使用说明", onDismiss = onDismiss, scrollable = true) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            UsageGuideText.split("\n\n").forEach { block ->
                if (block.isNotBlank()) {
                    Text(
                        text = block.trim(),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onBackground,
                    )
                }
            }
        }
        PrimaryButton(
            text = "知道了",
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Footnote(text = label)
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onBackground,
        )
    }
}

/**
 * 使用说明。
 *
 * <p>按空行分段，弹窗里一段一块显示，比一整坨文字好读。
 */
private const val UsageGuideText = """
【开始用水】
1. 打开手机蓝牙（安卓 12 以下的机型还要打开「定位」开关）
2. 到「用水」页点「开始扫描」，走近设备（建议 2 米内）
3. 列表出现设备后点它进入控制页，等状态显示「设备空闲」
4. 点「开始用水」出水；用完点「结束用水」自动结算

【闲置自动关停与超时设置】
服务端会在下单时下发该设备的闲置时限，停止出水超过这个时长设备会自动关阀。
用水页的「超时设置」可以改成自己指定的时长（1–60 分钟），并选择到点是只提醒
还是自动下发关阀指令。要留意：这里改的只是本应用的计时与提醒基准，
设备端真正的关阀时间由服务端下发给它的费率决定，客户端改不动。

【后台用水监控与通知】
用水期间监控跑在前台服务里，退出应用、划掉最近任务都不影响；
通知栏常驻状态条带「结束用水」按钮。开阀失败 / 设备被占用 / 超时关停默认弹横幅，
可以在「用水」页把「横幅提醒」单独关掉（应用内的提示不受这个开关影响）。

【账单】
「账单」页按月查看消费记录，可按设备类型筛选，含本月合计、近 7 天与笔均；
点一条可以看订单号。

【常见提示对照】
· 余额不足，请先充值 → 去充值页或官方小程序
· 当前项目不支持该应用 → 在「个人」页把接入身份切到支付宝端
· 请先绑定学校 → 先在官方小程序里绑定学校
· 该项目账户已禁用 → 联系学校后勤或运营方
· 登录已失效 → 重新用短信验证码登录
· 设备正在被他人使用 → 等对方结束，或换一台设备
· 设备正在刷卡消费 → 等刷卡结束再操作
· 该设备未绑定项目 → 核对蓝牙地址，或在「个人」页确认项目编号
· 设备响应超时 / 蓝牙连接已断开 → 走近设备后点「重新连接」

【扫不到设备怎么办】
1. 确认蓝牙已打开；安卓 12 以下确认「定位」开关是打开的
2. 确认已授予本应用「附近的设备」与通知权限
3. 走近设备再扫描；信号太弱（低于 -90）时很不稳定
4. 暂时关掉微信 / 支付宝，避免它们占用设备连接

【关于计费】
费用与设备控制全部由服务端负责，本应用只与设备通信并原样上报消费记录。
「开始用水」时服务端就会预扣，结束后按实际用量结算；对账单有疑问请走官方申诉渠道。

【隐私与声明】
登录凭据与账号信息只保存在本机，不上传任何与用水无关的个人数据。
本应用为第三方逆向客户端，非官方应用；如与官方小程序表现不一致，以官方服务端为准。
"""
