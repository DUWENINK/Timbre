package voice.core.online

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.okhttp.OkHttpDataSource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.IOException

@SingleIn(AppScope::class)
@Inject
public class FnOsDataSourceFactory internal constructor(private val accounts: FnOsAccountRepository) : DataSource.Factory {
  private val http = OkHttpDataSource.Factory(
    OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build(),
  ).setContentTypePredicate { type ->
    !type.startsWith("text/html", ignoreCase = true) && !type.startsWith("application/json", ignoreCase = true)
  }

  override fun createDataSource(): DataSource {
    val upstream = http.createDataSource()
    return object : DataSource by upstream {
      private var fileUri: Uri? = null

      override fun open(dataSpec: DataSpec): Long {
        val account = runBlocking { accounts.account.first() } ?: throw IOException("Sign in to the fnOS NAS to read this book")
        val path = FnOsLibrary.path(account, dataSpec.uri) ?: throw IOException("This book belongs to a different fnOS account")
        fileUri = dataSpec.uri
        return try {
          upstream.open(resolve(account, path, dataSpec, forceRefresh = false))
        } catch (e: InvalidResponseCodeException) {
          if (e.responseCode != 401 && e.responseCode != 403) throw e
          upstream.close()
          upstream.open(resolve(account, path, dataSpec, forceRefresh = true))
        }
      }

      private fun resolve(account: FnOsAccount, path: String, dataSpec: DataSpec, forceRefresh: Boolean): DataSpec {
        val request = try {
          runBlocking { accounts.prepareRead(account, path, forceRefresh) }
        } catch (e: FnOsAuthException) {
          throw IOException("Could not open the fnOS file", e)
        }
        val origin = fnOsAddress(account.address)
        val url = request.url.toHttpUrlOrNull() ?: throw IOException("Invalid fnOS download URL")
        if (url.scheme != origin.scheme || url.host != origin.host || url.port != origin.port ||
          url.username.isNotEmpty() || url.password.isNotEmpty()
        ) {
          throw IOException("The download URL does not belong to this NAS")
        }
        return dataSpec.buildUpon().setUri(url.toString()).setHttpRequestHeaders(request.headers).build()
      }

      override fun getUri(): Uri? = fileUri

      override fun close() {
        fileUri = null
        upstream.close()
      }
    }
  }
}
