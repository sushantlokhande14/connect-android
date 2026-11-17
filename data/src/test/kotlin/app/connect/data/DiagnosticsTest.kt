package app.connect.data

import app.connect.data.diagnostics.DiagnosticsLog
import app.connect.data.diagnostics.FileCrashReporter
import app.connect.data.diagnostics.FlagPayloadValidator
import app.connect.data.diagnostics.RecordingTracer
import app.connect.data.diagnostics.RingBreadcrumbs
import app.connect.data.diagnostics.ValidatingAnalytics
import app.connect.data.network.PushDto
import app.connect.domain.diagnostics.AnalyticsEvent
import app.connect.domain.diagnostics.Flag
import app.connect.domain.model.ConversationId
import app.connect.domain.model.PushPayload
import app.connect.domain.model.UserId
import app.connect.testing.Covers
import app.connect.testing.FakeClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AnalyticsContractTest {
    private val breadcrumbs = RingBreadcrumbs(FakeClock())
    private val log = DiagnosticsLog()
    private val analytics = ValidatingAnalytics(setOf(log), breadcrumbs)

    /** One instance of every event the app can send. A new event class must be added here to compile-check it. */
    private val everyEvent: List<AnalyticsEvent> = listOf(
        AnalyticsEvent.ScreenViewed("discovery"),
        AnalyticsEvent.SignInCompleted(success = false, errorKind = "Unauthorized"),
        AnalyticsEvent.SignUpCompleted(success = true),
        AnalyticsEvent.SignedOut("user_requested"),
        AnalyticsEvent.DiscoveryLoaded(durationMillis = 412, cardCount = 10, degraded = false),
        AnalyticsEvent.SwipePerformed("like", matched = true),
        AnalyticsEvent.MessageSendCompleted(success = true, wasRetry = false),
        AnalyticsEvent.PushOpened("new_message"),
        AnalyticsEvent.ErrorShown("discovery", "Timeout"),
        AnalyticsEvent.RetryTapped("discovery"),
    )

    @Test
    @Covers("DIAG-01")
    fun `every event the app defines passes the backend's rules`() {
        assertThat(everyEvent.map { it::class }.toSet()).containsExactlyElementsIn(AnalyticsEvent::class.sealedSubclasses)

        everyEvent.forEach { event ->
            assertThat(ValidatingAnalytics.problemWith(event.name, event.params)).isNull()
        }
    }

    @Test
    @Covers("DIAG-01")
    fun `accepted events reach every sink with their parameters`() {
        everyEvent.forEach(analytics::track)

        assertThat(log.events.value.map { it.name }).containsExactlyElementsIn(everyEvent.map { it.name }).inOrder()
        assertThat(log.events.value[4].params).containsExactly("duration_ms", 412L, "card_count", 10, "degraded", false)
    }

    @Test
    @Covers("DIAG-01")
    fun `malformed events are rejected with a reason`() {
        val problem = ValidatingAnalytics::problemWith

        assertThat(problem("ScreenViewed", emptyMap())).isEqualTo("name is not snake_case")
        assertThat(problem("x".repeat(41), emptyMap())).contains("longer than 40")
        assertThat(problem("ok", mapOf("badKey" to 1))).isEqualTo("parameter 'badKey' is not snake_case")
        assertThat(problem("ok", mapOf("list" to listOf(1)))).contains("unsupported type")
        assertThat(problem("ok", mapOf("text" to "x".repeat(101)))).contains("longer than 100")
        assertThat(problem("ok", (1..26).associate { "p$it" to it })).contains("more than 25")
    }

    @Test
    @Covers("DIAG-01")
    fun `a rejected event is dropped and leaves a breadcrumb`() {
        // An over-long screen name makes this otherwise valid event fail the value-length rule.
        analytics.track(AnalyticsEvent.ScreenViewed("s".repeat(150)))

        assertThat(log.events.value).isEmpty()
        assertThat(breadcrumbs.snapshot().single().message).startsWith("dropped screen_viewed:")
    }
}

class BreadcrumbsAndTracerTest {
    @Test
    fun `the trail keeps only the most recent entries`() {
        val clock = FakeClock()
        val breadcrumbs = RingBreadcrumbs(clock, capacity = 3)

        (1..5).forEach {
            clock.advance(10)
            breadcrumbs.leave("nav", "step $it")
        }

        assertThat(breadcrumbs.snapshot().map { it.message }).containsExactly("step 3", "step 4", "step 5").inOrder()
        assertThat(breadcrumbs.snapshot().map { it.atMillis }).containsExactly(30L, 40L, 50L).inOrder()
    }

    @Test
    fun `a span records its duration and attributes even when the block throws`() = runTest {
        val clock = FakeClock()
        val log = DiagnosticsLog()
        val tracer = RecordingTracer(clock, log, RingBreadcrumbs(clock))

        runCatching {
            tracer.trace<Unit>("discovery.load") { span ->
                span.attribute("mode", "concurrent")
                clock.advance(250)
                error("boom")
            }
        }

        val span = log.spans.value.single()
        assertThat(span.name).isEqualTo("discovery.load")
        assertThat(span.durationMillis).isEqualTo(250)
        assertThat(span.attributes).containsExactly("mode", "concurrent")
    }
}

class FileCrashReporterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val clock = FakeClock(now = 1_000)
    private val breadcrumbs = RingBreadcrumbs(clock)

    @Test
    @Covers("DIAG-02")
    fun `a report carries the exception and the trail that led to it`() {
        val reporter = FileCrashReporter(folder.root, breadcrumbs, clock)
        breadcrumbs.leave("nav", "opened discovery")
        breadcrumbs.leave("network", "GET /v1/discovery failed: Timeout")

        reporter.recordNonFatal(IllegalStateException("deck was empty"), mapOf("screen" to "discovery"))

        val report = reporter.pendingReports().single()
        assertThat(report.fatal).isFalse()
        assertThat(report.exception).isEqualTo("java.lang.IllegalStateException")
        assertThat(report.message).isEqualTo("deck was empty")
        assertThat(report.stack).isNotEmpty()
        assertThat(report.context).containsExactly("screen", "discovery")
        assertThat(report.breadcrumbs).containsExactly(
            "1000 [nav] opened discovery",
            "1000 [network] GET /v1/discovery failed: Timeout",
        ).inOrder()
    }

    @Test
    @Covers("DIAG-02")
    fun `an uncaught exception is written to disk before the previous handler runs`() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        var reportsWhenPreviousRan = -1
        try {
            val reporter = FileCrashReporter(folder.root, breadcrumbs, clock)
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> reportsWhenPreviousRan = reporter.pendingReports().size }
            reporter.install()

            val crashing = Thread { throw IllegalArgumentException("fatal") }.apply { name = "worker-7" }
            crashing.start()
            crashing.join()

            val report = reporter.pendingReports().single()
            assertThat(reportsWhenPreviousRan).isEqualTo(1)
            assertThat(report.fatal).isTrue()
            assertThat(report.thread).isEqualTo("worker-7")
            assertThat(report.exception).isEqualTo("java.lang.IllegalArgumentException")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun `old reports are pruned and long messages are cut`() {
        val reporter = FileCrashReporter(folder.root, breadcrumbs, clock, maxReports = 3)

        (1..5).forEach {
            clock.advance(1)
            reporter.recordNonFatal(RuntimeException("n$it " + "x".repeat(500)))
        }

        val reports = reporter.pendingReports()
        assertThat(reports).hasSize(3)
        assertThat(reports.map { it.message!!.substringBefore(' ') }).containsExactly("n3", "n4", "n5").inOrder()
        assertThat(reports.all { it.message!!.length <= 200 }).isTrue()

        reporter.clearReports()
        assertThat(reporter.pendingReports()).isEmpty()
    }
}

class FlagPayloadValidatorTest {
    @Test
    @Covers("DIAG-03")
    fun `a well formed payload is accepted in full`() {
        val result = FlagPayloadValidator.validate("""{"parallel_discovery_load": false, "show_like_quota": true}""")

        assertThat(result.isClean).isTrue()
        assertThat(result.accepted).containsExactly(Flag.PARALLEL_DISCOVERY_LOAD, false, Flag.SHOW_LIKE_QUOTA, true)
    }

    @Test
    @Covers("DIAG-03")
    fun `unknown names and wrong types are reported by key`() {
        val result = FlagPayloadValidator.validate(
            """{"parallel_discovery_load": "true", "paralel_discovery_load": false, "show_like_quota": 1}""",
        )

        assertThat(result.accepted).isEmpty()
        assertThat(result.problems).containsExactly(
            "flag 'parallel_discovery_load' must be true or false",
            "unknown flag 'paralel_discovery_load'",
            "flag 'show_like_quota' must be true or false",
        )
    }

    @Test
    @Covers("DIAG-03")
    fun `anything that is not a JSON object is rejected`() {
        listOf("", "[]", "true", "{not json").forEach { payload ->
            assertThat(FlagPayloadValidator.validate(payload).problems).containsExactly("payload is not a JSON object")
        }
    }
}

class PushDtoTest {
    @Test
    @Covers("CHAT-04")
    fun `an FCM data map becomes a typed payload`() {
        val dto = PushDto.fromData(
            mapOf(
                "type" to "new_message",
                "conversationId" to "c_1",
                "clientId" to "srv_4",
                "senderId" to "u_2",
                "senderName" to "Jonah",
                "body" to "hey",
                "sentAt" to "1234",
            ),
        )

        assertThat(dto?.toDomain()).isEqualTo(
            PushPayload.NewMessage(ConversationId("c_1"), "srv_4", UserId("u_2"), "Jonah", "hey", 1234),
        )
    }

    @Test
    fun `payloads that are incomplete or of an unknown type are ignored`() {
        assertThat(PushDto.fromData(mapOf("type" to "new_message"))).isNull()
        assertThat(PushDto.fromData(mapOf("conversationId" to "c_1"))).isNull()
        assertThat(PushDto.fromData(mapOf("type" to "new_message", "conversationId" to "c_1"))?.toDomain()).isNull()
        assertThat(PushDto.fromData(mapOf("type" to "promo", "conversationId" to "c_1"))?.toDomain()).isNull()
        assertThat(PushDto.fromData(mapOf("type" to "new_match", "conversationId" to "c_1"))?.toDomain()).isNull()
    }
}
