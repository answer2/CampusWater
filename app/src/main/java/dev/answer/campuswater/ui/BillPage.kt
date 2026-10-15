package dev.answer.campuswater.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.answer.campuswater.api.BillRecord
import dev.answer.campuswater.data.AuthState
import dev.answer.campuswater.data.BillRepo
import java.util.Locale
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 账单页：按月查看消费记录，可按设备类型筛选。
 *
 * <p>服务端只按月返回整月账单，所以合计、近 7 天、笔均都是本地算的；
 * 筛选也在本地做 —— 数据一次就拉全了，没必要为筛选再请求。
 */
@Composable
fun BillPage(padding: PaddingValues, bottomExtra: Dp = 0.dp) {
    val state by BillRepo.state.collectAsState()
    var month by remember { mutableStateOf(BillRepo.currentMonth()) }
    var showMonthPicker by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf(SORT_TIME_DESC) }
    var minInput by remember { mutableStateOf("") }
    var maxInput by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<BillRecord?>(null) }

    LaunchedEffect(month) {
        BillRepo.load(month)
    }

    val types = state.bills.map { it.typeLabel() }.distinct()
    val minMoney = minInput.toDoubleOrNull()
    val maxMoney = maxInput.toDoubleOrNull()
    // 类型 → 金额区间 → 排序，三步都在这里做：服务端一次给全月数据，没必要为此再请求
    val shown = state.bills
        .let { list -> if (filter == null) list else list.filter { it.typeLabel() == filter } }
        .filter { bill ->
            (minMoney == null || bill.money >= minMoney) &&
                (maxMoney == null || bill.money <= maxMoney)
        }
        .sortedWith(
            when (sort) {
                SORT_TIME_ASC -> compareBy { timeOf(it) }
                SORT_MONEY_DESC -> compareByDescending<BillRecord> { it.money }.thenBy { timeOf(it) }
                SORT_MONEY_ASC -> compareBy<BillRecord> { it.money }.thenByDescending { timeOf(it) }
                else -> compareByDescending { timeOf(it) }
            }
        )
    val total = shown.sumOf { it.money }
    val last7 = shown.filter {
        dev.answer.campuswater.api.WaterApi.parseBillTime(it.consumeDate) >=
            System.currentTimeMillis() - 7L * 24 * 3600 * 1000
    }.sumOf { it.money }

    Page(padding = padding, bottomExtra = bottomExtra) {
        BannerHost(
            banner = state.error?.let {
                dev.answer.campuswater.data.WaterEvent.Banner(
                    if (state.sessionExpired) {
                        dev.answer.campuswater.data.BannerKind.OPEN_FAILED
                    } else {
                        dev.answer.campuswater.data.BannerKind.INFO
                    },
                    "账单查询失败", it
                )
            },
            onDismiss = { BillRepo.clearError() },
        )

        // ------------------------------------------------------ 月份
        Section {
            BasicComponent(
                title = BillRepo.monthLabel(month),
                summary = "点此选择月份",
                onClick = { showMonthPicker = true },
            )
        }

        // ------------------------------------------------------ 概览
        Section(title = "本月概览") {
            CardBody {
                if (state.loading) {
                    LinearProgressIndicator(progress = null, modifier = Modifier.fillMaxWidth())
                }
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MoneyText(text = "¥" + String.format(Locale.US, "%.2f", total))
                    Footnote(text = "共 ${shown.size} 笔")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Stat(text = "近 7 天", value = "¥%.2f".format(last7))
                    Stat(
                        text = "笔均",
                        value = "¥%.2f".format(if (shown.isEmpty()) 0.0 else total / shown.size)
                    )
                }
            }
        }

        // ------------------------------------------------------ 筛选与排序
        Section(title = "筛选与排序") {
            CardBody {
                if (types.size > 1) {
                    Text(text = "设备类型", style = MiuixTheme.textStyles.body2)
                    ScrollRow {
                        FilterChip(label = "全部", selected = filter == null) { filter = null }
                        types.forEach { type ->
                            FilterChip(
                                label = type,
                                selected = filter == type,
                                tint = typeColor(type),
                            ) { filter = type }
                        }
                    }
                }
                Text(text = "排序", style = MiuixTheme.textStyles.body2)
                ScrollRow {
                    SORTS.forEach { item ->
                        FilterChip(label = item.first, selected = sort == item.second) { sort = item.second }
                    }
                }
                Text(text = "金额区间（元，留空表示不限）", style = MiuixTheme.textStyles.body2)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextField(
                        value = minInput,
                        onValueChange = { raw -> minInput = raw.digitsOnly() },
                        label = "最低",
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    TextField(
                        value = maxInput,
                        onValueChange = { raw -> maxInput = raw.digitsOnly() },
                        label = "最高",
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                if (minInput.isNotEmpty() || maxInput.isNotEmpty()) {
                    LinkButton(
                        text = "清除金额区间",
                        onClick = {
                            minInput = ""
                            maxInput = ""
                        },
                    )
                }
                if (minMoney != null && maxMoney != null && minMoney > maxMoney) {
                    Footnote(text = "最低大于最高，会一条都筛不出来。")
                }
            }
        }

        // ------------------------------------------------------ 明细
        Section(
            title = "消费明细",
            summary = "点一条可以看订单号；对账单有疑问请走官方申诉渠道。",
        ) {
            if (shown.isEmpty() && !state.loading) {
                CardBody {
                    Footnote(
                        text = "没有符合当前筛选条件的记录。" +
                            if (filter != null || minMoney != null || maxMoney != null) "（试着放宽筛选）" else "",
                    )
                }
            }
            shown.forEachIndexed { index, bill ->
                BillRow(bill) { detail = bill }
                if (index != shown.lastIndex) {
                    HorizontalDivider()
                }
            }
        }
    }

    if (showMonthPicker) {
        MonthPickerDialog(
            month = month,
            onPick = {
                month = it
                filter = null
                showMonthPicker = false
            },
            onDismiss = { showMonthPicker = false },
        )
    }

    detail?.let { bill ->
        AppDialog(
            title = bill.deviceName(),
            summary = bill.typeLabel() + " · " + bill.consumeDate,
            onDismiss = { detail = null },
        ) {
            DetailRow("消费金额", "¥" + String.format(Locale.US, "%.2f", bill.money))
            DetailRow("消费时间", bill.consumeDate.ifEmpty { "（服务端未给）" })
            DetailRow("设备名称", bill.deviceName())
            DetailRow("订单号", bill.orderNo.ifEmpty { bill.orderId.ifEmpty { "（无）" } })
            if (state.sessionExpired) {
                PrimaryButton(
                    text = "去重新登录",
                    onClick = { AuthState.markExpired() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            LinkButton(
                text = "关闭",
                onClick = { detail = null },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 月份选择：先选年，再点月。服务端只按月出数据，所以不做"日"级选择。 */
@Composable
private fun MonthPickerDialog(
    month: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var year by remember { mutableStateOf(month.substringBefore("-").toIntOrNull() ?: 0) }
    AppDialog(title = "选择月份", summary = "$year 年", onDismiss = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LinkButton(text = "上一年", onClick = { year -= 1 })
            Text(text = "$year", style = MiuixTheme.textStyles.title2)
            LinkButton(
                text = "下一年",
                enabled = year < BillRepo.currentYear(),
                onClick = { if (year < BillRepo.currentYear()) year += 1 },
            )
        }
        (1..12).chunked(4).forEach { rowMonths ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowMonths.forEach { m ->
                    val value = String.format(Locale.US, "%04d-%02d", year, m)
                    FilterChip(
                        label = "${m} 月",
                        selected = value == month,
                        modifier = Modifier.weight(1f),
                        onClick = { onPick(value) },
                    )
                }
                repeat(4 - rowMonths.size) { Spacer(Modifier.width(8.dp)) }
            }
        }
    }
}

/** 账单时间排序键；解析不出来时排到最后而不是抛异常。 */
private fun timeOf(bill: BillRecord): Long =
    dev.answer.campuswater.api.WaterApi.parseBillTime(bill.consumeDate).let { if (it < 0) 0L else it }

private const val SORT_TIME_DESC = "timeDesc"
private const val SORT_TIME_ASC = "timeAsc"
private const val SORT_MONEY_DESC = "moneyDesc"
private const val SORT_MONEY_ASC = "moneyAsc"

private val SORTS = listOf(
    "时间最新" to SORT_TIME_DESC,
    "时间最早" to SORT_TIME_ASC,
    "金额最高" to SORT_MONEY_DESC,
    "金额最低" to SORT_MONEY_ASC,
)

/** 只留数字和一个小数点。 */
private fun String.digitsOnly(): String {
    val sb = StringBuilder()
    var dotSeen = false
    for (c in this) {
        when {
            c.isDigit() -> sb.append(c)
            c == '.' && !dotSeen -> {
                dotSeen = true
                sb.append(c)
            }
        }
    }
    return sb.toString().take(7)
}

@Composable
private fun Stat(text: String, value: String) {
    Column {
        Footnote(text = text)
        Text(text = value, style = MiuixTheme.textStyles.body1)
    }
}

@Composable
private fun BillRow(bill: BillRecord, onClick: () -> Unit) {
    BasicComponent(
        title = bill.deviceName(),
        summary = bill.shortTime(),
        leftAction = {
            TypeTag(label = bill.typeLabel(), color = typeColor(bill.typeLabel()))
        },
        rightActions = {
            Text(
                text = "-¥" + String.format(Locale.US, "%.2f", bill.money),
                style = MiuixTheme.textStyles.body1,
                color = if (bill.money > 0) MiuixTheme.colorScheme.error
                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        },
        onClick = onClick,
    )
}

/** 类型配色：不同设备一个颜色，标签与筛选胶囊共用。 */
@Composable
private fun typeColor(label: String): androidx.compose.ui.graphics.Color = when (label) {
    "饮水机" -> androidx.compose.ui.graphics.Color(0xFF2E9E6B)
    "洗手台" -> androidx.compose.ui.graphics.Color(0xFFE0952F)
    "吹风机" -> androidx.compose.ui.graphics.Color(0xFF8A6FE8)
    "洗衣机" -> androidx.compose.ui.graphics.Color(0xFF2F8FD6)
    "充电" -> androidx.compose.ui.graphics.Color(0xFFC2793B)
    else -> MiuixTheme.colorScheme.primary
}

/** 设备类型小标签：不同设备不同颜色，扫一眼就能分清是洗澡还是接水。 */
@Composable
private fun TypeTag(label: String, color: androidx.compose.ui.graphics.Color) {
    top.yukonga.miuix.kmp.basic.Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(9.dp),
        color = color.copy(alpha = 0.16f),
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
    }
}
