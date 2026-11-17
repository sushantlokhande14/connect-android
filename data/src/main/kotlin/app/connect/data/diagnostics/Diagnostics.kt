package app.connect.data.diagnostics

import app.connect.domain.diagnostics.Analytics
import app.connect.domain.diagnostics.AnalyticsEvent
import app.connect.domain.diagnostics.Breadcrumb
import app.connect.domain.diagnostics.Breadcrumbs
import app.connect.domain.diagnostics.Span
import app.connect.domain.diagnostics.SpanRecord
import app.connect.domain.diagnostics.Tracer
import app.connect.domain.util.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Where accepted analytics events go. Production adds a Firebase sink; every build has the in-memory one. */
fun interface AnalyticsSink {
    fun accept(name: String, params: Map<String, Any>)
}

/**
 * Checks every event against the rules the analytics backend enforces before
 * it leaves the app. A malformed event is dropped and leaves a breadcrumb, so
 * it shows up in diagnostics instead of silently polluting a dashboard.
 */
class ValidatingAnalytics(
    private val sinks: Set<AnalyticsSink>,
    private val breadcrumbs: Breadcrumbs,
) : Analytics {
    override fun track(event: AnalyticsEvent) {
        val params = event.params
        val problem = problemWith(event.name, params)
        if (problem != null) {
            breadcrumbs.leave("analytics", "dropped ${event.name}: $problem")
            return
        }
        breadcrumbs.leave("analytics", event.name)
        sinks.forEach { it.accept(event.name, params) }
    }

    companion object {
        const val MAX_NAME_LENGTH = 40
        const val MAX_PARAMS = 25
        const val MAX_STRING_VALUE_LENGTH = 100
        private val SNAKE_CASE = Regex("^[a-z][a-z0-9_]*$")

        /** Null when the event is acceptable. */
        fun problemWith(name: String, params: Map<String, Any>): String? = when {
            name.length > MAX_NAME_LENGTH -> "name longer than $MAX_NAME_LENGTH characters"
            !SNAKE_CASE.matches(name) -> "name is not snake_case"
            params.size > MAX_PARAMS -> "more than $MAX_PARAMS parameters"
            else -> params.entries.firstNotNullOfOrNull { (key, value) ->
                when {
                    !SNAKE_CASE.matches(key) -> "parameter '$key' is not snake_case"
                    value !is String && value !is Number && value !is Boolean ->
                        "parameter '$key' has unsupported type ${value::class.simpleName}"
                    value is String && value.length > MAX_STRING_VALUE_LENGTH ->
                        "parameter '$key' is longer than $MAX_STRING_VALUE_LENGTH characters"
                    else -> null
                }
            }
        }
    }
}

data class RecordedEvent(val name: String, val params: Map<String, Any>)

/** Keeps the most recent events and spans in memory for the in-app diagnostics screen and for tests. */
class DiagnosticsLog(private val capacity: Int = 200) : AnalyticsSink {
    private val eventState = MutableStateFlow<List<RecordedEvent>>(emptyList())
    private val spanState = MutableStateFlow<List<SpanRecord>>(emptyList())

    val events: StateFlow<List<RecordedEvent>> = eventState
    val spans: StateFlow<List<SpanRecord>> = spanState

    override fun accept(name: String, params: Map<String, Any>) {
        eventState.update { (it + RecordedEvent(name, params)).takeLast(capacity) }
    }

    fun record(span: SpanRecord) {
        spanState.update { (it + span).takeLast(capacity) }
    }
}

class RingBreadcrumbs(
    private val clock: Clock,
    private val capacity: Int = 50,
) : Breadcrumbs {
    private val trail = ArrayDeque<Breadcrumb>(capacity)

    @Synchronized
    override fun leave(category: String, message: String) {
        if (trail.size == capacity) trail.removeFirst()
        trail.addLast(Breadcrumb(clock.nowMillis(), category, message))
    }

    @Synchronized
    override fun snapshot(): List<Breadcrumb> = trail.toList()
}

class RecordingTracer(
    private val clock: Clock,
    private val log: DiagnosticsLog,
    private val breadcrumbs: Breadcrumbs,
) : Tracer {
    override suspend fun <T> trace(name: String, block: suspend (Span) -> T): T {
        val attributes = LinkedHashMap<String, String>()
        val span = object : Span {
            override fun attribute(key: String, value: String) {
                synchronized(attributes) { attributes[key] = value }
            }
        }
        val start = clock.nowMillis()
        try {
            return block(span)
        } finally {
            val record = SpanRecord(name, clock.nowMillis() - start, synchronized(attributes) { attributes.toMap() })
            log.record(record)
            breadcrumbs.leave("trace", "$name ${record.durationMillis}ms ${record.attributes}")
        }
    }
}
