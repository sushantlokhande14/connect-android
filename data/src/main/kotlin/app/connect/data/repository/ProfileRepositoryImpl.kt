package app.connect.data.repository

import app.connect.data.local.ProfileDao
import app.connect.data.local.ProfileEntity
import app.connect.data.network.ApiCaller
import app.connect.data.network.ConnectApi
import app.connect.data.network.ProfileDto
import app.connect.data.network.ProfilePatch
import app.connect.data.session.SessionState
import app.connect.data.session.SessionStore
import app.connect.domain.model.AppError
import app.connect.domain.model.Outcome
import app.connect.domain.model.Profile
import app.connect.domain.model.ProfileUpdate
import app.connect.domain.model.UserId
import app.connect.domain.model.map
import app.connect.domain.repository.Freshness
import app.connect.domain.repository.ProfileRepository
import app.connect.domain.util.Clock
import app.connect.domain.util.SingleFlight
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** How long each kind of cached value is served without asking the server again. */
object CacheTtl {
    const val OWN_PROFILE_MILLIS = 5 * 60_000L
    const val OTHER_PROFILE_MILLIS = 10 * 60_000L
    const val PREFERENCES_MILLIS = 10 * 60_000L
    const val FIRST_DECK_PAGE_MILLIS = 60_000L
}

/**
 * Profiles are read through Room. A profile fetched for one screen is reused
 * by the next one that needs it, until its time-to-live runs out; a fetch that
 * fails for a transient reason falls back to the stored copy, however old.
 */
@Singleton
class ProfileRepositoryImpl @Inject constructor(
    private val api: ConnectApi,
    private val dao: ProfileDao,
    private val sessions: SessionStore,
    private val caller: ApiCaller,
    private val clock: Clock,
) : ProfileRepository {
    private val inFlight = SingleFlight<String, Outcome<Profile>>()

    override fun observeMe(): Flow<Profile?> = sessions.state.flatMapLatest { state ->
        when (state) {
            is SessionState.Active -> dao.observe(state.session.userId.value).map { it?.toDomain() }
            else -> flowOf(null)
        }
    }

    override suspend fun getMe(freshness: Freshness): Outcome<Profile> {
        val me = sessions.current()?.userId ?: return Outcome.Failure(AppError.Unauthorized)
        return read(me.value, freshness, CacheTtl.OWN_PROFILE_MILLIS, "GET /v1/me") { api.me() }
    }

    override suspend fun getProfile(id: UserId, freshness: Freshness): Outcome<Profile> =
        read(id.value, freshness, CacheTtl.OTHER_PROFILE_MILLIS, "GET /v1/profiles/{id}") { api.profile(id.value) }

    override suspend fun updateMe(update: ProfileUpdate): Outcome<Profile> =
        caller.call("PATCH /v1/me") {
            api.updateMe(ProfilePatch(update.displayName, update.bio, update.interests))
        }.map { store(it) }

    private suspend fun read(
        id: String,
        freshness: Freshness,
        ttlMillis: Long,
        label: String,
        fetch: suspend () -> ProfileDto,
    ): Outcome<Profile> {
        val cached = dao.get(id)
        if (freshness == Freshness.CACHE_FIRST && cached != null && cached.isFresh(ttlMillis)) {
            return Outcome.Success(cached.toDomain())
        }

        // Two screens asking for the same profile at once share one request.
        val fetched = inFlight.run(id) { caller.call(label) { fetch() }.map { store(it) } }

        if (fetched is Outcome.Failure && fetched.error.isTransient &&
            cached != null && freshness != Freshness.NETWORK_ONLY
        ) {
            return Outcome.Success(cached.toDomain())
        }
        return fetched
    }

    private suspend fun store(dto: ProfileDto): Profile {
        val profile = dto.toDomain()
        dao.upsert(ProfileEntity.from(profile, fetchedAtMillis = clock.nowMillis()))
        return profile
    }

    private fun ProfileEntity.isFresh(ttlMillis: Long) = clock.nowMillis() - fetchedAtMillis < ttlMillis
}
