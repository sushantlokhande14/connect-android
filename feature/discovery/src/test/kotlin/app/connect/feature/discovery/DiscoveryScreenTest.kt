package app.connect.feature.discovery

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import app.connect.core.ui.ConnectTheme
import app.connect.core.ui.TestTags
import app.connect.domain.model.AppError
import app.connect.domain.model.ConversationId
import app.connect.domain.model.DiscoveryCard
import app.connect.domain.model.DiscoveryPreferences
import app.connect.domain.model.SwipeAction
import app.connect.domain.model.UserId
import app.connect.testing.Covers
import app.connect.testing.Fixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiscoveryScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var retries = 0
    private var refreshes = 0
    private val swipes = mutableListOf<Pair<UserId, SwipeAction>>()
    private val opened = mutableListOf<UserId>()
    private val saidHiTo = mutableListOf<ConversationId>()

    private val cards = (1..3).map { DiscoveryCard(Fixtures.profile(it), distanceKm = it * 2, sharedInterests = listOf("film")) }

    private fun content(
        cards: List<DiscoveryCard> = this.cards,
        match: MatchEvent? = null,
        quota: Boolean = true,
    ) = DiscoveryUiState.Content(
        cards = cards,
        preferences = DiscoveryPreferences(),
        quota = Fixtures.quota.takeIf { quota },
        match = match,
    )

    private fun show(state: DiscoveryUiState) {
        compose.setContent {
            ConnectTheme {
                DiscoveryScreen(
                    state = state,
                    onRetry = { retries++ },
                    onRefresh = { refreshes++ },
                    onSwipe = { card, action -> swipes += card.profile.id to action },
                    onOpenProfile = { opened += it },
                    onSayHi = { saidHiTo += it },
                    onDismissMatch = {},
                    onDismissNotice = {},
                    onSavePreferences = {},
                )
            }
        }
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test
    @Covers("DISC-01")
    fun `loading shows a spinner and nothing to act on`() {
        show(DiscoveryUiState.Loading)

        assertThat(exists(TestTags.LOADING)).isTrue()
        assertThat(exists(TestTags.DISCOVERY_LIKE)).isFalse()
        assertThat(exists(TestTags.DISCOVERY_FILTERS)).isFalse()
    }

    @Test
    @Covers("DISC-02")
    fun `an error explains itself and retry reports back`() {
        show(DiscoveryUiState.Error(AppError.Offline))

        compose.onNodeWithText("No connection").assertExists()
        compose.onNodeWithText("You're offline. Check your connection and try again.").assertExists()
        compose.onNodeWithTag(TestTags.RETRY).performClick()

        assertThat(retries).isEqualTo(1)
    }

    @Test
    @Covers("DISC-01")
    fun `content shows the top card and the like quota`() {
        show(content())

        compose.onNodeWithText("Person 1, 25").assertExists()
        compose.onNodeWithTag(TestTags.DISCOVERY_QUOTA).assertTextEquals("25 likes left")
    }

    @Test
    @Covers("DISC-03")
    fun `without a quota the counter is not drawn and the deck still is`() {
        show(content(quota = false))

        assertThat(exists(TestTags.DISCOVERY_QUOTA)).isFalse()
        assertThat(exists(TestTags.DISCOVERY_CARD)).isTrue()
    }

    @Test
    @Covers("DISC-05")
    fun `the buttons act on the top card`() {
        show(content())

        compose.onNodeWithTag(TestTags.DISCOVERY_LIKE).performClick()
        compose.onNodeWithTag(TestTags.DISCOVERY_PASS).performClick()

        assertThat(swipes).containsExactly(UserId("u_1") to SwipeAction.LIKE, UserId("u_1") to SwipeAction.PASS).inOrder()
    }

    @Test
    @Covers("DISC-07")
    fun `dragging the card across the screen to the right likes`() {
        show(content())

        compose.onNodeWithTag(TestTags.DISCOVERY_CARD).performTouchInput { swipeRight() }
        compose.waitForIdle()

        assertThat(swipes).containsExactly(UserId("u_1") to SwipeAction.LIKE)
        assertThat(opened).isEmpty()
    }

    @Test
    @Covers("DISC-07")
    fun `dragging the card across the screen to the left passes`() {
        show(content())

        compose.onNodeWithTag(TestTags.DISCOVERY_CARD).performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertThat(swipes).containsExactly(UserId("u_1") to SwipeAction.PASS)
    }

    @Test
    @Covers("DISC-07")
    fun `a short drag lets go of the card without swiping`() {
        show(content())

        compose.onNodeWithTag(TestTags.DISCOVERY_CARD).performTouchInput {
            down(center)
            moveBy(Offset(width * 0.15f, 0f))
            moveBy(Offset(width * 0.05f, 0f))
            up()
        }
        compose.waitForIdle()

        assertThat(swipes).isEmpty()
    }

    @Test
    fun `tapping the card opens that person's profile`() {
        show(content())

        compose.onNodeWithTag(TestTags.DISCOVERY_CARD).performClick()

        assertThat(opened).containsExactly(UserId("u_1"))
    }

    @Test
    fun `an empty deck says so and offers a refresh`() {
        show(content(cards = emptyList()))

        assertThat(exists(TestTags.EMPTY)).isTrue()
        compose.onNodeWithText("You've seen everyone nearby").assertExists()
        compose.onNodeWithText("Refresh").performClick()

        assertThat(refreshes).isEqualTo(1)
        assertThat(exists(TestTags.DISCOVERY_LIKE)).isFalse()
    }

    @Test
    @Covers("DISC-05")
    fun `a match is announced and leads to its conversation`() {
        show(content(match = MatchEvent("Maya", ConversationId("c_9"))))

        compose.onNodeWithText("You and Maya like each other.").assertExists()
        compose.onNodeWithText("Say hi").performClick()

        assertThat(saidHiTo).containsExactly(ConversationId("c_9"))
    }
}
