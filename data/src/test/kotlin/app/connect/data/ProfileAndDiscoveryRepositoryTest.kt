package app.connect.data

import app.connect.data.repository.CacheTtl
import app.connect.domain.model.AppError
import app.connect.domain.model.DiscoveryPreferences
import app.connect.domain.model.Outcome
import app.connect.domain.model.ProfileUpdate
import app.connect.domain.model.SwipeAction
import app.connect.domain.model.UserId
import app.connect.domain.model.errorOrNull
import app.connect.domain.model.getOrNull
import app.connect.domain.repository.Freshness
import app.connect.fakebackend.Fault
import app.connect.fakebackend.NetworkProfile
import app.connect.testing.Covers
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProfileRepositoryTest {
    private val harness = DataHarness()

    @Before
    fun signIn() = runTestBlocking { harness.signInDemo() }

    @After
    fun tearDown() = harness.close()

    @Test
    @Covers("DISC-04")
    fun `a fresh cached profile is served without a request`() = runTestBlocking {
        harness.profiles.getMe()
        harness.profiles.getMe()
        harness.profiles.getMe()

        assertThat(harness.backend.requestCount("GET /v1/me")).isEqualTo(1)
    }

    @Test
    @Covers("DISC-04")
    fun `a cached profile is refetched once its time to live has passed`() = runTestBlocking {
        harness.profiles.getMe()
        harness.clock.advance(CacheTtl.OWN_PROFILE_MILLIS - 1)
        harness.profiles.getMe()
        assertThat(harness.backend.requestCount("GET /v1/me")).isEqualTo(1)

        harness.clock.advance(1)
        harness.profiles.getMe()

        assertThat(harness.backend.requestCount("GET /v1/me")).isEqualTo(2)
    }

    @Test
    @Covers("DISC-04")
    fun `concurrent reads of one profile produce one request`() = runTestBlocking {
        harness.backend.network = NetworkProfile.LTE

        val results = (1..6).map { async { harness.profiles.getMe(Freshness.NETWORK_FIRST) } }.awaitAll()

        assertThat(results.map { it.getOrNull()?.displayName }.distinct()).containsExactly("Sam")
        assertThat(harness.backend.requestCount("GET /v1/me")).isEqualTo(1)
    }

    @Test
    @Covers("PROF-01")
    fun `a stale profile is served when the refresh cannot reach the server`() = runTestBlocking {
        val id = UserId("u_3")
        val first = harness.profiles.getProfile(id).getOrNull()
        harness.clock.advance(CacheTtl.OTHER_PROFILE_MILLIS + 1)
        harness.backend.faults.failUntilCleared("GET /v1/profiles/{id}", Fault.Disconnect)

        val offline = harness.profiles.getProfile(id)

        assertThat(offline.getOrNull()).isEqualTo(first)
    }

    @Test
    @Covers("PROF-01")
    fun `without a cached copy an unreachable server is an offline error`() = runTestBlocking {
        harness.backend.faults.failUntilCleared("GET /v1/profiles/{id}", Fault.Disconnect)

        assertThat(harness.profiles.getProfile(UserId("u_3")).errorOrNull()).isEqualTo(AppError.Offline)
    }

    @Test
    fun `network only never falls back to the cache`() = runTestBlocking {
        harness.profiles.getMe()
        harness.backend.faults.fail("GET /v1/me", Fault.Http(503))

        assertThat(harness.profiles.getMe(Freshness.NETWORK_ONLY).errorOrNull()).isEqualTo(AppError.Server(503))
    }

    @Test
    fun `a server that never answers is reported as a timeout`() = runTestBlocking {
        harness.backend.faults.fail("GET /v1/profiles/{id}", Fault.Timeout)

        assertThat(harness.profiles.getProfile(UserId("u_3")).errorOrNull()).isEqualTo(AppError.Timeout)
    }

    @Test
    fun `an unknown profile is a rejection that carries the server code and is not cached over`() = runTestBlocking {
        val result = harness.profiles.getProfile(UserId("u_9999"))

        assertThat(result.errorOrNull()).isEqualTo(AppError.Rejected(404, "profile_not_found"))
    }

    @Test
    @Covers("PROF-02")
    fun `an edit is written through to the cache and reaches observers`() = runTestBlocking {
        harness.profiles.getMe()

        val updated = harness.profiles.updateMe(ProfileUpdate(bio = "New bio", interests = listOf("chess")))

        assertThat(updated.getOrNull()?.bio).isEqualTo("New bio")
        val observed = withTimeout(5_000) { harness.profiles.observeMe().filterNotNull().first { it.bio == "New bio" } }
        assertThat(observed.interests).containsExactly("chess")
        // The cache already holds the edit, so reading it back costs nothing.
        harness.backend.clearRequestLog()
        assertThat(harness.profiles.getMe().getOrNull()?.bio).isEqualTo("New bio")
        assertThat(harness.backend.requests()).isEmpty()
    }
}

@RunWith(RobolectricTestRunner::class)
class DiscoveryRepositoryTest {
    private val harness = DataHarness()

    @Before
    fun signIn() = runTestBlocking { harness.signInDemo() }

    @After
    fun tearDown() = harness.close()

    @Test
    @Covers("DISC-04")
    fun `returning to discovery within a minute reuses the first page`() = runTestBlocking {
        val first = harness.discovery.getDeck(null).getOrNull()
        val again = harness.discovery.getDeck(null).getOrNull()

        assertThat(again).isEqualTo(first)
        assertThat(harness.backend.requestCount("GET /v1/discovery")).isEqualTo(1)

        harness.clock.advance(CacheTtl.FIRST_DECK_PAGE_MILLIS)
        harness.discovery.getDeck(null)
        assertThat(harness.backend.requestCount("GET /v1/discovery")).isEqualTo(2)
    }

    @Test
    @Covers("DISC-06")
    fun `the cursor walks the deck without repeating anyone`() = runTestBlocking {
        val seen = ArrayList<UserId>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = harness.discovery.getDeck(cursor).getOrNull()!!
            seen += page.candidates.map { it.profile.id }
            cursor = page.nextCursor
            pages++
        } while (cursor != null)

        assertThat(pages).isGreaterThan(1)
        assertThat(seen).containsNoDuplicates()
    }

    @Test
    @Covers("DISC-05")
    fun `liking someone who already likes you is a match with a conversation`() = runTestBlocking {
        val result = harness.discovery.swipe(UserId("u_1"), SwipeAction.LIKE).getOrNull()!!

        assertThat(result.matched).isTrue()
        assertThat(result.conversationId).isNotNull()
    }

    @Test
    @Covers("DISC-05")
    fun `passing never matches and a swiped card does not come back from the cache`() = runTestBlocking {
        val first = harness.discovery.getDeck(null).getOrNull()!!
        val target = first.candidates.first().profile.id

        val result = harness.discovery.swipe(target, SwipeAction.PASS).getOrNull()!!
        val cached = harness.discovery.getDeck(null).getOrNull()!!

        assertThat(result.matched).isFalse()
        assertThat(cached.candidates.map { it.profile.id }).doesNotContain(target)
        assertThat(harness.backend.requestCount("GET /v1/discovery")).isEqualTo(1)
    }

    @Test
    fun `a like spends quota and a pass does not`() = runTestBlocking {
        val before = harness.discovery.getQuota().getOrNull()!!.remaining

        harness.discovery.swipe(UserId("u_2"), SwipeAction.PASS)
        harness.discovery.swipe(UserId("u_4"), SwipeAction.LIKE)

        assertThat(harness.discovery.getQuota().getOrNull()!!.remaining).isEqualTo(before - 1)
    }

    @Test
    @Covers("DISC-04")
    fun `filters are cached and changing them drops the cached deck`() = runTestBlocking {
        harness.discovery.getPreferences()
        harness.discovery.getPreferences()
        assertThat(harness.backend.requestCount("GET /v1/me/preferences")).isEqualTo(1)
        harness.discovery.getDeck(null)

        val narrowed = DiscoveryPreferences(minAge = 25, maxAge = 30, maxDistanceKm = 20)
        assertThat(harness.discovery.updatePreferences(narrowed).getOrNull()).isEqualTo(narrowed)

        // The saved filters are served from the cache, and the deck is fetched again because it depends on them.
        assertThat(harness.discovery.getPreferences().getOrNull()).isEqualTo(narrowed)
        assertThat(harness.backend.requestCount("GET /v1/me/preferences")).isEqualTo(1)
        val deck = harness.discovery.getDeck(null).getOrNull()!!
        assertThat(harness.backend.requestCount("GET /v1/discovery")).isEqualTo(2)
        assertThat(deck.candidates.all { it.profile.age in 25..30 && it.distanceKm <= 20 }).isTrue()
    }

    @Test
    fun `invalid filters are rejected by the server and the cache keeps the old ones`() = runTestBlocking {
        val original = harness.discovery.getPreferences().getOrNull()
        harness.backend.faults.fail("PUT /v1/me/preferences", Fault.Http(422, "invalid_preferences"))

        val result = harness.discovery.updatePreferences(DiscoveryPreferences(minAge = 30, maxAge = 40))

        assertThat(result.errorOrNull()).isEqualTo(AppError.Rejected(422, "invalid_preferences"))
        assertThat(harness.discovery.getPreferences().getOrNull()).isEqualTo(original)
    }

    @Test
    @Covers("DISC-02")
    fun `a failed deck request is not cached so the retry reaches the server`() = runTestBlocking {
        harness.backend.faults.fail("GET /v1/discovery", Fault.Http(500))

        val failed = harness.discovery.getDeck(null)
        val retried = harness.discovery.getDeck(null)

        assertThat(failed.errorOrNull()).isEqualTo(AppError.Server(500))
        assertThat(retried).isInstanceOf(Outcome.Success::class.java)
    }
}
