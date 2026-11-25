package voice.core.scanner

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import voice.core.initializer.AppInitializer
import voice.core.online.FnOsAccountRepository
import java.io.IOException

@ContributesIntoSet(AppScope::class)
public class MediaScanInitializer(
  private val mediaScanTrigger: MediaScanTrigger,
  private val accounts: FnOsAccountRepository,
  private val scope: CoroutineScope,
) : AppInitializer {

  override fun onAppStart(application: android.app.Application) {
    mediaScanTrigger.automaticScan()
    scope.launch {
      accounts.account.retryWhen { error, _ ->
        if (error !is IOException) return@retryWhen false
        delay(5_000)
        true
      }.drop(1).collect {
        mediaScanTrigger.scan(restartIfScanning = true)
      }
    }
  }
}
