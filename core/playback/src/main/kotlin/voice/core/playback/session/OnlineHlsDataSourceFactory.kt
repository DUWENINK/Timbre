@file:Suppress("UnstableApiUsage")

package voice.core.playback.session

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import voice.core.online.OnlineHlsStreams
import voice.core.online.OnlineStreamAuthProvider

/**
 * Data source for HLS playlist, segment and key requests.
 *
 * The playback data source chain only knows `online://` uris; an HLS playlist
 * is fetched from its real http url and its relative segment/key uris are
 * resolved against it by the HLS parser, so those requests never go through
 * [voice.core.online.OnlineStreamingDataSource]. Feeding them the source
 * headers (Referer/User-Agent/Cookie for hotlink-protected CDNs) therefore has
 * to happen here, per request, by looking the url up in
 * [OnlineHlsStreams].
 */
internal fun hlsDataSourceFactory(auth: OnlineStreamAuthProvider): DataSource.Factory {
  val upstream = DefaultHttpDataSource.Factory()
    .setAllowCrossProtocolRedirects(true)
    .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
    .setReadTimeoutMs(READ_TIMEOUT_MS)
  return ResolvingDataSource.Factory(
    upstream,
    ResolvingDataSource.Resolver { dataSpec ->
      val url = dataSpec.uri.toString()
      val headers = LinkedHashMap(OnlineHlsStreams.headersFor(url))
      // the app's own bearer token only goes to the configured server, and a
      // header the source script set explicitly wins over it
      if (headers.keys.none { it.equals("Authorization", ignoreCase = true) }) {
        auth.authorizationFor(url)?.let { headers["Authorization"] = it }
      }
      if (headers.isEmpty()) {
        dataSpec
      } else {
        // merge instead of replace: the HLS pipeline may already carry headers
        // on the spec, and these values win on a name clash
        dataSpec.buildUpon()
          .setHttpRequestHeaders(mergeHeaders(dataSpec.httpRequestHeaders, headers))
          .build()
      }
    },
  )
}

/**
 * Merges [extra] over [base] ignoring header name case. A case-sensitive merge
 * would leave two `Referer` lines (one from the pipeline, one from the source)
 * and strict CDNs reject such a request.
 */
private fun mergeHeaders(
  base: Map<String, String>,
  extra: Map<String, String>,
): Map<String, String> {
  val merged = LinkedHashMap<String, String>(base.size + extra.size)
  for ((name, value) in base) {
    if (extra.keys.none { it.equals(name, ignoreCase = true) }) merged[name] = value
  }
  merged.putAll(extra)
  return merged
}

private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 60_000
