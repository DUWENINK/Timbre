package voice.core.online

import android.net.Uri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import voice.core.documentfile.CachedDocumentFile
import voice.core.documentfile.DocumentFileSchemeHandler
import java.io.FileNotFoundException

internal class FnOsDocumentFile(
  private val accounts: FnOsAccountRepository,
  override val uri: Uri,
  known: FnOsFile? = null,
  private val scanning: Boolean = false,
  failure: Exception? = null,
  private val registeredDirectory: Boolean = false,
  private val library: FnOsLibrary? = null,
) : CachedDocumentFile {
  private var metadata: FnOsFile? = known
  override var error: Throwable? = failure
    private set

  private suspend fun <T> read(action: suspend (FnOsAccount, String) -> T): T? {
    error?.let { if (scanning) throw it else return null }
    return try {
      val account = accounts.account.first() ?: throw FnOsAuthException(FnOsAuthError.SESSION_EXPIRED)
      val path = FnOsLibrary.path(account, uri) ?: throw FnOsAuthException(FnOsAuthError.SESSION_EXPIRED)
      action(account, path)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      error = e
      // A partial recursive walk must not overwrite the book with only the chapters
      // encountered before the failed listing. The scanner records an unreachable book.
      if (scanning) throw e
      null
    }
  }

  private suspend fun properties(): FnOsFile? = metadata ?: read { account, path ->
    if (library != null && path in library.paths(account).first()) {
      return@read FnOsFile(path.substringAfterLast('/'), path, true, 0, 0).also { metadata = it }
    }
    accounts.listFiles(account, path.substringBeforeLast('/', "")).firstOrNull { it.path == path }
      ?.also { metadata = it } ?: throw FileNotFoundException(path)
  }

  override suspend fun children(): List<CachedDocumentFile> = read { account, path ->
    accounts.listFiles(account, path).filter { FnOsLibrary.isChild(path, it.path) }.map { entry ->
      FnOsDocumentFile(accounts, FnOsLibrary.uri(account, entry.path), entry, scanning)
    }
  }.orEmpty()

  override suspend fun name(): String? = if (registeredDirectory) uri.lastPathSegment else properties()?.name
  override suspend fun isDirectory(): Boolean = registeredDirectory || properties()?.isDirectory == true
  override suspend fun isFile(): Boolean = !registeredDirectory && properties()?.isDirectory == false
  override suspend fun length(): Long = properties()?.size ?: 0
  override suspend fun lastModified(): Long = properties()?.modifiedAt ?: 0
}

@ContributesIntoSet(AppScope::class)
@Inject
public class FnOsSchemeHandler internal constructor(
  private val accounts: FnOsAccountRepository,
  private val library: FnOsLibrary,
) : DocumentFileSchemeHandler {
  override fun supports(uri: Uri): Boolean = uri.scheme == "fnos"
  override fun create(uri: Uri): CachedDocumentFile = FnOsDocumentFile(accounts, uri, library = library)
}
