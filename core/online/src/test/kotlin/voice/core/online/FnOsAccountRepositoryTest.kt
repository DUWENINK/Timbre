package voice.core.online

import androidx.datastore.core.DataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import voice.core.common.DispatcherProvider
import voice.core.common.KeystoreStringCipher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class FnOsAccountRepositoryTest {
  @Test
  fun accountChangesOnlyAfterSuccessfulLoginAndCanBeRestoredAndRemovedOffline() = runTest {
    val store = object : DataStore<String> {
      override val data = MutableStateFlow("")
      override suspend fun updateData(transform: suspend (String) -> String): String =
        transform(data.value).also { data.value = it }
    }
    val encryptedValues = mutableMapOf<String, String>()
    val cipher = mockk<KeystoreStringCipher>()
    every { cipher.encrypt(any()) } answers {
      val id = "encrypted-${encryptedValues.size}"
      encryptedValues[id] = firstArg()
      id
    }
    every { cipher.decrypt(any()) } answers { encryptedValues[firstArg<String>()] }
    val client = mockk<FnOsAuthClient>()
    val dispatcher = StandardTestDispatcher(testScheduler)
    val dispatchers = DispatcherProvider(main = dispatcher, io = dispatcher, mainImmediate = dispatcher)
    fun repository() = FnOsAccountRepository(client, store, cipher, dispatchers)

    val address = "https://nas.example:5667"
    val session = FnOsSession(address, "reader", "access-token", null, "signing-secret")
    coEvery { client.login(address, "reader", "typed password", any()) } returns session
    val repository = repository()
    repository.login(address, "reader", "typed password") { error("Unexpected challenge") }
    val expected = FnOsAccount(address, "reader")
    assertEquals(expected, repository.account.first())
    assertEquals("encrypted-0", store.data.first())
    assertFalse(encryptedValues.values.single().contains("typed password"))

    val path = "vol1/1010/chapter.mp3"
    val first = FnOsReadRequest("https://nas.example/download/first", emptyMap())
    val second = FnOsReadRequest("https://nas.example/download/second", emptyMap())
    coEvery { client.prepareRead(any(), path) } returnsMany listOf(first, second)
    assertEquals(first, repository.prepareRead(expected, path))
    assertEquals(first, repository.prepareRead(expected, path))
    assertFailsWith<FnOsAuthException> { repository.prepareRead(expected.copy(username = "other"), path) }
    assertEquals(second, repository.prepareRead(expected, path, forceRefresh = true))
    coVerify(exactly = 2) { val _ = client.prepareRead(any(), path) }

    coEvery { client.login(any(), "other", any(), any()) } throws FnOsAuthException(FnOsAuthError.REJECTED)
    assertFailsWith<FnOsAuthException> { repository.login(address, "other", "invalid") { "" } }
    val reopened = repository()
    assertEquals(expected, reopened.account.first())
    coEvery { client.verify(any()) } throws FnOsAuthException(FnOsAuthError.CONNECTION)
    assertFailsWith<FnOsAuthException> { reopened.verify() }
    assertEquals(expected, reopened.account.first())

    coEvery { client.verify(any()) } returns FnOsSession(address, "reader", "renewed", null, "signing-secret")
    reopened.verify()
    assertEquals("encrypted-1", store.data.first())
    coEvery { client.verify(any()) } throws FnOsAuthException(FnOsAuthError.SESSION_EXPIRED)
    assertFailsWith<FnOsAuthException> { reopened.verify() }
    assertNull(reopened.account.first())
    reopened.login(address, "reader", "typed password") { "" }
    reopened.logout()
    assertEquals("", store.data.first())
    assertNull(repository.account.first())
  }
}
