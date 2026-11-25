package voice.core.online

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FnOsFilesTest {
  private val server = MockWebServer().apply { start() }
  private val key = ByteArray(32) { it.toByte() }
  private val session = FnOsSession(server.url("/nas/").toString(), "user", "session-token", null, Base64.getEncoder().encodeToString(key))
  private val requests = CopyOnWriteArrayList<JsonObject>()
  private val client = FnOsAuthClient()

  @AfterTest fun close() = server.close()

  @Test
  fun rootAndChildListingsIncludeAllChunksAndPreserveSpecialNames() = runBlocking {
    serve(listOf(
      """{"files":[{"name":"听书","v":2,"uid":1010,"dir":1}]}""",
      """{"data":[{"name":"共享","dir":true,"path":"/vol3/1003/共享"}],"result":"succ"}""",
    ))
    val root = client.listFiles(session, "")
    assertEquals(listOf("vol2/1010/听书", "vol3/1003/共享"), root.map { it.path })
    assertEquals("type=file", server.takeRequest().url.encodedQuery)
    assertFalse(requests[1].containsKey("main"))
    assertFalse(requests.last().containsKey("path"))

    serve(listOf("""{"files":[{"name":"章 #1%.mp3","size":42,"mtim":1750000000}],"errno":0}"""))
    val file = client.listFiles(session, root.first().path).single()
    assertEquals("vol2/1010/听书", requests.last()["path"]!!.jsonPrimitive.content)
    assertEquals(FnOsFile("章 #1%.mp3", "vol2/1010/听书/章 #1%.mp3", false, 42, 1750000000000), file)
  }

  @Test
  fun incompleteRootMetadataExposesTheAuthorizedVolume() = runBlocking {
    serve(listOf("""{"files":[{"name":"chapter.mp3","uid":1010}],"result":"succ"}"""))
    val volume = client.listFiles(session, "/").single()
    assertEquals("vol3/1010", volume.path)
    assertEquals(true, volume.isDirectory)
  }

  @Test
  fun downloadsOneFileFromItsSignedUriWithARangeAndTheSessionCookie() = runBlocking {
    serve(listOf("""{"download":[{"errno":0,"uri":"/download/audio?ticket=short"}]}""", """{"result":"succ"}"""))
    val read = client.prepareRead(session, "vol2/1010/听书/章 #1%.mp3")
    val files = requests.last()["files"] as JsonArray
    assertEquals("vol2/1010/听书/章 #1%.mp3", files.single().jsonPrimitive.content)
    server.takeRequest()
    server.enqueue(MockResponse.Builder().code(206).addHeader("Content-Range", "bytes 2-3/6").body("cd").build())
    val request = Request.Builder().url(read.url).header("Range", "bytes=2-3").apply {
      read.headers.forEach { (key, value) -> header(key, value) }
    }.build()
    OkHttpClient.Builder().followRedirects(false).build().newCall(request).execute().use {
      assertEquals(206, it.code)
      assertEquals("cd", it.body.string())
    }
    val get = server.takeRequest()
    assertEquals("/download/audio", get.url.encodedPath)
    assertEquals("session-token", get.headers["Cookie"]!!.substringAfter("fnos-token="))
    assertEquals("bytes=2-3", get.headers["Range"])
  }

  @Test
  fun rejectsExternalDownloadTargetsAndAccessDenied() = runBlocking {
    serve(listOf("""{"download":[{"url":"//elsewhere.invalid/path"}],"result":"succ"}"""))
    assertEquals(FnOsAuthError.PROTOCOL, assertFailsWith<FnOsAuthException> {
      client.prepareRead(session, "vol1/1010/01.mp3")
    }.reason)
    serve(listOf("""{"result":"fail","errno":1}"""))
    assertEquals(FnOsAuthError.REJECTED, assertFailsWith<FnOsAuthException> {
      client.listFiles(session, "vol1/1010/private")
    }.reason)
  }

  private fun serve(packets: List<String>) {
    server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
      override fun onMessage(webSocket: WebSocket, text: String) {
        val json = if (text.startsWith("{")) text else text.drop(44)
        val request = Json.parseToJsonElement(json).jsonObject
        requests += request
        val method = request.getValue("req").jsonPrimitive.content
        if (method != "util.getSI") {
          val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
          if (text.take(44) != Base64.getEncoder().encodeToString(mac.doFinal(json.toByteArray()))) {
            webSocket.close(1008, "Invalid authentication")
            return
          }
        }
        val replies = when (method) {
          "util.getSI" -> listOf("""{"si":"1"}""")
          "user.authToken" -> listOf("""{"result":"succ"}""")
          "stor.getUserStorage" -> listOf("""{"stor":[{"id":3,"comment":""}],"uid":1010,"result":"succ"}""")
          else -> packets
        }
        for (reply in replies) {
          webSocket.send(buildJsonObject {
            put("reqid", request.getValue("reqid"))
            Json.parseToJsonElement(reply).jsonObject.forEach { (key, value) -> put(key, value) }
          }.toString())
        }
      }
    }).build())
  }
}
