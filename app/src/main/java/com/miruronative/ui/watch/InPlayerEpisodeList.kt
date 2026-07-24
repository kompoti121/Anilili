package com.miruronative.ui.watch

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.miruronative.data.model.EpisodeItem
import com.miruronative.ui.adaptive.focusHighlight
import com.miruronative.ui.components.EpisodeBlockPicker
import com.miruronative.ui.components.FastScrollbar
import com.miruronative.ui.components.blockIndexContaining
import com.miruronative.ui.components.episodeBlocks

@Composable
internal fun InPlayerEpisodeDrawer(
    episodes: List<EpisodeItem>,
    currentIndex: Int,
    artworkUrl: String?,
    onSelectEpisode: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)

    var chosenBlockIndex by remember(episodes) { mutableStateOf<Int?>(null) }
    val blocks = remember(episodes) { episodeBlocks(episodes) }
    val currentEpisode = episodes.getOrNull(currentIndex)
    val blockIndex = (chosenBlockIndex ?: blockIndexContaining(blocks, currentEpisode?.number))
        .coerceIn(0, (blocks.size - 1).coerceAtLeast(0))
    val shownEpisodes = blocks.getOrNull(blockIndex)?.episodes ?: episodes

    val indexByPipeId = remember(episodes) {
        episodes.withIndex().associate { (index, episode) -> episode.pipeId to index }
    }

    val listState = rememberLazyListState()
    val initialFocusRequester = remember { FocusRequester() }

    LaunchedEffect(blockIndex, currentIndex) {
        val targetInShown = shownEpisodes.indexOfFirst { indexByPipeId[it.pipeId] == currentIndex }
        if (targetInShown >= 0) {
            listState.scrollToItem(targetInShown)
        }
        runCatching { initialFocusRequester.requestFocus() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(360.dp)
                .clickable(enabled = false) {}, // Scrim protection
            color = Color(0xF012131A),
            shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .focusGroup(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Episodes",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close episodes",
                            tint = Color.White.copy(alpha = 0.85f),
                        )
                    }
                }

                if (blocks.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    EpisodeBlockPicker(
                        blocks = blocks,
                        selectedIndex = blockIndex,
                        onSelect = { chosenBlockIndex = it },
                    )
                }

                Spacer(Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(shownEpisodes) { _, episode ->
                            val globalIndex = indexByPipeId[episode.pipeId] ?: -1
                            val isCurrent = globalIndex == currentIndex
                            val itemFocusRequester = if (isCurrent) initialFocusRequester else remember { FocusRequester() }

                            InPlayerEpisodeItemRow(
                                episode = episode,
                                artworkUrl = artworkUrl,
                                isCurrent = isCurrent,
                                focusRequester = itemFocusRequester,
                                onClick = {
                                    if (globalIndex >= 0) {
                                        onSelectEpisode(globalIndex)
                                        onDismiss()
                                    }
                                },
                            )
                        }
                    }

                    FastScrollbar(
                        state = listState,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun InPlayerEpisodeItemRow(
    episode: EpisodeItem,
    artworkUrl: String?,
    isCurrent: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = if (isCurrent) {
        Color(0xFF2E1C4E)
    } else {
        Color(0xFF1E1F2B)
    }
    val borderColor = if (isCurrent) Color(0xFF8B5CF6) else Color.Transparent

    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                val keyCode = event.nativeKeyEvent.keyCode
                val activate = keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                    keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                if (!activate) {
                    false
                } else {
                    if (event.type == KeyEventType.KeyUp) onClick()
                    true
                }
            }
            .focusHighlight(RoundedCornerShape(10.dp), focusedScale = 1.03f)
            .clip(RoundedCornerShape(10.dp))
            .background(backgroundColor)
            .border(1.5.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = episode.displayNumber,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (isCurrent) Color(0xFFC4B5FD) else Color.White.copy(alpha = 0.7f),
            modifier = Modifier.width(28.dp),
        )

        Box(
            modifier = Modifier
                .width(80.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = episode.image ?: artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (isCurrent) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Currently playing",
                        tint = Color(0xFFC4B5FD),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = episode.distinctTitle ?: "Episode ${episode.displayNumber}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (episode.filler) {
                Text(
                    text = "Filler",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}
