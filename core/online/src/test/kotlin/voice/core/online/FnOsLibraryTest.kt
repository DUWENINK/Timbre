package voice.core.online

import androidx.datastore.core.DataStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import voice.core.data.BookId
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class FnOsLibraryTest {
  private val reader = FnOsAccount("https://nas.example:5667", "reader")
  private val other = reader.copy(username = "other")
  private val active = MutableStateFlow<FnOsAccount?>(reader)
  private val accounts = mockk<FnOsAccountRepository> { every { account } returns active }
  private val store = object : DataStore<List<FnOsRoot>> {
    override val data = MutableStateFlow(emptyList<FnOsRoot>())
    override suspend fun updateData(transform: suspend (List<FnOsRoot>) -> List<FnOsRoot>): List<FnOsRoot> =
      transform(data.value).also { data.value = it }
  }
  private val library = FnOsLibrary(accounts, store)
  private val root = "vol1/1000/听书"
  private val folder = "$root/西游记"
  private val file = "$root/100% #?.mp3"

  @Test
  fun stableUrisRoundTripAndSeparateUsersAndServers() {
    val uri = FnOsLibrary.uri(reader, file)
    assertEquals(file, FnOsLibrary.path(reader, uri))
    assertEquals(uri, FnOsLibrary.uri(reader.copy(), file))
    assertNull(FnOsLibrary.path(other, uri))
    assertNull(FnOsLibrary.path(reader.copy(address = "https://other"), uri))
    assertNull(FnOsLibrary.path(reader, uri.buildUpon().encodedPath("/a%2Fb").build()))
    assertFailsWith<IllegalArgumentException> { FnOsLibrary.uri(reader, "folder/../other") }
  }

  @Test
  fun rootExpandsOfflineAndIsHiddenFromOtherAccounts() = runTest {
    val paths = listOf(folder, file)
    fun resource(path: String, directory: Boolean) = FnOsFile(
      name = path.substringAfterLast('/'), path = path, isDirectory = directory, size = 15L, modifiedAt = 1000L,
    )
    val listing = listOf(resource(folder, true), resource(file, false), resource("$root/cover.jpg", false))
    coEvery { accounts.listFiles(reader, root) } returns listing
    library.register(reader, root)
    library.register(reader, root)
    assertEquals(listOf(root), library.paths(reader).first())
    val expected = paths.map { FnOsLibrary.uri(reader, it) }
    assertEquals(expected, library.books().first().map { it.uri })
    assertEquals(paths, store.data.value.single().lastBooks)
    coEvery { accounts.listFiles(reader, root) } throws IOException("offline")
    assertEquals(expected, library.books().first().map { it.uri })
    active.value = other
    assertEquals(emptyList(), library.paths(other).first())
    assertEquals(emptyList(), library.books().first())
    assertFalse(library.hasAnyBooks())
    active.value = null
    assertEquals(emptyList(), library.books().first())
    coVerify(exactly = 0) { val _ = accounts.listFiles(other, any()) }
    assertFalse(library.removeBookRegistration(BookId(FnOsLibrary.uri(other, file))))
    assertTrue(library.removeBookRegistration(BookId(expected.last())))
    active.value = reader
    coEvery { accounts.listFiles(reader, root) } returns listing
    assertEquals(expected.take(1), library.books().first().map { it.uri })
    coEvery { accounts.listFiles(reader, root) } returns emptyList()
    assertEquals(emptyList(), library.books().first())
    library.remove(reader, root)
    assertFalse(library.hasAnyBooks())
  }

  @Test
  fun sharedLibraryScansTheRecipientPathAndKeepsItsBookIdWhenAccessIsRevoked() = runTest {
    val shared = "vol01/1010/共享书架"
    val bookPath = "$shared/西游记"
    val chapterName = "01 #序章%20.mp3"
    val chapterPath = "$bookPath/$chapterName"
    val bookFile = FnOsFile("西游记", bookPath, true, 0, 0)
    val chapter = FnOsFile(chapterName, chapterPath, false, 1234, 1000)
    coEvery { accounts.listFiles(reader, shared) } returns listOf(bookFile)
    coEvery { accounts.listFiles(reader, bookPath) } returns listOf(chapter)

    library.register(reader, shared)
    val book = library.books().first().single()
    assertEquals(bookPath, FnOsLibrary.path(reader, book.uri))
    val chapterUri = book.documentFile.children().single().uri
    assertEquals(chapterPath, FnOsLibrary.path(reader, chapterUri))
    assertNull(FnOsLibrary.path(other, chapterUri))

    val denied = FnOsAuthException(FnOsAuthError.REJECTED)
    coEvery { accounts.listFiles(reader, shared) } throws denied
    val unavailable = library.books().first().single()
    assertEquals(book.uri, unavailable.uri)
    assertEquals(denied, unavailable.documentFile.error)
    assertFailsWith<FnOsAuthException> { unavailable.documentFile.children() }
    active.value = other
    assertTrue(library.books().first().isEmpty())
    active.value = reader
    coEvery { accounts.listFiles(reader, shared) } returns listOf(bookFile)
    assertEquals(chapterUri, library.books().first().single().documentFile.children().single().uri)
    coVerify(exactly = 0) { val _ = accounts.listFiles(reader, "vol2/1010/媒体") }
  }

  @Test
  fun singleBookOpensItsRegisteredDirectoryWithoutRequiringAccessToParent() = runTest {
    coEvery { accounts.listFiles(reader, root) } returns listOf(
      FnOsFile(name = "01.mp3", path = "$root/01.mp3", isDirectory = false, size = 42L, modifiedAt = 1000L),
    )
    library.register(reader, root, asSingleBook = true)
    val book = library.books().first().single().documentFile
    assertTrue(book.isDirectory())
    assertEquals("听书", book.name())
    assertEquals("01.mp3", book.children().single().name())
    assertTrue(FnOsSchemeHandler(accounts, library).create(book.uri).isDirectory())
    coVerify(exactly = 0) { val _ = accounts.listFiles(reader, "vol1/1000") }
    active.value = other
    assertFailsWith<FnOsAuthException> { book.children() }
    coVerify(exactly = 0) { val _ = accounts.listFiles(other, any()) }
    assertTrue(library.removeBookRegistration(BookId(book.uri)))
    assertFalse(library.hasAnyBooks())
  }
}
