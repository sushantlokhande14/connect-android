package app.connect.domain.diagnostics

import kotlinx.coroutines.flow.StateFlow

/**
 * Typed analytics events. A screen cannot send a free-form string; adding an
 * event means adding a class here, which the contract test then checks.
 */
sealed class AnalyticsEvent(val name: String) {
    open val params: Map<String, Any> get() = emptyMap()

    data class ScreenViewed(val screen: String) : AnalyticsEvent("screen_viewed") {
        override val params get() = mapOf("screen" to screen)
    }

    data class SignInCompleted(val success: Boolean, val errorKind: String? = null) : AnalyticsEvent("sign_in_completed") {
        override val params get() = buildMap<String, Any> {
            put("success", success)
            errorKind?.let { put("error_kind", it) }
        }
    }

    data class SignUpCompleted(val success: Boolean, val errorKind: String? = null) : AnalyticsEvent("sign_up_completed") {
        override val params get() = buildMap<String, Any> {
            put("success", success)
            errorKind?.let { put("error_kind", it) }
        }
    }

    data class SignedOut(val reason: String) : AnalyticsEvent("signed_out") {
        override val params get() = mapOf("reason" to reason)
    }

    data class DiscoveryLoaded(val durationMillis: Long, val cardCount: Int, val degraded: Boolean) :
        AnalyticsEvent("discovery_loaded") {
        override val params get() = mapOf(
            "duration_ms" to durationMillis,
            "card_count" to cardCount,
            "degraded" to degraded,
        )
    }

    data class SwipePerformed(val action: String, val matched: Boolean) : AnalyticsEvent("swipe_performed") {
        override val params get() = mapOf("action" to action, "matched" to matched)
    }

    data class MessageSendCompleted(val success: Boolean, val wasRetry: Boolean) : AnalyticsEvent("message_send_completed") {
        override val params get() = mapOf("success" to success, "was_retry" to wasRetry)
    }

    data class PushOpened(val kind: String) : AnalyticsEvent("push_opened") {
        override val params get() = mapOf("kind" to kind)
    }

    data class ErrorShown(val screen: String, val errorKind: String) : AnalyticsEvent("error_shown") {
        override val params get() = mapOf("screen" to screen, "error_kind" to errorKind)
    }

    data class RetryTapped(val screen: String) : AnalyticsEvent("retry_tapped") {
        override val params get() = mapOf("screen" to screen)
    }
}

interface Analytics {
    fun track(event: AnalyticsEvent)
}

/** One finished timed operation, with whatever the operation attached to it. */
data class SpanRecord(
    val name: String,
    val durationMillis: Long,
    val attributes: Map<String, String>,
)

interface Span {
    fun attribute(key: String, value: String)
}

interface Tracer {
    suspend fun <T> trace(name: String, block: suspend (Span) -> T): T
}

data class Breadcrumb(val atMillis: Long, val category: String, val message: String)

/** A short, bounded trail of what the app did last. Attached to crash reports. */
interface Breadcrumbs {
    fun leave(category: String, message: String)

    fun snapshot(): List<Breadcrumb>
}

interface CrashReporter {
    fun install()

    fun recordNonFatal(throwable: Throwable, context: Map<String, String> = emptyMap())
}

enum class Flag(val key: String, val default: Boolean) {
    /** Kill switch for the concurrent discovery loader; off falls back to one request at a time. */
    PARALLEL_DISCOVERY_LOAD("parallel_discovery_load", true),
    SHOW_LIKE_QUOTA("show_like_quota", true),
    SIMULATED_PEER_REPLIES("simulated_peer_replies", true),
}

interface FeatureFlags {
    val values: StateFlow<Map<Flag, Boolean>>

    fun isEnabled(flag: Flag): Boolean = values.value[flag] ?: flag.default

    suspend fun override(flag: Flag, enabled: Boolean?)
}
