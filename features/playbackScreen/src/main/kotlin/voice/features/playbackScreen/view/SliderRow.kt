package voice.features.playbackScreen.view

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import voice.core.ui.formatTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import voice.core.strings.R as StringsR

@Composable
internal fun SliderRow(
  duration: Duration,
  playedTime: Duration,
  onSeek: (Duration) -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    var localValue by remember { mutableFloatStateOf(0F) }
    val interactionSource = remember { MutableInteractionSource() }
    val dragging by interactionSource.collectIsDraggedAsState()
    val progressLabel = stringResource(StringsR.string.a11y_slider_playback_progress)
    val forwardLabel = stringResource(StringsR.string.a11y_action_forward)
    val rewindLabel = stringResource(StringsR.string.a11y_action_rewind)
    val playedLabel = formatTime(
      timeMs = if (dragging) {
        (duration * localValue.toDouble()).inWholeMilliseconds
      } else {
        playedTime.inWholeMilliseconds
      },
      durationMs = duration.inWholeMilliseconds,
    )
    val stateLabel = stringResource(
      StringsR.string.a11y_playback_state,
      playedLabel,
      formatTime(
        timeMs = duration.inWholeMilliseconds,
        durationMs = duration.inWholeMilliseconds,
      ),
    )
    Text(
      text = playedLabel,
    )
    Slider(
      modifier = Modifier
        .weight(1F)
        .padding(horizontal = 8.dp)
        .semantics {
          contentDescription = progressLabel
          stateDescription = stateLabel
          // TalkBack-only shortcuts: seeking by dragging a slider to the
          // second is impractical for a blind user, so expose ±30s actions.
          customActions = listOf(
            CustomAccessibilityAction(forwardLabel) {
              onSeek((playedTime + 30.seconds).coerceAtMost(duration))
              true
            },
            CustomAccessibilityAction(rewindLabel) {
              onSeek((playedTime - 30.seconds).coerceAtLeast(Duration.ZERO))
              true
            },
          )
        },
      interactionSource = interactionSource,
      value = if (dragging) {
        localValue
      } else {
        (playedTime / duration).toFloat()
          .coerceIn(0F, 1F)
      },
      onValueChange = {
        localValue = it
      },
      onValueChangeFinished = {
        onSeek(duration * localValue.toDouble())
      },
    )
    Text(
      text = formatTime(
        timeMs = duration.inWholeMilliseconds,
        durationMs = duration.inWholeMilliseconds,
      ),
    )
  }
}

/**
 * 直播（HLS 滑动窗口）没有可拖动的时间轴：位置是窗口内偏移，窗口每滚一次
 * 就会回退，时长只是窗口长度。这里只显示红点 + 「直播中」，不画进度条。
 * 点播书籍（含点播型 HLS）继续走 [SliderRow]，行为完全不变。
 */
@Composable
internal fun LiveRow() {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Center,
  ) {
    Box(
      modifier = Modifier
        .size(8.dp)
        .background(MaterialTheme.colorScheme.error, CircleShape),
    )
    Spacer(modifier = Modifier.size(8.dp))
    Text(
      text = stringResource(StringsR.string.playback_live),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
