package voice.core.online

import androidx.datastore.core.DataStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OnlineStreamAuthProviderTest {

  private fun store(value: String): DataStore<String> {
    val store = mockk<DataStore<String>>()
    every { store.data } returns flowOf(value)
    return store
  }

  @Test
  fun `the bearer token only goes to the configured server`() {
    val provider = OnlineStreamAuthProvider(
      baseUrlStore = store("https://fm.example.com/"),
      tokenStore = store("secret-token"),
    )

    assertEquals(
      "Bearer secret-token",
      provider.authorizationFor("https://fm.example.com/api/hls/index.m3u8"),
    )
    // a third party cdn must never receive the app's token
    assertNull(provider.authorizationFor("https://cdn.example.net/index.m3u8"))
  }

  @Test
  fun `no token means no authorization header`() {
    val provider = OnlineStreamAuthProvider(
      baseUrlStore = store("https://fm.example.com"),
      tokenStore = store(""),
    )

    assertNull(provider.authorizationFor("https://fm.example.com/index.m3u8"))
  }

  @Test
  fun `an empty base url never matches`() {
    val provider = OnlineStreamAuthProvider(
      baseUrlStore = store(""),
      tokenStore = store("secret-token"),
    )

    assertNull(provider.authorizationFor("https://fm.example.com/index.m3u8"))
  }

  @Test
  fun `a look-alike host never receives the token`() {
    val provider = OnlineStreamAuthProvider(
      baseUrlStore = store("https://fm.example.com"),
      tokenStore = store("secret-token"),
    )

    // same string prefix, different registrable domain: a naive startsWith
    // check would leak the bearer token here
    assertNull(provider.authorizationFor("https://fm.example.com.evil.net/index.m3u8"))
    assertNull(provider.authorizationFor("https://fm.example.com@evil.net/index.m3u8"))
  }

  @Test
  fun `a different port is a different origin`() {
    val provider = OnlineStreamAuthProvider(
      baseUrlStore = store("https://fm.example.com"),
      tokenStore = store("secret-token"),
    )

    assertNull(provider.authorizationFor("https://fm.example.com:8443/index.m3u8"))
  }

  @Test
  fun `scheme and host are compared case insensitively`() {
    val provider = OnlineStreamAuthProvider(
      baseUrlStore = store("https://fm.example.com"),
      tokenStore = store("secret-token"),
    )

    assertEquals(
      "Bearer secret-token",
      provider.authorizationFor("HTTPS://FM.EXAMPLE.COM/index.m3u8"),
    )
  }
}
