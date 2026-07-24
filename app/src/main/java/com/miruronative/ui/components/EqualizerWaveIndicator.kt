package com.miruronative.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Animated Equalizer Wave indicator showing bouncing audio bars for currently playing content.
 */
@Composable
fun EqualizerWaveIndicator(
    modifier: Modifier = Modifier,
    barCount: Int = 4,
    color: Color = Color(0xFFC4B5FD),
    barWidth: Dp = 3.dp,
    maxHeight: Dp = 16.dp,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "equalizer_transition")

    val animValues = List(barCount) { index ->
        val duration = remember(index) { 380 + (index * 130) % 240 }
        val targetFraction = remember(index) { 0.45f + (index * 0.25f) % 0.55f }
        infiniteTransition.animateFloat(
            initialValue = 0.2f,
            targetValue = targetFraction,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = duration, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "bar_anim_$index",
        )
    }

    Row(
        modifier = modifier.height(maxHeight),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        animValues.forEach { anim ->
            Box(
                modifier = Modifier
                    .width(barWidth)
                    .fillMaxHeight(anim.value)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(color),
            )
        }
    }
}
