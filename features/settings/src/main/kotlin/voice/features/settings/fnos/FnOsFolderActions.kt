package voice.features.settings.fnos

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first
import voice.core.online.FnOsAccount
import voice.core.online.FnOsAccountRepository
import voice.core.online.FnOsAuthError
import voice.core.online.FnOsAuthException
import voice.core.online.FnOsFile
import voice.core.online.FnOsLibrary
import voice.core.online.FnOsShareOwner
import voice.core.scanner.MediaScanTrigger

@Inject
class FnOsFolderActions(
  private val accounts: FnOsAccountRepository,
  private val library: FnOsLibrary,
  private val scanner: MediaScanTrigger,
) {
  internal data class Directory(
    val account: FnOsAccount,
    val entries: List<FnOsFile>,
    val owners: List<FnOsShareOwner> = emptyList(),
  )

  internal val scanning get() = scanner.scannerActive

  internal fun paths(account: FnOsAccount) = library.paths(account)

  internal suspend fun browse(path: String): Directory {
    val account = currentAccount()
    return Directory(account, accounts.listFiles(account, path).sortedWith(fileOrder))
  }

  internal suspend fun browseShares(owner: FnOsShareOwner?): Directory {
    val account = currentAccount()
    return if (owner == null) {
      val owners = accounts.listShareOwners(account).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
      Directory(account, emptyList(), owners)
    } else {
      Directory(account, accounts.listSharedFolders(account, owner).sortedWith(fileOrder))
    }
  }

  private suspend fun currentAccount() = accounts.account.first() ?: throw FnOsAuthException(FnOsAuthError.SESSION_EXPIRED)

  private val fileOrder = compareByDescending<FnOsFile> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

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
