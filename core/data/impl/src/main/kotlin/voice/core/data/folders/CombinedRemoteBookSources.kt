package voice.core.data.folders

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import voice.core.data.BookId

@ContributesBinding(AppScope::class)
@Inject
public class CombinedRemoteBookSources internal constructor(
  private val providers: Set<@JvmSuppressWildcards RemoteBookSources>,
) : RemoteBookSources {

  override fun books(): Flow<List<DocumentFileWithUri>> =
    combine(providers.map { it.books() }) { books -> books.flatMap { it }.distinctBy { it.uri } }

  override suspend fun hasAnyBooks(): Boolean = providers.any { it.hasAnyBooks() }

  override suspend fun removeBookRegistration(bookId: BookId): Boolean =
    providers.any { it.removeBookRegistration(bookId) }
}
