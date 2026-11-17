package app.connect.domain.util

import app.connect.domain.model.AppError
import app.connect.domain.model.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

interface DispatcherProvider {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
}

fun interface Clock {
    fun nowMillis(): Long
}

/**
 * Collapses concurrent calls for the same key into one execution.
 *
 * Two screens asking for the current user's profile at the same moment used
 * to produce two identical requests. With this, the second caller waits for
 * the first caller's result. The entry is removed as soon as the call
 * finishes, so this is deduplication of in-flight work, not a cache.
 *
 * If the caller doing the work is cancelled, the callers waiting on it are
 * not cancelled with it: one of them takes over and runs the block itself.
 */
class SingleFlight<K : Any, V> {
    private val mutex = Mutex()
    private val inFlight = HashMap<K, CompletableDeferred<Result<V>?>>()

    suspend fun run(key: K, block: suspend () -> V): V {
        while (true) {
            val (deferred, isLeader) = mutex.withLock {
                val existing = inFlight[key]
                if (existing != null) {
                    existing to false
                } else {
                    val created = CompletableDeferred<Result<V>?>()
                    inFlight[key] = created
                    created to true
                }
            }

            if (!isLeader) {
                // null means the leader was cancelled before it had an answer.
                val shared = deferred.await() ?: continue
                return shared.getOrThrow()
            }

            var outcome: Result<V>? = null
            try {
                val value = block()
                outcome = Result.success(value)
                return value
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                outcome = Result.failure(failure)
                throw failure
            } finally {
                // Remove before completing, so a caller that arrives in between
                // starts fresh instead of receiving a finished entry.
                withContext(NonCancellable) { mutex.withLock { inFlight.remove(key) } }
                deferred.complete(outcome)
            }
        }
    }
}

/**
 * Exponential backoff with full jitter. Only transient failures are retried,
 * and callers pass this only around requests that are safe to repeat.
 */
class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMillis: Long = 200,
    val maxDelayMillis: Long = 2_000,
    private val random: Random = Random.Default,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    }

    /** Upper bound of the wait before [attempt]; the actual wait is uniform in 0..bound. */
    fun backoffCeilingMillis(attempt: Int): Long {
        if (attempt <= 1) return 0
        val shift = (attempt - 2).coerceAtMost(20)
        return (baseDelayMillis shl shift).coerceAtMost(maxDelayMillis)
    }

    suspend fun <T> run(
        shouldRetry: (AppError) -> Boolean = { it.isTransient },
        block: suspend (attempt: Int) -> Outcome<T>,
    ): Outcome<T> {
        var attempt = 1
        while (true) {
            val result = block(attempt)
            if (result is Outcome.Failure && attempt < maxAttempts && shouldRetry(result.error)) {
                attempt++
                delay(random.nextLong(backoffCeilingMillis(attempt) + 1))
                continue
            }
            return result
        }
    }

    companion object {
        val None = RetryPolicy(maxAttempts = 1)
    }
}
