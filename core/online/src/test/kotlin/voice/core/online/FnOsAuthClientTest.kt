package voice.core.online

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Before
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FnOsAuthClientTest {
  private val server = MockWebServer()
  private val client = FnOsAuthClient()
  private val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
  private val signingKey = ByteArray(32) { it.toByte() }
  private val requests = CopyOnWriteArrayList<JsonObject>()
  private val base get() = server.url("/fnos/").toString().trimEnd('/')

  @Before fun start() = server.start()

  @After fun stop() = server.close()

  @Test
  fun normalizesSystemAddresses() {
    assertEquals("https://nas.local:5667/", fnOsAddress("nas.local").toString())
    assertEquals("https://[::1]:5667/", fnOsAddress("[::1]").toString())
    assertEquals("https://[::1]:8001/", fnOsAddress("[::1]:8001").toString())
    assertEquals("https://nas.local/", fnOsAddress("https://nas.local").toString())
    listOf("https://user:password@nas", "https://nas/?token=x", "ftp://nas", "").forEach {
      assertEquals(FnOsAuthError.INVALID_ADDRESS, assertFailsWith<FnOsAuthException> { fnOsAddress(it) }.reason)
    }
  }

  @Test
  fun logsInUsingEncryptedNasCredentials() = runBlocking {
    serve()
    val session = client.login(base, "用户", " secret ") { error("Unexpected TOTP") }
    assertEquals(base, session.address)
    assertEquals("short", session.token)
    assertEquals("long", session.longToken)
    assertEquals(b64(signingKey), session.secret)
    val url = server.takeRequest().url
    assertEquals("/fnos/websocket", url.encodedPath)
    assertEquals("type=main", url.encodedQuery)
    assertEquals("用户", requests.last().str("user"))
    assertEquals(" secret ", requests.last().str("password"))
    assertEquals("session1", requests.last().str("si"))
  }

  @Test
  fun completesTotpOnSameSocketWithoutLongToken() = runBlocking {
    serve(loginReply = """{"result":"succ","token":null,"secret":"","isBindTwofaSecret":true,"isTrustedDevice":false,"accessToken":"challenge"}""")
    val session = client.login(base, "reader", "pw") { "012345" }
    assertEquals(listOf("util.crypto.getRSAPub", "user.login", "user.2fa.loginVerify"), requests.map { it.str("req") })
    assertEquals("012345", requests.last().str("code"))
    assertEquals("challenge", requests.last().str("accessToken"))
    assertEquals(null, session.longToken)
    assertEquals(b64(signingKey), session.secret)
  }

  @Test
  fun disconnectWhileWaitingForTotpCancelsPrompt() = runBlocking {
    serve(
      loginReply = """{"result":"succ","isBindTwofaSecret":true,"isTrustedDevice":false,"accessToken":"challenge"}""",
      disconnectAfterChallenge = true,
    )
    val error = assertFailsWith<FnOsAuthException> {
      client.login(base, "reader", "pw") { awaitCancellation() }
    }
    assertEquals(FnOsAuthError.CONNECTION, error.reason)
  }

  @Test
  fun acceptsValidStoredSessionWithoutRenewing() = runBlocking {
    serve()
    val session = FnOsSession(base, "reader", "valid", null, b64(signingKey))
    assertEquals(session, client.verify(session))
    assertEquals(listOf("util.crypto.getRSAPub", "user.authToken"), requests.map { it.str("req") })
  }

  @Test
  fun distinguishesRejectedLoginSetupAndPartialReply() = runBlocking {
    val cases = mapOf(
      """{"result":"fail"}""" to FnOsAuthError.REJECTED,
      """{"result":"succ","accessToken":"x","isTwofaEnforced":true,"isBindTwofaSecret":false}""" to FnOsAuthError.SETUP_REQUIRED,
      """{"result":"succ","token":"partial"}""" to FnOsAuthError.PROTOCOL,
    )
    for ((reply, expected) in cases) {
      serve(loginReply = reply)
      val error = assertFailsWith<FnOsAuthException> { client.login(base, "reader", "pw") { error("Unexpected TOTP") } }
      assertEquals(expected, error.reason)
    }
  }

  @Test
  fun refreshesExpiredTokenWithSignedRequests() = runBlocking {
    serve()
    val session = FnOsSession(base, "reader", "expired", "long", b64(signingKey))
    val renewed = client.verify(session)
    assertEquals("renewed", renewed.token)
    assertEquals("expired", requests[1].str("token"))
    assertEquals("long", requests[2].str("token"))
    assertEquals("user.tokenLogin", requests[2].str("req"))
  }

  @Test
  fun missingRenewalTokenRequiresNewLogin() = runBlocking {
    serve()
    val session = FnOsSession(base, "reader", "expired", null, b64(signingKey))
    assertEquals(FnOsAuthError.SESSION_EXPIRED, assertFailsWith<FnOsAuthException> { client.verify(session) }.reason)
    assertEquals(2, requests.size)
  }

  @Test
  fun rejectsWebsocketRedirect() = runBlocking {
    server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/other")).build())
    val error = assertFailsWith<FnOsAuthException> { client.login(base, "reader", "pw") { error("Unexpected TOTP") } }
    assertEquals(FnOsAuthError.CONNECTION, error.reason)
    assertEquals(1, server.requestCount)
  }

  private fun serve(loginReply: String? = null, disconnectAfterChallenge: Boolean = false) {
    server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
      override fun onMessage(webSocket: WebSocket, text: String) {
        val signed = !text.startsWith("{")
        val json = if (signed) text.substring(44) else text
        val wire = Json.parseToJsonElement(json).jsonObject
        if (signed) {
          val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(signingKey, "HmacSHA256")) }
          if (text.take(44) != b64(mac.doFinal(json.toByteArray()))) {
            webSocket.close(1008, "Invalid signature")
            return
          }
        }
        var aes: Cipher? = null
        val request = if (wire.str("req") == "encrypted") {
          val unwrap = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, rsa.private) }
          val key = SecretKeySpec(unwrap.doFinal(decode(wire.str("rsa"))), "AES")
          val iv = IvParameterSpec(decode(wire.str("iv")))
          val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
          cipher.init(Cipher.DECRYPT_MODE, key, iv)
          val decrypted = Json.parseToJsonElement(cipher.doFinal(decode(wire.str("aes"))).decodeToString()).jsonObject
          cipher.init(Cipher.ENCRYPT_MODE, key, iv)
          aes = cipher
          decrypted
        } else wire
        requests.add(request)
        val method = request.str("req")
        val result = buildJsonObject {
          put("reqid", request.str("reqid"))
          put("result", "succ")
          when (method) {
            "util.crypto.getRSAPub" -> {
              put("si", "session1")
              put("pub", "-----BEGIN PUBLIC KEY-----\n${b64(rsa.public.encoded)}\n-----END PUBLIC KEY-----\n")
            }
            "user.authToken" -> {
              if (request.str("token") != "valid") {
                put("result", "fail")
                put("errno", 135168)
              }
            }
            "user.tokenLogin" -> put("token", "renewed")
            "user.login", "user.2fa.loginVerify" -> {
              if (method == "user.login" && loginReply != null) {
                Json.parseToJsonElement(loginReply).jsonObject.forEach { (key, value) -> put(key, value) }
              } else {
                put("token", "short")
                if (method == "user.login") put("longToken", "long")
                put("secret", b64(checkNotNull(aes).doFinal(signingKey)))
              }
            }
          }
        }
        // Real servers may interleave unsolicited notifications or replies for other calls.
        webSocket.send("""{"reqid":"other","result":"fail"}""")
        if (method == "util.crypto.getRSAPub") {
          webSocket.send(result.toString().encodeUtf8())
        } else {
          webSocket.send(result.toString())
        }
        if (method == "user.login" && disconnectAfterChallenge) webSocket.close(1001, null)
      }
    }).build())
  }

  private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
  private fun b64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)
  private fun decode(data: String): ByteArray = Base64.getDecoder().decode(data)
}
