package dev.answer.campuswater.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.answer.campuswater.api.AliPayLauncher
import dev.answer.campuswater.api.ApiConfig
import dev.answer.campuswater.api.Money
import dev.answer.campuswater.api.RechargeOption
import dev.answer.campuswater.api.Session
import dev.answer.campuswater.api.WaterApi
import dev.answer.campuswater.data.AppSettings
import dev.answer.campuswater.data.RunLog
import dev.answer.campuswater.data.BannerKind
import dev.answer.campuswater.data.WaterEvent
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 充值页。
 *
 * <p>链路：查可用支付方式 → 查充值档位 → 服务端下单拿支付宝订单串 → 支付宝客户端付款
 * → 服务端回调入账。本应用不经手支付结果，付完回来刷新余额即可。
 */
@Composable
fun RechargePage(padding: PaddingValues, bottomExtra: Dp = 0.dp) {
    val context = LocalContext.current
    val session = Session.get()
    val scope = rememberCoroutineScope()

    var balance by remember { mutableStateOf(session.accountMoney) }
    var options by remember { mutableStateOf<List<RechargeOption>>(emptyList()) }
    var selected by remember { mutableStateOf<RechargeOption?>(null) }
    var alipayAvailable by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf<WaterEvent.Banner?>(null) }
    // 只记录，不在页面上摆日志区；查看入口在「个人 → 运行日志」
    fun log(message: String) = RunLog.add("充值", message)

    fun refreshBalance() {
        scope.launch {
            try {
                balance = withContext(Dispatchers.IO) { WaterApi.queryWallet() }
                log("余额已刷新：¥$balance")
            } catch (e: Exception) {
                log("刷新余额失败：${e.message}")
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshBalance()
        try {
            val loaded = withContext(Dispatchers.IO) { WaterApi.queryRechargeOptions() }
            options = loaded
            selected = loaded.firstOrNull()
            alipayAvailable = try {
                withContext(Dispatchers.IO) {
                    WaterApi.queryPayWays().contains(ApiConfig.RECHARGE_WAY_ALIPAY)
                }
            } catch (e: Exception) {
                true
            }
            if (loaded.isEmpty()) log("该项目暂未配置充值档位")
        } catch (e: Exception) {
            log("获取充值档位失败：${e.message}")
        }
    }

    Page(padding = padding, bottomExtra = bottomExtra) {
        BannerHost(banner = banner, onDismiss = { banner = null })

        // ------------------------------------------------------ 余额
        Section(title = "账户余额") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Stacked(modifier = Modifier.weight(1f), spacing = 2.dp) {
                    MoneyText(text = "¥" + Money.display(balance))
                    Footnote(
                        text = "手机号 ${session.telephone.ifEmpty { "未获取" }} · " +
                            "项目编号 ${session.projectId.ifEmpty { "未绑定" }}"
                    )
                }
                LinkButton(text = "刷新", onClick = { refreshBalance() })
            }
        }

        // ------------------------------------------------------ 金额
        Section(
            title = "选择充值金额",
            summary = if (options.isEmpty()) "服务端没有下发充值档位，本项目暂不支持在应用内充值。" else null,
        ) {
            CardBody {
                options.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { opt ->
                            val active = selected === opt
                            if (active) {
                                PrimaryButton(
                                    text = opt.amountLabel(),
                                    onClick = { selected = opt },
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                SecondaryButton(
                                    text = opt.amountLabel(),
                                    onClick = { selected = opt },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        // 不足三个时补位，保证每列宽度一致
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                selected?.let {
                    Footnote(
                        text = if (it.hasGift()) {
                            String.format(
                                Locale.US, "充 %.0f 送 %.0f，实得 ¥%.2f",
                                it.amount, it.givenMoney, it.credited()
                            )
                        } else {
                            String.format(Locale.US, "到账 ¥%.2f", it.credited())
                        }
                    )
                }
            }
        }

        // ------------------------------------------------------ 下单
        PrimaryButton(
            text = if (busy) "正在发起支付…" else "支付宝充值",
            enabled = !busy,
            onClick = {
                val amount = selected?.amount
                when {
                    amount == null -> banner = WaterEvent.Banner(
                        BannerKind.INFO, "还没有选择金额", "先点一个充值档位再下单"
                    )

                    !alipayAvailable -> banner = WaterEvent.Banner(
                        BannerKind.INFO, "该项目未开通支付宝充值", "请改用官方小程序充值"
                    )

                    !AliPayLauncher.isInstalled(context) -> banner = WaterEvent.Banner(
                        BannerKind.INFO, "未安装支付宝", "请先安装支付宝客户端，或改用官方小程序充值"
                    )

                    else -> {
                        busy = true
                        scope.launch {
                            try {
                                log("正在发起支付… ¥%.2f".format(amount))
                                val order = withContext(Dispatchers.IO) {
                                    WaterApi.createAlipayOrder(amount, ApiConfig.RECHARGE_WAY_ALIPAY)
                                }
                                log("已生成支付宝订单：$order")
                                if (order.isTradeNo) {
                                    banner = WaterEvent.Banner(
                                        BannerKind.INFO, "服务端返回的是交易号",
                                        "本应用只支持订单串形态，请改用官方小程序充值"
                                    )
                                } else {
                                    pay(context, order.orderStr, ::log, ::refreshBalance) { banner = it }
                                }
                            } catch (e: Exception) {
                                log("发起充值失败：${e.message}")
                                WaterApi.getLastRechargeRaw()?.let { log("服务端原始返回：$it") }
                                banner = WaterEvent.Banner(
                                    BannerKind.OPEN_FAILED, "充值未成功",
                                    (e.message ?: "服务端拒绝了本次下单") +
                                        "\n可改用官方小程序充值"
                                )
                            } finally {
                                busy = false
                            }
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (busy) {
            LinearProgressIndicator(progress = null, modifier = Modifier.fillMaxWidth())
        }
        Section(title = "关于充值") {
            CardBody {
                Footnote(
                    text = "本应用通过趣智校园服务端下单，再由支付宝客户端完成付款；" +
                        "支付结果由服务端回调入账，所以付完回到本页刷新余额即可。" +
                        "充值档位由服务端下发，具体取决于学校项目的配置。"
                )
            }
        }

    }
}

/**
 * 拉起支付宝并处理返回。
 *
 * <p>支付宝的支付接口会同步阻塞到用户操作完，AliPayLauncher 内部已经起了子线程，
 * 这里把回调转成挂起函数，免得"支付完 → 刷新余额"写成一堆嵌套回调。
 */
private suspend fun pay(
    context: Context,
    orderStr: String,
    log: (String) -> Unit,
    refreshBalance: () -> Unit,
    postBanner: (WaterEvent.Banner) -> Unit,
) {
    val activity = context as? ComponentActivity ?: run {
        log("无法拉起支付宝：当前界面上下文不可用")
        return
    }
    val result = suspendCancellableCoroutine { cont ->
        AliPayLauncher.pay(activity, orderStr) { r -> cont.resume(r) }
    }
    log("支付宝返回码 ${result.resultCode}：${result.describe()}")
    if (result.memo.isNotEmpty()) log("支付宝原始信息：${result.memo}")
    when {
        result.isSuccess -> {
            postBanner(WaterEvent.Banner(BannerKind.INFO, "支付完成", "正在刷新余额…"))
            delay(2000)
            refreshBalance()
        }

        result.isCancelled -> log("已取消支付，未产生扣款")

        result.isPending -> {
            // 待确认：服务端还没回入账通知，只能靠余额兜底
            log("支付结果待确认，5 秒后自动刷新余额")
            delay(5000)
            refreshBalance()
        }

        else -> log("支付失败；若已扣款请稍后刷新余额确认")
    }
}

