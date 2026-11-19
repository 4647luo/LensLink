package com.lutcamera.lutcamerapro.ui.components

import androidx.compose.animation.animateColorAsState
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
import com.lutcamera.lutcamerapro.ui.theme.AppleTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

enum class AppleButtonColor {
    PRIMARY, // Accent (Blue typically, or green in our palette)
                SECONDARY, // Grayish, for secondary actions
                DESTRUCTIVE, // Red
                SURFACE // Card background style
                }

@Composable
fun AppleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colorStyle: AppleButtonColor = AppleButtonColor.PRIMARY,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 50.dp,
    fontSize: TextUnit = 16.sp,
    cornerRadius: Dp = 14.dp
) {
    val apple = AppleTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val (baseColor, pressedColor, textColor) = when (colorStyle) {
        AppleButtonColor.PRIMARY -> Triple(apple.accent, apple.accent.copy(alpha = 0.7f), Color.White)
        AppleButtonColor.SECONDARY -> Triple(apple.surfaceSecondary, apple.border, apple.textPrimary)
        AppleButtonColor.DESTRUCTIVE -> Triple(apple.accentRed, apple.accentRed.copy(alpha = 0.7f), Color.White)
        AppleButtonColor.SURFACE -> Triple(apple.surface, apple.surfaceSecondary, apple.textPrimary)
    }

    val animatedColor by animateColorAsState(
        targetValue = if (!enabled) apple.surfaceSecondary else if (isPressed) pressedColor else baseColor,
        animationSpec = tween(100), label = ""
    )
    val animatedTextColor by animateColorAsState(
        targetValue = if (!enabled) apple.textTertiary else textColor,
        animationSpec = tween(100), label = ""
    )

    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .background(animatedColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
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
                    tint = animatedTextColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                color = animatedTextColor,
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun AppleIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colorStyle: AppleButtonColor = AppleButtonColor.PRIMARY,
    size: Dp = 56.dp,
    iconSize: Dp = 24.dp,
    enabled: Boolean = true
) {
    val apple = AppleTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val (baseColor, pressedColor, iconColor) = when (colorStyle) {
        AppleButtonColor.PRIMARY -> Triple(apple.accent, apple.accent.copy(alpha = 0.7f), Color.White)
        AppleButtonColor.SECONDARY -> Triple(apple.surfaceSecondary, apple.border, apple.textPrimary)
        AppleButtonColor.DESTRUCTIVE -> Triple(apple.accentRed, apple.accentRed.copy(alpha = 0.7f), Color.White)
        AppleButtonColor.SURFACE -> Triple(apple.surface, apple.surfaceSecondary, apple.textPrimary)
    }

    val animatedColor by animateColorAsState(
        targetValue = if (!enabled) apple.surfaceSecondary else if (isPressed) pressedColor else baseColor,
        animationSpec = tween(100), label = ""
    )
    val animatedIconColor by animateColorAsState(
        targetValue = if (!enabled) apple.textTertiary else iconColor,
        animationSpec = tween(100), label = ""
    )

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(animatedColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = animatedIconColor,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
fun AppleCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    backgroundColor: Color = AppleTheme.colors.cardBackground,
    borderColor: Color = AppleTheme.colors.border,
    bevelHeight: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(cornerRadius))
            .padding(16.dp),
        content = content
    )
}

@Composable
fun ApplePillBadge(
    text: String,
    modifier: Modifier = Modifier,
    dotColor: Color? = AppleTheme.colors.accent,
    badgeColor: Color = AppleTheme.colors.surfaceSecondary,
    textColor: Color = AppleTheme.colors.textPrimary,
    fontSize: TextUnit = 12.sp,
    paddingHorizontal: Dp = 10.dp,
    paddingVertical: Dp = 4.dp
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50.dp))
            .background(badgeColor)
            .padding(horizontal = paddingHorizontal, vertical = paddingVertical),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dotColor != null) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = text,
            fontSize = fontSize,
            fontWeight = FontWeight.Medium,
            color = textColor
        )
    }
}

@Composable
fun <T> AppleSegmentedControl(
    items: List<T>,
    selectedItem: T,
    onItemSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    labelProvider: (T) -> String,
    iconProvider: ((T) -> ImageVector)? = null
) {
    val apple = AppleTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(apple.surfaceSecondary)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items.forEach { item ->
            val isSelected = item == selectedItem
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSelected) apple.surface else Color.Transparent)
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
                            tint = if (isSelected) apple.textPrimary else apple.textSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = labelProvider(item),
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isSelected) apple.textPrimary else apple.textSecondary
                    )
                }
            }
        }
    }
}

@Composable
fun AppleParamPicker(
    title: String,
    options: List<String>,
    currentValue: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val apple = AppleTheme.colors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clickable(enabled = false, onClick = {}),
                shape = RoundedCornerShape(16.dp),
                color = apple.surface
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 16.dp)
                ) {
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = apple.textSecondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        textAlign = TextAlign.Center
                    )
                    
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(apple.divider))

                    LazyColumn(
                        modifier = Modifier.heightIn(max = 300.dp)
                    ) {
                        items(options) { option ->
                            val isSelected = option == currentValue
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(option) }
                                    .background(if (isSelected) apple.surfaceSecondary else Color.Transparent)
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = option,
                                    fontSize = 18.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) apple.accent else apple.textPrimary
                                )
                            }
                            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(apple.divider))
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height(50.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(apple.surfaceSecondary)
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                         Text(
                            text = "取消",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = apple.accent
                        )
                    }
                }
            }
        }
    }
}




