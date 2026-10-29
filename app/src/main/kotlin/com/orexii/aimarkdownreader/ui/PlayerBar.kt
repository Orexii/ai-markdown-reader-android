package com.orexii.aimarkdownreader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orexii.aimarkdownreader.core.Speeds
import com.orexii.aimarkdownreader.core.player.PlayerState
import kotlinx.coroutines.delay
import kotlin.math.max

/** Time as the extension shows it: m:ss, or h:mm:ss. */
fun clock(seconds: Double): String {
    val total = max(0.0, seconds).toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * The compact player, like the extension's Read Aloud view: the section being read, a progress bar with
 * chapter marks, the time left, and the buttons. Settings open in a sheet.
 */
@Composable
fun PlayerBar(
    state: PlayerState,
    status: String?,
    expandedSpeeds: Boolean,
    skipBack: Int,
    skipForward: Int,
    onCommand: (String) -> Unit,
    onSeek: (Double) -> Unit,
    onRate: (Double) -> Unit,
    onExpandSpeeds: (Boolean) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    /** Play was pressed but reading waits (for a call to end); the button cancels it. */
    pending: Boolean = false,
) {
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.noticeId) {
        if (state.noticeId == 0) return@LaunchedEffect
        notice = state.notice
        delay(2500)
        notice = null
    }
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 3.dp, shadowElevation = 6.dp) {
        // On wide screens (tablets, an unfolded Fold) the controls stay together in the middle.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp)) {
            Text(
                text = state.section.ifEmpty { state.title },
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Box(Modifier.fillMaxWidth()) {
                val total = max(1.0, state.total)
                Chapters(state, total, Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 10.dp))
                Slider(
                    value = scrubbing ?: (state.elapsed / total).toFloat().coerceIn(0f, 1f),
                    onValueChange = { scrubbing = it },
                    onValueChangeFinished = {
                        scrubbing?.let { onSeek(it * total) }
                        scrubbing = null
                    },
                    enabled = state.loaded,
                    modifier = Modifier.fillMaxWidth().height(36.dp).testTag("seek").semantics { contentDescription = "Position" },
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                val elapsed = scrubbing?.let { it * max(1.0, state.total) } ?: state.elapsed
                Text(clock(elapsed), style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("elapsed"))
                Text(
                    "${if (state.estimated) "~" else ""}${clock((state.total - elapsed) / state.rate)} left",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                SpeedButton(state.rate, expandedSpeeds, onRate, onExpandSpeeds)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ControlButton(PlayerIcons.Previous, "Previous paragraph", state.loaded) { onCommand("previousParagraph") }
                    SkipButton(PlayerIcons.Back, skipBack, "Back $skipBack seconds", state.loaded) { onCommand("skipBack") }
                    FilledIconButton(
                        onClick = { onCommand("togglePlay") },
                        enabled = state.loaded,
                        modifier = Modifier.size(56.dp).testTag("play").semantics {
                            contentDescription = when {
                                pending -> "Cancel"
                                state.playing -> "Pause"
                                else -> "Play"
                            }
                        },
                    ) {
                        if (pending || (state.playing && (state.waiting || !state.engineReady))) {
                            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Icon(if (state.playing) PlayerIcons.Pause else PlayerIcons.Play, contentDescription = null, modifier = Modifier.size(32.dp))
                        }
                    }
                    SkipButton(PlayerIcons.Forward, skipForward, "Forward $skipForward seconds", state.loaded) { onCommand("skipForward") }
                    ControlButton(PlayerIcons.Next, "Next paragraph", state.loaded) { onCommand("nextParagraph") }
                }
                IconButton(onClick = onSettings, modifier = Modifier.testTag("settings")) {
                    Icon(PlayerIcons.Settings, contentDescription = "Reading settings")
                }
            }
            val line = notice ?: status
            if (!line.isNullOrEmpty()) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).testTag("status"),
                )
            }
        }
        }
    }
}

@Composable
private fun Chapters(state: PlayerState, total: Double, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        for ((start, _) in state.chapters) {
            if (start <= 0) continue
            val x = (start / total).toFloat() * size.width
            drawLine(color, Offset(x, size.height / 2 - 7.dp.toPx()), Offset(x, size.height / 2 + 7.dp.toPx()), strokeWidth = 2.dp.toPx())
        }
    }
}

@Composable
private fun ControlButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = label }) {
        Icon(icon, contentDescription = null)
    }
}

@Composable
private fun SkipButton(icon: ImageVector, seconds: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = label }) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(30.dp))
            Text("$seconds", fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

/** The speed menu: 0.5×, 1×, 1.5×, 2× (or every quarter step), then More speeds… or Fewer speeds. */
@Composable
private fun SpeedButton(rate: Double, expanded: Boolean, onRate: (Double) -> Unit, onExpand: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.testTag("speed").semantics { contentDescription = "Speed ${Speeds.format(rate)}" }) {
            Text(Speeds.format(rate), style = MaterialTheme.typography.titleSmall)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (speed in Speeds.choices(expanded, rate)) {
                DropdownMenuItem(
                    text = { Text(Speeds.format(speed), fontWeight = if (speed == rate) FontWeight.Bold else FontWeight.Normal) },
                    onClick = {
                        onRate(speed)
                        open = false
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (expanded) "Fewer speeds" else "More speeds…") },
                onClick = { onExpand(!expanded) },
            )
        }
    }
}
