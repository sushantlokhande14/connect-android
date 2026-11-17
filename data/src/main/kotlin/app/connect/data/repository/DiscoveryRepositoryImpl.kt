package app.connect.data.repository

import app.connect.data.local.CacheDao
import app.connect.data.local.CacheEntryEntity
import app.connect.data.network.ApiCaller
import app.connect.data.network.ConnectApi
import app.connect.data.network.PreferencesDto
import app.connect.data.network.SwipeRequest
import app.connect.data.session.SessionStore
import app.connect.domain.model.DeckPage
import app.connect.domain.model.DiscoveryPreferences
import app.connect.domain.model.LikeQuota
import app.connect.domain.model.Outcome
import app.connect.domain.model.SwipeAction
import app.connect.domain.model.SwipeResult
import app.connect.domain.model.UserId
import app.connect.domain.model.map
import app.connect.domain.model.onSuccess
import app.connect.domain.repository.DiscoveryRepository
import app.connect.domain.repository.Freshness
import app.connect.domain.util.Clock
import app.connect.domain.util.SingleFlight
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiscoveryRepositoryImpl @Inject constructor(
    private val api: ConnectApi,
    private val cache: CacheDao,
    private val sessions: SessionStore,
    private val caller: ApiCaller,
    private val json: Json,
    private val clock: Clock,
) : DiscoveryRepository {
    private class CachedDeck(val owner: UserId, val page: DeckPage, val fetchedAtMillis: Long)

    private val deckLock = Mutex()
    private var firstPage: CachedDeck? = null
    private val preferencesFlight = SingleFlight<String, Outcome<DiscoveryPreferences>>()

    override suspend fun getPreferences(freshness: Freshness): Outcome<DiscoveryPreferences> {
        val key = preferencesKey() ?: return fetchPreferences(store = false)
        val cached = cache.get(key)
        val cachedValue = cached?.let { entry ->
            runCatching { json.decodeFromString(PreferencesDto.serializer(), entry.json).toDomain() }.getOrNull()
        }
        if (freshness == Freshness.CACHE_FIRST && cached != null && cachedValue != null &&
            clock.nowMillis() - cached.fetchedAtMillis < CacheTtl.PREFERENCES_MILLIS
        ) {
            return Outcome.Success(cachedValue)
        }

        val fetched = preferencesFlight.run(key) { fetchPreferences(store = true) }
        if (fetched is Outcome.Failure && fetched.error.isTransient &&
            cachedValue != null && freshness != Freshness.NETWORK_ONLY
        ) {
            return Outcome.Success(cachedValue)
        }
        return fetched
    }

    private suspend fun fetchPreferences(store: Boolean): Outcome<DiscoveryPreferences> =
        caller.call("GET /v1/me/preferences") { api.preferences() }
            .onSuccess { if (store) storePreferences(it) }
            .map { it.toDomain() }

    override suspend fun updatePreferences(preferences: DiscoveryPreferences): Outcome<DiscoveryPreferences> =
        caller.call("PUT /v1/me/preferences") { api.updatePreferences(PreferencesDto.from(preferences)) }
            .onSuccess { saved ->
                storePreferences(saved)
                // Different filters mean a different deck.
                deckLock.withLock { firstPage = null }
            }
            .map { it.toDomain() }

    override suspend fun getDeck(cursor: String?, freshness: Freshness): Outcome<DeckPage> {
        val owner = sessions.current()?.userId
        if (cursor == null && freshness == Freshness.CACHE_FIRST && owner != null) {
            deckLock.withLock {
                val cached = firstPage
                if (cached != null && cached.owner == owner && cached.page.candidates.isNotEmpty() &&
                    clock.nowMillis() - cached.fetchedAtMillis < CacheTtl.FIRST_DECK_PAGE_MILLIS
                ) {
                    return Outcome.Success(cached.page)
                }
            }
        }
        return caller.call("GET /v1/discovery") { api.discovery(cursor) }
            .map { it.toDomain() }
            .onSuccess { page ->
                if (cursor == null && owner != null) {
                    deckLock.withLock { firstPage = CachedDeck(owner, page, clock.nowMillis()) }
                }
            }
    }

    override suspend fun getQuota(): Outcome<LikeQuota> =
        caller.call("GET /v1/me/quota") { api.quota() }.map { it.toDomain() }

    override suspend fun swipe(target: UserId, action: SwipeAction): Outcome<SwipeResult> =
        caller.call("POST /v1/swipes") { api.swipe(SwipeRequest(target.value, action.name.lowercase())) }
            .map { it.toDomain() }
            .onSuccess {
                // A swiped card must not come back when the screen is reopened from cache.
                deckLock.withLock {
                    firstPage = firstPage?.let { cached ->
                        CachedDeck(
                            owner = cached.owner,
                            page = cached.page.copy(candidates = cached.page.candidates.filterNot { it.profile.id == target }),
                            fetchedAtMillis = cached.fetchedAtMillis,
                        )
                    }
                }
            }

    private suspend fun storePreferences(dto: PreferencesDto) {
        val key = preferencesKey() ?: return
        cache.put(CacheEntryEntity(key, json.encodeToString(PreferencesDto.serializer(), dto), clock.nowMillis()))
    }

    private fun preferencesKey(): String? = sessions.current()?.userId?.let { "preferences:${it.value}" }
}
