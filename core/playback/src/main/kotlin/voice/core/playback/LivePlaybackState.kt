package voice.core.playback

import androidx.media3.common.C
import androidx.media3.session.MediaController
import voice.core.data.Book
import voice.core.data.BookId
import voice.core.data.ChapterId
import voice.core.playback.session.bookId
import voice.core.playback.session.positionInChapter
import voice.core.playback.session.realChapterId
import voice.core.playback.session.toMediaIdOrNull

data class LivePlaybackState(
  val bookId: BookId,
  val chapterId: ChapterId,
  val positionMs: Long,
  val isPlaying: Boolean,
  val playbackSpeed: Float,
  /**
   * 直播（HLS 滑动窗口）没有可拖动的时间轴：position 是窗口内的偏移，
   * 窗口一滚就会回退，duration 只是窗口长度。UI 据此隐藏进度条。
   */
  val isLive: Boolean = false,
)

internal fun MediaController.livePlaybackStateSnapshot(bookId: BookId? = null): LivePlaybackState? {
  val mediaId = currentMediaItem?.mediaId?.toMediaIdOrNull() ?: return null
  val mediaItemBookId = mediaId.bookId ?: return null
  if (bookId != null && mediaItemBookId != bookId) {
    return null
  }
  val positionMs = currentPosition.takeUnless { it == C.TIME_UNSET || it < 0 } ?: return null
  val chapterId = mediaId.realChapterId ?: return null
  val positionInChapter = mediaId.positionInChapter(positionMs) ?: return null
  return LivePlaybackState(
    bookId = mediaItemBookId,
    chapterId = chapterId,
    positionMs = positionInChapter,
    isPlaying = isPlaying,
    playbackSpeed = playbackParameters.speed,
    isLive = isCurrentMediaItemLive,
  )
}

fun Book.overlay(livePlaybackState: LivePlaybackState): Book {
  return if (livePlaybackState.bookId == id) {
    update {
      it.copy(
        currentChapter = livePlaybackState.chapterId,
        positionInChapter = livePlaybackState.positionMs,
        playbackSpeed = livePlaybackState.playbackSpeed,
      )
    }
  } else {
    this
  }
}
