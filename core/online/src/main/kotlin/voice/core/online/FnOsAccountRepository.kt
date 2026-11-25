package voice.core.online

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import voice.core.common.DispatcherProvider
import voice.core.common.KeystoreStringCipher
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

public data class FnOsAccount(val address: String, val username: String)

public class FnOsAccountRepository internal constructor(
  private val client: FnOsAuthClient,
  private val store: DataStore<String>,
  private val cipher: KeystoreStringCipher,
  private val dispatchers: DispatcherProvider,
) {
  private val mutex = Mutex()
  private class Ticket(val request: FnOsReadRequest, val sessionToken: String, val created: TimeMark)
  private val tickets = LinkedHashMap<String, Ticket>(16, 0.75f, true)
  private val json = Json { ignoreUnknownKeys = true }
  private val sessions = store.data.map { encrypted ->
    val plain = encrypted.takeIf { it.isNotEmpty() }?.let(cipher::decrypt)
    try {
      plain?.let { json.decodeFromString<FnOsSession>(it) }
    } catch (_: SerializationException) {
      null
    }
  }.flowOn(dispatchers.io)

  public val account: Flow<FnOsAccount?> = sessions.map { session ->
    session?.let { FnOsAccount(it.address, it.username) }
  }.distinctUntilChanged()

  public suspend fun login(address: String, username: String, password: String, otp: suspend () -> String) {
    mutex.withLock {
      save(client.login(address, username, password, otp))
    }
  }

  public suspend fun verify() {
    mutex.withLock {
      try {
        val session = sessions.first() ?: throw FnOsAuthException(FnOsAuthError.SESSION_EXPIRED)
        val verified = client.verify(session)
        if (verified !== session) save(verified)
      } catch (e: FnOsAuthException) {
        if (e.reason == FnOsAuthError.SESSION_EXPIRED) forget()
        throw e
      }
    }
  }

  /** Removes this device's saved fnOS credentials, even when the NAS is offline. */
  public suspend fun logout() {
    mutex.withLock { forget() }
  }

  public suspend fun listFiles(account: FnOsAccount, path: String): List<FnOsFile> =
    withSession(account) { session -> client.listFiles(session, path) }

  public suspend fun listShareOwners(account: FnOsAccount): List<FnOsShareOwner> =
    withSession(account) { session -> client.listShareOwners(session) }

  public suspend fun listSharedFolders(account: FnOsAccount, owner: FnOsShareOwner): List<FnOsFile> =
    withSession(account) { session -> client.listSharedFolders(session, owner) }

  public suspend fun prepareRead(account: FnOsAccount, path: String, forceRefresh: Boolean = false): FnOsReadRequest =
    withSession(account) { session ->
      val cached = tickets[path]
      if (!forceRefresh && cached != null && cached.sessionToken == session.token && cached.created.elapsedNow() < 30.seconds) {
        cached.request
      } else {
        tickets.remove(path)
        val request = client.prepareRead(session, path)
        if (tickets.size >= 16) tickets.remove(tickets.keys.first())
        tickets[path] = Ticket(request, session.token, TimeSource.Monotonic.markNow())
        request
      }
    }

  private suspend fun <T> withSession(account: FnOsAccount, action: suspend (FnOsSession) -> T): T = mutex.withLock {
    val session = sessions.first()
    if (session == null || session.address != account.address || session.username != account.username) {
      throw FnOsAuthException(FnOsAuthError.SESSION_EXPIRED)
    }
    try {
      action(session)
    } catch (e: FnOsAuthException) {
      if (e.reason != FnOsAuthError.SESSION_EXPIRED) throw e
      val renewed = try {
        client.verify(session)
      } catch (verifyError: FnOsAuthException) {
        if (verifyError.reason == FnOsAuthError.SESSION_EXPIRED) forget()
        throw verifyError
      }
      if (renewed !== session) save(renewed)
      action(renewed)
    }
  }

  private suspend fun forget() {
    tickets.clear()
    store.updateData { "" }
  }

  private suspend fun save(session: FnOsSession) {
    val encrypted = withContext(dispatchers.io) {
      cipher.encrypt(json.encodeToString(FnOsSession.serializer(), session))
    }
    store.updateData { encrypted }
    tickets.clear()
  }
}
