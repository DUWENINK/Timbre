package voice.core.scanner

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import voice.core.online.FnOsAccount
import voice.core.online.FnOsAccountRepository
import kotlin.test.Test

class MediaScanInitializerTest {
  @Test
  fun switchingOrLosingTheNasSessionRefreshesTheShelf() = runTest {
    val active = MutableStateFlow<FnOsAccount?>(FnOsAccount("https://nas", "reader"))
    val repository = mockk<FnOsAccountRepository> { every { account } returns active }
    val trigger = mockk<MediaScanTrigger>(relaxUnitFun = true)
    MediaScanInitializer(trigger, repository, backgroundScope).onAppStart(mockk())
    runCurrent()
    verify(exactly = 1) { trigger.automaticScan() }
    verify(exactly = 0) { trigger.scan(true) }

    active.value = FnOsAccount("https://nas", "another")
    runCurrent()
    active.value = null
    runCurrent()
    verify(exactly = 2) { trigger.scan(true) }
  }
}
