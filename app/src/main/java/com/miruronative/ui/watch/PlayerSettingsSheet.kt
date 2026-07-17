package com.miruronative.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class PlayerContentScale(val label: String) {
    FIT("Fit"),
    CROP("Crop"),
    FILL("Fill"),
}

internal data class PlayerQualityOption(
    val label: String,
    val selected: Boolean,
    val onSelect: () -> Unit,
)

private val SheetColor = Color(0xFF1B1B1F)

/**
 * The player's settings, as a bottom sheet styled to match the shared player chrome: volume,
 * playback speed, quality, and playback toggles. Every section is capability-gated — pass `null`
 * or an empty list and it isn't drawn — so the same sheet serves the native and embed players
 * without either showing a control it can't honor.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PlayerSettingsSheet(
    onDismiss: () -> Unit,
    autoplay: Boolean,
    onAutoplayChange: (Boolean) -> Unit,
    speed: Float? = null,
    onSpeedChange: (Float) -> Unit = {},
    qualityOptions: List<PlayerQualityOption> = emptyList(),
    subtitleOptions: List<PlayerQualityOption> = emptyList(),
    audioOptions: List<PlayerQualityOption> = emptyList(),
    contentScale: PlayerContentScale? = null,
    onContentScaleChange: (PlayerContentScale) -> Unit = {},
    onCaptionAppearance: (() -> Unit)? = null,
    autoSkip: Boolean? = null,
    onAutoSkipChange: (Boolean) -> Unit = {},
    onEnterPip: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetColor,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 22.dp, end = 22.dp, bottom = 32.dp),
        ) {
            Text(
                "Settings",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(12.dp))

            SectionLabel("Volume")
            MediaVolumeSlider(modifier = Modifier.fillMaxWidth(), showPercentLabel = true)

            speed?.let { current ->
                SectionLabel("Playback Speed")
                SpeedSlider(current, onSpeedChange)
            }

            if (qualityOptions.isNotEmpty()) {
                SectionLabel("Quality")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    qualityOptions.forEach { option ->
                        ChoiceChip(option.label, option.selected, option.onSelect)
                    }
                }
            }

            if (audioOptions.size > 1) {
                SectionLabel("Audio")
                audioOptions.forEach { option ->
                    TrackRow(option.label, option.selected, option.onSelect)
                }
            }

            if (subtitleOptions.isNotEmpty()) {
                SectionLabel("Subtitles")
                subtitleOptions.forEach { option ->
                    TrackRow(option.label, option.selected, option.onSelect)
                }
            }

            contentScale?.let { current ->
                SectionLabel("Content Scale")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlayerContentScale.entries.forEach { scale ->
                        ChoiceChip(scale.label, scale == current) { onContentScaleChange(scale) }
                    }
                }
            }

            onCaptionAppearance?.let { open ->
                SectionLabel("Captions")
                ClickableRow("Caption appearance…", open)
            }

            SectionLabel("Playback")
            ToggleRow("Auto-play next episode", autoplay, onAutoplayChange)
            autoSkip?.let { ToggleRow("Auto-skip intro/outro", it, onAutoSkipChange) }
            onEnterPip?.let { ClickableRow("Picture-in-Picture", it) }
        }
    }
}

@Composable
private fun SpeedSlider(speed: Float, onSpeedChange: (Float) -> Unit) {
    val speeds = PlaybackSpeeds
    val index = remember(speed) {
        speeds.indexOfFirst { abs(it - speed) < 0.001f }
            .takeIf { it >= 0 }
            ?: speeds.indexOfFirst { it == 1f }.coerceAtLeast(0)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Speed, contentDescription = null, tint = Color.White)
        Slider(
            value = index.toFloat(),
            onValueChange = { onSpeedChange(speeds[it.roundToInt().coerceIn(0, speeds.lastIndex)]) },
            valueRange = 0f..speeds.lastIndex.toFloat(),
            steps = (speeds.size - 2).coerceAtLeast(0),
            colors = whiteSliderColors(),
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
        )
        Text(
            speed.formatPlaybackSpeed(),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.widthIn(min = 40.dp),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.primary else Color.White,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
private fun ClickableRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun whiteSliderColors() = SliderDefaults.colors(
    thumbColor = Color.White,
    activeTrackColor = Color.White,
    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
)
