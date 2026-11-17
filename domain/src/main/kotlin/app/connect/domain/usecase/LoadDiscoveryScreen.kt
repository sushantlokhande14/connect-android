package app.connect.domain.usecase

import app.connect.domain.diagnostics.Analytics
import app.connect.domain.diagnostics.AnalyticsEvent
import app.connect.domain.diagnostics.FeatureFlags
import app.connect.domain.diagnostics.Flag
import app.connect.domain.diagnostics.Tracer
import app.connect.domain.model.AppError
import app.connect.domain.model.Candidate
import app.connect.domain.model.DeckPage
import app.connect.domain.model.DiscoveryCard
import app.connect.domain.model.DiscoveryPreferences
import app.connect.domain.model.LikeQuota
import app.connect.domain.model.Outcome
import app.connect.domain.model.Profile
import app.connect.domain.model.getOrNull
import app.connect.domain.repository.ChatRepository
import app.connect.domain.repository.DiscoveryRepository
import app.connect.domain.repository.Freshness
import app.connect.domain.repository.ProfileRepository
import app.connect.domain.util.Clock
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import javax.inject.Inject

data class DiscoveryScreenData(
    val me: Profile,
    val preferences: DiscoveryPreferences,
    val cards: List<DiscoveryCard>,
    val nextCursor: String?,
    /** Null when the quota request failed. The screen hides the counter instead of failing. */
    val quota: LikeQuota?,
    /** Null when the unread request failed. The badge is hidden instead of failing the screen. */
    val unreadCount: Int?,
) {
    val degraded: Boolean get() = quota == null || unreadCount == null
}

/**
 * Loads everything the discovery screen shows.
 *
 * The screen needs five things: the viewer's profile, their filters, the first
 * deck page, the like quota and the unread count. None of the five needs
 * another one's response to make its request, so they are started together and
 * the load takes as long as the slowest instead of the sum. The only real
 * dependency is the join at the end: a card's shared interests need both the
 * deck and the viewer's profile.
 *
 * The parts are not equally important. Profile, filters and deck are required:
 * if one fails the screen cannot be drawn, so the first such failure cancels
 * the rest and is returned. Quota and unread count are optional: if one fails
 * the screen is drawn without it.
 */
class LoadDiscoveryScreen @Inject constructor(
    private val profiles: ProfileRepository,
    private val discovery: DiscoveryRepository,
    private val chat: ChatRepository,
    private val flags: FeatureFlags,
    private val tracer: Tracer,
    private val analytics: Analytics,
    private val clock: Clock,
) {
    suspend operator fun invoke(freshness: Freshness = Freshness.CACHE_FIRST): Outcome<DiscoveryScreenData> =
        tracer.trace("discovery.load") { span ->
            val startedAt = clock.nowMillis()
            val concurrent = flags.isEnabled(Flag.PARALLEL_DISCOVERY_LOAD)
            span.attribute("mode", if (concurrent) "concurrent" else "sequential")
            span.attribute("freshness", freshness.name)

            val result = if (concurrent) loadConcurrently(freshness) else loadSequentially(freshness)

            when (result) {
                is Outcome.Success -> {
                    span.attribute("outcome", if (result.value.degraded) "degraded" else "ok")
                    analytics.track(
                        AnalyticsEvent.DiscoveryLoaded(
                            durationMillis = clock.nowMillis() - startedAt,
                            cardCount = result.value.cards.size,
                            degraded = result.value.degraded,
                        ),
                    )
                }
                is Outcome.Failure -> span.attribute("outcome", result.error.kind())
            }
            result
        }

    private suspend fun loadConcurrently(freshness: Freshness): Outcome<DiscoveryScreenData> = coroutineScope {
        val me = async { profiles.getMe(freshness) }
        val preferences = async { discovery.getPreferences(freshness) }
        val deck = async { discovery.getDeck(cursor = null, freshness = freshness) }
        val quota = async { discovery.getQuota() }
        val unread = async { chat.fetchUnreadCount() }

        val failure = firstFailure(listOf(me, preferences, deck))
        if (failure != null) {
            listOf(me, preferences, deck, quota, unread).forEach { it.cancel() }
            return@coroutineScope Outcome.Failure(failure)
        }

        Outcome.Success(
            assemble(
                me = (me.await() as Outcome.Success).value,
                preferences = (preferences.await() as Outcome.Success).value,
                deck = (deck.await() as Outcome.Success).value,
                quota = quota.await().getOrNull(),
                unread = unread.await().getOrNull(),
            ),
        )
    }

    /** One request at a time. Kept behind the flag as the fallback and as the comparison point. */
    private suspend fun loadSequentially(freshness: Freshness): Outcome<DiscoveryScreenData> {
        val me = when (val r = profiles.getMe(freshness)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> return r
        }
        val preferences = when (val r = discovery.getPreferences(freshness)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> return r
        }
        val deck = when (val r = discovery.getDeck(cursor = null, freshness = freshness)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> return r
        }
        val quota = discovery.getQuota().getOrNull()
        val unread = chat.fetchUnreadCount().getOrNull()
        return Outcome.Success(assemble(me, preferences, deck, quota, unread))
    }

    /**
     * Waits for the required parts in the order they finish and returns the
     * first failure without waiting for the slower ones. Null when all succeeded.
     */
    private suspend fun firstFailure(parts: List<Deferred<Outcome<*>>>): AppError? {
        val pending = parts.toMutableList()
        while (pending.isNotEmpty()) {
            val (finished, outcome) = select<Pair<Deferred<Outcome<*>>, Outcome<*>>> {
                pending.forEach { part -> part.onAwait { value -> part to value } }
            }
            pending.remove(finished)
            if (outcome is Outcome.Failure) return outcome.error
        }
        return null
    }

    private fun assemble(
        me: Profile,
        preferences: DiscoveryPreferences,
        deck: DeckPage,
        quota: LikeQuota?,
        unread: Int?,
    ) = DiscoveryScreenData(
        me = me,
        preferences = preferences,
        cards = deck.candidates.map { it.toCard(me) },
        nextCursor = deck.nextCursor,
        quota = quota,
        unreadCount = unread,
    )

    companion object {
        fun Candidate.toCard(viewer: Profile): DiscoveryCard {
            val mine = viewer.interests.toSet()
            return DiscoveryCard(
                profile = profile,
                distanceKm = distanceKm,
                sharedInterests = profile.interests.filter { it in mine },
            )
        }
    }
}
