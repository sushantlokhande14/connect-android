package app.connect.feature.discovery

import app.connect.domain.diagnostics.AnalyticsEvent
import app.connect.domain.diagnostics.Flag
import app.connect.domain.model.AppError
import app.connect.domain.model.Candidate
import app.connect.domain.model.ConversationId
import app.connect.domain.model.DeckPage
import app.connect.domain.model.DiscoveryPreferences
import app.connect.domain.model.Outcome
import app.connect.domain.model.SwipeAction
import app.connect.domain.model.SwipeResult
import app.connect.domain.usecase.LoadDiscoveryScreen
import app.connect.domain.usecase.SwipeOnCard
import app.connect.testing.Covers
import app.connect.testing.FakeChatRepository
import app.connect.testing.FakeDiscoveryRepository
import app.connect.testing.FakeFeatureFlags
import app.connect.testing.FakeProfileRepository
import app.connect.testing.Fixtures
import app.connect.testing.MainDispatcherRule
import app.connect.testing.RecordingAnalytics
import app.connect.testing.RecordingTracer
import app.connect.testing.SchedulerClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class DiscoveryViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val profiles = FakeProfileRepository()
    private val discovery = FakeDiscoveryRepository()
    private val chat = FakeChatRepository()
    private val analytics = RecordingAnalytics()
    private var flags = FakeFeatureFlags()

    private fun viewModel(): DiscoveryViewModel {
        val clock = SchedulerClock(main.scheduler)
        val load = LoadDiscoveryScreen(profiles, discovery, chat, flags, RecordingTracer(clock), analytics, clock)
        return DiscoveryViewModel(load, SwipeOnCard(discovery, analytics), discovery, flags, analytics)
    }

    private fun DiscoveryViewModel.content() = state.value as DiscoveryUiState.Content

    @Test
    @Covers("DISC-01")
    fun `the screen starts loading and then shows the deck`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        assertThat(viewModel.state.value).isEqualTo(DiscoveryUiState.Loading)

        advanceUntilIdle()

        assertThat(viewModel.content().cards.map { it.profile.id.value }).containsExactly("u_1", "u_2", "u_3").inOrder()
        assertThat(viewModel.content().quota).isEqualTo(Fixtures.quota)
        assertThat(analytics.of<AnalyticsEvent.ScreenViewed>()).containsExactly(AnalyticsEvent.ScreenViewed("discovery"))
    }

    @Test
    @Covers("DISC-02")
    fun `a failed first load shows the error and retry recovers`() = runTest(main.dispatcher) {
        discovery.deckResults[null] = Outcome.Failure(AppError.Timeout)
        val viewModel = viewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value).isEqualTo(DiscoveryUiState.Error(AppError.Timeout))

        discovery.deckResults[null] = Outcome.Success(Fixtures.deck())
        viewModel.retry()
        assertThat(viewModel.state.value).isEqualTo(DiscoveryUiState.Loading)
        advanceUntilIdle()

        assertThat(viewModel.content().cards).hasSize(3)
        assertThat(analytics.events).containsAtLeast(
            AnalyticsEvent.ErrorShown("discovery", "Timeout"),
            AnalyticsEvent.RetryTapped("discovery"),
        ).inOrder()
    }

    @Test
    @Covers("DISC-03")
    fun `a missing quota hides the counter and still shows the deck`() = runTest(main.dispatcher) {
        discovery.quotaResult = Outcome.Failure(AppError.Server(500))
        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.content().quota).isNull()
        assertThat(viewModel.content().cards).hasSize(3)
    }

    @Test
    @Covers("DIAG-03")
    fun `the quota flag hides the counter even when the quota loaded`() = runTest(main.dispatcher) {
        flags = FakeFeatureFlags(mapOf(Flag.SHOW_LIKE_QUOTA to false))
        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.content().quota).isNull()
    }

    @Test
    @Covers("DISC-05")
    fun `a swipe removes the card and spends a like before the request finishes`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        val top = viewModel.content().cards.first()

        viewModel.swipe(top, SwipeAction.LIKE)

        // No advance yet: the request has not run, and the screen has already moved on.
        assertThat(viewModel.content().cards.map { it.profile.id.value }).containsExactly("u_2", "u_3").inOrder()
        assertThat(viewModel.content().quota?.remaining).isEqualTo(Fixtures.quota.remaining - 1)
        assertThat(discovery.swipes).isEmpty()

        advanceUntilIdle()
        assertThat(discovery.swipes).containsExactly(top.profile.id to SwipeAction.LIKE)
    }

    @Test
    @Covers("DISC-05")
    fun `a swipe the server refused puts the card back and refunds the like`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        val top = viewModel.content().cards.first()
        discovery.swipeResult = Outcome.Failure(AppError.Offline)

        viewModel.swipe(top, SwipeAction.LIKE)
        advanceUntilIdle()

        assertThat(viewModel.content().cards.first()).isEqualTo(top)
        assertThat(viewModel.content().cards).hasSize(3)
        assertThat(viewModel.content().quota).isEqualTo(Fixtures.quota)
        assertThat(viewModel.content().notice).isEqualTo(AppError.Offline)

        viewModel.dismissNotice()
        assertThat(viewModel.content().notice).isNull()
    }

    @Test
    fun `passing does not spend a like`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.swipe(viewModel.content().cards.first(), SwipeAction.PASS)
        advanceUntilIdle()

        assertThat(viewModel.content().quota).isEqualTo(Fixtures.quota)
    }

    @Test
    @Covers("DISC-05")
    fun `a mutual like announces the match with its conversation`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        val top = viewModel.content().cards.first()
        discovery.swipeResult = Outcome.Success(SwipeResult(matched = true, conversationId = ConversationId("c_9")))

        viewModel.swipe(top, SwipeAction.LIKE)
        advanceUntilIdle()

        assertThat(viewModel.content().match).isEqualTo(MatchEvent(top.profile.displayName, ConversationId("c_9")))
        assertThat(analytics.of<AnalyticsEvent.SwipePerformed>()).containsExactly(AnalyticsEvent.SwipePerformed("like", matched = true))

        viewModel.dismissMatch()
        assertThat(viewModel.content().match).isNull()
    }

    @Test
    fun `a second tap on a card that is already leaving is ignored`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        val top = viewModel.content().cards.first()

        viewModel.swipe(top, SwipeAction.LIKE)
        viewModel.swipe(top, SwipeAction.LIKE)
        advanceUntilIdle()

        assertThat(discovery.swipes).hasSize(1)
        assertThat(viewModel.content().quota?.remaining).isEqualTo(Fixtures.quota.remaining - 1)
    }

    @Test
    @Covers("DISC-06")
    fun `running low fetches the next page and skips people already in the deck`() = runTest(main.dispatcher) {
        discovery.deckResults["c1"] = Outcome.Success(
            DeckPage(
                candidates = listOf(3, 4, 5).map { Candidate(Fixtures.profile(it), distanceKm = it) },
                nextCursor = null,
            ),
        )
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.swipe(viewModel.content().cards.first(), SwipeAction.PASS)
        advanceUntilIdle()

        assertThat(discovery.getDeckCalls).containsExactly(null, "c1").inOrder()
        assertThat(viewModel.content().cards.map { it.profile.id.value }).containsExactly("u_2", "u_3", "u_4", "u_5").inOrder()
        assertThat(viewModel.content().loadingMore).isFalse()

        // The last page had no cursor, so emptying the deck further asks for nothing more.
        viewModel.swipe(viewModel.content().cards.first(), SwipeAction.PASS)
        viewModel.swipe(viewModel.content().cards.first(), SwipeAction.PASS)
        advanceUntilIdle()
        assertThat(discovery.getDeckCalls).hasSize(2)
    }

    @Test
    @Covers("DISC-02")
    fun `a failed refresh keeps the cards that are on screen`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        discovery.deckResults[null] = Outcome.Failure(AppError.Offline)

        viewModel.refresh()
        assertThat(viewModel.content().refreshing).isTrue()
        advanceUntilIdle()

        assertThat(viewModel.content().cards).hasSize(3)
        assertThat(viewModel.content().refreshing).isFalse()
        assertThat(viewModel.content().notice).isEqualTo(AppError.Offline)
    }

    @Test
    fun `saving filters stores them and reloads the deck`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        val narrowed = DiscoveryPreferences(minAge = 25, maxAge = 32, maxDistanceKm = 15)

        viewModel.savePreferences(narrowed)
        advanceUntilIdle()

        assertThat(discovery.savedPreferences).containsExactly(narrowed)
        assertThat(viewModel.content().preferences).isEqualTo(narrowed)
        assertThat(discovery.getDeckCalls).hasSize(2)
    }
}
