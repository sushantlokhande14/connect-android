package app.connect.data.network

import app.connect.data.session.SessionStore
import app.connect.domain.diagnostics.Breadcrumbs
import app.connect.domain.model.SignOutReason
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Attaches the access token and, when the server answers 401, refreshes the
 * session once and replays the request.
 *
 * Several requests usually fail together when a token expires (the discovery
 * screen sends five at once). The refresh is therefore single-flight: the
 * first failing request refreshes while the others wait on the lock, see that
 * the token already changed, and replay with the new one. Refresh tokens are
 * single-use on the server, so two parallel refreshes would sign the user out.
 *
 * This is an interceptor rather than an OkHttp Authenticator so it behaves the
 * same against a real socket and against the in-process demo backend, which
 * answers above the layer where Authenticators run.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val sessions: SessionStore,
    private val authApi: Provider<AuthApi>,
    private val breadcrumbs: Breadcrumbs,
) : Interceptor {
    private val refreshLock = Any()

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = sessions.current()?.tokens?.accessToken ?: return chain.proceed(original)

        val response = chain.proceed(original.withBearer(token))
        if (response.code != 401) return response

        val fresh = refreshedToken(failedToken = token) ?: return response
        response.close()
        return chain.proceed(original.withBearer(fresh))
    }

    /** The token to replay with, or null if the session could not be renewed. */
    private fun refreshedToken(failedToken: String): String? = synchronized(refreshLock) {
        val current = sessions.current() ?: return null
        if (current.tokens.accessToken != failedToken) {
            // Another request refreshed while this one waited for the lock.
            return current.tokens.accessToken
        }

        val result = try {
            authApi.get().refresh(RefreshRequest(current.tokens.refreshToken)).execute()
        } catch (io: IOException) {
            // Could not reach the server. The session may still be good, so keep it.
            breadcrumbs.leave("auth", "token refresh unreachable")
            return null
        }

        val renewed = result.body()
        return when {
            result.isSuccessful && renewed != null -> {
                val session = renewed.toDomain()
                runBlocking { sessions.save(session) }
                breadcrumbs.leave("auth", "token refreshed")
                session.tokens.accessToken
            }
            result.code() == 401 -> {
                // The refresh token itself was rejected: this session is over.
                runBlocking { sessions.clear(SignOutReason.SESSION_EXPIRED) }
                breadcrumbs.leave("auth", "session expired")
                null
            }
            else -> null
        }
    }

    private fun Request.withBearer(token: String): Request =
        newBuilder().header("Authorization", "Bearer $token").build()
}
