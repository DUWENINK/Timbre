package voice.features.settings.fnos

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first
import voice.core.online.FnOsAccount
import voice.core.online.FnOsAccountRepository
import voice.core.online.FnOsAuthError
import voice.core.online.FnOsAuthException
import voice.core.online.FnOsFile
import voice.core.online.FnOsLibrary
import voice.core.scanner.MediaScanTrigger

@Inject
class FnOsFolderActions(
  private val accounts: FnOsAccountRepository,
  private val library: FnOsLibrary,
  private val scanner: MediaScanTrigger,
) {
  internal data class Directory(val account: FnOsAccount, val entries: List<FnOsFile>)

  internal val scanning get() = scanner.scannerActive

  internal fun paths(account: FnOsAccount) = library.paths(account)

  internal suspend fun browse(path: String): Directory {
    val account = accounts.account.first() ?: throw FnOsAuthException(FnOsAuthError.SESSION_EXPIRED)
    val entries = accounts.listFiles(account, path)
      .sortedWith(compareByDescending<FnOsFile> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    return Directory(account, entries)
  }

  internal suspend fun add(account: FnOsAccount, path: String, asSingleBook: Boolean) {
    library.register(account, path, asSingleBook)
    scan()
  }

  internal suspend fun remove(account: FnOsAccount, path: String) {
    library.remove(account, path)
    scan()
  }

  internal fun scan() = scanner.scan(restartIfScanning = true)
}
