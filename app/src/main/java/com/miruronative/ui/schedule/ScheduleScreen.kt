package com.miruronative.ui.schedule

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.miruronative.data.model.AiringSchedule
import com.miruronative.data.reminder.ReminderManager
import com.miruronative.ui.UiState
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import com.miruronative.ui.adaptive.focusHighlight
import com.miruronative.ui.components.ErrorBox
import com.miruronative.ui.components.LoadingBox
import com.miruronative.ui.components.PullRefreshContainer
import com.miruronative.ui.components.ScrollAwareTopBar
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFmt = DateTimeFormatter.ofPattern("h:mm a")
private val dateFmt = DateTimeFormatter.ofPattern("EEEE, MMM d")
private val dayFmt = DateTimeFormatter.ofPattern("EEE")
private val shortDateFmt = DateTimeFormatter.ofPattern("MMM d")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    onAnimeClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    vm: ScheduleViewModel = viewModel(),
) {
    val device = LocalAppDeviceProfile.current
    val state by vm.state.collectAsState()
    val isRefreshing by vm.isRefreshing.collectAsState()
    val scheduled by ReminderManager.scheduled.collectAsState()
    val context = LocalContext.current
    var pendingReminder by remember { mutableStateOf<AiringSchedule?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingReminder?.let(ReminderManager::toggle)
        pendingReminder = null
    }
    val toggleReminder: (AiringSchedule) -> Unit = { item ->
        val alreadyScheduled = ReminderManager.isScheduled(item)
        if (!alreadyScheduled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingReminder = item
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ReminderManager.toggle(item)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            ScrollAwareTopBar {
                TopAppBar(
                    title = { Text("Schedule", fontWeight = FontWeight.Black) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            DayTabs(vm.selectedDay, vm::selectDay)
            Text(
                text = LocalDate.now(ZoneId.systemDefault())
                    .plusDays(vm.selectedDay.toLong())
                    .format(dateFmt),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = device.pagePadding,
                    top = 8.dp,
                    bottom = 4.dp,
                ),
            )
            when (val s = state) {
                is UiState.Loading -> LoadingBox()
                is UiState.Error -> ErrorBox(s.message, vm::load)
                is UiState.Success -> PullRefreshContainer(
                    isRefreshing = isRefreshing,
                    onRefresh = vm::refresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    ScheduleList(s.data, scheduled, onAnimeClick, toggleReminder)
                }
            }
        }
    }
}

@Composable
private fun DayTabs(selected: Int, onSelect: (Int) -> Unit) {
    val device = LocalAppDeviceProfile.current
    val today = LocalDate.now(ZoneId.systemDefault())
    LazyRow(
        modifier = Modifier.focusGroup(),
        contentPadding = PaddingValues(horizontal = device.pagePadding, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(9) { index ->
            val offset = index - 2
            val date = today.plusDays(offset.toLong())
            val active = offset == selected
            Column(
                modifier = Modifier
                    .width(88.dp)
                    .height(68.dp)
                    .focusHighlight(RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface,
                    )
                    .border(
                        width = 1.dp,
                        color = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(14.dp),
                    )
                    .clickable { onSelect(offset) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = when (offset) {
                        0 -> "Today"
                        1 -> "Tomorrow"
                        -1 -> "Yesterday"
                        else -> date.format(dayFmt)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = date.format(shortDateFmt),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.72f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ScheduleList(
    items: List<AiringSchedule>,
    scheduled: Set<String>,
    onAnimeClick: (Int) -> Unit,
    onToggleReminder: (AiringSchedule) -> Unit,
) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing scheduled", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val zone = ZoneId.systemDefault()
    val groups = remember(items) { items.sortedBy(AiringSchedule::airingAt).groupBy(AiringSchedule::airingAt) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        groups.forEach { (airingAt, entries) ->
            item(key = airingAt) {
                TimeSlot(
                    airingAt = airingAt,
                    entries = entries,
                    zone = zone,
                    scheduled = scheduled,
                    onAnimeClick = onAnimeClick,
                    onToggleReminder = onToggleReminder,
                )
            }
        }
    }
}

@Composable
private fun TimeSlot(
    airingAt: Long,
    entries: List<AiringSchedule>,
    zone: ZoneId,
    scheduled: Set<String>,
    onAnimeClick: (Int) -> Unit,
    onToggleReminder: (AiringSchedule) -> Unit,
) {
    val device = LocalAppDeviceProfile.current
    val lineColor = MaterialTheme.colorScheme.outline
    val dotColor = if (airingAt * 1000L > System.currentTimeMillis()) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val x = device.pagePadding.toPx() + 8.dp.toPx()
                drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                drawCircle(dotColor, 5.dp.toPx(), Offset(x, 20.dp.toPx()))
                drawCircle(
                    color = androidx.compose.ui.graphics.Color.Black,
                    radius = 2.dp.toPx(),
                    center = Offset(x, 20.dp.toPx()),
                )
            },
    ) {
        Text(
            text = Instant.ofEpochSecond(airingAt).atZone(zone).format(timeFmt),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(
                start = device.pagePadding + 28.dp,
                top = 10.dp,
                bottom = 4.dp,
            ),
        )
        entries.forEach { item ->
            ScheduleCard(
                item = item,
                zone = zone,
                reminded = ReminderManager.id(item) in scheduled,
                onAnimeClick = onAnimeClick,
                onToggleReminder = onToggleReminder,
            )
        }
    }
}

@Composable
private fun ScheduleCard(
    item: AiringSchedule,
    zone: ZoneId,
    reminded: Boolean,
    onAnimeClick: (Int) -> Unit,
    onToggleReminder: (AiringSchedule) -> Unit,
) {
    val media = item.media ?: return
    val device = LocalAppDeviceProfile.current
    val upcoming = item.airingAt * 1000L > System.currentTimeMillis()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = device.pagePadding + 28.dp, end = device.pagePadding, top = 5.dp, bottom = 7.dp)
            .focusHighlight(RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .clickable { onAnimeClick(media.id) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = media.coverImage.best,
            contentDescription = media.title.preferred,
            modifier = Modifier
                .size(width = 62.dp, height = 82.dp)
                .clip(RoundedCornerShape(9.dp)),
            contentScale = ContentScale.Crop,
        )
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = media.title.preferred,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Episode ${item.episode}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                media.format?.let { ScheduleTag(it.replace('_', ' ')) }
                media.seasonYear?.let { ScheduleTag(it.toString()) }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = Instant.ofEpochSecond(item.airingAt).atZone(zone).format(timeFmt),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (upcoming) "UPCOMING" else "AIRED",
                style = MaterialTheme.typography.labelSmall,
                color = if (upcoming) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (upcoming) {
                IconButton(
                    onClick = { onToggleReminder(item) },
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(36.dp)
                        .focusHighlight(RoundedCornerShape(18.dp)),
                ) {
                    Icon(
                        imageVector = if (reminded) Icons.Filled.Notifications else Icons.Outlined.NotificationsNone,
                        contentDescription = if (reminded) "Remove reminder" else "Remind me",
                        tint = if (reminded) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScheduleTag(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}
