package dev.answer.campuswater.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 取色板：饱和度/明度方块 + 色相条。
 *
 * <p>只给一个十六进制输入框等于没给"全部颜色"——用户看不见有哪些可选。
 * 这里铺一整块 HSV 面，色相单独一条，两个维度都能直接点也能拖。
 */

internal fun hsvColor(hue: Float, saturation: Float, brightness: Float): Color =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness)))

/** 色值显示成 #RRGGBB。 */
internal fun hexOf(hue: Float, saturation: Float, brightness: Float): String =
    String.format(
        Locale.US,
        "#%06X",
        android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness)) and 0xFFFFFF,
    )

/** 把指针位置换算成 0..1 的归一化坐标：按下即取位，拖动连续取位。 */
private fun Modifier.normalizedPointer(onPoint: (x: Float, y: Float) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false).let { down ->
                onPoint(down.position.x / size.width, down.position.y / size.height)
            }
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.pressed } ?: break
                onPoint(change.position.x / size.width, change.position.y / size.height)
                change.consume()
            }
        }
    }

/** 当前色预览条，顺便把十六进制值显示出来。 */
@Composable
fun ColorPreviewBar(hex: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(color)
            .border(1.dp, Color.Black.copy(alpha = 0.08f), RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = hex,
            style = MiuixTheme.textStyles.footnote1,
            color = if (color.luminance() > 0.6f) Color.Black.copy(alpha = 0.7f) else Color.White,
        )
    }
}

/**
 * 饱和度（横轴 0..1）× 明度（纵轴 1..0）取色面。
 *
 * @param onPicked 回传新的 (saturation, value)
 */
@Composable
fun SaturationValueSquare(
    hue: Float,
    saturation: Float,
    brightness: Float,
    onPicked: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pure = hsvColor(hue, 1f, 1f)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(170.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(pure)
            // 白→纯色是横轴，透明→黑是纵轴，两层叠起来正好是整片 HSV 面
            .background(Brush.horizontalGradient(listOf(Color.White, pure.copy(alpha = 0f))))
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            .normalizedPointer { x, y -> onPicked(x.coerceIn(0f, 1f), (1f - y).coerceIn(0f, 1f)) },
    ) {
        Box(
            Modifier
                .offset(x = maxWidth * saturation - 13.dp, y = maxHeight * (1f - brightness) - 13.dp)
                .size(26.dp)
                .border(2.dp, Color.White, CircleShape)
                .background(hsvColor(hue, saturation, brightness), CircleShape),
        )
    }
}

/** 色相条：0..360 度。 */
@Composable
fun HueSlider(
    hue: Float,
    onHue: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red,
                    )
                )
            )
            .normalizedPointer { x, _ -> onHue((x.coerceIn(0f, 1f) * 360f).roundToInt().toFloat()) },
        contentAlignment = Alignment.CenterStart,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .offset(x = maxWidth * (hue / 360f) - 13.dp, y = (-3).dp)
                    .size(26.dp)
                    .border(3.dp, Color.White, CircleShape)
                    .background(hsvColor(hue, 1f, 1f), CircleShape),
            )
        }
    }
}
