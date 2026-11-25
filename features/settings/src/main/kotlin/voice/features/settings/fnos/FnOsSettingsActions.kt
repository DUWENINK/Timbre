package voice.features.settings.fnos

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import voice.core.online.FnOsAccountRepository
import voice.core.online.FnOsAuthError
import voice.core.online.FnOsAuthException
import voice.core.strings.R as StringsR

internal class FnOsSettingsActions(
  private val repository: FnOsAccountRepository,
  private val scope: CoroutineScope,
) {
  private var operation by mutableStateOf<Job?>(null)
  private var pendingCode by mutableStateOf<CompletableDeferred<String>?>(null)
  val working get() = operation != null
  val awaitingCode get() = pendingCode != null

  var error by mutableStateOf<Int?>(null)
    private set

  fun login(address: String, username: String, password: String) = runAction {
    repository.login(address, username, password, ::requestCode)
  }

  fun logout() = runAction {
    repository.logout()
  }

  fun cancel() {
    operation?.cancel()
  }

  fun submitCode(code: String) {
    pendingCode?.complete(code)
  }

  private suspend fun requestCode() = withContext(Dispatchers.Main.immediate) {
    val request = CompletableDeferred<String>()
    pendingCode = request
    try {
      request.await()
    } finally {
      pendingCode = null
    }
  }

  private fun runAction(action: suspend () -> Unit) {
    if (working) return
    error = null
    val job = scope.launch(start = CoroutineStart.LAZY) {
      try {
        action()
      } catch (e: CancellationException) {
        throw e
      } catch (e: FnOsAuthException) {
        error = e.reason.messageResource()
      } catch (_: Exception) {
        error = StringsR.string.fnos_storage_error
      }
    }
    operation = job
    job.invokeOnCompletion { operation = null }
    job.start()
  }
}

internal fun FnOsAuthError.messageResource(): Int = when (this) {
  FnOsAuthError.INVALID_ADDRESS -> StringsR.string.fnos_invalid_address
  FnOsAuthError.REJECTED -> StringsR.string.fnos_rejected
  FnOsAuthError.INVALID_OTP -> StringsR.string.fnos_invalid_otp
  FnOsAuthError.SETUP_REQUIRED -> StringsR.string.fnos_setup_required
  FnOsAuthError.CONNECTION -> StringsR.string.fnos_connection_error
  FnOsAuthError.PROTOCOL -> StringsR.string.fnos_protocol_error
  FnOsAuthError.SESSION_EXPIRED -> StringsR.string.fnos_expired
}
