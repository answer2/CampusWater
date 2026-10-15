package dev.answer.campuswater.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import dev.answer.campuswater.ui.glass.DampedDragAnimation
import dev.answer.campuswater.ui.glass.InteractiveHighlight
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tanh
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 液态玻璃层（Kyant0 Backdrop）。
 *
 * <p>用法照官方示例：**一块背景层**被记进 [LayerBackdrop]，需要玻璃质感的组件用
 * `drawBackdrop` 去采样它。上一版我拿"滚动的内容"当采样源，卡片自己采自己会形成
 * 回环，所以换成独立的背景层，卡片/横幅/按钮/底栏共用这一块源。
 *
 * <p>能力分级由库自己兜：模糊要安卓 12+，折射与高光要安卓 13+；不满足时对应 effect
 * 直接跳过，组件仍有半透明底，不会变成看不见的透明块。
 */

/** 当前生效的玻璃采样源；null 表示玻璃模式关闭。 */
val LocalGlassBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** 玻璃采样用的背景层。 */
@Composable
fun rememberGlassBackdrop(): LayerBackdrop = rememberLayerBackdrop()

/**
 * 画出被采样的背景并登记为源。
 *
 * <p>页面本体要保持平整（接近系统设置那种浅灰底），只在屏幕最下方留一团主色光斑：
 * 底栏那颗玻璃胶囊得有点东西可折，不然它和一块半透明灰条没区别。
 */
@Composable
fun GlassBackdropLayer(backdrop: LayerBackdrop) {
    val primary = MiuixTheme.colorScheme.primary
    val background = MiuixTheme.colorScheme.background
    Box(
        Modifier
            .fillMaxSize()
            .layerBackdrop(backdrop)
            .background(background)
            .background(
                Brush.verticalGradient(
                    0.65f to Color.Transparent,
                    0.88f to primary.copy(alpha = 0.12f),
                    1f to primary.copy(alpha = 0.22f),
                )
            )
    )
}

/**
 * 通用玻璃表面：卡片、横幅、按钮都走它。
 *
 * @param containerColor 玻璃自身染色；不给就用主题卡片色的半透明版
 */
@Composable
fun Modifier.liquidGlass(
    backdrop: LayerBackdrop?,
    shape: () -> Shape = { Capsule() },
    containerColor: Color = Color.Unspecified,
    blurRadius: Dp = 16.dp,
    refractionHeight: Dp = 20.dp,
    refractionAmount: Dp = 22.dp,
): Modifier {
    if (backdrop == null) return this
    val density = LocalDensity.current
    val tint = if (containerColor.isSpecified) {
        containerColor
    } else {
        MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.48f)
    }
    return drawBackdrop(
        backdrop = backdrop,
        shape = shape,
        effects = {
            vibrancy()
            blur(with(density) { blurRadius.toPx() })
            with(density) { lens(refractionHeight.toPx(), refractionAmount.toPx()) }
        },
        onDrawSurface = { drawRect(tint) },
    )
}

/**
 * 玻璃模式下的卡片形状。
 *
 * <p>iOS 的分组卡片只有 10–14pt，不是 HyperOS 那种大圆角；之前给 24dp，
 * 一行行看下去像一堆气泡，而不是一个列表。
 */
fun glassCardShape(): Shape = RoundedCornerShape(13.dp)

// ------------------------------------------------------------------ 底栏

private val LocalLiquidBottomTabScale = staticCompositionLocalOf { { 1f } }

/**
 * 液态玻璃底栏：胶囊面板 + **可拖动的选中胶囊** + 按压高光。
 *
 * <p>三层结构照官方示例：面板层采样背景做模糊与折射；一层 alpha=0 的"标签着色层"
 * 只作为被采样源（让选中胶囊透出主色）；选中胶囊层组合前两块源，按下时才加强折射。
 * 拖动换页与弹性由 [DampedDragAnimation] 驱动，按压光斑由 [InteractiveHighlight] 驱动。
 */
@Composable
fun LiquidBottomBar(
    items: List<NavigationItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val accentColor = MiuixTheme.colorScheme.primary
    val containerColor = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.22f)
    val tabsBackdrop = rememberLayerBackdrop()
    val pillIdleColor = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.08f)
    val animationScope = rememberCoroutineScope()
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val tabsCount = items.size

    // 先让开系统导航条/手势区，再留悬浮间距：贴着屏幕底，玻璃就浮不起来
    BoxWithConstraints(
        modifier
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 18.dp)
            .fillMaxWidth(),
        // 官方就是这个对齐：胶囊高 56、面板高 64，少了它选中胶囊会顶出面板上沿
        contentAlignment = Alignment.CenterStart,
    ) {
        val panelWidth = with(density) { constraints.maxWidth.toFloat() }
        val tabWidth = (panelWidth - with(density) { 8.dp.toPx() }) / tabsCount

        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / panelWidth).fastCoerceIn(-1f, 1f)
                with(density) { 4.dp.toPx() } * fraction.sign * EaseOut.transform(abs(fraction))
            }
        }

        // 选中的唯一真相是外部 selected。上一版把它存在 remember(selected) 里，
        // selected 一变就重建 state，而 snapshotFlow 的闭包读的还是被换掉那个旧 state，
        // 于是点标签换了页、胶囊却留在原地（真机截图实测）。
        var selectedIndex by remember { mutableIntStateOf(selected) }
        LaunchedEffect(selected) { selectedIndex = selected }

        val damped = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selected.toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    val target = targetValue.fastRoundToInt().coerceIn(0, tabsCount - 1)
                    // 换页交给 selected 那条路径，胶囊统一由下面的 effect 弹过去
                    if (target != selectedIndex) onSelect(target) else animateToValue(target.toFloat())
                    animationScope.launch { offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f)) }
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / tabWidth * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                },
            )
        }
        var settled by remember { mutableStateOf(false) }
        LaunchedEffect(selectedIndex) {
            // 首次组合胶囊已经在位，再 animate 会白闪一下
            if (!settled) {
                settled = true
                return@LaunchedEffect
            }
            damped.animateToValue(selectedIndex.toFloat())
        }

        val interactive = remember(animationScope) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, offset ->
                    Offset(
                        if (isLtr) {
                            (damped.value + 0.5f) * tabWidth + panelOffset
                        } else {
                            size.width - (damped.value + 0.5f) * tabWidth + panelOffset
                        },
                        size.height / 2f,
                    )
                },
            )
        }

        val tabContent: @Composable RowScope.() -> Unit = {
            items.forEachIndexed { index, item ->
                GlassTab(item = item, onClick = { onSelect(index) })
            }
        }

        // 1) 面板层
        Row(
            Modifier
                .graphicsLayer {
                    translationX = panelOffset
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        // 提高模糊半径让磨砂质感更明显，尤其在高分屏上
                        blur(with(density) { 28.dp.toPx() })
                        with(density) { lens(28.dp.toPx(), 28.dp.toPx()) }
                    },
                    layerBlock = {
                        val progress = damped.pressProgress
                        val scale = lerp(
                            1f,
                            1f + with(density) { 16.dp.toPx() } / size.width,
                            progress,
                        )
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = { drawRect(containerColor) },
                )
                .then(interactive.modifier)
                .height(64.dp)
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = tabContent,
        )

        // 2) 着色层：alpha 0，只作为选中胶囊的采样源（让它透出主色）
        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides { lerp(1f, 1.2f, damped.pressProgress) }
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer {
                        translationX = panelOffset
                    }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            val progress = damped.pressProgress
                            vibrancy()
                            // 提高模糊半径让磨砂质感更明显
                            blur(with(density) { 28.dp.toPx() })
                            with(density) {
                                lens(28.dp.toPx() * progress, 28.dp.toPx() * progress)
                            }
                        },
                        highlight = {
                            Highlight.Default.copy(alpha = damped.pressProgress * 1.2f)
                        },
                        onDrawSurface = { drawRect(containerColor) },
                    )
                    .then(interactive.modifier)
                    .height(56.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = tabContent,
            )
        }

        // 3) 选中胶囊：拖动换页 + 高光/内阴影/速度拉伸。
        //    手势只能挂在这一层：它是 z 序最上的节点。之前铺了整条栏的透明手势层，
        //    抬起时也会走 onDragStopped，把刚点中的标签又拽回原位，点击就废了。
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .graphicsLayer {
                    translationX = if (isLtr) {
                        damped.value * tabWidth + panelOffset
                    } else {
                        panelWidth - (damped.value + 1f) * tabWidth + panelOffset
                    }
                }
                .then(interactive.gestureModifier)
                .then(damped.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { Capsule() },
                    effects = {
                        val progress = damped.pressProgress
                        with(density) {
                            lens(
                                14.dp.toPx() * progress,
                                18.dp.toPx() * progress,
                                chromaticAberration = true,
                            )
                        }
                    },
                    highlight = { Highlight.Default.copy(alpha = damped.pressProgress * 1.3f) },
                    shadow = { Shadow(alpha = damped.pressProgress * 0.8f) },
                    innerShadow = {
                        InnerShadow(radius = 10.dp * damped.pressProgress, alpha = damped.pressProgress * 0.9f)
                    },
                    layerBlock = {
                        scaleX = damped.scaleX
                        scaleY = damped.scaleY
                        val velocity = damped.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = damped.pressProgress
                        // 这里不是组合上下文，颜色必须在外面取好再闭包进来
                        drawRect(pillIdleColor, alpha = 1f - progress)
                        drawRect(Color.Black.copy(alpha = 0.04f * progress))
                    },
                )
                .height(56.dp)
                .fillMaxWidth(1f / tabsCount),
        )
    }
}

@Composable
private fun RowScope.GlassTab(item: NavigationItem, onClick: () -> Unit) {
    val scale = LocalLiquidBottomTabScale.current
    val color = MiuixTheme.colorScheme.onBackground
    Column(
        Modifier
            .clip(Capsule())
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val s = scale()
                scaleX = s
                scaleY = s
            },
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = color,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = item.label,
            style = MiuixTheme.textStyles.footnote1,
            color = color,
        )
    }
}

/**
 * 苹果样式开关：照官方 LiquidToggle 的结构移植。
 *
 * <p>轨道是纯色胶囊（关=灰、开=苹果绿，按进度混色），滑块是一块玻璃：它同时采样
 * 轨道和页面背景，所以按住时会把底下的颜色折射上来、并轻微压扁回弹，可以左右拖着走。
 */
@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val backdrop = LocalGlassBackdrop.current ?: return
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val dark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val accentColor = if (dark) Color(0xFF30D158) else Color(0xFF34C759)
    // 关闭态 iOS 用的是一块很浅的灰（#E9E9EA）加一条发丝描边，不是半透明灰；
    // 之前抄官方示例的 0.2 透灰，压在玻璃卡片上就显得脏。
    val trackColor = if (dark) Color(0xFF39393D) else Color(0xFFE9E9EA)
    val trackOutline = if (dark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.08f)
    val dragWidth = with(density) { 20.dp.toPx() }
    val animationScope = rememberCoroutineScope()
    var didDrag by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(if (checked) 1f else 0f) }

    val damped = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStarted = {},
            onDragStopped = {
                val next = if (didDrag) targetValue >= 0.5f else !checked
                didDrag = false
                fraction = if (next) 1f else 0f
                if (next != checked) onCheckedChange(next) else animateToValue(if (next) 1f else 0f)
            },
            onDrag = { _, dragAmount ->
                if (!didDrag) didDrag = dragAmount.x != 0f
                val delta = dragAmount.x / dragWidth
                fraction = if (isLtr) (fraction + delta).fastCoerceIn(0f, 1f)
                else (fraction - delta).fastCoerceIn(0f, 1f)
            },
        )
    }
    LaunchedEffect(damped) {
        snapshotFlow { fraction }.collectLatest { damped.updateValue(it) }
    }
    LaunchedEffect(checked) {
        val target = if (checked) 1f else 0f
        if (target != fraction) {
            fraction = target
            damped.animateToValue(target)
        }
    }

    val trackBackdrop = rememberLayerBackdrop()
    Box(modifier.graphicsLayer { alpha = if (enabled) 1f else 0.4f }, contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier
                .layerBackdrop(trackBackdrop)
                .clip(Capsule())
                .drawBehind {
                    val fraction = damped.value
                    drawRect(androidx.compose.ui.graphics.lerp(trackColor, accentColor, fraction))
                    // 发丝描边只在关闭态有，变绿的过程中淡出——iOS 就是这样收边的
                    if (fraction < 0.98f) {
                        drawRoundRect(
                            color = trackOutline,
                            cornerRadius = CornerRadius(size.height / 2f),
                            style = Stroke(width = with(density) { 1.dp.toPx() }),
                            alpha = 1f - fraction,
                        )
                    }
                }
                .size(51.dp, 31.dp),
        )
        Box(
            Modifier
                .graphicsLayer {
                    val padding = with(density) { 2.dp.toPx() }
                    translationX = if (isLtr) {
                        lerp(padding, padding + dragWidth, damped.value)
                    } else {
                        lerp(-padding, -(padding + dragWidth), damped.value)
                    }
                }
                .semantics { role = Role.Switch }
                .then(if (enabled) damped.modifier else Modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(
                        backdrop,
                        rememberBackdrop(trackBackdrop) { drawBackdrop ->
                            val progress = damped.pressProgress
                            scale(
                                lerp(2f / 3f, 0.75f, progress),
                                lerp(0f, 0.75f, progress),
                            ) { drawBackdrop() }
                        },
                    ),
                    shape = { Capsule() },
                    effects = {
                        val progress = damped.pressProgress
                        blur(with(density) { 10.dp.toPx() } * (1f - progress))
                        with(density) {
                            lens(6.dp.toPx() * progress, 12.dp.toPx() * progress, chromaticAberration = true)
                        }
                    },
                    highlight = {
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.3f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.3f,
                            alpha = damped.pressProgress * 1.2f,
                        )
                    },
                    shadow = { Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.05f)) },
                    innerShadow = {
                        InnerShadow(radius = 4.dp * damped.pressProgress, alpha = damped.pressProgress)
                    },
                    layerBlock = {
                        scaleX = damped.scaleX
                        scaleY = damped.scaleY
                        val velocity = damped.velocity / 50f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = { drawRect(Color.White.copy(alpha = 1f - damped.pressProgress)) },
                )
                .size(27.dp, 27.dp),
        )
    }
}

/**
 * 顶栏背后的玻璃：采样滚动的页面内容。
 *
 * <p>底色要给得极省：给多了就是一条看得见的"栏"（上一版 0.55 就是这个毛病），
 * Apple 的效果是静止时完全隐形，只有内容滚过去的那一片被糊掉。
 */
@Composable
fun GlassToolbarLayer(backdrop: LayerBackdrop, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    Box(
        modifier
            .fillMaxSize()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RectangleShape },
                effects = {
                    vibrancy()
                    blur(with(density) { 32.dp.toPx() })
                },
                onDrawSurface = {
                    // 极轻的底色让顶栏在浅色背景下也有存在感
                    drawRect(Color.White.copy(alpha = 0.02f))
                },
            ),
    )
}

/**
 * 顶栏上的悬浮玻璃胶囊：Apple 的新 toolbar 里按钮是各自浮起来的（编辑、分段控件、
 * 圆形返回键都是一颗胶囊），不是贴在一条栏上。
 */
@Composable
fun GlassPill(
    modifier: Modifier = Modifier,
    shape: () -> Shape = { CircleShape },
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = LocalGlassBackdrop.current
    Box(
        modifier
            .then(
                if (backdrop != null) {
                    Modifier.liquidGlass(
                        backdrop = backdrop,
                        shape = shape,
                        blurRadius = 18.dp,
                        refractionHeight = 14.dp,
                        refractionAmount = 16.dp,
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/**
 * 分组卡片。
 *
 * <p>这里**故意不用玻璃**：Apple 那套里只有浮在内容之上的控件（底栏、悬浮按钮）是玻璃，
 * 内容卡片是一整块不透明的白。全页面都半透明折射的话，字底下永远铺着一层别人的内容，
 * 读起来脏。所以玻璃只留给 [LiquidBottomBar]、[GlassPill] 和开关滑块。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(glassCardShape())
            .background(MiuixTheme.colorScheme.surface)
            .padding(16.dp),
        content = content,
    )
}
