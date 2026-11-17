package app.connect.domain.util

import app.connect.domain.model.AppError
import app.connect.domain.model.Outcome
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

class SingleFlightTest {
    @Test
    fun `concurrent callers for one key share a single execution`() = runTest {
        val flight = SingleFlight<String, Int>()
        var executions = 0

        val results = (1..8).map {
            async {
                flight.run("me") {
                    executions++
                    delay(100)
                    42
                }
            }
        }.awaitAll()

        assertThat(results).containsExactlyElementsIn(List(8) { 42 })
        assertThat(executions).isEqualTo(1)
        assertThat(currentTime).isEqualTo(100)
    }

    @Test
    fun `different keys do not wait for each other`() = runTest {
        val flight = SingleFlight<String, String>()
        var executions = 0

        val a = async { flight.run("a") { executions++; delay(100); "a" } }
        val b = async { flight.run("b") { executions++; delay(100); "b" } }

        assertThat(listOf(a.await(), b.await())).containsExactly("a", "b")
        assertThat(executions).isEqualTo(2)
        assertThat(currentTime).isEqualTo(100)
    }

    @Test
    fun `a finished call is not reused by a later one`() = runTest {
        val flight = SingleFlight<String, Int>()
        var executions = 0

        val first = flight.run("me") { ++executions }
        val second = flight.run("me") { ++executions }

        assertThat(first).isEqualTo(1)
        assertThat(second).isEqualTo(2)
    }

    @Test
    fun `a failure reaches every waiting caller and is not remembered`() = runTest {
        val flight = SingleFlight<String, Int>()
        var executions = 0
        val failing: suspend () -> Int = {
            executions++
            delay(50)
            throw IllegalStateException("boom")
        }

        val callers = (1..3).map { async { runCatching { flight.run("me", failing) } } }.awaitAll()

        assertThat(callers.map { it.exceptionOrNull()?.message }).containsExactly("boom", "boom", "boom")
        assertThat(executions).isEqualTo(1)
        assertThat(flight.run("me") { 7 }).isEqualTo(7)
    }

    @Test
    fun `when the caller doing the work is cancelled a waiting caller takes over`() = runTest {
        val flight = SingleFlight<String, String>()
        var executions = 0
        val work: suspend () -> String = {
            executions++
            delay(100)
            "done"
        }

        val leader = launch { flight.run("me", work) }
        runCurrent()
        val follower = async { flight.run("me", work) }
        runCurrent()
        advanceTimeBy(40)
        leader.cancelAndJoin()

        assertThat(follower.await()).isEqualTo("done")
        assertThat(executions).isEqualTo(2)
    }
}

class RetryPolicyTest {
    @Test
    fun `transient failures are retried until one succeeds`() = runTest {
        val policy = RetryPolicy(maxAttempts = 3, baseDelayMillis = 100, random = Random(1))
        val attempts = mutableListOf<Int>()

        val result = policy.run { attempt ->
            attempts += attempt
            if (attempt < 3) Outcome.Failure(AppError.Timeout) else Outcome.Success("ok")
        }

        assertThat(result).isEqualTo(Outcome.Success("ok"))
        assertThat(attempts).containsExactly(1, 2, 3).inOrder()
    }

    @Test
    fun `the last failure is returned when attempts run out`() = runTest {
        val policy = RetryPolicy(maxAttempts = 2, baseDelayMillis = 10)
        var calls = 0

        val result = policy.run<String> {
            calls++
            Outcome.Failure(AppError.Server(503))
        }

        assertThat(result).isEqualTo(Outcome.Failure(AppError.Server(503)))
        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun `failures that cannot succeed on a second try are not retried`() = runTest {
        var calls = 0

        val result = RetryPolicy(maxAttempts = 5).run<String> {
            calls++
            Outcome.Failure(AppError.Unauthorized)
        }

        assertThat(result).isEqualTo(Outcome.Failure(AppError.Unauthorized))
        assertThat(calls).isEqualTo(1)
        assertThat(currentTime).isEqualTo(0)
    }

    @Test
    fun `backoff ceiling doubles and is capped`() {
        val policy = RetryPolicy(maxAttempts = 10, baseDelayMillis = 200, maxDelayMillis = 2_000)

        assertThat((1..7).map(policy::backoffCeilingMillis))
            .containsExactly(0L, 200L, 400L, 800L, 1_600L, 2_000L, 2_000L)
            .inOrder()
    }

    @Test
    fun `waits never exceed the ceiling`() = runTest {
        val policy = RetryPolicy(maxAttempts = 4, baseDelayMillis = 100, maxDelayMillis = 1_000, random = Random(7))

        policy.run<String> { Outcome.Failure(AppError.Offline) }

        // Three waits with ceilings 100, 200 and 400 ms.
        assertThat(currentTime).isAtMost(700)
    }

    @Test
    fun `a policy needs at least one attempt`() {
        assertThrows(IllegalArgumentException::class.java) { RetryPolicy(maxAttempts = 0) }
    }
}
