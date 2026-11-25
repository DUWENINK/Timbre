package voice.features.settings.fnos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import voice.core.common.rootGraphAs
import voice.core.online.FnOsAuthError
import voice.core.online.FnOsAuthException
import voice.core.ui.icons.VoiceIcons
import voice.navigation.Destination
import voice.navigation.NavEntryProvider
import voice.navigation.Navigator
import voice.core.strings.R as StringsR

@ContributesTo(AppScope::class)
interface FnOsBrowseProvider {
  @Provides
  @IntoSet
  fun fnOsBrowseNavEntryProvider(navigator: Navigator): NavEntryProvider<*> =
    NavEntryProvider<Destination.FnOsBrowse> { key ->
      NavEntry(key) {
        FnOsBrowse(
          path = key.path,
          folders = rootGraphAs<FnOsSettingsProvider>().fnOsFolders,
          onOpen = { navigator.goTo(key.copy(path = it)) },
          onImported = { navigator.openShelf(key.origin) },
          onBack = navigator::goBack,
        )
      }
    }
}

private sealed interface Listing {
  data object Loading : Listing
  data class Ready(val directory: FnOsFolderActions.Directory) : Listing
  data class Failed(val error: Int) : Listing
}

@Composable
private fun FnOsBrowse(
  path: String,
  folders: FnOsFolderActions,
  onOpen: (String) -> Unit,
  onImported: () -> Unit,
  onBack: () -> Unit,
) {
  var attempt by remember { mutableIntStateOf(0) }
  val listing by produceState<Listing>(Listing.Loading, path, attempt) {
    value = Listing.Loading
    value = try {
      Listing.Ready(folders.browse(path))
    } catch (e: Exception) {
      Listing.Failed(e.folderError())
    }
  }
  val scope = rememberCoroutineScope()
  var saving by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<Int?>(null) }

  Scaffold(topBar = {
    TopAppBar(
      title = { Text(stringResource(StringsR.string.fnos_choose_folder)) },
      navigationIcon = {
        IconButton(onClick = onBack) {
          Icon(VoiceIcons.ArrowBack, stringResource(StringsR.string.common_action_close))
        }
      },
    )
  }) { padding ->
    LazyColumn(contentPadding = padding) {
      item {
        Text(
          path.ifEmpty { stringResource(StringsR.string.fnos_library_title) },
          modifier = Modifier.padding(16.dp),
          style = MaterialTheme.typography.titleMedium,
        )
      }
      when (val result = listing) {
        Listing.Loading -> item { CircularProgressIndicator(modifier = Modifier.padding(16.dp)) }
        is Listing.Failed -> item {
          Column(Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(result.error), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { attempt++ }) { Text(stringResource(StringsR.string.common_error_generic_retry)) }
          }
        }
        is Listing.Ready -> {
          val directory = result.directory
          if (path.isNotEmpty()) {
            item {
              fun addFolder(asSingleBook: Boolean) {
                if (saving) return
                saving = true
                error = null
                scope.launch {
                  try {
                    folders.add(directory.account, path, asSingleBook)
                    onImported()
                  } catch (e: Exception) {
                    error = e.folderError()
                  } finally {
                    saving = false
                  }
                }
              }
              Column(Modifier.padding(horizontal = 16.dp)) {
                Button(enabled = !saving, onClick = { addFolder(true) }) {
                  Text(stringResource(StringsR.string.webdav_add_as_book))
                }
                TextButton(enabled = !saving, onClick = { addFolder(false) }) {
                  Text(stringResource(StringsR.string.webdav_add_as_library_root))
                }
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
              }
            }
          }
          if (directory.entries.isEmpty()) {
            item { Text(stringResource(StringsR.string.fnos_folder_empty), modifier = Modifier.padding(16.dp)) }
          }
          items(directory.entries, key = { it.path }) { entry ->
            if (entry.isDirectory) {
              ListItem(
                modifier = Modifier.fillMaxWidth().clickable(enabled = !saving) { onOpen(entry.path) },
                leadingContent = { Icon(VoiceIcons.Folder, contentDescription = null) },
              ) { Text(entry.name) }
            } else {
              ListItem { Text(entry.name) }
            }
          }
        }
      }
    }
  }
}

internal fun Exception.folderError(): Int = when (this) {
  is CancellationException -> throw this
  is FnOsAuthException -> if (reason == FnOsAuthError.REJECTED) StringsR.string.fnos_folder_access_error else reason.messageResource()
  else -> StringsR.string.fnos_folder_error
}
