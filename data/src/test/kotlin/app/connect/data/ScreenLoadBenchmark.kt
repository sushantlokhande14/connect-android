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
 * Measures how long the discovery screen's data takes to load, end to end
 * through the real client stack: Retrofit, OkHttp over a localhost socket,
 * kotlinx.serialization, Room. Only the server is simulated, with the latency
 * model in NetworkProfile.
 *
 * It answers one question: for the same screen and the same simulated
 * network, how much of the load time does each change remove? The changes are
 * applied one at a time so each one's share is visible:
 *
 *   baseline          one request at a time, nothing cached, and a session
 *                     probe before the load (the naive version of the screen)
 *   no-probe          baseline without the session probe, which is redundant
 *                     because an expired token is already handled on 401
 *   concurrent        the production loader on a first visit (empty cache)
 *   concurrent+cache  the production loader on a return visit: profile and
 *                     filters come from the cache, the rest is fetched
 *
 * Run with:  ./gradlew :data:testDebugUnitTest -Pbenchmark
 * It is excluded from the normal test run because it takes minutes and
 * produces a report rather than a verdict.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenLoadBenchmark {
    private val runs = System.getProperty("benchmark.runs")?.toIntOrNull() ?: 30
    private val warmups = 3
    private val outDir = File(System.getProperty("benchmark.out") ?: "build/benchmark")

    private class Arm(val name: String, val samplesMillis: List<Double>) {
        val sorted = samplesMillis.sorted()
        val mean get() = samplesMillis.average()
        val median get() = percentile(0.5)
        val p90 get() = percentile(0.9)

        fun percentile(p: Double): Double {
            val rank = p * (sorted.size - 1)
            val low = sorted[rank.toInt()]
            val high = sorted[(rank.toInt() + 1).coerceAtMost(sorted.size - 1)]
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

    private fun timeMillis(block: suspend () -> Boolean): Double {
        var ok = false
        val nanos = measureNanoTime { ok = runBlocking { block() } }
        check(ok) { "a benchmarked load failed; the sample would be meaningless" }
        return nanos / 1_000_000.0
    }

    private fun measure(profile: NetworkProfile): List<Arm> {
        val baseline = ArrayList<Double>()
        val noProbe = ArrayList<Double>()
        val concurrent = ArrayList<Double>()
        val cached = ArrayList<Double>()

        // Each run uses a new harness, so every arm starts from an empty cache and a new connection pool.
        // The four arms are measured back to back inside a run, which spreads any drift in the machine's
        // load evenly across them.
        repeat(warmups + runs) { run ->
            val record = run >= warmups
            DataHarness(timeouts = NetworkFactory.Timeouts(readMillis = 30_000, callMillis = 60_000)).use { harness ->
                runBlocking { harness.signInDemo() }
                harness.backend.network = profile
                val naive = SequentialUncachedLoader(harness.api, harness.caller)
                val production = productionLoader(harness)

                val a = timeMillis { naive.load(probeSession = true) }
                val b = timeMillis { naive.load(probeSession = false) }
                // First visit: nothing cached yet.
                val c = timeMillis { production(Freshness.CACHE_FIRST) is Outcome.Success }
                // Return visit after the deck's one-minute lifetime, inside the profile's and filters' lifetimes.
                harness.clock.advance(CacheTtl.FIRST_DECK_PAGE_MILLIS + 1)
                val d = timeMillis { production(Freshness.CACHE_FIRST) is Outcome.Success }

                if (record) {
                    baseline += a
                    noProbe += b
                    concurrent += c
                    cached += d
                }
            }
        }
        return listOf(
            Arm("baseline", baseline),
            Arm("no-probe", noProbe),
            Arm("concurrent", concurrent),
            Arm("concurrent+cache", cached),
        )
    }

    @Test
    fun `discovery screen load`() {
        outDir.mkdirs()
        val results = NetworkProfile.ALL.associateWith(::measure)

        val markdown = StringBuilder()
        val json = StringBuilder("{\n  \"runsPerArm\": $runs,\n  \"profiles\": [\n")
        markdown.appendLine("| Network | Loader | Median ms | Mean ms | p90 ms | vs baseline (median) |")
        markdown.appendLine("|---|---|---:|---:|---:|---:|")
        results.entries.forEachIndexed { index, (profile, arms) ->
            val base = arms.first().median
            arms.forEach { arm ->
                val change = if (arm === arms.first()) "" else "%.1f%%".format(-(base - arm.median) / base * 100)
                markdown.appendLine(
                    "| ${profile.name} | ${arm.name} | %.0f | %.0f | %.0f | %s |"
                        .format(arm.median, arm.mean, arm.p90, change),
                )
            }
            json.append("    {\"network\": \"${profile.name}\", \"arms\": [")
            json.append(
                arms.joinToString(", ") { arm ->
                    "{\"name\": \"${arm.name}\", \"medianMs\": %.1f, \"meanMs\": %.1f, \"p90Ms\": %.1f, \"samplesMs\": [%s]}"
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
