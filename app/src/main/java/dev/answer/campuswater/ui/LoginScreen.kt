package dev.answer.campuswater.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.answer.campuswater.api.ApiConfig
import dev.answer.campuswater.api.ApiException
import dev.answer.campuswater.api.Session
import dev.answer.campuswater.api.WaterApi
import dev.answer.campuswater.data.AppSettings
import dev.answer.campuswater.data.BannerKind
import dev.answer.campuswater.data.WaterEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 验证码重发冷却（秒），与小程序短信登录页一致。 */
private const val CODE_COOLDOWN_SEC = 60L

/**
 * 登录页。
 *
 * <p>默认短信验证码登录：未注册的手机号服务端会自动建号。这条路径不需要微信 openid，
 * 所以第三方客户端能直接复现；密码登录作为备选（口令按服务端要求做哈希）。
 */
@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    val session = Session.get()
    var telephone by remember { mutableStateOf(session.telephone) }
    var smsCode by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordMode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var codeReadyAt by remember { mutableLongStateOf(0L) }
    var remaining by remember { mutableLongStateOf(0L) }
    var showPlatformPicker by remember { mutableStateOf(false) }
    var agreed by remember { mutableStateOf(AppSettings.termsAccepted) }
    var showTerms by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 冷却读秒以"到点时刻"为基准，不用自减计数：切后台再回来也不会跑偏
    LaunchedEffect(codeReadyAt) {
        while (true) {
            val left = codeReadyAt - System.currentTimeMillis()
            remaining = if (left > 0) left else 0L
            if (left <= 0) break
            delay(500L)
        }
    }

    fun submit(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                // 网络调用必须离开主线程，否则直接 NetworkOnMainThreadException
                withContext(Dispatchers.IO) { action() }
                onLoggedIn()
            } catch (e: Exception) {
                error = describeLoginError(e)
            } finally {
                busy = false
            }
        }
    }

    fun sendCode() {
        if (telephone.length != 11) {
            error = "请输入 11 位手机号"
            return
        }
        error = null
        scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { WaterApi.sendVerifyCode(telephone) }
                codeReadyAt = System.currentTimeMillis() + CODE_COOLDOWN_SEC * 1000L
                notice = "验证码已发送，请查看短信（60 秒后可重发）"
            } catch (e: Exception) {
                error = describeLoginError(e)
            } finally {
                busy = false
            }
        }
    }

    Scaffold(topBar = {
        // 登录页没有玻璃层，顶栏保持 Miuix 自己的样子
        TopAppBar("登录", largeTitle = "校园用水")
    }) { padding ->
        Page(padding = padding) {
            BannerHost(
                banner = error?.let { WaterEvent.Banner(BannerKind.OPEN_FAILED, "登录失败", it) },
                onDismiss = { error = null },
            )

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Footnote(text = "用手机号登录趣智校园账号；未注册的号码，服务端会自动建号。")
                notice?.let {
                    Text(
                        text = it,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }

                Section(title = if (passwordMode) "账号密码登录" else "短信验证码登录") {
                    CardBody {
                        TextField(
                            value = telephone,
                            onValueChange = { raw ->
                                telephone = raw.filter { c -> c.isDigit() }.take(11)
                            },
                            label = "手机号",
                            singleLine = true,
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        )
                        if (passwordMode) {
                            TextField(
                                value = password,
                                onValueChange = { password = it },
                                label = "密码",
                                singleLine = true,
                                enabled = !busy,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextField(
                                    value = smsCode,
                                    onValueChange = { raw ->
                                        smsCode = raw.filter { c -> c.isDigit() }.take(6)
                                    },
                                    label = "验证码",
                                    singleLine = true,
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                )
                                LinkButton(
                                    text = if (remaining > 0) "${(remaining + 999) / 1000} 秒后可重发"
                                    else "获取验证码",
                                    onClick = { sendCode() },
                                    enabled = !busy && remaining == 0L,
                                    primary = true,
                                )
                            }
                        }
                    }
                }

                PrimaryButton(
                    text = if (busy) "正在登录…" else "登录",
                    enabled = !busy,
                    onClick = {
                        when {
                            !agreed -> {
                                error = "请先阅读并同意《用户协议与声明》"
                                showTerms = true
                            }
                            telephone.length != 11 -> error = "请输入 11 位手机号"
                            passwordMode && password.isEmpty() -> error = "请输入密码"
                            !passwordMode && smsCode.isEmpty() -> error = "请输入验证码"
                            else -> submit {
                                if (passwordMode) {
                                    WaterApi.passwordLogin(telephone, password)
                                } else {
                                    WaterApi.registerAndLogin(telephone, smsCode)
                                }
                                WaterApi.refreshAccount()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LinkButton(
                        text = if (passwordMode) "改用短信登录" else "改用密码登录",
                        onClick = { passwordMode = !passwordMode; error = null },
                    )
                    LinkButton(text = "接入身份", onClick = { showPlatformPicker = true })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AppSwitch(
                        checked = agreed,
                        onCheckedChange = {
                            agreed = it
                            AppSettings.termsAccepted = it
                        },
                    )
                    Text(
                        text = "我已阅读并同意《用户协议与声明》",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(indication = null, interactionSource = null) { showTerms = true },
                    )
                }

                if (busy) {
                    LinearProgressIndicator(progress = null, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    if (showPlatformPicker) {
        PlatformPicker(
            onPicked = {
                session.platform = it
                session.save()
                showPlatformPicker = false
            },
            onDismiss = { showPlatformPicker = false },
        )
    }

    if (showTerms) {
        TermsDialog(
            onAgree = {
                agreed = true
                AppSettings.termsAccepted = true
                showTerms = false
            },
            onDismiss = { showTerms = false },
        )
    }
}

/**
 * 用户协议与声明。
 *
 * <p>第三方客户端本来就该把"我复用了它的签名规则去访问同一套接口"这件事写在登录前，
 * 而不是塞进个人页里等人自己翻。
 */
@Composable
private fun TermsDialog(onAgree: () -> Unit, onDismiss: () -> Unit) {
    AppDialog(
        title = "用户协议与声明",
        onDismiss = onDismiss,
        scrollable = true,
    ) {
        TermsText.split("\n\n").filter { it.isNotBlank() }.forEach { block ->
            Text(
                text = block.trim(),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        PrimaryButton(
            text = "同意并继续",
            onClick = onAgree,
            modifier = Modifier.fillMaxWidth(),
        )
        LinkButton(
            text = "不同意",
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val TermsText = """
【本应用是什么】
第三方逆向客户端，不是官方应用。它按公开可复现的方式复用「趣智校园」小程序的接口与签名规则，
用来控制同一套校园水控设备。计费、开阀、结算全部由服务端决定，本应用不做任何绕过。

【使用风险】
复用小程序接口可能与学校或运营方的服务条款冲突，继续使用即表示你已了解并自行承担这一风险。
如与官方小程序表现不一致，以官方服务端为准；对账单有疑问请走官方申诉渠道。

【隐私】
登录凭据、账号信息、项目编号与设置只保存在本机，不上传任何与用水无关的个人数据。
本应用不含统计 SDK，也不会读取通讯录、定位、相册等无关权限。

【蓝牙与用水】
「开始用水」时服务端即预扣费用；蓝牙断开、应用被杀都可能让设备按自身闲置时限自动关阀。
用水期间请留意通知栏的常驻状态条与闲置倒计时，不要长时间无人看管。
"""

/** 接入身份选择：界面只给中文名，原始参数值作为副标题保留，便于对照抓包排查。 */
@Composable
private fun PlatformPicker(onPicked: (String) -> Unit, onDismiss: () -> Unit) {
    AppDialog(
        title = "接入身份",
        summary = "服务端靠这个字段判断当前项目允许哪个端接入。",
        onDismiss = onDismiss,
    ) {
        Stacked {
            ApiConfig.CLIENT_PLATFORMS.forEach { item ->
                BasicComponent(
                    title = ApiConfig.platformLabel(item),
                    summary = "参数值 $item · 接入端编号 ${ApiConfig.clientTypeFor(item)}",
                    onClick = { onPicked(item) },
                )
            }
        }
    }
}

private fun describeLoginError(e: Throwable): String = when {
    e is ApiException && e.isClientNotAllowed() ->
        "当前项目不支持「${ApiConfig.platformLabel(Session.get().platform)}」接入，请切换接入身份后重试"
    e is ApiException -> "${e.errorMessage}（错误码 ${e.errorCode}）"
    e.message.isNullOrBlank() -> e.javaClass.simpleName
    else -> e.message!!
}
