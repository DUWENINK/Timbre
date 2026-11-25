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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
      actions.edit()
      actions.login("nas", "user", "password")
      runCurrent()
      assertTrue(actions.awaitingCode)
      actions.login("nas", "user", "password")
      actions.cancel()
      runCurrent()
      assertFalse(actions.awaitingCode)
      assertFalse(actions.working)
      assertTrue(actions.editing)
      assertNull(actions.error)
      actions.submitCode("111111")

      actions.login("nas", "user", "password")
      runCurrent()
      assertTrue(actions.awaitingCode)
      actions.submitCode("012345")
      runCurrent()
      assertEquals(listOf("012345"), receivedCodes)
      assertFalse(actions.working)
      assertFalse(actions.editing)
      coVerify(exactly = 2) { repository.login(any(), any(), any(), any()) }
    } finally {
      Dispatchers.resetMain()
    }
  }
}
