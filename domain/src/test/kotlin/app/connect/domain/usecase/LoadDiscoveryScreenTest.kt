package app.connect.domain.usecase

import app.connect.domain.diagnostics.AnalyticsEvent
import app.connect.domain.diagnostics.Flag
import app.connect.domain.model.AppError
import app.connect.domain.model.Candidate
import app.connect.domain.model.DeckPage
import app.connect.domain.model.Outcome
import app.connect.domain.model.getOrNull
import app.connect.testing.Covers
import app.connect.testing.FakeChatRepository
import app.connect.testing.FakeDiscoveryRepository
import app.connect.testing.FakeFeatureFlags
import app.connect.testing.FakeProfileRepository
import app.connect.testing.Fixtures
import app.connect.testing.RecordingAnalytics
import app.connect.testing.RecordingTracer
import app.connect.testing.SchedulerClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LoadDiscoveryScreenTest {
    private val profiles = FakeProfileRepository().apply { meDelayMillis = 120 }
    private val discovery = FakeDiscoveryRepository().apply {
        preferencesDelayMillis = 90
        deckDelayMillis = 400
        quotaDelayMillis = 80
    }
    private val chat = FakeChatRepository().apply { unreadDelayMillis = 150 }
    private val analytics = RecordingAnalytics()

    private fun TestScope.loader(flags: FakeFeatureFlags = FakeFeatureFlags()): Pair<LoadDiscoveryScreen, RecordingTracer> {
        val clock = SchedulerClock(testScheduler)
        val tracer = RecordingTracer(clock)
        return LoadDiscoveryScreen(profiles, discovery, chat, flags, tracer, analytics, clock) to tracer
    }

    @Test
    @Covers("DISC-01")
    fun `concurrent load takes as long as the slowest request`() = runTest {
        val (load, tracer) = loader()

        val result = load()

        assertThat(result).isInstanceOf(Outcome.Success::class.java)
        // 120, 90, 400, 80 and 150 ms overlap; only the 400 ms deck request sets the total.
        assertThat(currentTime).isEqualTo(400)
        assertThat(tracer.spans.single().attributes["mode"]).isEqualTo("concurrent")
        assertThat(tracer.spans.single().durationMillis).isEqualTo(400)
    }

    @Test
    @Covers("DISC-01")
    fun `sequential fallback takes the sum of all requests`() = runTest {
        val (load, tracer) = loader(FakeFeatureFlags(mapOf(Flag.PARALLEL_DISCOVERY_LOAD to false)))

        val result = load()

        assertThat(result).isInstanceOf(Outcome.Success::class.java)
        assertThat(currentTime).isEqualTo(120 + 90 + 400 + 80 + 150)
        assertThat(tracer.spans.single().attributes["mode"]).isEqualTo("sequential")
    }

    @Test
    @Covers("DISC-01")
    fun `both modes produce the same screen`() = runTest {
        val (concurrent, _) = loader()
        val (sequential, _) = loader(FakeFeatureFlags(mapOf(Flag.PARALLEL_DISCOVERY_LOAD to false)))

        assertThat(concurrent().getOrNull()).isEqualTo(sequential().getOrNull())
    }

    @Test
    @Covers("DISC-02")
    fun `a failed required request fails the load without waiting for slower ones`() = runTest {
        profiles.meResult = Outcome.Failure(AppError.Timeout)
        val (load, _) = loader()

        val result = load()

        assertThat(result).isEqualTo(Outcome.Failure(AppError.Timeout))
        // The profile request fails at 120 ms. The 400 ms deck request is cancelled, not awaited.
        assertThat(currentTime).isEqualTo(120)
        assertThat(analytics.of<AnalyticsEvent.DiscoveryLoaded>()).isEmpty()
    }

    @Test
    @Covers("DISC-02")
    fun `retrying after a failure loads the screen`() = runTest {
        discovery.deckResults[null] = Outcome.Failure(AppError.Server(503))
        val (load, _) = loader()
        assertThat(load()).isEqualTo(Outcome.Failure(AppError.Server(503)))

        discovery.deckResults[null] = Outcome.Success(Fixtures.deck())

        assertThat(load().getOrNull()?.cards).hasSize(3)
    }

    @Test
    @Covers("DISC-03")
    fun `failed optional requests degrade the screen instead of failing it`() = runTest {
        discovery.quotaResult = Outcome.Failure(AppError.Server(500))
        chat.unreadResult = Outcome.Failure(AppError.Offline)
        val (load, tracer) = loader()

        val data = load().getOrNull()

        assertThat(data).isNotNull()
        assertThat(data!!.cards).hasSize(3)
        assertThat(data.quota).isNull()
        assertThat(data.unreadCount).isNull()
        assertThat(data.degraded).isTrue()
        assertThat(tracer.spans.single().attributes["outcome"]).isEqualTo("degraded")
        assertThat(analytics.of<AnalyticsEvent.DiscoveryLoaded>().single().degraded).isTrue()
    }

    @Test
    @Covers("DISC-01")
    fun `cards carry the interests shared with the viewer`() = runTest {
        discovery.deckResults[null] = Outcome.Success(
            DeckPage(
                candidates = listOf(
                    Candidate(Fixtures.profile(1, interests = listOf("film", "chess", "climbing")), 3),
                    Candidate(Fixtures.profile(2, interests = listOf("sailing")), 9),
                ),
                nextCursor = null,
            ),
        )
        val (load, _) = loader()

        val cards = load().getOrNull()!!.cards

        assertThat(cards[0].sharedInterests).containsExactly("film", "climbing").inOrder()
        assertThat(cards[1].sharedInterests).isEmpty()
    }

    @Test
    @Covers("DIAG-01")
    fun `a successful load reports its duration and card count`() = runTest {
        val (load, _) = loader()

        load()

        val event = analytics.of<AnalyticsEvent.DiscoveryLoaded>().single()
        assertThat(event.durationMillis).isEqualTo(400)
        assertThat(event.cardCount).isEqualTo(3)
        assertThat(event.degraded).isFalse()
    }

    @Test
    fun `each repository is asked exactly once per load`() = runTest {
        val (load, _) = loader()

        load()

        assertThat(profiles.getMeCalls).isEqualTo(1)
        assertThat(discovery.getPreferencesCalls).isEqualTo(1)
        assertThat(discovery.getDeckCalls).containsExactly(null)
        assertThat(discovery.getQuotaCalls).isEqualTo(1)
        assertThat(chat.fetchUnreadCalls).isEqualTo(1)
    }
}
