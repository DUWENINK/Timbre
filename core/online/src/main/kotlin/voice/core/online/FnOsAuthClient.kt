package voice.core.online

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@Serializable
public class FnOsSession(
  public val address: String,
  public val username: String,
  public val token: String,
  public val longToken: String?,
  public val secret: String,
  public val uid: Long? = null,
)

public enum class FnOsAuthError {
  INVALID_ADDRESS, REJECTED, INVALID_OTP, SETUP_REQUIRED, SESSION_EXPIRED, CONNECTION, PROTOCOL,
}

public class FnOsAuthException(public val reason: FnOsAuthError) : Exception(reason.name)

private fun fail(reason: FnOsAuthError): Nothing = throw FnOsAuthException(reason)
private fun malformed(): Nothing = fail(FnOsAuthError.PROTOCOL)

internal fun fnOsAddress(input: String): HttpUrl {
  val address = input.trim()
  if (address.isEmpty() || address.any { it.isWhitespace() || it == '\\' } || address.startsWith('/')) {
    fail(FnOsAuthError.INVALID_ADDRESS)
  }
  val explicitScheme = "://" in address
  val url = (if (explicitScheme) address else "https://$address").toHttpUrlOrNull()
    ?: fail(FnOsAuthError.INVALID_ADDRESS)
  if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) {
    fail(FnOsAuthError.INVALID_ADDRESS)
  }
  val authority = address.substringBefore('/')
  val hasPort = authority.substringAfterLast(']').contains(':')
  return if (!explicitScheme && !hasPort) url.newBuilder().port(5667).build() else url
}

private fun JsonObject.field(name: String): String? =
  (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

private fun JsonObject.flag(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

internal fun fnOsMessage(req: String, fields: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
  put("req", req)
  // fnOS accepts request identifiers of up to 28 characters.
  put("reqid", UUID.randomUUID().toString().replace("-", "").take(28))
  fields()
}

private fun JsonObjectBuilder.device(id: String) {
  put("deviceName", "Timbre Android")
  put("deviceType", "Browser")
  put("did", id)
}

private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)
private fun String.decode64(): ByteArray = Base64.getDecoder().decode(this)

// fnOS exchanges an RSA-wrapped AES key for each encrypted login or TOTP request.
private class EnvelopeKey {
  private val key = SecretKeySpec(ByteArray(32).also(random::nextBytes), "AES")
  private val ivSpec = IvParameterSpec(ByteArray(16).also(random::nextBytes))

  private fun cipher(mode: Int): Cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(mode, key, ivSpec) }

  suspend fun encrypt(request: JsonObject, publicKey: PublicKey): String = withContext(Dispatchers.Default) {
    val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE, publicKey) }
    buildJsonObject {
      put("req", "encrypted")
      put("iv", ivSpec.iv.base64())
      put("rsa", rsa.doFinal(key.encoded).base64())
      put("aes", cipher(Cipher.ENCRYPT_MODE).doFinal(request.toString().toByteArray()).base64())
    }.toString()
  }

  fun decryptSecret(secret: String): String =
    cipher(Cipher.DECRYPT_MODE).doFinal(secret.decode64()).takeIf { it.isNotEmpty() }?.base64() ?: malformed()

  companion object {
    private val random = SecureRandom()
  }
}

internal class FnOsConnection(
  private val ws: WebSocket,
  private val replies: Channel<String>,
  private val closed: CompletableDeferred<FnOsAuthError>,
) {
  lateinit var si: JsonPrimitive
  lateinit var key: PublicKey
  val deviceId: String = UUID.randomUUID().toString()

  suspend fun exchange(request: JsonObject, wire: String = request.toString(), streamField: String? = null): JsonObject {
    if (!ws.send(wire)) fail(FnOsAuthError.CONNECTION)
    return withTimeoutOrNull(if (streamField == null) 15_000L else 60_000L) {
      val items = mutableListOf<JsonElement>()
      var response: JsonObject
      do {
        response = Json.parseToJsonElement(replies.receive()) as? JsonObject ?: malformed()
        if (response["reqid"] != request["reqid"]) continue
        if (streamField == null) return@withTimeoutOrNull response
        val chunk = response[streamField] ?: (response["data"] as? JsonArray).takeIf { streamField == "files" }
        if (chunk != null && chunk != JsonNull) items.addAll(chunk as? JsonArray ?: malformed())
        if ("result" in response || "errno" in response || "errmsg" in response) break
      } while (true)
      JsonObject(response + (checkNotNull(streamField) to JsonArray(items)))
    } ?: fail(FnOsAuthError.CONNECTION)
  }

  suspend fun handshake(file: Boolean) {
    val hello = exchange(fnOsMessage(if (file) "util.getSI" else "util.crypto.getRSAPub"))
    si = (hello["si"] as? JsonPrimitive)?.takeIf { it != JsonNull && it.content.isNotBlank() } ?: malformed()
    if (file) return
    val pem = hello.field("pub") ?: malformed()
    val der = pem.trim().removePrefix("-----BEGIN PUBLIC KEY-----").removeSuffix("-----END PUBLIC KEY-----")
      .filterNot(Char::isWhitespace).decode64()
    key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
  }

  suspend fun awaitCode(otp: suspend () -> String): String = coroutineScope {
    val heartbeat = launch {
      while (true) {
        delay(30_000L)
        if (!ws.send("""{"req":"ping"}""")) fail(FnOsAuthError.CONNECTION)
      }
    }
    val prompt = async { otp() }
    try {
      withTimeoutOrNull(180_000L) {
        select {
          closed.onAwait { fail(it) }
          prompt.onAwait { it }
        }
      } ?: fail(FnOsAuthError.INVALID_OTP)
    } finally {
      heartbeat.cancel()
      prompt.cancel()
    }
  }

  suspend fun signed(request: JsonObject, secret: String, streamField: String? = null): JsonObject {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.decode64(), "HmacSHA256"))
    val json = request.toString()
    return exchange(request, mac.doFinal(json.toByteArray()).base64() + json, streamField)
  }
}

public class FnOsAuthClient {
  private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()

  public suspend fun login(
    address: String,
    username: String,
    password: String,
    otp: suspend () -> String,
  ): FnOsSession {
    val base = fnOsAddress(address)
    return connect(base) {
      var envelope = EnvelopeKey()
      val request = fnOsMessage("user.login") {
        put("user", username)
        put("password", password)
        put("stay", true)
        put("si", si)
        device(deviceId)
      }
      var response = exchange(request, envelope.encrypt(request, key))
      response.checkSuccess(FnOsAuthError.REJECTED)
      val challenge = response.field("accessToken")
      if (challenge != null && response.field("token") == null && response.field("secret") == null) {
        if (response.flag("isTwofaEnforced") == true && response.flag("isBindTwofaSecret") == false) {
          fail(FnOsAuthError.SETUP_REQUIRED)
        }
        if (response.flag("isBindTwofaSecret") != true || response.flag("isTrustedDevice") != false) malformed()
        val code = awaitCode(otp)
        if (!code.matches(Regex("[0-9]{6}"))) fail(FnOsAuthError.INVALID_OTP)
        val verify = fnOsMessage("user.2fa.loginVerify") {
          put("accessToken", challenge)
          put("code", code)
          put("isTrustedDevice", false)
          put("stay", 1)
          put("si", si)
          device(deviceId)
        }
        envelope = EnvelopeKey()
        response = exchange(verify, envelope.encrypt(verify, key))
        response.checkSuccess(FnOsAuthError.INVALID_OTP)
      }
      FnOsSession(
        address = base.toString().trimEnd('/'),
        username = username,
        token = response.field("token") ?: malformed(),
        longToken = response.field("longToken"),
        secret = envelope.decryptSecret(response.field("secret") ?: malformed()),
        uid = (response["uid"] as? JsonPrimitive)?.longOrNull,
      )
    }
  }

  public suspend fun verify(session: FnOsSession): FnOsSession = connect(fnOsAddress(session.address)) {
    if (authorize(session, main = true)) return@connect session
    val longToken = session.longToken ?: fail(FnOsAuthError.SESSION_EXPIRED)
    val renewed = signed(fnOsMessage("user.tokenLogin") {
      put("token", longToken)
      put("si", si)
      device(deviceId)
    }, session.secret)
    renewed.checkSuccess(FnOsAuthError.SESSION_EXPIRED)
    FnOsSession(session.address, session.username, renewed.field("token") ?: malformed(), renewed.field("longToken") ?: longToken, session.secret, session.uid)
  }

  internal suspend fun listFiles(session: FnOsSession, path: String): List<FnOsFile> =
    withFileConnection(session) { listDirectory(session, path) }

  internal suspend fun prepareRead(session: FnOsSession, path: String): FnOsReadRequest =
    withFileConnection(session) { downloadRequest(session, path) }

  private suspend fun <T> withFileConnection(session: FnOsSession, action: suspend FnOsConnection.() -> T): T =
    withContext(Dispatchers.IO) {
      connect(fnOsAddress(session.address), file = true) {
        if (!authorize(session, main = false)) fail(FnOsAuthError.SESSION_EXPIRED)
        action()
      }
    }

  private suspend fun FnOsConnection.authorize(session: FnOsSession, main: Boolean): Boolean {
    val auth = signed(fnOsMessage("user.authToken") {
      if (main) put("main", true)
      put("token", session.token)
      put("si", si)
    }, session.secret)
    if (auth.field("result") == "succ") return true
    if ((auth["errno"] as? JsonPrimitive)?.intOrNull == 135168) return false
    malformed()
  }

  private suspend fun <T> connect(base: HttpUrl, file: Boolean = false, action: suspend FnOsConnection.() -> T): T {
    val url = base.newBuilder().addPathSegment("websocket").addQueryParameter("type", if (file) "file" else "main").build()
    val frameLimit = if (file) 4 * 1024 * 1024 else MAX_FRAME_SIZE
    val replies = Channel<String>(if (file) 2 else 32)
    val closed = CompletableDeferred<FnOsAuthError>()
    val socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
      override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        if (bytes.size > frameLimit) {
          closeReplies(FnOsAuthError.PROTOCOL)
          webSocket.cancel()
        } else {
          onMessage(webSocket, bytes.utf8())
        }
      }

      override fun onMessage(webSocket: WebSocket, text: String) {
        if (text.length > frameLimit || (if (file) replies.trySendBlocking(text) else replies.trySend(text)).isFailure) {
          closeReplies(FnOsAuthError.PROTOCOL)
          webSocket.cancel()
        }
      }

      override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        closeReplies(FnOsAuthError.CONNECTION)
        webSocket.close(code, null)
      }

      override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        closeReplies(FnOsAuthError.CONNECTION)
      }

      private fun closeReplies(reason: FnOsAuthError) {
        closed.complete(reason)
        replies.close(FnOsAuthException(reason))
      }
    })
    try {
      val connection = FnOsConnection(socket, replies, closed)
      connection.handshake(file)
      return connection.action()
    } catch (_: GeneralSecurityException) {
      malformed()
    } catch (_: IllegalArgumentException) {
      malformed()
    } finally {
      socket.cancel()
      replies.cancel()
    }
  }
}

private const val MAX_FRAME_SIZE = 65_536

private fun JsonObject.checkSuccess(rejection: FnOsAuthError) {
  when (field("result")) {
    "succ" -> Unit
    "fail" -> fail(rejection)
    else -> malformed()
  }
}
