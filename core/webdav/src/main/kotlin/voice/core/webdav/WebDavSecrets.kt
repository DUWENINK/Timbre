package voice.core.webdav

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import voice.core.common.KeystoreStringCipher

public interface WebDavSecrets {
  public fun encrypt(plain: String): String
  public fun decrypt(encrypted: String): String?
}

@ContributesBinding(AppScope::class)
@Inject
public class KeystoreWebDavSecrets : WebDavSecrets {
  private val cipher = KeystoreStringCipher("voice_webdav_password")

  override fun encrypt(plain: String): String = cipher.encrypt(plain)

  override fun decrypt(encrypted: String): String? = cipher.decrypt(encrypted)
}
