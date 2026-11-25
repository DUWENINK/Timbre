package voice.features.settings.fnos

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import voice.core.online.FnOsAccount
import voice.core.online.FnOsAccountRepository
import voice.core.online.FnOsFile
import voice.core.online.FnOsLibrary
import voice.core.scanner.MediaScanTrigger
import voice.navigation.Destination
import voice.navigation.Navigator
import voice.navigation.Origin
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FnOsFolderActionsTest {
  private val account = FnOsAccount("https://nas.test", "reader")
  private val repository = mockk<FnOsAccountRepository> { every { account } returns flowOf(this@FnOsFolderActionsTest.account) }
  private val library = mockk<FnOsLibrary>(relaxUnitFun = true)
  private val scanner = mockk<MediaScanTrigger>(relaxUnitFun = true)
  private val actions = FnOsFolderActions(repository, library, scanner)

  @Test
  fun `browser lists the selected folder under the saved account with directories first`() = runTest {
    fun entry(name: String, directory: Boolean) = mockk<FnOsFile> {
      every { this@mockk.name } returns name
      every { isDirectory } returns directory
    }
    val audio = entry("001.mp3", false)
    val first = entry("Alpha", true)
    val last = entry("books", true)
    coEvery { repository.listFiles(account, "volume/library") } returns listOf(audio, last, first)

    val folder = actions.browse("volume/library")

    assertEquals(account, folder.account)
    assertEquals(listOf(first, last, audio), folder.entries)
  }

  @Test
  fun `both import modes persist their selection before starting a scan`() = runTest {
    actions.add(account, "volume/Book", true)
    actions.add(account, "volume/Library", false)
    coVerify { library.register(account, "volume/Book", true) }
    coVerify { library.register(account, "volume/Library", false) }
    verify(exactly = 2) { scanner.scan(restartIfScanning = true) }

    coEvery { library.register(any(), any(), any()) } throws IOException()
    assertFailsWith<IOException> { actions.add(account, "volume/Other", true) }
    verify(exactly = 2) { scanner.scan(restartIfScanning = true) }
  }

  @Test
  fun `import during setup goes through onboarding completion`() {
    val navigator = mockk<Navigator>(relaxUnitFun = true)
    navigator.openShelf(Origin.Onboarding)
    verify { navigator.setRoot(Destination.OnboardingCompletion) }
    navigator.openShelf(Origin.Default)
    verify { navigator.setRoot(Destination.BookOverview) }
  }
}
