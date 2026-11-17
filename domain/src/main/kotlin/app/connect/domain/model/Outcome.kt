package app.connect.domain.model

/**
 * Every failure a screen can be asked to render. The data layer maps
 * exceptions and HTTP codes into one of these, so nothing above it catches
 * IOException or reads a status code.
 */
sealed class AppError {
    data object Offline : AppError()
    data object Timeout : AppError()
    data object Unauthorized : AppError()
    data class Server(val code: Int) : AppError()
    data class Rejected(val code: Int, val reason: String?) : AppError()
    data class Validation(val field: String, val reason: String) : AppError()
    data class Unexpected(val detail: String?) : AppError()

    /** True when trying the same request again could plausibly succeed. */
    val isTransient: Boolean
        get() = this is Offline || this is Timeout || this is Server

    /** Stable short name for logs and analytics, e.g. "Offline" or "Server". Never includes user data. */
    fun kind(): String = this::class.simpleName ?: "Unknown"
}

sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: AppError) : Outcome<Nothing>
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

inline fun <T, R> Outcome<T>.flatMap(transform: (T) -> Outcome<R>): Outcome<R> = when (this) {
    is Outcome.Success -> transform(value)
    is Outcome.Failure -> this
}

inline fun <T> Outcome<T>.onSuccess(block: (T) -> Unit): Outcome<T> {
    if (this is Outcome.Success) block(value)
    return this
}

inline fun <T> Outcome<T>.onFailure(block: (AppError) -> Unit): Outcome<T> {
    if (this is Outcome.Failure) block(error)
    return this
}

fun <T> Outcome<T>.getOrNull(): T? = (this as? Outcome.Success)?.value

fun <T> Outcome<T>.errorOrNull(): AppError? = (this as? Outcome.Failure)?.error
