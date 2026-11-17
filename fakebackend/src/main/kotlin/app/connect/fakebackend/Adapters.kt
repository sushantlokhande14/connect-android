package app.connect.fakebackend

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Answers requests from inside the OkHttp client, with no socket. The demo
 * build installs this as its last interceptor, so everything above it
 * (Retrofit, auth header, token refresh, error mapping) runs exactly as it
 * would against a real server.
 */
class FakeBackendInterceptor(
    private val backend: FakeBackend,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val answer = backend.handle(request.toFakeRequest())
        if (answer.delayMillis > 0) sleep(answer.delayMillis)
        when (answer.transport) {
            TransportFault.TIMEOUT -> throw SocketTimeoutException("simulated timeout")
            TransportFault.DISCONNECT -> throw IOException("simulated connection reset")
            null -> Unit
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(answer.status)
            .message(if (answer.status in 200..299) "OK" else "Error")
            .body(answer.body.toResponseBody(JSON))
            .build()
    }

    companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        fun Request.toFakeRequest(): FakeRequest {
            val bodyText = body?.let { Buffer().also(it::writeTo).readUtf8() }
            return FakeRequest(
                method = method,
                path = url.encodedPath,
                query = url.queryParameterNames.associateWith { url.queryParameter(it).orEmpty() },
                headers = headers.toMap(),
                body = bodyText,
            )
        }
    }
}
