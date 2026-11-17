package app.connect.testing

import app.connect.domain.diagnostics.Analytics
import app.connect.domain.diagnostics.AnalyticsEvent
import app.connect.domain.diagnostics.Breadcrumb
import app.connect.domain.diagnostics.Breadcrumbs
import app.connect.domain.diagnostics.FeatureFlags
import app.connect.domain.diagnostics.Flag
import app.connect.domain.diagnostics.Span
import app.connect.domain.diagnostics.SpanRecord
import app.connect.domain.diagnostics.Tracer
import app.connect.domain.util.Clock
import app.connect.domain.util.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Names the regression scenario a test covers. The ids are defined in
 * docs/scenarios.yml; tools/scenario_coverage.py joins the two to report which
 * scenarios have an automated test and which are still manual.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Repeatable
annotation class Covers(val scenario: String)

/** Swaps Dispatchers.Main for a test dispatcher, so viewModelScope runs on virtual time. */
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    val scheduler: TestCoroutineScheduler get() = dispatcher.scheduler

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

class TestDispatcherProvider(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val main: CoroutineDispatcher = dispatcher
    override val io: CoroutineDispatcher = dispatcher
    override val default: CoroutineDispatcher = dispatcher
}

class FakeClock(var now: Long = 0L) : Clock {
    override fun nowMillis(): Long = now

    fun advance(millis: Long) {
        now += millis
    }
}

/** Reads the virtual time of a test scheduler, so traced durations match what the test advanced. */
class SchedulerClock(private val scheduler: TestCoroutineScheduler) : Clock {
    override fun nowMillis(): Long = scheduler.currentTime
}

class RecordingAnalytics : Analytics {
    val events = mutableListOf<AnalyticsEvent>()

    override fun track(event: AnalyticsEvent) {
        events += event
    }

    inline fun <reified T : AnalyticsEvent> of(): List<T> = events.filterIsInstance<T>()
}

class RecordingTracer(private val clock: Clock = Clock { 0L }) : Tracer {
    val spans = mutableListOf<SpanRecord>()

    override suspend fun <T> trace(name: String, block: suspend (Span) -> T): T {
        val attributes = linkedMapOf<String, String>()
        val start = clock.nowMillis()
        val span = object : Span {
            override fun attribute(key: String, value: String) {
                attributes[key] = value
            }
        }
        try {
            return block(span)
        } finally {
            spans += SpanRecord(name, clock.nowMillis() - start, attributes.toMap())
        }
    }
}

class RecordingBreadcrumbs(private val clock: Clock = Clock { 0L }) : Breadcrumbs {
    private val trail = mutableListOf<Breadcrumb>()

    override fun leave(category: String, message: String) {
        trail += Breadcrumb(clock.nowMillis(), category, message)
    }

    override fun snapshot(): List<Breadcrumb> = trail.toList()
}

class FakeFeatureFlags(overrides: Map<Flag, Boolean> = emptyMap()) : FeatureFlags {
    private val state = MutableStateFlow(Flag.entries.associateWith { overrides[it] ?: it.default })
    override val values: StateFlow<Map<Flag, Boolean>> = state

    override suspend fun override(flag: Flag, enabled: Boolean?) {
        state.update { it + (flag to (enabled ?: flag.default)) }
    }
}
