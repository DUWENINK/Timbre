package voice.core.online

import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers which resolved chapter urls are HLS playlists and which headers
 * their source asked for, so the HLS data source can attach those headers to
 * the playlist, segment and key requests alike.
 *
 * Only chapters whose resolved url is a playlist end up here; a source that
 * returns a plain media file never touches this object, so its playback path
 * stays exactly as before.
 *
 * The registry is intentionally small and process wide: one chapter is being
 * played at a time, and segments of a chapter are fetched with the same headers
 * as its playlist. Bounded to [MAX_SESSIONS] so a long listening session on
 * different books cannot grow it without limit.
 */
public object OnlineHlsStreams {

  private const val MAX_SESSIONS = 8

  /** Only a handful of chapters are ever in play at once. */
  private const val MAX_HANDLED_CHAPTERS = 64

  private class Session(
    val headers: Map<String, String>,
    val host: String?,
    val domain: String?,
  ) {
    @Volatile
    var lastUsedAt: Long = System.nanoTime()
  }

  private val sessions = ConcurrentHashMap<String, Session>()

  /**
   * Chapters whose media source was already built from a playlist url. The
   * player consults this to avoid rebuilding a chapter that is already on the
   * HLS path, which would interrupt playback for no reason.
   */
  private val handledChapters = ConcurrentHashMap.newKeySet<String>()

  /** True when [url] points at an HLS playlist rather than a media file. */
  public fun isHlsUrl(url: String): Boolean {
    if (url.isEmpty()) return false
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return path.endsWith(".m3u8") || path.endsWith(".m3u")
  }

  /**
   * Registers [stream] as the HLS session of [chapterUri]. Repeated calls for
   * the same chapter (a re-resolve) replace the previous headers.
   */
  public fun remember(
    chapterUri: String,
    stream: ResolvedStream,
  ) {
    if (!isHlsUrl(stream.url)) return
    sessions[chapterUri] = Session(
      headers = stream.headers,
      host = hostOf(stream.url),
      domain = domainOf(stream.url),
    )
    trim()
  }

  /**
   * Headers the source asked for when it handed out the playlist containing
   * [url]. Empty when [url] does not belong to a known HLS session, which is
   * the case for every non HLS chapter and for unrelated http traffic.
   */
  public fun headersFor(url: String): Map<String, String> {
    val host = hostOf(url) ?: return emptyMap()
    val domain = domainOf(url)
    var best: Session? = null
    for (session in sessions.values) {
      val matches = host == session.host || (domain != null && domain == session.domain)
      if (!matches) continue
      if (best == null || session.lastUsedAt > best.lastUsedAt) best = session
    }
    best?.let { session ->
      session.lastUsedAt = System.nanoTime()
      return session.headers
    }
    // segments and keys are frequently served from a different host than the
    // playlist itself. With a single session in memory there is no ambiguity
    // about which source the request belongs to, so its headers are used;
    // with several sessions only the host/domain match above applies, so a
    // source never receives another source's referer or cookies.
    val only = sessions.values.singleOrNull()
    only?.let { session ->
      session.lastUsedAt = System.nanoTime()
      return session.headers
    }
    return emptyMap()
  }

  /** Marks [chapterUri] as already built from a playlist url. */
  public fun markHandled(chapterUri: String) {
    if (handledChapters.size >= MAX_HANDLED_CHAPTERS) handledChapters.clear()
    handledChapters.add(chapterUri)
  }

  /** True once [chapterUri]'s media source was built from a playlist url. */
  public fun isHandled(chapterUri: String): Boolean = chapterUri in handledChapters

  public fun clear() {
    sessions.clear()
    handledChapters.clear()
  }

  private fun trim() {
    if (sessions.size <= MAX_SESSIONS) return
    val oldest = sessions.entries
      .sortedBy { it.value.lastUsedAt }
      .take(sessions.size - MAX_SESSIONS)
    for ((chapterUri, session) in oldest) {
      sessions.remove(chapterUri, session)
    }
  }

  private fun hostOf(url: String): String? = try {
    URI(url).host?.lowercase()?.takeIf { it.isNotEmpty() }
  } catch (_: Exception) {
    null
  }

  /**
   * Coarse registrable domain (`cdn.example.com` -> `example.com`), so a
   * playlist on one sub domain and its segments on another still match. Only
   * used to decide whether a request may inherit a session's headers.
   *
   * An ip literal has no registrable domain: matching it by "last two labels"
   * would pair unrelated hosts, so ip literals only ever match their exact
   * host.
   */
  private fun domainOf(url: String): String? {
    val host = hostOf(url) ?: return null
    if (host.all { it.isDigit() || it == '.' }) return null
    return host.split('.')
      .takeLast(2)
      .joinToString(".")
      .takeIf { it.contains('.') }
  }
}
