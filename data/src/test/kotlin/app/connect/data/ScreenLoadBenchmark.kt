package app.connect.data

import app.connect.data.network.ApiCaller
import app.connect.data.network.ConnectApi
import app.connect.data.network.NetworkFactory
import app.connect.data.repository.CacheTtl
import app.connect.domain.diagnostics.Flag
import app.connect.domain.model.Outcome
import app.connect.domain.repository.Freshness
import app.connect.domain.usecase.LoadDiscoveryScreen
import app.connect.fakebackend.NetworkProfile
import app.connect.testing.FakeFeatureFlags
import app.connect.testing.RecordingAnalytics
import app.connect.testing.RecordingTracer
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.system.measureNanoTime

/**
 * Measures how long the discovery screen's data takes to load through the
 * real client stack: Retrofit, OkHttp's interceptor chain (auth included),
 * kotlinx.serialization and Room. The server is simulated in process, with
 * the latency model in NetworkProfile.
 *
 * It answers one question: on the same simulated network, how much of the
 * load time does each change remove? The changes are applied one at a time:
 *
 *   baseline      one request at a time, nothing cached, and a session probe
 *                 before the load (the naive version of the screen)
 *   no probe      the baseline without the probe, which is redundant because
 *                 an expired token is already handled when a request gets 401
 *   concurrent    the production loader on a first visit, nothing cached
 *   revisit       the production loader coming back after more than a minute:
 *                 profile and filters come from the cache, the deck is refetched
 *   quick revisit the production loader coming back within a minute: the deck
 *                 is cached too
 *
 * Fairness. Every arm of a run faces the same network: the latency model is
 * reseeded before each arm, and each endpoint draws from its own stream, so
 * "the deck request" costs the same in every arm of that run. Runs differ
 * from each other, which is what makes the percentiles mean something.
 *
 * What this is not: a measurement of a real network or a real device. The
 * result is exactly as trustworthy as the latency model, which is printed
 * with it.
 *
 * Run with:  ./gradlew :data:testDebugUnitTest -Pbenchmark
 */
@RunWith(RobolectricTestRunner::class)
class ScreenLoadBenchmark {
    private val runs = System.getProperty("benchmark.runs")?.toIntOrNull() ?: 30
    private val warmups = 3
    private val outDir = File(System.getProperty("benchmark.out") ?: "build/benchmark")

    private class Arm(val name: String) {
        val samplesMillis = ArrayList<Double>()
        var requests = 0

        private val sorted get() = samplesMillis.sorted()
        val mean get() = samplesMillis.average()
        val median get() = percentile(0.5)
        val p90 get() = percentile(0.9)

        private fun percentile(p: Double): Double {
            val values = sorted
            val rank = p * (values.size - 1)
            val low = values[rank.toInt()]
            val high = values[(rank.toInt() + 1).coerceAtMost(values.size - 1)]
            return low + (high - low) * (rank - rank.toInt())
        }
    }

    /** The naive loader the production one is compared against. It exists only here. */
    private class SequentialUncachedLoader(private val api: ConnectApi, private val caller: ApiCaller) {
        suspend fun load(probeSession: Boolean): Boolean {
            if (probeSession && caller.callEmpty("GET /v1/session") { api.session() } is Outcome.Failure) return false
            val steps: List<suspend () -> Outcome<*>> = listOf(
                { caller.call("GET /v1/me") { api.me() } },
                { caller.call("GET /v1/me/preferences") { api.preferences() } },
                { caller.call("GET /v1/discovery") { api.discovery(null) } },
                { caller.call("GET /v1/me/quota") { api.quota() } },
                { caller.call("GET /v1/conversations/unread-count") { api.unreadCount() } },
            )
            return steps.all { it() is Outcome.Success }
        }
    }

    private fun productionLoader(harness: DataHarness) = LoadDiscoveryScreen(
        profiles = harness.profiles,
        discovery = harness.discovery,
        chat = harness.chat,
        flags = FakeFeatureFlags(mapOf(Flag.PARALLEL_DISCOVERY_LOAD to true)),
        tracer = RecordingTracer(),
        analytics = RecordingAnalytics(),
        clock = harness.clock,
    )

    private fun measure(profile: NetworkProfile): List<Arm> {
        val arms = listOf("baseline", "no probe", "concurrent", "revisit", "quick revisit").map(::Arm)

        repeat(warmups + runs) { run ->
            val record = run >= warmups
            // A new harness per run: every run starts with empty caches.
            DataHarness(
                inProcess = true,
                timeouts = NetworkFactory.Timeouts(readMillis = 60_000, callMillis = 120_000),
            ).use { harness ->
                runBlocking { harness.signInDemo() }
                harness.backend.network = profile
                val naive = SequentialUncachedLoader(harness.api, harness.caller)
                val production = productionLoader(harness)
                val seed = 7_919L * (run + 1)

                fun time(arm: Arm, load: suspend () -> Boolean) {
                    // Same seed before every arm of this run: each arm sees the same network.
                    harness.backend.reseedLatency(seed)
                    harness.backend.clearRequestLog()
                    var ok = false
                    val nanos = measureNanoTime { ok = runBlocking { load() } }
                    check(ok) { "a benchmarked load failed; the sample would be meaningless" }
                    if (record) {
                        arm.samplesMillis += nanos / 1_000_000.0
                        arm.requests = harness.backend.requests().size
                    }
                }

                time(arms[0]) { naive.load(probeSession = true) }
                time(arms[1]) { naive.load(probeSession = false) }
                // The naive loader does not touch the repositories, so their caches are still empty here.
                time(arms[2]) { production(Freshness.CACHE_FIRST) is Outcome.Success }
                // Past the deck's one-minute lifetime, inside the profile's and the filters' lifetimes.
                harness.clock.advance(CacheTtl.FIRST_DECK_PAGE_MILLIS + 1)
                time(arms[3]) { production(Freshness.CACHE_FIRST) is Outcome.Success }
                // Straight back again: now the deck fetched a moment ago is still fresh.
                time(arms[4]) { production(Freshness.CACHE_FIRST) is Outcome.Success }
            }
        }
        return arms
    }

    @Test
    fun `discovery screen load`() {
        outDir.mkdirs()
        val results = NetworkProfile.ALL.associateWith(::measure)

        val routes = listOf(
            "GET /v1/session", "GET /v1/me", "GET /v1/me/preferences", "GET /v1/discovery",
            "GET /v1/me/quota", "GET /v1/conversations/unread-count",
        )
        val markdown = StringBuilder()
        markdown.appendLine("Simulated latency per endpoint: log-normal, sigma 0.25, with these medians in ms.")
        markdown.appendLine()
        markdown.appendLine("| Network | " + routes.joinToString(" | ") { it.removePrefix("GET /v1/") } + " |")
        markdown.appendLine("|---|" + routes.joinToString("") { "---:|" })
        NetworkProfile.ALL.forEach { profile ->
            markdown.appendLine("| ${profile.name} | " + routes.joinToString(" | ") { "%.0f".format(profile.latencyFor(it).medianMillis) } + " |")
        }
        markdown.appendLine()
        markdown.appendLine("$runs runs per arm after $warmups warm-up runs. Every arm of a run sees the same latency draws.")
        markdown.appendLine()
        markdown.appendLine("| Network | Loader | Requests | Median ms | Mean ms | p90 ms | vs baseline | vs no probe |")
        markdown.appendLine("|---|---|---:|---:|---:|---:|---:|---:|")

        val json = StringBuilder("{\n  \"runsPerArm\": $runs,\n  \"sigma\": 0.25,\n  \"profiles\": [\n")
        results.entries.forEachIndexed { index, (profile, arms) ->
            val baseline = arms[0].median
            val noProbe = arms[1].median
            arms.forEachIndexed { position, arm ->
                fun change(reference: Double) = "%+.1f%%".format((arm.median - reference) / reference * 100)
                markdown.appendLine(
                    "| ${profile.name} | ${arm.name} | ${arm.requests} | %.0f | %.0f | %.0f | %s | %s |".format(
                        arm.median, arm.mean, arm.p90,
                        if (position == 0) "" else change(baseline),
                        if (position <= 1) "" else change(noProbe),
                    ),
                )
            }
            json.append("    {\"network\": \"${profile.name}\", \"arms\": [")
            json.append(
                arms.joinToString(", ") { arm ->
                    "{\"name\": \"${arm.name}\", \"requests\": ${arm.requests}, \"medianMs\": %.1f, \"meanMs\": %.1f, \"p90Ms\": %.1f, \"samplesMs\": [%s]}"
                        .format(arm.median, arm.mean, arm.p90, arm.samplesMillis.joinToString(", ") { "%.1f".format(it) })
                },
            )
            json.append("]}").append(if (index < results.size - 1) ",\n" else "\n")
        }
        json.append("  ]\n}\n")

        File(outDir, "screen_load.md").writeText(markdown.toString())
        File(outDir, "screen_load.json").writeText(json.toString())
        println(markdown)
    }
}
