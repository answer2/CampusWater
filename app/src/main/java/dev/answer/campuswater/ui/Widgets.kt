package dev.answer.campuswater.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import dev.answer.campuswater.data.AppSettings
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 全站统一的间距与按钮。
 *
 * <p>为什么要专门收一层：Miuix 0.7.2 的 [Button] 只把背景色画成 `colors.color`，
 * **不会**给内容提供 contentColor（内部 Surface 的 contentColor 落到 onSurface），
 * 而 [Text] 的取色顺序是 `color → style.color → LocalContentColor`，
 * `style.color` 又带着主题前景色 —— 结果就是"蓝底按钮上是黑字"（真机截图实测）。
 * 所以按钮文字的颜色必须由调用方显式给，这里统一给好，别在每个页面各写一遍。
 */
object Space {
    /** 页面左右留白：SmallTitle 默认是 28dp，这里统一到 16dp 与卡片对齐。 */
    val PageHorizontal = 16.dp

    /** 卡片内部留白：Miuix 的 Card 默认 insideMargin 是 0，得自己补。 */
    val CardInside = PaddingValues(16.dp)

    /**
     * 卡片之间。
     *
     * <p>Apple 的分组列表在"上一组底部 → 下一组标题"之间留得很松（约 30pt），
     * 靠这段空白而不是分割线来分组。之前给 12dp，整页挤成一坨，读起来像设置项清单。
     */
    /** 液态玻璃（Apple）风格下的组间距。 */
    val SectionGap = 28.dp

    /** Miuix 风格下的组间距：HyperOS 的设置列表排得密。 */
    val SectionGapCompact = 12.dp
}

/**
 * 顶栏高度。
 *
 * <p>玻璃模式下页面要铺到屏幕最上（内容从顶栏底下穿过），所以 Scaffold 给的顶部
 * 内边距不能当布局内边距用，得由 [Page] 补进滚动内容里。
 */
val LocalTopInset = staticCompositionLocalOf { 0.dp }

/** 页面滚动内容那一层：顶栏与底栏的模糊、折射都采样它。null 表示玻璃没开。 */
val LocalContentBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * 液态玻璃质感是否开启。
 *
 * <p>这里 collect 一下 [AppSettings.revision]：SharedPreferences 本身不可观察，
 * 不订阅的话改了开关要重进页面才生效。
 */
@Composable
fun glassMode(): Boolean {
    // 只为了订阅变更；值本身用 AppSettings 上的同步读法
    AppSettings.revision.collectAsState()
    return AppSettings.glassEffect
}

/** 玻璃模式下的卡片圆角与半透明底；关掉就是 Miuix 默认材质。 */
@Composable
fun cardShape(): Pair<androidx.compose.ui.unit.Dp, CardColors> {
    val glass = glassMode()
    return if (glass) {
        13.dp to CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.65f)
        )
    } else {
        CardDefaults.CornerRadius to CardDefaults.defaultColors()
    }
}

/** 页面骨架：整页可滚动 + 统一左右留白 + 统一的纵向节奏。 */
@Composable
fun Page(
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    bottomExtra: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val contentBackdrop = LocalContentBackdrop.current
    val gap = if (LocalGlassBackdrop.current != null) Space.SectionGap else Space.SectionGapCompact
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(padding)
            .padding(horizontal = Space.PageHorizontal)
            // 玻璃模式下顶栏/底栏采样的是这一层，所以它必须登记在裁剪之前
            .then(if (contentBackdrop != null) Modifier.layerBackdrop(contentBackdrop) else Modifier)
            .verticalScroll(rememberScrollState())
            // 顶部留白放在滚动里面：这样内容是"从顶栏底下穿过去"的，
            // 顶栏才有东西可糊；放在外面就永远有一块空白垫着，模糊等于没有。
            .padding(top = LocalTopInset.current, bottom = bottomExtra),
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/** 一个小节：中文标题 + 一张卡片。标题为空就只出卡片。 */
@Composable
fun Section(
    title: String? = null,
    summary: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (title != null) {
            SmallTitle(
                text = title,
                // 和卡片里的正文对齐：页边距 16 + 这里 16 = 卡片内文字起始位置
                insideMargin = PaddingValues(
                    horizontal = Space.PageHorizontal,
                    vertical = 4.dp,
                ),
            )
        }
        val backdrop = LocalGlassBackdrop.current
        if (backdrop != null) {
            // 有采样源：卡片本身就是玻璃（真模糊 + 边缘折射）
            GlassCard(modifier = Modifier.fillMaxWidth(), content = content)
        } else {
            val (radius, colors) = cardShape()
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = radius,
                colors = colors,
                content = content,
            )
        }
        if (summary != null) {
            Text(
                text = summary,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/** 纵向堆叠：卡片内容默认间距，替代到处手写 spacedBy。 */
@Composable
fun Stacked(
    modifier: Modifier = Modifier,
    spacing: androidx.compose.ui.unit.Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing), content = content)
}

/** 卡片里带内边距的内容块。 */
@Composable
fun CardBody(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.padding(Space.CardInside),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** 主操作：主色底 + 主色上的前景色文字。 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColorsPrimary(),
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.button,
            color = if (enabled) {
                MiuixTheme.colorScheme.onPrimary
            } else {
                MiuixTheme.colorScheme.disabledOnPrimary
            },
        )
    }
}

/** 危险操作：错误色底。 */
@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            color = MiuixTheme.colorScheme.error,
            disabledColor = MiuixTheme.colorScheme.disabledPrimaryButton,
        ),
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.button,
            color = MiuixTheme.colorScheme.onError,
        )
    }
}

/** 次级操作：浅底 + 正常前景色。 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            color = MiuixTheme.colorScheme.surfaceVariant,
            disabledColor = MiuixTheme.colorScheme.disabledSecondaryVariant,
        ),
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.button,
            color = if (enabled) {
                MiuixTheme.colorScheme.onSurface
            } else {
                MiuixTheme.colorScheme.disabledOnSurface
            },
        )
    }
}

/** 文字按钮（不需要底色时用）：Miuix 自带的 TextButton 已经处理了文字色。 */
@Composable
fun LinkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    TextButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = if (primary) ButtonDefaults.textButtonColorsPrimary()
        else ButtonDefaults.textButtonColors(),
    )
}

/** 一小段说明文字（灰、小）。 */
@Composable
fun Footnote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = modifier,
    )
}

/** 强调数字（余额、金额）。 */
@Composable
fun MoneyText(text: String, modifier: Modifier = Modifier, color: Color = MiuixTheme.colorScheme.primary) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.headline1,
        color = color,
        modifier = modifier,
    )
}

/**
 * 统一风格的弹窗。
 *
 * <p>没有直接用 Miuix 的 SuperDialog：它的圆角、内边距和按钮排布都是固定的，几层
 * 内容一叠就显得挤。这里按 Apple 那套"标题居中 + 正文左对齐 + 动作铺满"重画一块面板。
 *
 * <p>注意：安卓上 Dialog 是独立窗口，采样不到页面内容，所以玻璃模式下这里也做不了
 * 真折射，只能靠大圆角、半透明底和一条高光边把质感撑起来。
 */
@Composable
fun AppDialog(
    title: String,
    summary: String? = null,
    onDismiss: () -> Unit,
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 关掉液态玻璃就整个回到 Miuix 自己的 SuperDialog：圆角、内边距、动效都是它的。
    // 弹窗样式跟着界面风格走，不然"切回 Miuix"只切了一半。
    if (LocalGlassBackdrop.current == null) {
        SuperDialog(
            show = remember { mutableStateOf(true) },
            title = title,
            summary = summary,
            onDismissRequest = onDismiss,
        ) {
            if (scrollable) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            } else {
                DialogBody(content = content)
            }
        }
        return
    }
    val panelShape = RoundedCornerShape(24.dp)
    val surface = MiuixTheme.colorScheme.surface
    val hairline = MiuixTheme.colorScheme.outline.copy(alpha = 0.25f)
    // 浅色下 iOS 的 alert 不是"压暗"整屏，而是往白了洗一层；深色下才反过来压暗。
    val scrim = if (MiuixTheme.colorScheme.background.luminance() > 0.5f) {
        Color.White.copy(alpha = 0.5f)
    } else {
        Color.Black.copy(alpha = 0.42f)
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(scrim)
                .clickable(indication = null, interactionSource = null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .padding(horizontal = 24.dp)
                    .shadow(22.dp, panelShape)
                    .clip(panelShape)
                    .background(surface)
                    .border(1.dp, hairline, panelShape)
                    // 挡住穿透到遮罩上的点击：点面板不该关掉弹窗
                    .clickable(indication = null, interactionSource = null) {}
                    .padding(22.dp),
            ) {
                // 官方 alert 的标题和描述都是左对齐的（不是居中），标题一行、描述一行，
                // 中间只留 6dp；描述到按钮之间才拉开。上一版居中反而像安卓原生对话框。
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = title,
                        style = MiuixTheme.textStyles.title2,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    if (summary != null) {
                        Text(
                            text = summary,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            lineHeight = 19.sp,
                        )
                    }
                }
                if (scrollable) {
                    Column(
                        modifier = Modifier
                            .padding(top = 18.dp)
                            .heightIn(max = 440.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = content,
                    )
                } else {
                    Column(
                        modifier = Modifier.padding(top = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = content,
                    )
                }
            }
        }
    }
}

/** 弹窗内容：统一 12dp 纵向节奏。 */
@Composable
fun DialogBody(content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/** 开关：玻璃模式下走真的玻璃轨道（能透出下方内容），否则用 Miuix 的。 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (LocalGlassBackdrop.current != null) {
        GlassSwitch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)
    } else {
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)
    }
}

/** 筛选用的小胶囊：选中走主色底 + 主色前景，未选中走浅底。 */
@Composable
fun FilterChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = MiuixTheme.colorScheme.primary,
    onClick: () -> Unit,
) {
    val chipShape = RoundedCornerShape(12.dp)
    val labelColor = if (selected) {
        MiuixTheme.colorScheme.onPrimary
    } else {
        MiuixTheme.colorScheme.onSurface
    }
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = chipShape,
        color = if (selected) tint else MiuixTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = labelColor,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

/** 主题色选择：一行圆点，点一下全站换色。 */
@Composable
fun ColorSwatchRow(
    selectedArgb: Long,
    onPick: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 一行放不下 10 个圆点，按 5 个一组换行，避免最后一个被裁掉
        ThemePalette.all.chunked(5).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { item ->
                val color = if (item.argb == 0L) {
                    MiuixTheme.colorScheme.primary
                } else {
                    ThemePalette.colorOf(item.argb)
                }
                val selected = item.argb == selectedArgb
                Surface(
                    onClick = { onPick(item.argb) },
                    shape = CircleShape,
                    color = color,
                    modifier = Modifier.size(if (selected) 34.dp else 28.dp),
                    border = if (selected) {
                        BorderStroke(2.dp, MiuixTheme.colorScheme.onBackground)
                    } else {
                        null
                    },
                ) {}
            }
        }
        }
        val preset = ThemePalette.all.firstOrNull { it.argb == selectedArgb }
        Footnote(
            text = (preset?.name ?: ThemePalette.CUSTOM_NAME + " " + ThemePalette.toHex(selectedArgb)) +
                if (selectedArgb == 0L) " · 各机型会随壁纸变化" else " · 整套配色由该色推导",
        )
    }
}

/**
 * 使用时段拆分显示。
 *
 * <p>以前是一行 "06:00-08:00 / 11:30-13:30"，看不出现在算哪一段；
 * 改成一段一行，并标出进行中 / 待开始 / 已结束。跨午夜的时段交给 [UseTimeSlot.containsNow]。
 */
@Composable
fun UseTimeList(
    slots: List<dev.answer.campuswater.api.UseTimeSlot>,
    loading: Boolean = false,
) {
    if (loading) {
        Footnote(text = "正在查询使用时段…")
        return
    }
    if (slots.isEmpty()) {
        Footnote(text = "未配置使用时段，全天可用。")
        return
    }
    val now = tickingNowMinutes()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        slots.forEach { slot ->
            val start = slot.startTime.toClockMinutes()
            val stop = slot.stopTime.toClockMinutes()
            val active = slot.containsNow()
            val progress = if (start >= 0 && stop >= 0) slot.progressAt(now) else null
            val state = when {
                active -> "进行中"
                start < 0 -> ""
                now < start -> "还没开始"
                else -> "已结束"
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 两端时间单独成块、字号拉大：原来 "06:30:00-08:00:00" 挤在一行里很难读
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = slot.startTime.take(5),
                            fontSize = 21.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = "—",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            text = slot.stopTime.take(5),
                            fontSize = 21.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Footnote(text = state)
                }
                progress?.let { (elapsed, total) ->
                    TimeProgressBar(elapsed.toFloat() / total.toFloat())
                    Footnote(
                        text = "已用 ${elapsed} 分钟 · 还剩 ${total - elapsed} 分钟",
                    )
                }
            }
        }
    }
}

/** 时段进度条：只画一条细胶囊，进度用主色填充。 */
@Composable
private fun TimeProgressBar(fraction: Float) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(shape)
            .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.28f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(shape)
                .background(MiuixTheme.colorScheme.primary),
        )
    }
}

/** 分钟级刷新：进度条要动，但没必要每秒重画一次整页。 */
@Composable
private fun tickingNowMinutes(): Int {
    var minutes by remember { mutableIntStateOf(clockMinutes()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000L)
            minutes = clockMinutes()
        }
    }
    return minutes
}

private fun clockMinutes(): Int = java.util.Calendar.getInstance().let {
    it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE)
}

/** "HH:mm" 或 "HH:mm:ss" → 当天分钟数；解析不出来返回 -1。 */
private fun String.toClockMinutes(): Int {
    val parts = trim().split(":")
    if (parts.size < 2) {
        return -1
    }
    val h = parts[0].toIntOrNull() ?: return -1
    val m = parts[1].toIntOrNull() ?: return -1
    if (h !in 0..24 || m !in 0..59) {
        return -1
    }
    return h * 60 + m
}

/** 这个时段此刻走了多久 / 一共多久（分钟）。不在时段内返回 null。 */
private fun dev.answer.campuswater.api.UseTimeSlot.progressAt(now: Int): Pair<Int, Int>? {
    val start = startTime.toClockMinutes()
    val stop = stopTime.toClockMinutes()
    if (start < 0 || stop < 0 || !containsNow()) {
        return null
    }
    // 跨天时段（如 22:00–06:00）总长要绕一圈午夜
    val total = if (stop > start) stop - start else (1440 - start) + stop
    val since = ((now - start) % 1440 + 1440) % 1440
    val elapsed = since.coerceAtMost(total)
    return elapsed to total
}

/** 一行放不下的胶囊组：横向滚动，不换行。 */
@Composable
fun ScrollRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** 一行"标签 + 值"：设备信息、账户信息这类只读明细。 */
@Composable
fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Footnote(text = label, modifier = Modifier.width(88.dp))
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
    }
}
