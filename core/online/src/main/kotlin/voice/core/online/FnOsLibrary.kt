package voice.core.online

import android.net.Uri
import androidx.datastore.core.DataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import voice.core.data.BookId
import voice.core.data.folders.DocumentFileWithUri
import voice.core.data.folders.RemoteBookSources
import voice.core.data.isAudioFile
import java.security.MessageDigest

@Serializable
internal data class FnOsRoot(
  val authority: String,
  val path: String,
  val singleBook: Boolean,
  val lastBooks: List<String> = emptyList(),
  val excluded: Set<String> = emptySet(),
)

public class FnOsLibrary internal constructor(
  private val accounts: FnOsAccountRepository,
  private val roots: DataStore<List<FnOsRoot>>,
) : RemoteBookSources {

  public fun paths(account: FnOsAccount): Flow<List<String>> {
    val owner = authority(account)
    return roots.data.map { all -> all.filter { it.authority == owner }.map { it.path } }.distinctUntilChanged()
  }

  public suspend fun register(account: FnOsAccount, path: String, asSingleBook: Boolean = false) {
    require(validPath(path))
    if (accounts.account.first() != account) throw FnOsAuthException(FnOsAuthError.SESSION_EXPIRED)
    val root = FnOsRoot(authority(account), path, asSingleBook)
    roots.updateData { all ->
      val old = all.find { it.authority == root.authority && it.path == path }
      if (old?.singleBook == asSingleBook) all else all.filterNot { it == old } + root
    }
  }

  public suspend fun remove(account: FnOsAccount, path: String) {
    val owner = authority(account)
    roots.updateData { all -> all.filterNot { it.authority == owner && it.path == path } }
  }

  override suspend fun hasAnyBooks(): Boolean {
    val owner = accounts.account.first()?.let(::authority) ?: return false
    return roots.data.first().any { it.authority == owner }
  }

  override suspend fun removeBookRegistration(bookId: BookId): Boolean {
    val target = bookId.toUri()
    if (target.scheme != "fnos") return false
    val path = decodedPath(target) ?: return false
    var changed = false
    roots.updateData { all ->
      val updated = all.mapNotNull { root ->
        when {
          target.encodedAuthority != root.authority -> root
          root.path == path -> null
          !root.singleBook && path.substringBeforeLast('/', "") == root.path ->
            root.copy(lastBooks = root.lastBooks - path, excluded = root.excluded + path)
          else -> root
        }
      }
      changed = updated != all
      updated
    }
    return changed
  }

  override fun books(): Flow<List<DocumentFileWithUri>> =
    combine(roots.data, accounts.account) { all, account ->
      if (account == null) return@combine emptyList()
      val owner = authority(account)
      all.filter { it.authority == owner }.flatMap { expand(it, account) }.distinctBy { it.uri }
    }

  private suspend fun expand(root: FnOsRoot, account: FnOsAccount): List<DocumentFileWithUri> {
    fun book(path: String, file: FnOsFile? = null, failure: Exception? = null): DocumentFileWithUri {
      val uri = makeUri(root.authority, path)
      val document = FnOsDocumentFile(accounts, uri, file, scanning = true, failure = failure, registeredDirectory = root.singleBook)
      return DocumentFileWithUri(document, uri)
    }
    if (root.singleBook) return listOf(book(root.path))

    // Keep exact book IDs when the NAS is offline. An empty
    // successful listing, on the other hand, really does remove the root's former books.
    val files = try {
      accounts.listFiles(account, root.path)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return root.lastBooks.filterNot { it in root.excluded }.map { book(it, failure = e) }
    }

    val books = files.filter { isChild(root.path, it.path) && it.path !in root.excluded }
      .map { book(it.path, it) }
      .filter { it.documentFile.isDirectory() || it.documentFile.isAudioFile() }
    val latest = books.map { checkNotNull(decodedPath(it.uri)) }
    if (latest != root.lastBooks) {
      roots.updateData { all -> all.map { if (it == root) it.copy(lastBooks = latest) else it } }
    }
    return books
  }

  public companion object {
    private fun authority(account: FnOsAccount): String {
      val bytes = (account.address + "\u0000" + account.username).toByteArray()
      return MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
    }

    public fun uri(account: FnOsAccount, path: String): Uri {
      require(path.isEmpty() || validPath(path))
      return makeUri(authority(account), path)
    }

    public fun path(account: FnOsAccount, uri: Uri): String? =
      if (uri.scheme == "fnos" && uri.encodedAuthority == authority(account)) decodedPath(uri) else null

    internal fun isChild(parent: String, path: String): Boolean =
      validPath(path) && path.substringBeforeLast('/', "") == parent

    private fun validPath(path: String): Boolean =
      path.split('/').all { it.isNotEmpty() && it != "." && it != ".." && '\u0000' !in it }

    private fun decodedPath(uri: Uri): String? {
      if (uri.encodedQuery != null || uri.encodedFragment != null) return null
      val segments = uri.pathSegments
      if (segments.any { '/' in it }) return null
      return segments.joinToString("/").takeIf { it.isEmpty() || validPath(it) }
    }

    private fun makeUri(authority: String, path: String): Uri =
      Uri.Builder().scheme("fnos").authority(authority).path("/$path").build()
  }
}
