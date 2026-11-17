package app.connect.fakebackend

import kotlin.math.exp
import kotlin.random.Random

data class FakeRequest(
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

enum class TransportFault { TIMEOUT, DISCONNECT }

data class FakeResponse(
    val status: Int,
    val body: String = "",
    val delayMillis: Long = 0,
    val transport: TransportFault? = null,
)

/** Latency of one endpoint: log-normal around a median, which is how request latencies are usually shaped. */
data class Latency(val medianMillis: Double, val sigma: Double = 0.25) {
    fun sample(random: Random): Long {
        if (medianMillis <= 0.0) return 0
        // Box-Muller: two uniforms to one standard normal.
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        val normal = kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
        return (medianMillis * exp(sigma * normal)).toLong().coerceAtLeast(1)
    }
}

/**
 * How long each endpoint takes to answer. The numbers are assumptions, not
 * measurements of a real service: a round trip plus server time, with the
 * discovery query the most expensive call. docs/benchmark.md lists them next
 * to every result so a reader can judge the result against the inputs.
 */
class NetworkProfile(
    val name: String,
    private val fallback: Latency,
    private val perRoute: Map<String, Latency> = emptyMap(),
) {
    fun latencyFor(route: String): Latency = perRoute[route] ?: fallback

    fun sample(route: String, random: Random): Long = latencyFor(route).sample(random)

    fun scaled(name: String, factor: Double) = NetworkProfile(
        name = name,
        fallback = fallback.copy(medianMillis = fallback.medianMillis * factor),
        perRoute = perRoute.mapValues { it.value.copy(medianMillis = it.value.medianMillis * factor) },
    )

    companion object {
        val INSTANT = NetworkProfile("instant", Latency(0.0))

        val LTE = NetworkProfile(
            name = "lte",
            fallback = Latency(150.0),
            perRoute = mapOf(
                "POST /v1/auth/login" to Latency(320.0),
                "POST /v1/auth/signup" to Latency(380.0),
                "POST /v1/auth/refresh" to Latency(180.0),
                "GET /v1/session" to Latency(130.0),
                "GET /v1/me" to Latency(180.0),
                "GET /v1/me/preferences" to Latency(140.0),
                "GET /v1/me/quota" to Latency(120.0),
                "GET /v1/discovery" to Latency(420.0),
                "GET /v1/conversations/unread-count" to Latency(160.0),
                "GET /v1/conversations" to Latency(240.0),
                "GET /v1/conversations/{id}/messages" to Latency(200.0),
                "POST /v1/conversations/{id}/messages" to Latency(190.0),
                "GET /v1/profiles/{id}" to Latency(170.0),
                "POST /v1/swipes" to Latency(150.0),
            ),
        )

        val WIFI = LTE.scaled("wifi", 0.45)
        val SLOW_3G = LTE.scaled("slow-3g", 3.0)

        val ALL = listOf(WIFI, LTE, SLOW_3G)

        fun byName(name: String): NetworkProfile =
            (ALL + INSTANT).firstOrNull { it.name == name } ?: error("Unknown network profile '$name'")
    }
}

sealed interface Fault {
    data class Http(val status: Int, val code: String = "injected_failure") : Fault
    data object Timeout : Fault
    data object Disconnect : Fault

    /** The server processes the request, then the connection drops before the answer arrives. */
    data object LostResponse : Fault
    data class Slow(val extraMillis: Long) : Fault
}

/** Scripted failures, keyed by route ("GET /v1/discovery"). Thread-safe. */
class FaultPlan {
    private class Rule(val fault: Fault, var remaining: Int)

    private val rules = HashMap<String, ArrayDeque<Rule>>()

    /** Fails the next [times] requests to [route]. */
    @Synchronized
    fun fail(route: String, fault: Fault, times: Int = 1) {
        require(times > 0) { "times must be positive" }
        rules.getOrPut(route) { ArrayDeque() }.addLast(Rule(fault, times))
    }

    /**
     * Fails every request to [route] until [clear] is called.
     *
     * This is how to simulate being offline. Failing a single request is not enough for transport
     * faults: when a pooled connection drops, OkHttp replays the request once on a new connection
     * without telling the caller, so one dropped connection is usually invisible to the app.
     */
    fun failUntilCleared(route: String, fault: Fault) = fail(route, fault, times = Int.MAX_VALUE)

    @Synchronized
    fun clear(route: String? = null) {
        if (route == null) rules.clear() else rules.remove(route)
    }

    @Synchronized
    internal fun take(route: String): Fault? {
        val queue = rules[route] ?: return null
        val rule = queue.firstOrNull() ?: return null
        if (rule.remaining != Int.MAX_VALUE && --rule.remaining == 0) queue.removeFirst()
        return rule.fault
    }
}
