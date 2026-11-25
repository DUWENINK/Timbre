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
import java.security.KeyPairGenerator
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
  fun copiedFolderPathsAcceptVolumePathsAndRejectWebLinksAndTraversal() {
    assertEquals("vol2/1012/听书 #1", fnOsFolderPath("/vol2/1012/听书 #1/"))
    assertEquals("vol2/1012/听书 #1", fnOsFolderPath("vol2/1012/听书 #1"))
    for (invalid in listOf("", "/vol2/", "https://nas.test/share/book", "/vol2/../private", "/vol2//book")) {
      assertEquals(null, fnOsFolderPath(invalid), invalid)
    }
  }

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
  fun usesEmbeddedOwnerNamesWithoutALookupAndListsWithTheReadersSession() = runBlocking {
    serve(listOf(
      """{"users":[{"uid":1012,"name":"共享者"}]}""",
      """{"users":[{"uid":1013,"name":"另一个账号"},1012],"uver":111677748019200,"result":"succ"}""",
    ))
    val owners = client.listShareOwners(session)
    assertEquals(listOf(FnOsShareOwner(1012, "共享者"), FnOsShareOwner(1013, "另一个账号")), owners)
    assertEquals(listOf("util.getSI", "user.authToken", "file.share.listOthers"), requests.map { it.getValue("req").jsonPrimitive.content })
    assertEquals(1, server.requestCount)
    assertFalse(requests.last().containsKey("uid"))

    serve(listOf(
      """{"share":[{"path":"/vol2/1012/Books/#Audio/","link":"Library","uid":1012,"v":2,"mtim":1700000000}]}""",
      """{"result":"succ"}""",
    ))
    val folder = client.listSharedFolders(session, owners.first()).single()
    assertEquals(FnOsFile("#Audio", "vol2/1012/Books/#Audio", true, 0, 1700000000000), folder)
    assertEquals("1012", requests.last().getValue("uid").jsonPrimitive.content)
    assertEquals("file.share.list", requests.last().getValue("req").jsonPrimitive.content)
    serve(listOf("""{"files":[{"name":"1.mp3","size":32}],"result":"succ"}"""))
    val audio = client.listFiles(session, folder.path).single()
    assertEquals("vol2/1012/Books/#Audio/1.mp3", audio.path)
    assertEquals("session-token", requests[requests.lastIndex - 1].getValue("token").jsonPrimitive.content)
  }

  @Test
  fun followsIdsFromTheFileManagersUidArrayToTheSelectedOwnersFolders() = runBlocking {
    serve(listOf(
      """{"uid":[1012],"users":[{"uid":4000,"name":"unrelated"}]}""",
      """{"uid":["1013",1012],"uver":111677748019200,"result":"succ"}""",
    ))
    serve(listOf(
      """{"users":[{"uid":4000,"user":"无关用户"},{"uid":1012,"user":"可可"}]}""",
      """{"groups":[{"uid":1013,"user":"共享组"}],"result":"succ"}""",
    ))
    val owners = client.listShareOwners(session)
    assertEquals(listOf(FnOsShareOwner(1012, "可可"), FnOsShareOwner(1013, "UID 1013")), owners)
    val listRequest = requests.single { it.getValue("req").jsonPrimitive.content == "file.share.listOthers" }
    assertFalse("uid" in listRequest)
    assertEquals(2, server.requestCount)
    assertEquals("type=file", server.takeRequest().url.encodedQuery)
    assertEquals("type=main", server.takeRequest().url.encodedQuery)
    val auth = requests.single { it.getValue("req").jsonPrimitive.content == "user.authToken" && "main" in it }
    assertEquals("true", auth.getValue("main").toString())
    assertEquals(session.token, auth.getValue("token").jsonPrimitive.content)
    val lookup = requests.single { it.getValue("req").jsonPrimitive.content == "user.listUG" }
    assertEquals("true", lookup.getValue("users").toString())
    assertEquals("false", lookup.getValue("groups").toString())
    assertFalse("uver" in lookup)

    serve(listOf(
      """{"share":[{"path":"/vol2/1013/Private/PhysicalName","link":"/vol01/1013/听书 #1/"}]}""",
      """{"share":[{"link":"vol01/1013/Only Link"}],"result":"succ"}""",
    ))
    val folders = client.listSharedFolders(session, owners.last())
    assertEquals(listOf("听书 #1", "Only Link"), folders.map { it.name })
    assertEquals(listOf("vol01/1013/听书 #1", "vol01/1013/Only Link"), folders.map { it.path })
    assertEquals("file.share.list", requests.last().getValue("req").jsonPrimitive.content)
    assertEquals("1013", requests.last().getValue("uid").toString())

    serve(listOf("""{"files":[{"name":"01.mp3"}],"result":"succ"}"""))
    val audio = client.listFiles(session, folders.first().path).single()
    assertEquals("vol01/1013/听书 #1", requests.last().getValue("path").jsonPrimitive.content)
    serve(listOf("""{"download":[{"uri":"/download/chapter01"}],"result":"succ"}"""))
    val read = client.prepareRead(session, audio.path)
    assertEquals("file.download", requests.last().getValue("req").jsonPrimitive.content)
    assertEquals("vol01/1013/听书 #1/01.mp3", (requests.last().getValue("files") as JsonArray).single().jsonPrimitive.content)
    assertEquals("fnos-token=session-token", read.headers["Cookie"])
  }

  @Test
  fun keepsVisibleOwnersWhenTheServerDeniesTheNameLookup() = runBlocking {
    serve(listOf("""{"users":[1012,{"uid":1013,"name":"共享者"}],"result":"succ"}"""))
    serve(listOf("""{"result":"fail","errno":4100,"errmsg":"access denied"}"""))

    assertEquals(listOf(FnOsShareOwner(1012, "UID 1012"), FnOsShareOwner(1013, "共享者")), client.listShareOwners(session))
    assertEquals("user.listUG", requests.last().getValue("req").jsonPrimitive.content)
  }

  @Test
  fun keepsVisibleOwnersWhenTheMainSocketIsUnavailable() = runBlocking {
    serve(listOf("""{"uid":[1012],"result":"succ"}"""))
    server.enqueue(MockResponse.Builder().code(503).build())

    assertEquals(listOf(FnOsShareOwner(1012, "UID 1012")), client.listShareOwners(session))
    assertEquals(2, server.requestCount)
  }

  @Test
  fun emptyVisibleOwnersAndRevokedSharesKeepTheServersMeaning() = runBlocking {
    serve(listOf("""{"uver":111677748019200,"result":"succ"}"""))
    assertEquals(emptyList(), client.listShareOwners(session))
    serve(listOf("""{"uid":[],"result":"succ"}"""))
    assertEquals(emptyList(), client.listShareOwners(session))
    assertEquals(2, server.requestCount)
    assertFalse(requests.any { it.getValue("req").jsonPrimitive.content == "user.listUG" })
    serve(listOf("""{"uid":{"invalid":true},"errno":4100}"""))
    assertEquals(FnOsAuthError.REJECTED, assertFailsWith<FnOsAuthException> { client.listShareOwners(session) }.reason)
    serve(listOf("""{"uid":[1012]}""", """{"uid":false,"errmsg":"access denied"}"""))
    assertEquals(FnOsAuthError.REJECTED, assertFailsWith<FnOsAuthException> { client.listShareOwners(session) }.reason)
    serve(listOf("""{"users":[{"name":"Missing explicit UID"}],"result":"succ"}"""))
    assertEquals(FnOsAuthError.PROTOCOL, assertFailsWith<FnOsAuthException> { client.listShareOwners(session) }.reason)
    serve(listOf("""{"result":"fail","errno":4100,"errmsg":"path not found"}"""))
    assertEquals(FnOsAuthError.REJECTED, assertFailsWith<FnOsAuthException> {
      client.listSharedFolders(session, FnOsShareOwner(1012, "Other account"))
    }.reason)
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
        if (method != "util.getSI" && method != "util.crypto.getRSAPub") {
          val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
          if (text.take(44) != Base64.getEncoder().encodeToString(mac.doFinal(json.toByteArray()))) {
            webSocket.close(1008, "Invalid authentication")
            return
          }
        }
        val replies = when (method) {
          "util.getSI" -> listOf("""{"si":"1"}""")
          "util.crypto.getRSAPub" -> listOf("""{"si":"2","pub":"$publicKey"}""")
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

  private companion object {
    val publicKey: String by lazy {
      val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
      Base64.getEncoder().encodeToString(generator.generateKeyPair().public.encoded)
    }
  }
}
