package voice.features.playbackScreen.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import voice.core.strings.R
import voice.core.ui.icons.VoiceIcons

@Composable
internal fun OverflowMenu(
  skipSilence: Boolean,
  onSkipSilenceClick: () -> Unit,
  onVolumeBoostClick: () -> Unit,
  onSkipIntroOutroClick: () -> Unit,
) {
  Box {
    var expanded by remember { mutableStateOf(false) }
    IconButton(
      onClick = {
        expanded = !expanded
      },
    ) {
      Icon(
        imageVector = VoiceIcons.MoreVert,
        contentDescription = stringResource(id = R.string.common_action_more),
      )
    }
    DropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
    ) {
      // The checkbox is display only; the row carries the toggle semantics, so
      // TalkBack announces "checked/unchecked" for the item itself instead of
      // reading a disabled checkbox. The menu stays open so the new state is
      // visible immediately (the view state may land a moment later for online
      // books) instead of closing on an apparently dead tap.
      DropdownMenuItem(
        onClick = onSkipSilenceClick,
        modifier = Modifier.toggleable(
          value = skipSilence,
          role = Role.Checkbox,
          onValueChange = { onSkipSilenceClick() },
        ),
        text = {
          Text(text = stringResource(id = R.string.playback_option_skip_silence))
        },
        trailingIcon = {
          Checkbox(
            checked = skipSilence,
            onCheckedChange = null,
            modifier = Modifier.clearAndSetSemantics {},
          )
        },
      )
      DropdownMenuItem(
        onClick = {
          expanded = false
          onVolumeBoostClick()
        },
        text = {
          Text(text = stringResource(id = R.string.playback_option_volume_boost))
        },
      )
      DropdownMenuItem(
        onClick = {
          expanded = false
          onSkipIntroOutroClick()
        },
        text = {
          Text(text = stringResource(id = R.string.playback_option_skip_intro_outro))
        },
      )
    }
  }
}
