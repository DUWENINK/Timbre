package voice.core.playback.player

import androidx.datastore.core.DataStore
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import voice.core.analytics.api.Analytics
import voice.core.common.DispatcherProvider
import voice.core.data.Book
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.repo.BookRepository
import voice.core.data.store.AutoRewindAmountStore
import voice.core.data.store.BtSkipToChapterStore
import voice.core.data.store.CurrentBookStore
import voice.core.data.store.SeekTimeStore
import voice.core.logging.api.Logger
import voice.core.online.OnlineHlsStreams
import voice.core.online.OnlinePlaybackCatalog
import voice.core.online.OnlinePreloadSettings
import voice.core.online.OnlinePreloadSettingsStore
import voice.core.online.OnlineSourceStreamingClient
import voice.core.online.OnlineUri
import voice.core.online.ResolvedStream
import voice.core.playback.misc.Decibel
import voice.core.playback.misc.VolumeGain
import voice.core.playback.session.MediaId
import voice.core.playback.session.MediaItemProvider
import voice.core.playback.session.OnlineHlsMediaSourceFactory
import voice.core.playback.session.bookId
import voice.core.playback.session.playbackItemForPosition
import voice.core.playback.session.positionInMediaItem
import voice.core.playback.session.realChapterId
import voice.core.playback.session.toMediaIdOrNull
import voice.core.sleeptimer.SleepTimer
import voice.core.sleeptimer.SleepTimerState
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/** Upper bound for the pre-play url resolve of the starting chapter. */
private const val PRIME_TIMEOUT_MS = 8_000L

/** Refreshes per chapter before a dead playlist link is reported to the user. */
private const val MAX_HLS_REFRESHES = 2

/** Upper bound for the per-chapter refresh counters. */
private const val MAX_TRACKED_HLS_REFRESHES = 128

/** Server answers that mean "this signed link is no longer usable". */
private val HLS_EXPIRED_STATUS_CODES = setOf(401, 403, 404, 410)

@Inject
class VoicePlayer(
  private val player: Player,
  private val repo: BookRepository,
  @CurrentBookStore
  private val currentBookStoreId: DataStore<BookId?>,
  @SeekTimeStore
  private val seekTimeStore: DataStore<Int>,
  @BtSkipToChapterStore
  private val btSkipToChapterStore: DataStore<Boolean>,
  @AutoRewindAmountStore
  private val autoRewindAmountStore: DataStore<Int>,
  private val mediaItemProvider: MediaItemProvider,
  private val onlinePlaybackCatalog: OnlinePlaybackCatalog,
  @OnlinePreloadSettingsStore
  private val onlinePreloadSettingsStore: DataStore<OnlinePreloadSettings>,
  @OnlineSourceStreamingClient
  private val onlineStreamingClient: OkHttpClient,
  private val scope: CoroutineScope,
  private val volumeGain: VolumeGain,
  private val sleepTimer: SleepTimer,
  private val analytics: Analytics,
  private val dispatcherProvider: DispatcherProvider = DispatcherProvider(),
) : ForwardingPlayer(player) {

  // 蓝牙/锁屏的上一集/下一集行为：true=切换章节，false=快退/快进。
  // 在 init 里收集一次缓存，避免在媒体按键回调里阻塞读 DataStore。
  @Volatile
  private var btSkipToChapter: Boolean = true

  init {
    scope.launch {
      btSkipToChapterStore.data.collect { btSkipToChapter = it }
    }
  }

  private val endOfChapterSleepTimerListener = object : Player.Listener {
    override fun onPositionDiscontinuity(
      oldPosition: Player.PositionInfo,
      newPosition: Player.PositionInfo,
      reason: Int,
    ) {
      if (reason == DISCONTINUITY_REASON_AUTO_TRANSITION) {
        pauseAndDisableSleepTimerIfEndOfChapter()
      }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
      if (playbackState == STATE_ENDED) {
        pauseAndDisableSleepTimerIfEndOfChapter()
      }
    }

    private fun pauseAndDisableSleepTimerIfEndOfChapter() {
      if (sleepTimer.state.value !is SleepTimerState.Enabled.WithEndOfChapter) return
      Logger.v("Pausing due to EndOfChapter")
      sleepTimer.disable()
      player.pause()
    }
  }

  /**
   * The preload state machine of online books: resolves the next chapters'
   * stream urls (and measures their durations) only while the current chapter
   * really plays, paced by the user's preload settings. Online sources are
   * frequently rate limited, so nothing here loads before playback and every
   * request is spaced and capped.
   */
  private val onlinePlaybackPreloader = OnlinePlaybackPreloader(
    player = player,
    catalog = onlinePlaybackCatalog,
    settingsStore = onlinePreloadSettingsStore,
    httpClient = onlineStreamingClient,
    scope = scope,
    dispatcherProvider = dispatcherProvider,
  )

  /**
   * Chapters whose url was not resolved when the playlist was built cannot be
   * recognised as HLS by the media source factory, so they would start on the
   * progressive path and fail on a playlist. This listener rebuilds such a
   * chapter once its url is known - only that chapter, and only while it is
   * still the one playing. Direct links resolve to a media url and are left
   * untouched.
   */
  private val hlsRebuildListener = object : Player.Listener {
    override fun onMediaItemTransition(
      mediaItem: MediaItem?,
      reason: Int,
    ) {
      if (mediaItem != null) rebuildAsHlsIfNeeded(mediaItem)
    }

    override fun onPlayerError(error: PlaybackException) {
      refreshExpiredHlsLink(error)
    }
  }

  /** Chapter uris whose HLS re-resolve is in flight, so a duplicate is skipped. */
  private val hlsRebuildsInFlight = ConcurrentHashMap.newKeySet<String>()

  /** Chapter uris whose expired playlist link is being refreshed. */
  private val hlsRefreshesInFlight = ConcurrentHashMap.newKeySet<String>()

  /** Refreshes per chapter, so a source that keeps handing out dead links stops. */
  private val hlsRefreshCounts = ConcurrentHashMap<String, Int>()

  private fun rebuildAsHlsIfNeeded(mediaItem: MediaItem) {
    val configuration = mediaItem.localConfiguration ?: return
    val chapterUri = configuration.uri.toString()
    if (!OnlineUri.isOnlineUri(chapterUri)) return
    // already built from a playlist url by the media source factory
    if (OnlineHlsStreams.isHandled(chapterUri)) return
    val ref = OnlineUri.parse(chapterUri) ?: return

    val cached = onlinePlaybackCatalog.peekStreamUrl(ref)
    if (cached != null) {
      if (OnlineHlsStreams.isHlsUrl(cached.url)) {
        // never mutate the playlist from inside the transition callback: the
        // replace runs on the next dispatch, once the player settled on the item
        scope.launch { replaceWithHls(chapterUri, mediaItem.mediaId, cached) }
      }
      return
    }
    if (!hlsRebuildsInFlight.add(chapterUri)) return
    scope.launch {
      try {
        val stream = onlinePlaybackCatalog.resolveStreamUrl(ref, reportErrors = false) ?: return@launch
        if (!OnlineHlsStreams.isHlsUrl(stream.url)) return@launch
        replaceWithHls(chapterUri, mediaItem.mediaId, stream)
      } finally {
        hlsRebuildsInFlight.remove(chapterUri)
      }
    }
  }

  private fun replaceWithHls(
    chapterUri: String,
    mediaId: String,
    stream: ResolvedStream,
  ) {
    // 另一次 transition 可能已经在这条 url 解析期间重建过该章：重复 replace
    // 会把正在播放的内容从头重来，所以先看标记
    if (OnlineHlsStreams.isHandled(chapterUri)) return
    val index = player.currentMediaItemIndex
    if (index == C.INDEX_UNSET || index !in 0 until player.mediaItemCount) return
    val current = player.getMediaItemAt(index)
    // the user may have moved on while the url was resolving
    if (current.mediaId != mediaId) return
    OnlineHlsStreams.remember(chapterUri, stream)
    // marked before the replace: the transition it triggers must not resolve
    // this chapter a second time
    OnlineHlsStreams.markHandled(chapterUri)
    player.replaceMediaItem(index, OnlineHlsMediaSourceFactory.hlsMediaItem(current, stream.url))
  }

  /**
   * HLS direct links are signed and expire while a chapter plays. The
   * streaming data source refreshes such a link when the server rejects it,
   * but a playlist is fetched by the HLS pipeline itself, so a dead link would
   * end the chapter. This asks the source for a fresh url after an auth or
   * expiry status and rebuilds just that item, keeping the playback position.
   * Bounded per chapter, so a source that keeps handing out dead links fails
   * normally instead of looping.
   */
  private fun refreshExpiredHlsLink(error: PlaybackException) {
    val status = httpStatusOf(error) ?: return
    if (status !in HLS_EXPIRED_STATUS_CODES) return
    val mediaItem = player.currentMediaItem ?: return
    // chapter marks resolve to the same chapter, so a clipped playback of the
    // chapter is refreshed as well
    val mediaId = mediaItem.mediaId.toMediaIdOrNull() ?: return
    val chapterId = mediaId.realChapterId ?: return
    val chapterUri = chapterId.value
    // only a chapter that is really playing from a playlist can be refreshed
    if (!OnlineHlsStreams.isHandled(chapterUri)) return
    if ((hlsRefreshCounts[chapterUri] ?: 0) >= MAX_HLS_REFRESHES) return
    if (!hlsRefreshesInFlight.add(chapterUri)) return
    val ref = OnlineUri.parse(chapterUri)
    if (ref == null) {
      hlsRefreshesInFlight.remove(chapterUri)
      return
    }
    scope.launch {
      try {
        val expiredUrl = player.currentMediaItem?.localConfiguration?.uri?.toString()
        val _ = onlinePlaybackCatalog.invalidateStreamUrl(ref)
        val stream = onlinePlaybackCatalog.resolveStreamUrl(ref, reportErrors = false) ?: return@launch
        if (!OnlineHlsStreams.isHlsUrl(stream.url)) return@launch
        // the source handed out the same dead link: rebuilding would fail again
        if (stream.url == expiredUrl) return@launch
        val index = player.currentMediaItemIndex
        if (index == C.INDEX_UNSET || index !in 0 until player.mediaItemCount) return@launch
        val current = player.getMediaItemAt(index)
        if (current.mediaId != mediaItem.mediaId) return@launch
        val position = player.currentPosition.coerceAtLeast(0L)
        if (hlsRefreshCounts.size > MAX_TRACKED_HLS_REFRESHES) hlsRefreshCounts.clear()
        hlsRefreshCounts[chapterUri] = (hlsRefreshCounts[chapterUri] ?: 0) + 1
        OnlineHlsStreams.remember(chapterUri, stream)
        OnlineHlsStreams.markHandled(chapterUri)
        player.replaceMediaItem(index, OnlineHlsMediaSourceFactory.hlsMediaItem(current, stream.url))
        player.seekTo(index, position)
        player.prepare()
      } finally {
        hlsRefreshesInFlight.remove(chapterUri)
      }
    }
  }

  /** First http status on the error's cause chain, when the failure was http. */
  private fun httpStatusOf(error: PlaybackException): Int? {
    var cause: Throwable? = error
    var depth = 0
    while (cause != null && depth < 8) {
      if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
      cause = cause.cause
      depth++
    }
    return null
  }

  /**
   * Resolves the starting chapter's url before the playlist is handed over, so
   * the media source factory can tell an HLS playlist from a direct link for
   * the chapter that is about to play. Bounded, so a slow source cannot delay
   * the hand-over: a miss keeps the progressive path and is corrected by
   * [hlsRebuildListener] once the url arrives.
   */
  private suspend fun primeHlsChapter(prepared: PreparedBook?) {
    if (prepared == null) return
    val item = prepared.mediaItems.getOrNull(prepared.startIndex) ?: return
    val ref = item.localConfiguration?.uri?.toString()?.let(OnlineUri::parse) ?: return
    withTimeoutOrNull(PRIME_TIMEOUT_MS) {
      onlinePlaybackCatalog.resolveStreamUrl(ref, reportErrors = false)
    }
  }

  init {
    player.addListener(endOfChapterSleepTimerListener)
    player.addListener(onlinePlaybackPreloader)
    player.addListener(hlsRebuildListener)
  }

  fun forceSeekToNext() {
    scope.launch {
      val nextMediaItemIndex = player.nextMediaItemIndex.takeUnless { it == C.INDEX_UNSET }
      if (nextMediaItemIndex != null) {
        player.seekTo(nextMediaItemIndex, 0)
      } else if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) {
        // there is no next chapter yet because the import hasn't stored it,
        // and the listener is playing. Ending the current chapter right away
        // makes the tap take effect instead of doing nothing while the rest
        // of the chapter would play out. The seek lands inside the last
        // chapter mark so it doesn't pause, and once the synchronizer appends
        // the next imported chapter it resumes into it automatically.
        val duration = player.duration
        if (player.currentMediaItemIndex != C.INDEX_UNSET && duration != C.TIME_UNSET) {
          player.seekTo(player.currentMediaItemIndex, (duration - 1).coerceAtLeast(0))
        }
      }
    }
  }

  fun forceSeekToPrevious() {
    scope.launch {
      // this is the explicit "previous chapter" action of the app, so it always
      // changes the chapter. Restarting the current one when it just started
      // made the button appear broken while listening to an episode.
      val previousMediaItemIndex = player.previousMediaItemIndex.takeUnless { it == C.INDEX_UNSET }
      if (previousMediaItemIndex != null) {
        player.seekTo(previousMediaItemIndex, 0)
      } else {
        player.seekTo(0)
      }
    }
  }

  override fun getAvailableCommands(): Player.Commands {
    // On Android 13, the notification always shows the "skip to next" and "skip to previous"
    // actions.
    // However these are also used internally when seeking for example through a bluetooth headset
    // We use these and delegate them to fast forward / rewind.
    // The player however only advertises the seek to next and previous item in the case
    // that it's not the first or last track. Therefore we manually advertise that these
    // are available.
    return super.getAvailableCommands()
      .buildUpon()
      .addAll(
        COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        COMMAND_SEEK_TO_PREVIOUS,
        COMMAND_SEEK_TO_NEXT,
        COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
      )
      .build()
  }

  override fun seekToPreviousMediaItem() {
    if (btSkipToChapter) {
      super.seekToPreviousMediaItem()
    } else {
      seekBack()
    }
  }

  override fun seekToNextMediaItem() {
    if (btSkipToChapter) {
      super.seekToNextMediaItem()
    } else {
      seekForward()
    }
  }

  override fun seekToPrevious() {
    if (btSkipToChapter) {
      super.seekToPrevious()
    } else {
      seekBack()
    }
  }

  override fun seekToNext() {
    if (btSkipToChapter) {
      super.seekToNext()
    } else {
      seekForward()
    }
  }

  override fun seekBack() {
    scope.launch {
      seekBackBy(seekTimeStore.data.first().seconds)
    }
  }

  private suspend fun seekBackBy(skipAmount: Duration) {
    seekBackBy(
      skipAmount = skipAmount,
      crossMediaItems = true,
    )
  }

  private suspend fun seekBackBy(
    skipAmount: Duration,
    crossMediaItems: Boolean,
  ) {
    var currentPosition = player.currentPosition.takeUnless { it == C.TIME_UNSET }
      ?.milliseconds
      ?.coerceAtLeast(ZERO)
      ?: return
    var remaining = skipAmount
    var mediaItemIndex = player.currentMediaItemIndex.takeUnless { it == C.INDEX_UNSET } ?: return

    while (remaining > currentPosition) {
      if (!crossMediaItems) {
        player.seekTo(mediaItemIndex, 0)
        return
      }
      remaining -= currentPosition
      val previousMediaItemIndex = mediaItemIndex - 1
      if (previousMediaItemIndex < 0) {
        player.seekTo(0)
        return
      }
      val previousMediaItem = player.getMediaItemAt(previousMediaItemIndex)
      currentPosition = previousMediaItem.mediaMetadata.durationMs?.milliseconds ?: return
      mediaItemIndex = previousMediaItemIndex
    }

    player.seekTo(mediaItemIndex, (currentPosition - remaining).inWholeMilliseconds)
  }

  override fun seekForward() {
    scope.launch {
      val skipAmount = seekTimeStore.data.first().seconds

      val currentPosition = player.currentPosition.takeUnless { it == C.TIME_UNSET }
        ?.milliseconds
        ?.coerceAtLeast(ZERO)
        ?: return@launch
      val newPosition = currentPosition + skipAmount

      val duration = player.duration.takeUnless { it == C.TIME_UNSET }
        ?.milliseconds
        ?: return@launch

      if (newPosition > duration) {
        val nextMediaItemIndex = nextMediaItemIndex.takeUnless { it == C.INDEX_UNSET }
          ?: return@launch
        player.seekTo(nextMediaItemIndex, (duration - newPosition).absoluteValue.inWholeMilliseconds)
      } else {
        player.seekTo(newPosition.inWholeMilliseconds)
      }
    }
  }

  override fun play() {
    playWhenReady = true
    // a prepare withheld for an online book runs once playback is requested
    runPrepareIfAllowed()
  }

  override fun setPlayWhenReady(playWhenReady: Boolean) {
    Logger.d("setPlayWhenReady=$playWhenReady")
    analytics.event(if (playWhenReady) "play" else "pause")

    if (playWhenReady) {
      updateLastPlayedAt()
      runPrepareIfAllowed()
    } else {
      val currentPosition = player.currentPosition.takeUnless { it == C.TIME_UNSET }?.milliseconds ?: ZERO
      if (currentPosition > ZERO) {
        scope.launch {
          seekBackBy(
            skipAmount = autoRewindAmountStore.data.first().seconds,
            crossMediaItems = false,
          )
        }
      }
    }
    super.setPlayWhenReady(playWhenReady)
  }

  override fun pause() {
    playWhenReady = false
  }

  private fun updateLastPlayedAt() {
    scope.launch {
      currentBookStoreId.data.first()?.let { bookId ->
        repo.updateBook(bookId) {
          val lastPlayedAt = Instant.now()
          Logger.v("Update ${it.name}: lastPlayedAt to $lastPlayedAt")
          it.copy(lastPlayedAt = lastPlayedAt)
        }
      }
    }
  }

  override fun getPlaybackState(): Int = when (val state = super.getPlaybackState()) {
    // redirect buffering to ready to prevent visual artifacts on seeking
    STATE_BUFFERING -> STATE_READY
    else -> state
  }

  override fun setMediaItem(
    mediaItem: MediaItem,
    startPositionMs: Long,
  ) {
    setBook(mediaItem)
  }

  private var setBookJob: Job? = null
  private var playlistExpandJob: Job? = null

  // True while the media item currently being assembled belongs to an online
  // book - the only book kind whose source api calls must wait for playback.
  @Volatile
  private var currentBookIsOnline: Boolean = false

  // 已经交给播放器的书（正在组装或组装完成）。setBook 是异步的，而 withheld
  // 的 prepare 会让 playbackState 停在 IDLE，调用方光看状态分不清"这本书还
  // 没设过"和"设过了只是没 prepare"，就会重复 setMediaItem；那会 cancel 掉
  // 正在进行的组装，连它已经发出的音频解析一起中断 —— 请求收不回来，源端
  // 的限流额度已经花掉，用户真正点的那一集于是 429。
  @Volatile
  private var bookIdInPlayer: BookId? = null

  // prepare()/play() arriving while setBook is still assembling ran on an
  // empty playlist and did nothing. The flag re-applies them once the items
  // land; play() needs no flag because playWhenReady persists on the player.
  private var pendingPrepare = false

  override fun prepare() {
    pendingPrepare = true
    runPrepareIfAllowed()
  }

  /**
   * Runs a requested prepare - unless the current book is an online one and
   * nobody is playing: entering the player of a rate limited source must not
   * spend its api budget on a chapter nobody listens to (the first request of
   * the actually picked chapter would then fail). The withheld prepare runs
   * on play()/setPlayWhenReady(true). Local and WebDav books prepare right
   * away like before - their data is local or the user's own server, so
   * buffering while paused is free.
   */
  private fun runPrepareIfAllowed() {
    if (!pendingPrepare) return
    if (currentBookIsOnline && !player.playWhenReady) return
    pendingPrepare = false
    super.prepare()
  }

  override fun stop() {
    setBookJob?.cancel()
    playlistExpandJob?.cancel()
    pendingPrepare = false
    currentBookIsOnline = false
    bookIdInPlayer = null
    super.stop()
  }

  override fun clearMediaItems() {
    setBookJob?.cancel()
    playlistExpandJob?.cancel()
    pendingPrepare = false
    currentBookIsOnline = false
    bookIdInPlayer = null
    super.clearMediaItems()
  }

  override fun setMediaItem(
    mediaItem: MediaItem,
    resetPosition: Boolean,
  ) {
    setBook(mediaItem)
  }
  override fun setMediaItems(mediaItems: List<MediaItem>) {
    val first = mediaItems.firstOrNull() ?: return
    setBook(first)
  }

  override fun setMediaItems(
    mediaItems: List<MediaItem>,
    resetPosition: Boolean,
  ) {
    val first = mediaItems.firstOrNull() ?: return
    setBook(first)
  }

  override fun setMediaItem(mediaItem: MediaItem) {
    setBook(mediaItem)
  }

  override fun setMediaItems(
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ) {
    val first = mediaItems.firstOrNull() ?: return
    setBook(first)
  }

  private fun setBook(mediaItem: MediaItem) {
    Logger.v("setBook(${mediaItem.mediaId})")
    val mediaId = mediaItem.mediaId.toMediaIdOrNull()
    if (mediaId !is MediaId.Book) {
      if (mediaId != null) {
        Logger.w("Unexpected mediaId=$mediaId")
      }
      return
    }
    // 同一本书已经交给播放器（正在组装或已组装）：直接忽略这次设置。
    // 重建会 cancel 掉正在跑的组装，连它已经发出的音频解析一起中断，而
    // 请求收不回来 —— 源端的限流额度照样被吃掉，用户真正点的那一集就 429。
    // 这条判定不依赖跨进程查询，客户端走哪条路径都拦得住；要真正重载这本
    // 书（导入新章节等）请先 clearMediaItems()/stop() 把标记清掉。
    if (bookIdInPlayer == mediaId.id) {
      Logger.d("setBook(${mediaId.id}) ignored: playlist already handed over")
      return
    }
    // Assembling touches the database and builds one MediaItem per chapter:
    // both grow with the chapter count and must never run on the calling
    // (usually main) thread. Later calls win: an assembly still in flight is
    // cancelled so rapid book switches cannot apply out of order.
    setBookJob?.cancel()
    playlistExpandJob?.cancel()
    pendingPrepare = false
    currentBookIsOnline = onlinePlaybackCatalog.isOnlineBookId(mediaId.id)
    // 同步落下标记：播放列表还没组装完，这本书也已经"归播放器了"，
    // 调用方不该再设一次（那会把这里的组装取消掉）
    bookIdInPlayer = mediaId.id
    setBookJob = scope.launch {
      try {
        // a failed assembly (book deleted, position no longer resolvable) must
        // not re-prepare the previous book's stale playlist, so the trailing
        // prepare is gated on the items actually having been set
        var assembled = false
        var expand: PreparedBook? = null
        val assemblyDuration = measureTime {
          // one IO hop: load the book, build a leading prefix and resolve cover.
          val prepared = withContext(dispatcherProvider.io) {
            val assembling = prepareBook(mediaId.id)
            primeHlsChapter(assembling)
            assembling
          }
          if (prepared == null) {
            // 组装失败就放开标记，否则调用方会一直以为"已经设过"，
            // 这本书再也重建不起来
            if (bookIdInPlayer == mediaId.id) {
              bookIdInPlayer = null
            }
            return@measureTime
          }
          player.setPlaybackSpeed(prepared.book.content.playbackSpeed)
          // apply directly instead of through setSkipSilenceEnabled: that
          // override persists the value, which would rewrite the shelf on
          // every prepare with the value just read from it
          if (player is ExoPlayer) {
            player.skipSilenceEnabled = prepared.book.content.skipSilence
          }
          volumeGain.gain = Decibel(prepared.book.content.gain)
          player.setMediaItems(
            prepared.mediaItems,
            prepared.startIndex,
            prepared.startPositionMs,
          )
          assembled = true
          if (prepared.needsFullPlaylist) {
            expand = prepared
          }
        }
        // prepare()/play() that arrived while the book was still assembling ran
        // on an empty playlist and did nothing: apply them now that the items
        // landed, otherwise tap-play on a cold book stays silent. For an online
        // book the prepare stays withheld while paused (runPrepareIfAllowed).
        if (assembled && (pendingPrepare || player.playWhenReady)) {
          pendingPrepare = true
          runPrepareIfAllowed()
        }
        // assembling the playlist of a book with thousands of chapters is the
        // one step of a playback start that grows with the chapter count. The
        // log marks how long it took so a slow start can be attributed.
        Logger.i("setBook(${mediaItem.mediaId}) took $assemblyDuration")
        val toExpand = expand ?: return@launch
        // append the tail off the critical path; indexes already match the final
        // playlist so chapter picks inside the prefix work immediately
        playlistExpandJob = scope.launch {
          expandPlaylist(toExpand)
        }
      } catch (e: Throwable) {
        // 组装抛异常（数据库读失败、书被删掉的竞态）也必须放开标记，
        // 否则这本书会被永远当成"已经设过"，之后再也重建不起来。
        // 只在标记仍属于这本书时清，避免抹掉后一本书的标记。
        if (bookIdInPlayer == mediaId.id) {
          bookIdInPlayer = null
        }
        throw e
      }
    }
  }

  /**
   * Loads [bookId] and builds a leading playlist prefix through the resume
   * chapter. Deliberately resolves no stream url here: for an online book
   * that request waits until playback (see [runPrepareIfAllowed]), the
   * preload state machine then keeps the next chapters warm.
   */
  private suspend fun prepareBook(bookId: BookId): PreparedBook? {
    // books of the online source live in their own store: the room
    // lookup misses, the online catalog synthesizes the book instead
    val book = repo.get(bookId) ?: onlinePlaybackCatalog.book(bookId) ?: return null
    val currentPlaybackItem = book.playbackItemForPosition(
      chapterId = book.content.currentChapter,
      positionInChapterMs = book.content.positionInChapter,
    ) ?: return null

    // leading prefix: indexes == final full playlist, so chapter seeks and the
    // import synchronizer stay correct while the tail is still missing. The
    // artwork of every item comes from book.content.cover - online books
    // included - so it must not be patched per code path here: the import
    // synchronizer writes playlist items of its own and would drop it.
    val prefix = mediaItemProvider.playbackItemsPrefix(
      book = book,
      resumeItemIndex = currentPlaybackItem.index,
    )

    return PreparedBook(
      book = book,
      mediaItems = prefix.items,
      startIndex = prefix.resumeIndex,
      startPositionMs = currentPlaybackItem.positionInMediaItem(book.content.positionInChapter),
      needsFullPlaylist = prefix.isPartial,
    )
  }

  /**
   * Appends the remaining chapters after the leading prefix. Uses
   * [Player.addMediaItems] so the playing item and its buffer are not replaced
   * — critical for long-book listen feel.
   */
  private suspend fun expandPlaylist(prepared: PreparedBook) {
    val prefixSize = prepared.mediaItems.size
    val tail = withContext(dispatcherProvider.io) {
      mediaItemProvider.playbackItems(prepared.book, fromItemIndex = prefixSize)
    }
    if (tail.isEmpty()) return
    // drop the expansion when the user already switched books
    val playingBookId = player.currentMediaItem?.mediaId?.toMediaIdOrNull()?.bookId
    if (playingBookId != null && playingBookId != prepared.book.id) {
      return
    }
    // import synchronizer or a previous expand may already have grown the list
    val already = player.mediaItemCount
    if (already >= prefixSize + tail.size) {
      return
    }
    val missing = if (already > prefixSize) {
      // something else appended a shorter tail; only add what is still missing
      tail.drop(already - prefixSize)
    } else {
      tail
    }
    if (missing.isEmpty()) return
    player.addMediaItems(already, missing)
    Logger.i(
      "Expanded playlist of ${prepared.book.id}: " +
        "prefix=$prefixSize +${missing.size} → ${already + missing.size}",
    )
  }

  override fun setPlaybackSpeed(speed: Float) {
    super.setPlaybackSpeed(speed)
    scope.launch {
      val bookId = currentBookStoreId.data.first()
      // online books have no room row: their values live on the online shelf
      if (bookId != null && onlinePlaybackCatalog.isOnlineBookId(bookId)) {
        onlinePlaybackCatalog.setPlaybackSpeed(bookId, speed)
      } else {
        updateBook { it.copy(playbackSpeed = speed) }
      }
    }
  }

  fun setSkipSilenceEnabled(enabled: Boolean) {
    scope.launch {
      val bookId = currentBookStoreId.data.first()
      // online books have no room row: their values live on the online shelf
      if (bookId != null && onlinePlaybackCatalog.isOnlineBookId(bookId)) {
        onlinePlaybackCatalog.setSkipSilence(bookId, enabled)
      } else {
        updateBook { it.copy(skipSilence = enabled) }
      }
    }
    if (player is ExoPlayer) {
      player.skipSilenceEnabled = enabled
    }
  }

  fun setGain(gain: Decibel) {
    volumeGain.gain = gain
    scope.launch {
      val bookId = currentBookStoreId.data.first()
      // online books have no room row: their values live on the online shelf
      if (bookId != null && onlinePlaybackCatalog.isOnlineBookId(bookId)) {
        onlinePlaybackCatalog.setGain(bookId, gain.value)
      } else {
        updateBook { it.copy(gain = gain.value) }
      }
    }
  }

  fun setSkipIntro(skipIntroMs: Long) {
    scope.launch {
      val value = skipIntroMs.coerceAtLeast(0)
      val bookId = currentBookStoreId.data.first()
      // online books have no room row: their values live on the online shelf
      if (bookId != null && onlinePlaybackCatalog.isOnlineBookId(bookId)) {
        onlinePlaybackCatalog.setSkipIntro(bookId, value)
      } else {
        updateBook { it.copy(skipIntro = value) }
      }
    }
  }

  fun setSkipOutro(skipOutroMs: Long) {
    scope.launch {
      val value = skipOutroMs.coerceAtLeast(0)
      val bookId = currentBookStoreId.data.first()
      // online books have no room row: their values live on the online shelf
      if (bookId != null && onlinePlaybackCatalog.isOnlineBookId(bookId)) {
        onlinePlaybackCatalog.setSkipOutro(bookId, value)
      } else {
        updateBook { it.copy(skipOutro = value) }
      }
    }
  }

  private suspend fun updateBook(update: (BookContent) -> BookContent) {
    val bookId = currentBookStoreId.data.first() ?: return
    repo.updateBook(bookId, update)
  }

  private data class PreparedBook(
    val book: Book,
    val mediaItems: List<MediaItem>,
    /**
     * Resume index inside [mediaItems]. For a leading prefix this is also the
     * final full-playlist index, so chapter seeks need not wait for the tail.
     */
    val startIndex: Int,
    val startPositionMs: Long,
    val needsFullPlaylist: Boolean,
  )
}
