@file:Suppress("UnstableApiUsage")

package voice.core.playback.di

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaLibraryService
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import voice.core.featureflag.FeatureFlag
import voice.core.featureflag.Media3AudioOffloadFeatureFlagQualifier
import voice.core.online.OnlineDataSourceFactory
import voice.core.online.OnlinePlaybackCatalog
import voice.core.online.OnlineStreamAuthProvider
import voice.core.online.OnlineUri
import voice.core.playback.misc.VolumeGain
import voice.core.playback.notification.MainActivityIntentProvider
import voice.core.playback.player.DurationInconsistenciesUpdater
import voice.core.playback.player.OnlyAudioRenderersFactory
import voice.core.playback.player.PlaybackIoPriorityUpdater
import voice.core.playback.player.SkipIntroOutro
import voice.core.playback.player.VoicePlayer
import voice.core.playback.player.onAudioSessionIdChanged
import voice.core.playback.playstate.PlayStateDelegatingListener
import voice.core.playback.playstate.PositionUpdater
import voice.core.playback.session.BookPlaylistSynchronizer
import voice.core.playback.session.LibrarySessionCallback
import voice.core.playback.session.OnlineHlsMediaSourceFactory
import voice.core.playback.session.PlaybackService
import voice.core.online.FnOsDataSourceFactory
import voice.core.online.RoutingDataSource
import voice.core.playback.session.hlsDataSourceFactory
import voice.core.webdav.WebDavDataSourceFactory
import voice.core.webdav.WebDavPlaybackCache
import voice.core.strings.R as StringsR

@ContributesTo(PlaybackScope::class)
interface PlaybackModule {

  @Provides
  @SingleIn(PlaybackScope::class)
  fun mediaSourceFactory(
    webDavPlaybackCache: WebDavPlaybackCache,
    webDavDataSourceFactory: WebDavDataSourceFactory,
    onlineDataSourceFactory: OnlineDataSourceFactory,
    fnOsDataSourceFactory: FnOsDataSourceFactory,
    onlinePlaybackCatalog: OnlinePlaybackCatalog,
    onlineStreamAuth: OnlineStreamAuthProvider,
  ): MediaSource.Factory {
    val extractorsFactory = DefaultExtractorsFactory()
      .setConstantBitrateSeekingEnabled(true)
    val webDav = webDavPlaybackCache.dataSourceFactory(webDavDataSourceFactory)
    val upstream = DataSource.Factory {
      RoutingDataSource(
        routes = listOf(
          { uri: Uri -> uri.scheme == "fnos" } to fnOsDataSourceFactory,
          { uri: Uri -> OnlineUri.isOnlineUri(uri.toString()) } to onlineDataSourceFactory,
        ),
        fallback = webDav,
      )
    }
    // progressive stays the default: every direct-link source keeps the exact
    // data source chain it had. Only a chapter whose resolved url is an HLS
    // playlist is handed to HlsMediaSource, by OnlineHlsMediaSourceFactory.
    val progressive = DefaultMediaSourceFactory(upstream, extractorsFactory)
    return OnlineHlsMediaSourceFactory(
      progressive = progressive,
      hls = HlsMediaSource.Factory(hlsDataSourceFactory(onlineStreamAuth)),
      catalog = onlinePlaybackCatalog,
    )
  }

  @Provides
  @SingleIn(PlaybackScope::class)
  fun player(
    context: Context,
    onlyAudioRenderersFactory: OnlyAudioRenderersFactory,
    mediaSourceFactory: MediaSource.Factory,
    playStateDelegatingListener: PlayStateDelegatingListener,
    positionUpdater: PositionUpdater,
    bookPlaylistSynchronizer: BookPlaylistSynchronizer,
    skipIntroOutro: SkipIntroOutro,
    volumeGain: VolumeGain,
    durationInconsistenciesUpdater: DurationInconsistenciesUpdater,
    playbackIoPriorityUpdater: PlaybackIoPriorityUpdater,
    @Media3AudioOffloadFeatureFlagQualifier media3AudioOffloadFeatureFlag: FeatureFlag<Boolean>,
  ): Player {
    val audioAttributes = AudioAttributes.Builder()
      .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
      .setUsage(C.USAGE_MEDIA)
      .build()

    return ExoPlayer.Builder(context, onlyAudioRenderersFactory, mediaSourceFactory)
      .setAudioAttributes(audioAttributes, true)
      .setHandleAudioBecomingNoisy(true)
      // streaming from webdav needs the network to stay alive while the
      // player holds a buffer, local playback is unaffected by the network wake lock
      .setWakeMode(C.WAKE_MODE_NETWORK)
      .build()
      .also { player ->
        if (media3AudioOffloadFeatureFlag.get()) {
          player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(
              TrackSelectionParameters.AudioOffloadPreferences.Builder()
                .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
                .setIsGaplessSupportRequired(true)
                .setIsSpeedChangeSupportRequired(true)
                .build(),
            )
            .build()
        }
        playStateDelegatingListener.attachTo(player)
        positionUpdater.attachTo(player)
        durationInconsistenciesUpdater.attachTo(player)
        // the import pauses its analysis while the player buffers, so starting
        // a chapter isn't queued behind the import's storage traffic
        playbackIoPriorityUpdater.attachTo(player)
        // keeps a book that is still being imported playable by appending the
        // chapters as soon as they are known
        bookPlaylistSynchronizer.attachTo(player)
        // skips the intro and outro the current book was configured with
        skipIntroOutro.attachTo(player)
        player.onAudioSessionIdChanged {
          volumeGain.audioSessionId = it
        }
      }
  }

  @Provides
  @SingleIn(PlaybackScope::class)
  fun scope(): CoroutineScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

  @Provides
  @SingleIn(PlaybackScope::class)
  fun session(
    service: PlaybackService,
    player: VoicePlayer,
    callback: LibrarySessionCallback,
    mainActivityIntentProvider: MainActivityIntentProvider,
    context: Context,
  ): MediaLibraryService.MediaLibrarySession {
    return MediaLibraryService.MediaLibrarySession.Builder(service, player, callback)
      .setSessionActivity(mainActivityIntentProvider.toCurrentBook())
      .setMediaButtonPreferences(
        listOf(
          CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
            .setDisplayName(context.getString(StringsR.string.playback_action_rewind))
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setSlots(CommandButton.SLOT_BACK)
            .build(),
          CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
            .setDisplayName(context.getString(StringsR.string.playback_action_fast_forward))
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build(),
        ),
      )
      .build()
  }
}
