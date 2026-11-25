package voice.core.online

import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(AndroidJUnit4::class)
class FnOsDataSourceFactoryTest {
  private val nas = MockWebServer()
  private val accounts = mockk<FnOsAccountRepository>()
  private val source = FnOsDataSourceFactory(accounts).createDataSource()
  private val path = "vol1/1000/书 架/01.mp3"
  private val account get() = FnOsAccount(nas.url("/").toString().trimEnd('/'), "reader")
  private val fileUri get() = FnOsLibrary.uri(account, path)

  @Before fun start() = nas.start()

  @After fun stop() {
    source.close()
    nas.close()
  }

  @Test
  fun seekReopensAnExpiredDownloadAndKeepsTheLibraryUri() {
    every { accounts.account } returns flowOf(account)
    coEvery { accounts.prepareRead(account, path, false) } returns readRequest("/expired")
    coEvery { accounts.prepareRead(account, path, true) } returns readRequest("/fresh")
    nas.enqueue(MockResponse.Builder().code(403).build())
    nas.enqueue(MockResponse.Builder().code(206).addHeader("Content-Range", "bytes 5-7/12").body("fgh").build())

    assertEquals(3L, source.open(DataSpec.Builder().setUri(fileUri).setPosition(5).setLength(3).build()))
    assertEquals(fileUri, source.uri)
    val bytes = ByteArray(3)
    assertEquals(3, source.read(bytes, 0, bytes.size))
    assertEquals("fgh", bytes.decodeToString())
    for (endpoint in listOf("/expired", "/fresh")) {
      val request = nas.takeRequest()
      assertEquals(endpoint, request.url.encodedPath)
      assertEquals("bytes=5-7", request.headers["Range"])
      assertEquals("fnos-token=test", request.headers["Cookie"])
    }
  }

  @Test
  fun differentAccountCannotOpenAnExistingShelfEntry() {
    every { accounts.account } returns flowOf(account.copy(username = "different"))
    assertFailsWith<IOException> { source.open(DataSpec(fileUri)) }
    coVerify(exactly = 0) { val _ = accounts.prepareRead(any(), any(), any()) }
    assertEquals(0, nas.requestCount)
  }

  @Test
  fun redirectsDoNotForwardTheNasCookie() {
    every { accounts.account } returns flowOf(account)
    coEvery { accounts.prepareRead(account, path, false) } returns readRequest("/download")
    nas.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/elsewhere").build())
    assertFailsWith<IOException> { source.open(DataSpec(fileUri)) }
    assertEquals(1, nas.requestCount)
  }

  private fun readRequest(endpoint: String) =
    FnOsReadRequest(nas.url(endpoint).toString(), mapOf("Cookie" to "fnos-token=test"))
}
