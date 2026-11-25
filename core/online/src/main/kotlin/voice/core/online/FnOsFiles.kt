package voice.core.online

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

public data class FnOsFile(
  val name: String,
  val path: String,
  val isDirectory: Boolean,
  val size: Long,
  val modifiedAt: Long,
)

/** Short-lived GET request. Send only to its original URL, without following redirects. */
public class FnOsReadRequest(public val url: String, public val headers: Map<String, String>)

private fun protocolError(): Nothing = throw FnOsAuthException(FnOsAuthError.PROTOCOL)

private fun JsonObject.string(name: String) = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.number(name: String) = (this[name] as? JsonPrimitive)?.longOrNull

private fun JsonObject.checkFileResult() {
  val result = string("result")
  val errno = number("errno")
  if (result == "fail" || (errno != null && errno != 0L) || string("errmsg")?.isNotBlank() == true) {
    throw FnOsAuthException(FnOsAuthError.REJECTED)
  }
}

private fun validName(name: String): Boolean =
  name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name && '\u0000' !in name

private fun pathOf(raw: String): String {
  val path = raw.removePrefix("/")
  require(path.split('/').all(::validName) && Regex("vol[0-9]+").matches(path.substringBefore('/')))
  return path
}

internal suspend fun FnOsConnection.listDirectory(session: FnOsSession, path: String): List<FnOsFile> {
  val parent = if (path == "" || path == "/") "" else pathOf(path)
  val response = signed(fnOsMessage("file.ls") {
    if (parent.isNotEmpty()) put("path", parent)
  }, session.secret, streamField = "files")
  response.checkFileResult()
  var needsVolumes = false
  val entries = (response["files"] as JsonArray).mapNotNull { element ->
    val item = element as? JsonObject ?: protocolError()
    val name = item.string("name")?.takeIf(::validName) ?: return@mapNotNull null
    val volume = item.number("v") ?: item.number("vid")
    val owner = session.uid ?: item.number("uid")
    val suppliedPath = item.string("path")
    val pathValue = when {
      parent.isNotEmpty() -> "$parent/$name"
      !suppliedPath.isNullOrEmpty() -> pathOf(suppliedPath)
      volume != null && owner != null -> "vol$volume/$owner/$name"
      else -> {
        // Some firmware omits volume metadata on the aggregate root. Offer the
        // account's volume directories so those entries are still reachable.
        needsVolumes = true
        return@mapNotNull null
      }
    }
    val dir = item["dir"] as? JsonPrimitive
    FnOsFile(name, pathValue, dir?.intOrNull == 1 || dir?.booleanOrNull == true,
      item.number("size") ?: 0, (item.number("mtim") ?: 0) * 1000)
  }
  return if (needsVolumes) {
    val roots = signed(fnOsMessage("stor.getUserStorage"), session.secret)
    roots.checkFileResult()
    val uid = roots.number("uid") ?: session.uid ?: protocolError()
    val volumes = roots["stor"] as? JsonArray ?: protocolError()
    entries + volumes.map { volume ->
      val info = volume as? JsonObject ?: protocolError()
      val id = info.number("id") ?: protocolError()
      FnOsFile(info.string("comment")?.takeIf { it.isNotBlank() } ?: "vol$id", "vol$id/$uid", true, 0, 0)
    }.filter { root -> entries.none { it.path == root.path } }
  } else entries
}

internal suspend fun FnOsConnection.downloadRequest(session: FnOsSession, path: String): FnOsReadRequest {
  val request = fnOsMessage("file.download") {
    put("files", JsonArray(listOf(JsonPrimitive(pathOf(path)))))
  }
  val reply = signed(request, session.secret, streamField = "download")
  reply.checkFileResult()
  val entry = (reply["download"] as JsonArray).firstOrNull() as? JsonObject
  entry?.checkFileResult()
  val location = sequenceOf(entry, reply).filterNotNull().firstNotNullOfOrNull { item ->
    listOf("uri", "url", "downloadUrl", "href", "link", "src", "location")
      .firstNotNullOfOrNull { item.string(it)?.takeIf(String::isNotBlank) }
  } ?: protocolError()
  val base = fnOsAddress(session.address)
  val url = base.resolve(location) ?: protocolError()
  if (url.scheme != base.scheme || url.host != base.host || url.port != base.port ||
    url.username.isNotEmpty() || url.password.isNotEmpty()) protocolError()
  return FnOsReadRequest(url.toString(), mapOf("Cookie" to "fnos-token=${session.token}"))
}
