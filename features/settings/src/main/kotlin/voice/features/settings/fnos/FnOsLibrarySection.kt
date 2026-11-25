package voice.features.settings.fnos

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import voice.core.online.FnOsAccount
import voice.core.ui.icons.VoiceIcons
import voice.core.strings.R as StringsR

@Composable
internal fun FnOsLibrarySection(
  account: FnOsAccount,
  folders: FnOsFolderActions,
  onBrowse: () -> Unit,
  onShelf: () -> Unit,
) {
  var attempt by remember(account) { mutableIntStateOf(0) }
  var error by remember(account, attempt) { mutableStateOf<Int?>(null) }
  val paths by produceState(emptyList<String>(), account, attempt) {
    try {
      folders.paths(account).collect { value = it }
    } catch (e: Exception) {
      error = e.folderError()
    }
  }
  val scanning by folders.scanning.collectAsState(initial = false)
  var removeCandidate by remember(account) { mutableStateOf<String?>(null) }
  var removing by remember(account) { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  HorizontalDivider()
  Text(stringResource(StringsR.string.fnos_registered_folders), style = MaterialTheme.typography.titleMedium)
  paths.forEach { path ->
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(path, Modifier.weight(1f))
      IconButton(enabled = !removing, onClick = { removeCandidate = path }) {
        Icon(VoiceIcons.Delete, stringResource(StringsR.string.fnos_remove_folder, path))
      }
    }
  }
  Button(onClick = onBrowse) { Text(stringResource(StringsR.string.fnos_choose_folder)) }
  if (paths.isNotEmpty()) {
    Row {
      TextButton(onClick = folders::scan, enabled = !scanning && !removing) {
        Text(stringResource(if (scanning) StringsR.string.fnos_scanning else StringsR.string.fnos_rescan))
      }
      TextButton(onClick = onShelf) { Text(stringResource(StringsR.string.fnos_open_shelf)) }
    }
  }
  error?.let {
    Text(stringResource(it), color = MaterialTheme.colorScheme.error)
    TextButton(onClick = { attempt++ }) { Text(stringResource(StringsR.string.common_error_generic_retry)) }
  }
  HorizontalDivider()
  removeCandidate?.let { path ->
    AlertDialog(
      onDismissRequest = { removeCandidate = null },
      title = { Text(stringResource(StringsR.string.fnos_remove_folder, path)) },
      text = { Text(stringResource(StringsR.string.fnos_remove_message)) },
      dismissButton = {
        TextButton(onClick = { removeCandidate = null }) { Text(stringResource(StringsR.string.common_action_cancel)) }
      },
      confirmButton = {
        TextButton(onClick = {
          removeCandidate = null
          removing = true
          error = null
          scope.launch {
            try {
              folders.remove(account, path)
            } catch (e: Exception) {
              error = e.folderError()
            } finally {
              removing = false
            }
          }
        }) { Text(stringResource(StringsR.string.common_action_remove)) }
      },
    )
  }
}
