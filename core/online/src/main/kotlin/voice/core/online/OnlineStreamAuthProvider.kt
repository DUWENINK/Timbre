package voice.core.online

import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

/**
 * Supplies the bearer token the app itself uses for its configured server, so
 * requests made outside [OnlineStreamingDataSource] (HLS playlists, segments
 * and keys) can authenticate the same way it does.
 *
 * The values are kept warm by collecting the stores once at construction.
 * HLS segment requests run on the player's loading thread and must not touch
 * DataStore on every request, so the first read after a cold start falls back
 * to a one-shot blocking read - the same trade-off the streaming data source
 * already makes.
 */
@SingleIn(AppScope::class)
@Inject
public class OnlineStreamAuthProvider(
  @OnlineSourceBaseUrlStore private val baseUrlStore: DataStore<String>,
  @OnlineSourceTokenStore private val tokenStore: DataStore<String>,
) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val baseUrl = AtomicReference<String?>(null)
  private val token = AtomicReference<String?>(null)

  init {
    scope.launch {
      baseUrlStore.data.collect { baseUrl.set(it) }
    }
    scope.launch {
      tokenStore.data.collect { token.set(it) }
    }
  }

  /**
   * `Authorization` value for [url], or null when no token is configured or
   * the url does not belong to the configured server. Third party CDNs never
   * receive it, matching the streaming data source.
   */
  public fun authorizationFor(url: String): String? {
    val bearer = tokenValue().trim()
    if (bearer.isEmpty()) return null
    val base = baseUrlValue().trim().trimEnd('/')
    if (base.isEmpty() || !sameOrigin(url, base)) return null
    return "Bearer $bearer"
  }

  /**
   * Compares scheme, host and effective port instead of string prefixes:
   * `https://api.example.com` must not match `https://api.example.com.evil.com`
   * and send the bearer token there.
   */
  private fun sameOrigin(url: String, base: String): Boolean {
    return try {
      val target = URI(url)
      val server = URI(base)
      target.scheme.equals(server.scheme, ignoreCase = true) &&
        target.host.equals(server.host, ignoreCase = true) &&
        effectivePort(target) == effectivePort(server)
    } catch (_: Exception) {
      false
    }
  }

  private fun effectivePort(uri: URI): Int = when {
    uri.port != -1 -> uri.port
    uri.scheme.equals("https", ignoreCase = true) -> 443
    else -> 80
  }

  /**
   * A broken store must degrade to "no token" instead of throwing on the
   * player's loading thread: one bad read would otherwise fail every segment
   * of the chapter.
   */
  private fun baseUrlValue(): String {
    baseUrl.get()?.let { return it }
    val loaded = runCatching { runBlocking { baseUrlStore.data.first() } }.getOrNull() ?: return ""
    baseUrl.set(loaded)
    return loaded
  }

  private fun tokenValue(): String {
    token.get()?.let { return it }
    val loaded = runCatching { runBlocking { tokenStore.data.first() } }.getOrNull() ?: return ""
    token.set(loaded)
    return loaded
  }
}
