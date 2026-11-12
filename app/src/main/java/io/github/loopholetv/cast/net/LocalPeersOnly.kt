package io.github.loopholetv.cast.net

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Refuses, before anything connects, every request whose host isn't an IPv4 literal on the local
 * network ([isLocalIpv4Literal]); a name is refused even if it would resolve to a local address.
 * For clients that only ever talk to other TVs, so a playlist served by one can't send the player
 * anywhere else. The refusal names no address.
 */
class LocalPeersOnly : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!isLocalIpv4Literal(chain.request().url.host)) throw IOException("not a local peer")
        return chain.proceed(chain.request())
    }
}
