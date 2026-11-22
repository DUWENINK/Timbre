package voice.core.playback.session

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import org.junit.runner.RunWith
import voice.core.online.OnlineHlsStreams
import voice.core.online.OnlinePlaybackCatalog
import voice.core.online.ResolvedStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * Guards the branch that decides between the old progressive path and HLS:
 * everything that is not a resolved playlist url has to reach the original
 * factory untouched, so direct-link sources, local files and WebDAV playback
 * keep the exact media source they had before.
 */
@RunWith(AndroidJUnit4::class)
class OnlineHlsMediaSourceFactoryTest {

  private val chapterUri = "online://play?s=fanqie_pro&b=book&c=chapter-1"
  private val playlistUrl = "https://cdn.example.com/x/index.m3u8"

  private val progressive: MediaSource.Factory = mockk()
  private val hls: HlsMediaSource.Factory = mockk()
  private val catalog: OnlinePlaybackCatalog = mockk()
  private val progressiveSource: MediaSource = mockk()
  private val hlsSource: HlsMediaSource = mockk()

  private val factory = OnlineHlsMediaSourceFactory(progressive, hls, catalog)

  @AfterTest
  fun cleanUp() {
    OnlineHlsStreams.clear()
  }

  private fun item(uri: String): MediaItem {
    return MediaItem.Builder().setMediaId("chapter-media-id").setUri(uri).build()
  }

  @Test
  fun `a non online item is handed to the progressive factory`() {
    val mediaItem = item("https://example.com/a.mp3")
    every { progressive.createMediaSource(mediaItem) } returns progressiveSource

    assertSame(progressiveSource, factory.createMediaSource(mediaItem))
  }

  @Test
  fun `an online chapter with no resolved url keeps the progressive path`() {
    val mediaItem = item(chapterUri)
    every { catalog.peekStreamUrl(any()) } returns null
    every { progressive.createMediaSource(mediaItem) } returns progressiveSource

    assertSame(progressiveSource, factory.createMediaSource(mediaItem))
  }

  @Test
  fun `an online chapter with a direct link keeps the progressive path`() {
    val mediaItem = item(chapterUri)
    every { catalog.peekStreamUrl(any()) } returns ResolvedStream("https://cdn.example.com/a.mp3")
    every { progressive.createMediaSource(mediaItem) } returns progressiveSource

    assertSame(progressiveSource, factory.createMediaSource(mediaItem))
  }

  @Test
  fun `a resolved playlist url is played as hls with the real url`() {
    val mediaItem = item(chapterUri)
    val headers = mapOf("Referer" to "https://m.lrts.me/")
    val handedToHls = mutableListOf<MediaItem>()
    every { catalog.peekStreamUrl(any()) } returns ResolvedStream(playlistUrl, headers)
    every { hls.createMediaSource(any()) } answers {
      handedToHls += firstArg<MediaItem>()
      hlsSource
    }

    assertSame(hlsSource, factory.createMediaSource(mediaItem))

    val hlsItem = handedToHls.single()
    assertEquals(playlistUrl, hlsItem.localConfiguration?.uri.toString())
    assertEquals(MimeTypes.APPLICATION_M3U8, hlsItem.localConfiguration?.mimeType)
    assertEquals("chapter-media-id", hlsItem.mediaId)
    // the source headers are registered for the segment and key requests
    assertEquals(headers, OnlineHlsStreams.headersFor(playlistUrl))
    assertEquals(headers, OnlineHlsStreams.headersFor("https://cdn.example.com/x/seg-1.ts"))
  }

  @Test
  fun `hls item keeps identity and swaps the uri`() {
    val original = item(chapterUri)

    val hlsItem = OnlineHlsMediaSourceFactory.hlsMediaItem(original, playlistUrl)

    assertNotNull(original.localConfiguration, "the chapter item must carry a uri")
    assertEquals(original.mediaId, hlsItem.mediaId)
    assertEquals(playlistUrl, hlsItem.localConfiguration?.uri.toString())
    assertEquals(MimeTypes.APPLICATION_M3U8, hlsItem.localConfiguration?.mimeType)
  }

  @Test
  fun `hls item keeps the clipping of the chapter`() {
    val original = MediaItem.Builder()
      .setMediaId("chapter-media-id")
      .setUri(chapterUri)
      .setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder()
          .setStartPositionMs(1_000)
          .setEndPositionMs(9_000)
          .build(),
      )
      .build()

    val hlsItem = OnlineHlsMediaSourceFactory.hlsMediaItem(original, playlistUrl)

    val clipping = assertNotNull(hlsItem.clippingConfiguration)
    assertEquals(1_000, clipping.startPositionMs)
    assertEquals(9_000, clipping.endPositionMs)
  }

  @Test
  fun `hls item keeps the metadata`() {
    val original = MediaItem.Builder()
      .setMediaId("chapter-media-id")
      .setUri(chapterUri)
      .setMediaMetadata(MediaMetadata.Builder().setTitle("第1章").build())
      .build()

    val hlsItem = OnlineHlsMediaSourceFactory.hlsMediaItem(original, playlistUrl)

    assertEquals("第1章", hlsItem.mediaMetadata.title)
  }

  @Test
  fun `hls item is marked handled so the player does not rebuild it`() {
    val mediaItem = item(chapterUri)
    every { catalog.peekStreamUrl(any()) } returns ResolvedStream(playlistUrl)
    every { hls.createMediaSource(any()) } returns hlsSource

    factory.createMediaSource(mediaItem)

    assertEquals(true, OnlineHlsStreams.isHandled(chapterUri))
  }
}
