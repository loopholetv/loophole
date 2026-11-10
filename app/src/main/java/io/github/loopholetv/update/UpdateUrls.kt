package io.github.loopholetv.update

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.IOException

/** Which URLs the updater may fetch (the release JSON, the APK and every redirect hop). */
fun interface UpdateUrlPolicy {
    fun allows(url: HttpUrl): Boolean

    fun allows(url: String): Boolean = url.toHttpUrlOrNull()?.let(::allows) ?: false

    companion object {
        /** GitHub's API and release-asset hosts, https only. */
        val GITHUB_ONLY = UpdateUrlPolicy { it.isHttps && it.host in GITHUB_UPDATE_HOSTS }

        /** Any http(s) URL: debug builds only, for a local test server. */
        val ANY_HTTP = UpdateUrlPolicy { it.scheme == "https" || it.scheme == "http" }
    }
}

val GITHUB_UPDATE_HOSTS = setOf(
    "api.github.com",
    "github.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
)

class BlockedUpdateUrlException : IOException("Update URL not allowed")

/**
 * The updater's own client: a copy of [shared] that never follows a redirect between https and
 * http and refuses any hop (redirects included) that [policy] does not allow.
 */
fun updateHttpClient(shared: OkHttpClient, policy: UpdateUrlPolicy): OkHttpClient =
    shared.newBuilder()
        .followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            if (!policy.allows(chain.request().url)) throw BlockedUpdateUrlException()
            chain.proceed(chain.request())
        }
        .build()
