@file:Suppress("UnstableApiUsage")

package voice.core.playback.session

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import voice.core.online.OnlineHlsStreams
import voice.core.online.OnlinePlaybackCatalog
import voice.core.online.OnlineUri

/**
 * Decides per chapter whether it is played as HLS or as a progressive file.
 *
 * An online chapter is always addressed by `online://play?...`, and the
 * progressive factory would hand that scheme to [OnlineStreamingDataSource],
 * which streams whatever the resolver returns as raw bytes - a playlist would
 * then be read as if it were audio. To play a playlist the media source needs
 * the *real* url (so relative segment and key uris resolve correctly) and the
 * HLS mime type.
 *
 * The decision is made from the url the catalog already resolved, never by
 * fetching anything here: an online chapter whose url is not cached yet keeps
 * the previous progressive behaviour, and every non playlist url - every
 * source that hands out direct links - takes exactly the old path.
 */
internal class OnlineHlsMediaSourceFactory(
  private val progressive: MediaSource.Factory,
  private val hls: HlsMediaSource.Factory,
  private val catalog: OnlinePlaybackCatalog,
) : MediaSource.Factory {

  @Suppress("DEPRECATION")
  override fun getSupportedTypes(): IntArray {
    return intArrayOf(C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_HLS)
  }

  override fun setDrmSessionManagerProvider(
    drmSessionManagerProvider: DrmSessionManagerProvider,
  ): MediaSource.Factory {
    progressive.setDrmSessionManagerProvider(drmSessionManagerProvider)
    hls.setDrmSessionManagerProvider(drmSessionManagerProvider)
    return this
  }

  override fun setLoadErrorHandlingPolicy(
    loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
  ): MediaSource.Factory {
    progressive.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
    hls.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
    return this
  }

  override fun createMediaSource(mediaItem: MediaItem): MediaSource {
    val chapterUri = mediaItem.localConfiguration?.uri?.toString()
    val ref = chapterUri?.let(OnlineUri::parse)
      ?: return progressive.createMediaSource(mediaItem)
    val stream = catalog.peekStreamUrl(ref)
      ?: return progressive.createMediaSource(mediaItem)
    if (!OnlineHlsStreams.isHlsUrl(stream.url)) {
      return progressive.createMediaSource(mediaItem)
    }
    OnlineHlsStreams.remember(chapterUri, stream)
    OnlineHlsStreams.markHandled(chapterUri)
    return hls.createMediaSource(hlsMediaItem(mediaItem, stream.url))
  }

  internal companion object {
    /**
     * The playlist item that has to be handed to [HlsMediaSource]: the real
     * playlist url plus the HLS mime type. Relative segment and key uris are
     * resolved against this url, which is why the `online://` uri must not be
     * reused here.
     */
    fun hlsMediaItem(
      mediaItem: MediaItem,
      url: String,
    ): MediaItem {
      return mediaItem.buildUpon()
        .setUri(url)
        .setMimeType(MimeTypes.APPLICATION_M3U8)
        .build()
    }
  }
}
