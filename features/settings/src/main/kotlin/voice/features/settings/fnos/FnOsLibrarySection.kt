package voice.features.settings.fnos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
  val paths by produceState<List<String>?>(null, account, attempt) {
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

  val registered = paths
  Column(
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(
        stringResource(StringsR.string.fnos_registered_folders),
        modifier = Modifier.weight(1f).semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
      )
      if (!registered.isNullOrEmpty()) {
        TextButton(onClick = onBrowse) {
          Icon(VoiceIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text(stringResource(StringsR.string.fnos_add_folder))
        }
      }
    }
    when {
      registered == null -> if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
      registered.isEmpty() -> {
        Text(
          stringResource(StringsR.string.fnos_library_empty),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onBrowse) { Text(stringResource(StringsR.string.fnos_add_folder)) }
      }
      else -> {
        Column {
          registered.forEach { path ->
            FolderRow(path, enabled = !removing, onRemove = { removeCandidate = path })
          }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        FlowRow(
          modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          itemVerticalAlignment = Alignment.CenterVertically,
        ) {
          Button(onClick = onShelf) {
            Icon(VoiceIcons.LibraryBooks, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(StringsR.string.fnos_open_shelf))
          }
          TextButton(onClick = folders::scan, enabled = !scanning && !removing) {
            Icon(VoiceIcons.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(if (scanning) StringsR.string.fnos_scanning else StringsR.string.fnos_rescan))
          }
        }
      }
    }
    error?.let {
      Text(stringResource(it), color = MaterialTheme.colorScheme.error)
      TextButton(onClick = { attempt++ }) { Text(stringResource(StringsR.string.common_error_generic_retry)) }
    }
  }
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

@Composable
private fun FolderRow(path: String, enabled: Boolean, onRemove: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Icon(VoiceIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    Column(modifier = Modifier.weight(1f)) {
      Text(path.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium)
      if ('/' in path) {
        Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    IconButton(enabled = enabled, onClick = onRemove) {
      Icon(VoiceIcons.Delete, stringResource(StringsR.string.fnos_remove_folder, path), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}
