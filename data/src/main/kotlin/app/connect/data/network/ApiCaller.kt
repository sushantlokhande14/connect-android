package app.connect.data.network

import app.connect.domain.diagnostics.Breadcrumbs
import app.connect.domain.model.AppError
import app.connect.domain.model.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.io.InterruptedIOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place where transport failures become [AppError]. Repositories wrap
 * every request in [call]; nothing above this file catches an exception from
 * the network.
 */
@Singleton
class ApiCaller @Inject constructor(
    private val json: Json,
    private val breadcrumbs: Breadcrumbs,
) {
    suspend fun <T> call(label: String, block: suspend () -> T): Outcome<T> {
        val error = try {
            return Outcome.Success(block())
        } catch (cancelled: CancellationException) {
            // Cancellation is not a failure to report; let structured concurrency see it.
            throw cancelled
        } catch (http: HttpException) {
            fromStatus(http.code(), http.response()?.errorBody()?.string())
        } catch (timeout: InterruptedIOException) {
            // SocketTimeoutException and OkHttp's call timeout both extend this.
            AppError.Timeout
        } catch (io: IOException) {
            AppError.Offline
        } catch (malformed: SerializationException) {
            AppError.Unexpected("unreadable response")
        }
        breadcrumbs.leave("network", "$label failed: ${error.kind()}")
        return Outcome.Failure(error)
    }

    /** For endpoints that answer with no body. */
    suspend fun callEmpty(label: String, block: suspend () -> Response<Unit>): Outcome<Unit> =
        when (val result = call(label, block)) {
            is Outcome.Failure -> result
            is Outcome.Success ->
                if (result.value.isSuccessful) {
                    Outcome.Success(Unit)
                } else {
                    val error = fromStatus(result.value.code(), result.value.errorBody()?.string())
                    breadcrumbs.leave("network", "$label failed: ${error.kind()}")
                    Outcome.Failure(error)
                }
        }

    private fun fromStatus(status: Int, body: String?): AppError = when {
        status == 401 -> AppError.Unauthorized
        status == 408 -> AppError.Timeout
        status >= 500 -> AppError.Server(status)
        else -> AppError.Rejected(status, body?.let(::errorCode))
    }

    private fun errorCode(body: String): String? =
        runCatching { json.decodeFromString(ErrorDto.serializer(), body).code }.getOrNull()
}
