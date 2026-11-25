package voice.features.settings.fnos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import voice.core.common.rootGraphAs
import voice.core.online.FnOsAccount
import voice.core.online.FnOsAccountRepository
import voice.navigation.Origin
import voice.core.ui.icons.VoiceIcons
import voice.navigation.Destination
import voice.navigation.NavEntryProvider
import voice.navigation.Navigator
import java.io.IOException
import voice.core.strings.R as StringsR

@ContributesTo(AppScope::class)
interface FnOsSettingsProvider {
  val fnOsAccounts: FnOsAccountRepository
  val fnOsFolders: FnOsFolderActions

  @Provides
  @IntoSet
  fun fnOsSettingsNavEntryProvider(navigator: Navigator): NavEntryProvider<*> =
    NavEntryProvider<Destination.FnOsSettings> { key ->
      NavEntry(key) {
        val graph = rootGraphAs<FnOsSettingsProvider>()
        FnOsSettings(
          graph.fnOsAccounts,
          graph.fnOsFolders,
          onBrowse = { navigator.goTo(Destination.FnOsBrowse(key.origin)) },
          onShelf = { navigator.openShelf(key.origin) },
          onBack = navigator::goBack,
        )
      }
    }
}

internal fun Navigator.openShelf(origin: Origin) {
  setRoot(if (origin == Origin.Onboarding) Destination.OnboardingCompletion else Destination.BookOverview)
}

@Composable
private fun FnOsSettings(
  repository: FnOsAccountRepository,
  folders: FnOsFolderActions,
  onBrowse: () -> Unit,
  onShelf: () -> Unit,
  onBack: () -> Unit,
) {
  var loadAttempt by remember { mutableIntStateOf(0) }
  val accountState by produceState<AccountState>(AccountState.Loading, repository, loadAttempt) {
    value = AccountState.Loading
    try {
      repository.account.collect { value = AccountState.Loaded(it) }
    } catch (_: IOException) {
      value = AccountState.Unavailable
    }
  }
  val scope = rememberCoroutineScope()
  val actions = remember(repository, scope) { FnOsSettingsActions(repository, scope) }
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(StringsR.string.fnos_library_title)) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(VoiceIcons.ArrowBack, stringResource(StringsR.string.common_action_close))
          }
        },
      )
    },
  ) { insets ->
    Column(
      modifier = Modifier.fillMaxWidth().padding(insets).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      val savedAccount = when (val state = accountState) {
        AccountState.Loading -> {
          CircularProgressIndicator()
          return@Column
        }
        AccountState.Unavailable -> {
          Text(stringResource(StringsR.string.fnos_storage_error), color = MaterialTheme.colorScheme.error)
          Button(onClick = { loadAttempt++ }) { Text(stringResource(StringsR.string.common_error_generic_retry)) }
          return@Column
        }
        is AccountState.Loaded -> state.account
      }
      if (savedAccount == null) {
        FnOsLoginForm(enabled = !actions.working, onLogin = actions::login)
        if (actions.working && !actions.awaitingCode) {
          TextButton(onClick = actions::cancel) { Text(stringResource(StringsR.string.common_action_cancel)) }
        }
      } else {
        FnOsAccountRow(savedAccount, enabled = !actions.working, onLogout = actions::logout)
        key(savedAccount) {
          FnOsLibrarySection(savedAccount, folders, onBrowse, onShelf)
        }
      }
      if (actions.working && !actions.awaitingCode) CircularProgressIndicator()
      actions.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
    }
  }
  if (actions.awaitingCode) {
    FnOsCodeDialog(onConfirm = actions::submitCode, onDismiss = actions::cancel)
  }
}

private sealed interface AccountState {
  data object Loading : AccountState
  data object Unavailable : AccountState
  data class Loaded(val account: FnOsAccount?) : AccountState
}

@Composable
private fun FnOsAccountRow(account: FnOsAccount, enabled: Boolean, onLogout: () -> Unit) {
  Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
          Icon(VoiceIcons.Person, contentDescription = null)
        }
      }
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(account.username, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
          account.address,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      TextButton(onClick = onLogout, enabled = enabled) { Text(stringResource(StringsR.string.fnos_sign_out)) }
    }
  }
}

@Composable
private fun FnOsLoginForm(
  enabled: Boolean,
  onLogin: (String, String, String) -> Unit,
) {
  var address by rememberSaveable { mutableStateOf("") }
  var username by rememberSaveable { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  OutlinedTextField(
    value = address,
    onValueChange = { address = it },
    enabled = enabled,
    singleLine = true,
    label = { Text(stringResource(StringsR.string.fnos_address)) },
    placeholder = { Text(stringResource(StringsR.string.fnos_address_hint)) },
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    modifier = Modifier.fillMaxWidth(),
  )
  OutlinedTextField(
    value = username,
    onValueChange = { username = it },
    enabled = enabled,
    singleLine = true,
    label = { Text(stringResource(StringsR.string.fnos_username)) },
    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
    modifier = Modifier.fillMaxWidth(),
  )
  OutlinedTextField(
    value = password,
    onValueChange = { password = it },
    enabled = enabled,
    singleLine = true,
    label = { Text(stringResource(StringsR.string.fnos_password)) },
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    visualTransformation = PasswordVisualTransformation(),
    modifier = Modifier.fillMaxWidth(),
  )
  Button(
    enabled = enabled && address.isNotBlank() && username.isNotBlank() && password.isNotEmpty(),
    onClick = { onLogin(address.trim(), username.trim(), password) },
  ) { Text(stringResource(StringsR.string.fnos_sign_in)) }
}

@Composable
private fun FnOsCodeDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
  var code by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(StringsR.string.fnos_otp)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(StringsR.string.fnos_otp_prompt))
        OutlinedTextField(
          value = code,
          onValueChange = { code = it.filter { char -> char in '0'..'9' }.take(6) },
          singleLine = true,
          label = { Text(stringResource(StringsR.string.fnos_otp)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        )
      }
    },
    confirmButton = {
      TextButton(enabled = code.length == 6, onClick = { onConfirm(code) }) {
        Text(stringResource(StringsR.string.fnos_sign_in))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(StringsR.string.common_action_cancel)) }
    },
  )
}
