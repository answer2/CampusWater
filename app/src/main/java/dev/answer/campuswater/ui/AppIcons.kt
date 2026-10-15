package dev.answer.campuswater.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 底部导航用的三个自带图标。
 *
 * <p>必须现画 `ImageVector` 而不是复用 res/drawable：Miuix 的
 * [top.yukonga.miuix.kmp.basic.NavigationBar] 只接受 `ImageVector`
 * （它的 `addPath(pathData=…)` 要的是已解析的节点列表，不是 SVG 字符串，
 * 而 `PathParser` 在 Compose 里不是公开 API）。
 */
private fun icon(
    name: String,
    block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).path(
    fill = SolidColor(Color.Black),
    pathFillType = PathFillType.NonZero,
    pathBuilder = block,
).build()

private fun icon2(
    name: String,
    first: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
    second: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).path(fill = SolidColor(Color.Black), pathBuilder = first)
    .path(fill = SolidColor(Color.Black), pathBuilder = second)
    .build()

object AppIcons {

    /** 水滴：用水。 */
    val Water: ImageVector = icon("water") {
        moveTo(12f, 2.5f)
        curveTo(12f, 2.5f, 5.2f, 10.6f, 5.2f, 15f)
        curveTo(5.2f, 18.4f, 8.3f, 21.3f, 12f, 21.3f)
        curveTo(15.7f, 21.3f, 18.8f, 18.4f, 18.8f, 15f)
        curveTo(18.8f, 10.6f, 12f, 2.5f, 12f, 2.5f)
        close()
    }

    /** 钱包：充值。 */
    val Wallet: ImageVector = icon2(
        "wallet",
        first = {
            moveTo(5f, 5f)
            curveTo(3.9f, 5f, 3f, 5.9f, 3f, 7f)
            lineTo(3f, 17f)
            curveTo(3f, 18.1f, 3.9f, 19f, 5f, 19f)
            lineTo(19f, 19f)
            curveTo(20.1f, 19f, 21f, 18.1f, 21f, 17f)
            lineTo(21f, 7f)
            curveTo(21f, 5.9f, 20.1f, 5f, 19f, 5f)
            close()
        },
        second = {
            moveTo(16f, 13.5f)
            curveTo(15.17f, 13.5f, 14.5f, 12.83f, 14.5f, 12f)
            curveTo(14.5f, 11.17f, 15.17f, 10.5f, 16f, 10.5f)
            curveTo(16.83f, 10.5f, 17.5f, 11.17f, 17.5f, 12f)
            curveTo(17.5f, 12.83f, 16.83f, 13.5f, 16f, 13.5f)
            close()
        },
    )

    /** 小票：账单。 */
    val Receipt: ImageVector = icon("receipt") {
        moveTo(5f, 3f)
        lineTo(19f, 3f)
        lineTo(19f, 21f)
        lineTo(16.5f, 19.4f)
        lineTo(14f, 21f)
        lineTo(11.5f, 19.4f)
        lineTo(9f, 21f)
        lineTo(6.5f, 19.4f)
        lineTo(5f, 21f)
        close()
    }

    /** 人：个人。 */
    val Person: ImageVector = icon2(
        "person",
        first = {
            moveTo(12f, 4.5f)
            curveTo(13.66f, 4.5f, 15f, 5.84f, 15f, 7.5f)
            curveTo(15f, 9.16f, 13.66f, 10.5f, 12f, 10.5f)
            curveTo(10.34f, 10.5f, 9f, 9.16f, 9f, 7.5f)
            curveTo(9f, 5.84f, 10.34f, 4.5f, 12f, 4.5f)
            close()
        },
        second = {
            moveTo(4.5f, 19.5f)
            curveTo(4.5f, 15.9f, 8.05f, 14.2f, 12f, 14.2f)
            curveTo(15.95f, 14.2f, 19.5f, 15.9f, 19.5f, 19.5f)
            lineTo(4.5f, 19.5f)
            close()
        },
    )
}
