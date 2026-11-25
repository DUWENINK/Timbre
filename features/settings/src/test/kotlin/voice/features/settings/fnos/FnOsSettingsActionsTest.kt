package voice.features.settings.fnos

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import voice.core.online.FnOsAccountRepository
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import voice.core.strings.R as StringsR

@OptIn(ExperimentalCoroutinesApi::class)
class FnOsSettingsActionsTest {
  @Test
  fun `a cancelled challenge cannot complete or leak its code into a new login`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      val repository = mockk<FnOsAccountRepository>()
      val receivedCodes = mutableListOf<String>()
      coEvery { repository.login(any(), any(), any(), any()) } coAnswers {
        val requestCode = arg<suspend () -> String>(3)
        receivedCodes += requestCode()
      }
      val actions = FnOsSettingsActions(repository, this)
      actions.login("nas", "user", "password")
      actions.cancel()
      runCurrent()
      assertFalse(actions.working)
      actions.login("nas", "user", "password")
      runCurrent()
      assertTrue(actions.awaitingCode)
      actions.login("nas", "user", "password")
      actions.cancel()
      runCurrent()
      assertFalse(actions.awaitingCode)
      assertFalse(actions.working)
      assertNull(actions.error)
      actions.submitCode("111111")

      actions.login("nas", "user", "password")
      runCurrent()
      assertTrue(actions.awaitingCode)
      actions.submitCode("012345")
      runCurrent()
      assertEquals(listOf("012345"), receivedCodes)
      assertFalse(actions.working)
      coVerify(exactly = 2) { repository.login(any(), any(), any(), any()) }
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `a failed logout can be retried`() = runTest {
    val repository = mockk<FnOsAccountRepository>()
    coEvery { repository.logout() } throws IOException()
    val actions = FnOsSettingsActions(repository, this)
    actions.logout()
    runCurrent()
    assertEquals(StringsR.string.fnos_storage_error, actions.error)
    assertFalse(actions.working)

    coEvery { repository.logout() } returns Unit
    actions.logout()
    runCurrent()
    assertNull(actions.error)
    assertFalse(actions.working)
  }
}
