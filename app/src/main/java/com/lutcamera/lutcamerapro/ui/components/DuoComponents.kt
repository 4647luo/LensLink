package com.lutcamera.lutcamerapro.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lutcamera.lutcamerapro.ui.theme.DuoTheme

enum class DuoButtonColor {
    GREEN, SKY, YELLOW, FOX, RED, PURPLE, NEUTRAL, SURFACE
}

/**
 * 🦉 多邻国标志性 3D 立体触感按钮
 * 特性：底部带有立体倒角厚度层，手指按下时下陷位移，提供真实的机械触感回弹体验 */
@Composable
fun DuoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colorStyle: DuoButtonColor = DuoButtonColor.GREEN,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 48.dp,
    fontSize: TextUnit = 15.sp,
    cornerRadius: Dp = 16.dp,
    bevelHeight: Dp = 4.dp
) {
    val duo = DuoTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val (topColor, shadowColor, textColor) = when (colorStyle) {
        DuoButtonColor.GREEN -> Triple(duo.green, duo.greenShadow, Color.White)
        DuoButtonColor.SKY -> Triple(duo.sky, duo.skyShadow, Color.White)
        DuoButtonColor.YELLOW -> Triple(duo.yellow, duo.yellowShadow, Color(0xFF4B4B4B))
        DuoButtonColor.FOX -> Triple(duo.fox, duo.foxShadow, Color.White)
        DuoButtonColor.RED -> Triple(duo.red, duo.redShadow, Color.White)
        DuoButtonColor.PURPLE -> Triple(duo.purple, duo.purpleShadow, Color.White)
        DuoButtonColor.NEUTRAL -> Triple(duo.surfaceSecondary, duo.border, duo.textPrimary)
        DuoButtonColor.SURFACE -> Triple(duo.surface, duo.border, duo.textPrimary)
    }

    val animatedTopOffset by animateDpAsState(
        targetValue = if (isPressed && enabled) bevelHeight else 0.dp,
        animationSpec = tween(durationMillis = 60),
        label = "DuoButtonOffset"
    )

    Box(
        modifier = modifier
            .height(height + bevelHeight)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
    ) {
        // Shadow
                Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .offset(y = bevelHeight)
                .clip(RoundedCornerShape(cornerRadius))
                .background(if (enabled) shadowColor else duo.border)
        )

        // 上部按钮主体（按压下沉）
                Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .offset(y = animatedTopOffset)
                .clip(RoundedCornerShape(cornerRadius))
                .background(if (enabled) topColor else duo.surfaceSecondary)
                .border(
                    width = if (colorStyle == DuoButtonColor.NEUTRAL || colorStyle == DuoButtonColor.SURFACE) 2.dp else 0.dp,
                    color = if (colorStyle == DuoButtonColor.NEUTRAL || colorStyle == DuoButtonColor.SURFACE) duo.border else Color.Transparent,
                    shape = RoundedCornerShape(cornerRadius)
                ),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (enabled) textColor else duo.textTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = text,
                    color = if (enabled) textColor else duo.textTertiary,
                    fontSize = fontSize,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * 🦉 多邻国 3D 圆形图标按键（专用于快捷、对焦 AF、控制开关等） */
@Composable
fun DuoIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colorStyle: DuoButtonColor = DuoButtonColor.GREEN,
    size: Dp = 56.dp,
    bevelHeight: Dp = 4.dp,
    iconSize: Dp = 24.dp,
    enabled: Boolean = true
) {
    val duo = DuoTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val (topColor, shadowColor, iconColor) = when (colorStyle) {
        DuoButtonColor.GREEN -> Triple(duo.green, duo.greenShadow, Color.White)
        DuoButtonColor.SKY -> Triple(duo.sky, duo.skyShadow, Color.White)
        DuoButtonColor.YELLOW -> Triple(duo.yellow, duo.yellowShadow, Color(0xFF4B4B4B))
        DuoButtonColor.FOX -> Triple(duo.fox, duo.foxShadow, Color.White)
        DuoButtonColor.RED -> Triple(duo.red, duo.redShadow, Color.White)
        DuoButtonColor.PURPLE -> Triple(duo.purple, duo.purpleShadow, Color.White)
        DuoButtonColor.NEUTRAL -> Triple(duo.surfaceSecondary, duo.border, duo.textPrimary)
        DuoButtonColor.SURFACE -> Triple(duo.surface, duo.border, duo.textPrimary)
    }

    val animatedTopOffset by animateDpAsState(
        targetValue = if (isPressed && enabled) bevelHeight else 0.dp,
        animationSpec = tween(durationMillis = 60),
        label = "DuoIconOffset"
    )

    Box(
        modifier = modifier
            .size(size + bevelHeight)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.TopCenter
    ) {
        // 底部阴影
                Box(
            modifier = Modifier
                .size(size)
                .offset(y = bevelHeight)
                .clip(CircleShape)
                .background(if (enabled) shadowColor else duo.border)
        )

        // 按钮主体
                Box(
            modifier = Modifier
                .size(size)
                .offset(y = animatedTopOffset)
                .clip(CircleShape)
                .background(if (enabled) topColor else duo.surfaceSecondary)
                .border(
                    width = if (colorStyle == DuoButtonColor.SURFACE) 2.dp else 0.dp,
                    color = duo.border,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) iconColor else duo.textTertiary,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

/**
 * 🦉 多邻国 2dp 实心描边大圆角卡片容器 */
@Composable
fun DuoCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 18.dp,
    backgroundColor: Color = DuoTheme.colors.cardBackground,
    borderColor: Color = DuoTheme.colors.border,
    bevelHeight: Dp = 2.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val duo = DuoTheme.colors

    Box(modifier = modifier) {
        // 底部立体小边框（多邻国沉底阴影）
                Box(
            modifier = Modifier
                .matchParentSize()
                .offset(y = bevelHeight)
                .clip(RoundedCornerShape(cornerRadius))
                .background(duo.border)
        )

        // 卡片正体
                Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(cornerRadius))
                .background(backgroundColor)
                .border(2.dp, borderColor, RoundedCornerShape(cornerRadius))
                .padding(14.dp),
            content = content
        )
    }
}

/**
 * 🦉 多邻国胶囊药丸状徽章（Pill Badge） */
@Composable
fun DuoPillBadge(
    text: String,
    modifier: Modifier = Modifier,
    dotColor: Color? = DuoTheme.colors.green,
    badgeColor: Color = DuoTheme.colors.surfaceSecondary,
    textColor: Color = DuoTheme.colors.textPrimary,
    fontSize: TextUnit = 12.sp,
    paddingHorizontal: Dp = 10.dp,
    paddingVertical: Dp = 4.dp
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50.dp))
            .background(badgeColor)
            .border(1.5.dp, DuoTheme.colors.border, RoundedCornerShape(50.dp))
            .padding(horizontal = paddingHorizontal, vertical = paddingVertical),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dotColor != null) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = text,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
    }
}

/**
 * 🦉 多邻国胶囊式分段滑动选择器 (Segmented Control)
 */
@Composable
fun <T> DuoSegmentedControl(
    items: List<T>,
    selectedItem: T,
    onItemSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    labelProvider: (T) -> String,
    iconProvider: ((T) -> ImageVector)? = null
) {
    val duo = DuoTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(duo.surfaceSecondary)
            .border(2.dp, duo.border, RoundedCornerShape(16.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEach { item ->
            val isSelected = item == selectedItem
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) duo.surface else Color.Transparent)
                    .then(
                        if (isSelected) Modifier.border(2.dp, duo.border, RoundedCornerShape(12.dp))
                        else Modifier
                    )
                    .clickable { onItemSelected(item) },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (iconProvider != null) {
                        Icon(
                            imageVector = iconProvider(item),
                            contentDescription = null,
                            tint = if (isSelected) duo.green else duo.textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = labelProvider(item),
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                        color = if (isSelected) duo.textPrimary else duo.textSecondary
                    )
                }
            }
        }
    }
}



