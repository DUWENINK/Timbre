package voice.core.online

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnlineHlsStreamsTest {

  @AfterTest
  fun cleanUp() {
    OnlineHlsStreams.clear()
  }

  @Test
  fun `isHlsUrl recognises playlist urls`() {
    assertTrue(OnlineHlsStreams.isHlsUrl("https://cdn.example.com/a/b/index.m3u8"))
    assertTrue(OnlineHlsStreams.isHlsUrl("https://cdn.example.com/a/b/index.M3U8?token=1"))
    assertTrue(OnlineHlsStreams.isHlsUrl("https://cdn.example.com/a/b/index.m3u#frag"))
    assertFalse(OnlineHlsStreams.isHlsUrl("https://cdn.example.com/a/b/index.mp3"))
    assertFalse(OnlineHlsStreams.isHlsUrl("https://cdn.example.com/a/b/index.m3u8.mp3"))
    assertFalse(OnlineHlsStreams.isHlsUrl(""))
  }

  @Test
  fun `a single session serves its playlist segments and keys`() {
    val headers = mapOf("Referer" to "https://m.lrts.me/", "User-Agent" to "ua")
    OnlineHlsStreams.remember(
      "online://play?s=a&b=b&c=c",
      ResolvedStream("https://cdn.example.com/x/index.m3u8", headers),
    )

    assertEquals(headers, OnlineHlsStreams.headersFor("https://cdn.example.com/x/index.m3u8"))
    assertEquals(headers, OnlineHlsStreams.headersFor("https://cdn.example.com/x/seg-1.ts"))
    assertEquals(headers, OnlineHlsStreams.headersFor("https://edge.example.com/x/key.bin"))
    // a segment on another host still belongs to the one chapter in play
    assertEquals(headers, OnlineHlsStreams.headersFor("https://other.example.org/x/seg-2.ts"))
  }

  @Test
  fun `with several sessions only matching hosts receive headers`() {
    val first = mapOf("Referer" to "https://first.example/")
    val second = mapOf("Referer" to "https://second.example/")
    OnlineHlsStreams.remember(
      "online://play?s=a&b=1&c=1",
      ResolvedStream("https://cdn-a.example.com/x/index.m3u8", first),
    )
    OnlineHlsStreams.remember(
      "online://play?s=b&b=2&c=2",
      ResolvedStream("https://cdn-b.example.net/y/index.m3u8", second),
    )

    assertEquals(first, OnlineHlsStreams.headersFor("https://cdn-a.example.com/x/seg-1.ts"))
    assertEquals(second, OnlineHlsStreams.headersFor("https://cdn-b.example.net/y/seg-1.ts"))
    assertEquals(emptyMap(), OnlineHlsStreams.headersFor("https://other.example.org/z.ts"))
  }

  @Test
  fun `direct media urls are never registered`() {
    OnlineHlsStreams.remember(
      "online://play?s=a&b=b&c=c",
      ResolvedStream("https://cdn.example.com/a.mp3", mapOf("Referer" to "https://m.lrts.me/")),
    )

    assertEquals(emptyMap(), OnlineHlsStreams.headersFor("https://cdn.example.com/a.mp3"))
  }

  @Test
  fun `a re-resolve replaces the headers of the same chapter`() {
    val old = mapOf("Referer" to "https://old.example/")
    val new = mapOf("Referer" to "https://new.example/")
    val chapterUri = "online://play?s=a&b=b&c=c"
    OnlineHlsStreams.remember(chapterUri, ResolvedStream("https://cdn.example.com/old/index.m3u8", old))
    OnlineHlsStreams.remember(chapterUri, ResolvedStream("https://cdn.example.com/new/index.m3u8", new))

    assertEquals(new, OnlineHlsStreams.headersFor("https://cdn.example.com/new/index.m3u8"))
    assertEquals(new, OnlineHlsStreams.headersFor("https://cdn.example.com/old/index.m3u8"))
  }
}
