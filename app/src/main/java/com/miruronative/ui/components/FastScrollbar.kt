package com.miruronative.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Calculates target index in [totalItems] corresponding to a drag fraction in range [0f, 1f].
 */
fun calculateScrollTargetIndex(fraction: Float, totalItems: Int): Int {
    if (totalItems <= 0) return 0
    val clamped = fraction.coerceIn(0f, 1f)
    return ((totalItems - 1) * clamped).roundToInt().coerceIn(0, totalItems - 1)
}

/**
 * A draggable fast scrollbar with click-and-drag, track tap, and direct jump support.
 */
@Composable
fun FastScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    trackColor: Color = Color.White.copy(alpha = 0.16f),
    thumbColor: Color = MaterialTheme.colorScheme.primary,
) {
    val coroutineScope = rememberCoroutineScope()
    val layoutInfo = state.layoutInfo
    val totalItems = layoutInfo.totalItemsCount

    if (totalItems <= 0) return

    val visibleItemsCount = layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
    val firstVisibleIndex = state.firstVisibleItemIndex

    val maxScrollIndex = (totalItems - visibleItemsCount).coerceAtLeast(1)
    val progressFraction = (firstVisibleIndex.toFloat() / maxScrollIndex.toFloat()).coerceIn(0f, 1f)

    var trackHeightPx by remember { mutableFloatStateOf(1f) }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(16.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(8.dp)
                .onGloballyPositioned { trackHeightPx = it.size.height.toFloat().coerceAtLeast(1f) }
                .clip(RoundedCornerShape(4.dp))
                .background(trackColor)
                .pointerInput(totalItems) {
                    detectTapGestures { offset ->
                        val fraction = (offset.y / trackHeightPx).coerceIn(0f, 1f)
                        val targetIndex = calculateScrollTargetIndex(fraction, totalItems)
                        coroutineScope.launch { state.scrollToItem(targetIndex) }
                    }
                }
                .pointerInput(totalItems) {
                    var dragY = 0f
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            dragY = offset.y
                            val fraction = (dragY / trackHeightPx).coerceIn(0f, 1f)
                            val targetIndex = calculateScrollTargetIndex(fraction, totalItems)
                            coroutineScope.launch { state.scrollToItem(targetIndex) }
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            dragY += dragAmount
                            val fraction = (dragY / trackHeightPx).coerceIn(0f, 1f)
                            val targetIndex = calculateScrollTargetIndex(fraction, totalItems)
                            coroutineScope.launch { state.scrollToItem(targetIndex) }
                        },
                    )
                },
        ) {
            val thumbHeightDp = 44.dp
            val maxOffsetY = (trackHeightPx - 100f).coerceAtLeast(0f)
            val thumbOffsetYPx = maxOffsetY * progressFraction

            Box(
                modifier = Modifier
                    .offset { IntOffset(x = 0, y = thumbOffsetYPx.roundToInt()) }
                    .width(8.dp)
                    .height(thumbHeightDp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(thumbColor),
            )
        }
    }
}
