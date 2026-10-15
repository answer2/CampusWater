package dev.answer.campuswater.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.answer.campuswater.data.BannerKind
import dev.answer.campuswater.data.Tone
import dev.answer.campuswater.data.WaterEvent
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.useful.Cancel
import top.yukonga.miuix.kmp.icon.icons.useful.Info
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 语义色：界面里所有"状态"都从这里取，避免散着写十六进制。 */
@Composable
fun toneColor(tone: Tone): Color = when (tone) {
    Tone.NEUTRAL -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    Tone.OK -> MiuixTheme.colorScheme.primary
    Tone.WARN -> Color(0xFFE0952F)
    Tone.ERROR -> MiuixTheme.colorScheme.error
}

@Composable
fun bannerColor(kind: BannerKind): Color = when (kind) {
    BannerKind.OPEN_FAILED, BannerKind.CLOSE_FAILED -> MiuixTheme.colorScheme.error
    BannerKind.OCCUPIED -> Color(0xFFE0952F)
    BannerKind.AUTO_CLOSED -> MiuixTheme.colorScheme.primary
    BannerKind.INFO -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}

/**
 * 界面内的顶部横幅。
 *
 * <p>和系统通知的「横幅提醒」是同一个事件的两个出口：应用在前台时看这里，
 * 退到后台时看通知栏。关掉「横幅提醒」只影响系统那一侧 ——
 * "开阀失败"不让人知道才是问题，所以界面里始终会显示。
 */
@Composable
fun BannerHost(banner: WaterEvent.Banner?, onDismiss: () -> Unit) {
    AnimatedVisibility(
        visible = banner != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        val current = banner ?: return@AnimatedVisibility
        val accent = bannerColor(current.kind)
        LaunchedEffect(current) {
            delay(BANNER_TIMEOUT_MS)
            onDismiss()
        }
        // 横幅是内容不是浮层，所以跟卡片一样：实色浅底，不做玻璃
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(cardShape().first),
            color = accent.copy(alpha = 0.12f),
        ) {
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = MiuixIcons.Useful.Info,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier
                        .size(20.dp)
                        .align(Alignment.CenterVertically),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = current.title,
                        style = MiuixTheme.textStyles.title2,
                        color = accent,
                    )
                    Text(
                        text = current.message,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onBackground,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = MiuixIcons.Useful.Cancel,
                        contentDescription = "关闭提示",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

private const val BANNER_TIMEOUT_MS = 6_000L
