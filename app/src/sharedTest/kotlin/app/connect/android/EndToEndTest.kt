package app.connect.android

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import app.connect.core.ui.TestTags
import app.connect.data.di.ApplicationScope
import app.connect.domain.model.ConversationId
import app.connect.fakebackend.FakeBackend
import app.connect.fakebackend.Fault
import app.connect.fakebackend.NetworkProfile
import app.connect.testing.Covers
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * The whole app, driven through its UI: the real activity, navigation, Hilt
 * graph, repositories, Retrofit and Room, against the demo backend running in
 * process. The same file runs on the JVM under Robolectric and on an emulator.
 *
 * Synchronisation is by waiting for what a user would see, never by sleeping.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class EndToEndTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    // On a device the permission dialog would cover the app. Robolectric has no dialog to cover it.
    @get:Rule(order = 1)
    val permission: GrantPermissionRule =
        if (!isRobolectric && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            GrantPermissionRule.grant()
        }

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var backend: FakeBackend

    @Inject
    lateinit var notifier: RecordingNotifier

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    @Before
    fun setUp() {
        hilt.inject()
        backend.network = NetworkProfile.INSTANT
        backend.simulatePeerReplies = false
    }

    @After
    fun tearDown() {
        // Stops the push poller, which would otherwise keep running into the next test.
        appScope.cancel()
    }

    @Test
    @Covers("AUTH-01")
    @Covers("NAV-01")
    fun signingInLandsOnDiscovery() {
        signIn()

        waitFor { tagShown(TestTags.DISCOVERY_CARD) }
        compose.onNodeWithTag(TestTags.NAV_DISCOVER).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.DISCOVERY_QUOTA).assertTextEquals("25 likes left")
    }

    @Test
    @Covers("AUTH-02")
    fun wrongPasswordShowsAnErrorAndStaysOnTheForm() {
        signIn(password = "not-the-password1")

        waitFor { tagShown(TestTags.AUTH_ERROR) }
        compose.onNodeWithTag(TestTags.AUTH_ERROR).assertTextEquals("Wrong email or password.")
        assertThat(tagShown(TestTags.DISCOVERY_CARD)).isFalse()
    }

    @Test
    @Covers("AUTH-03")
    fun signUpValidatesBeforeSending() {
        waitFor { tagShown(TestTags.AUTH_TOGGLE_MODE) }
        compose.onNodeWithTag(TestTags.AUTH_TOGGLE_MODE).performScrollTo().performClick()
        waitFor { tagShown(TestTags.AUTH_NAME) }

        compose.onNodeWithTag(TestTags.AUTH_SUBMIT).performScrollTo().performClick()

        waitFor { textShown("Enter your name") }
        assertThat(backend.requestCount("POST /v1/auth/signup")).isEqualTo(0)
    }

    @Test
    @Covers("NAV-01")
    fun tabsAndBackNavigation() {
        signIn()
        waitFor { tagShown(TestTags.DISCOVERY_CARD) }

        compose.onNodeWithTag(TestTags.NAV_CHATS).performClick()
        waitFor { textShown("No conversations yet") }

        compose.onNodeWithTag(TestTags.NAV_ME).performClick()
        waitFor { tagShown(TestTags.PROFILE_NAME) }
        compose.onNodeWithTag(TestTags.PROFILE_NAME).assertTextEquals("Sam, 27")

        compose.onNodeWithTag(TestTags.NAV_DISCOVER).performClick()
        waitFor { tagShown(TestTags.DISCOVERY_CARD) }

        // Into a detail screen, where the bottom bar is hidden, and back out with the system back button.
        compose.onNodeWithTag(TestTags.DISCOVERY_CARD).performClick()
        waitFor { tagShown(TestTags.PROFILE_NAME) }
        assertThat(tagShown(TestTags.NAV_DISCOVER)).isFalse()

        Espresso.pressBack()

        waitFor { tagShown(TestTags.DISCOVERY_CARD) }
        compose.onNodeWithTag(TestTags.NAV_DISCOVER).assertIsDisplayed()
    }

    @Test
    @Covers("DISC-02")
    fun aFailedLoadOffersRetryAndRecovers() {
        backend.faults.failUntilCleared("GET /v1/discovery", Fault.Http(503))
        signIn()

        waitFor { tagShown(TestTags.ERROR) }
        assertThat(textShown("Something went wrong on our side. Try again in a moment.")).isTrue()

        backend.faults.clear()
        compose.onNodeWithTag(TestTags.RETRY).performClick()

        waitFor { tagShown(TestTags.DISCOVERY_CARD) }
    }

    @Test
    @Covers("DISC-05")
    @Covers("CHAT-01")
    fun aMatchOpensAConversationAndAMessageCanBeSent() {
        signIn()
        waitFor { tagShown(TestTags.DISCOVERY_CARD) }

        likeUntilMatched()
        compose.onNodeWithText("Say hi").performClick()

        waitFor { tagShown(TestTags.CHAT_INPUT) }
        compose.onNodeWithTag(TestTags.CHAT_INPUT).performTextInput("hello there")
        compose.onNodeWithTag(TestTags.CHAT_SEND).performClick()

        waitFor { textShown("hello there") }
        val conversation = backend.conversationIdsOf(FakeBackend.DEMO_EMAIL).single()
        waitFor { backend.storedMessageBodies(conversation) == listOf("hello there") }
    }

    @Test
    @Covers("CHAT-04")
    fun aPushedMessageRaisesTheBadgeAndIsNotNotifiedWhileItsConversationIsOpen() {
        signIn()
        waitFor { tagShown(TestTags.DISCOVERY_CARD) }
        val conversation = backend.startConversation(FakeBackend.DEMO_EMAIL)

        backend.peerSends(conversation, "are you there?")

        waitFor { notifier.messages.size == 1 }
        assertThat(notifier.messages.single().body).isEqualTo("are you there?")
        waitFor { compose.onAllNodesWithContentDescription("Chats, 1 unread").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag(TestTags.NAV_CHATS).performClick()
        waitFor { tagShown(TestTags.CONVERSATION_ROW) }
        compose.onNodeWithTag(TestTags.CONVERSATION_ROW).performClick()
        waitFor { tagShown(TestTags.CHAT_INPUT) }
        waitFor { textShown("are you there?") }

        // With the conversation on screen, the next message appears in place and raises no notification.
        backend.peerSends(conversation, "second message")

        waitFor { textShown("second message") }
        assertThat(notifier.messages).hasSize(1)
    }

    @Test
    @Covers("NAV-02")
    fun aNotificationTapOpensItsConversation() {
        signIn()
        waitFor { tagShown(TestTags.DISCOVERY_CARD) }
        val conversation = backend.startConversation(FakeBackend.DEMO_EMAIL)
        backend.peerSends(conversation, "are you there?")

        // The same intent SystemNotifier attaches to a notification, delivered to the running activity.
        compose.activityRule.scenario.onActivity { activity ->
            activity.onNewIntent(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(Routes.chatDeepLink(ConversationId(conversation))),
                    activity,
                    MainActivity::class.java,
                ),
            )
        }

        waitFor { tagShown(TestTags.CHAT_INPUT) }
        waitFor { textShown("are you there?") }
    }

    @Test
    @Covers("AUTH-06")
    fun aSessionEndedByTheServerReturnsToSignInWithAnExplanation() {
        signIn()
        waitFor { tagShown(TestTags.DISCOVERY_CARD) }

        backend.expireAccessTokens()
        backend.revokeRefreshTokens()

        // No user action needed: the next request the app makes discovers the session is gone.
        waitFor { tagShown(TestTags.AUTH_EMAIL) }
        assertThat(textShown("your session ended", substring = true)).isTrue()
        assertThat(tagShown(TestTags.NAV_DISCOVER)).isFalse()
    }

    @Test
    @Covers("NAV-03")
    fun theCurrentScreenSurvivesActivityRecreation() {
        signIn()
        waitFor { tagShown(TestTags.DISCOVERY_CARD) }
        compose.onNodeWithTag(TestTags.DISCOVERY_CARD).performClick()
        waitFor { tagShown(TestTags.PROFILE_NAME) }

        compose.activityRule.scenario.recreate()

        waitFor { tagShown(TestTags.PROFILE_NAME) }
        assertThat(tagShown(TestTags.AUTH_EMAIL)).isFalse()
    }

    @Test
    @Covers("AUTH-06")
    fun signingOutReturnsToTheForm() {
        signIn()
        waitFor { tagShown(TestTags.DISCOVERY_CARD) }
        compose.onNodeWithTag(TestTags.NAV_ME).performClick()
        waitFor { tagShown(TestTags.PROFILE_SIGN_OUT) }

        compose.onNodeWithTag(TestTags.PROFILE_SIGN_OUT).performScrollTo().performClick()

        waitFor { tagShown(TestTags.AUTH_EMAIL) }
        // A deliberate sign-out is not explained as an expired session.
        assertThat(textShown("your session ended", substring = true)).isFalse()
    }

    // ------------------------------------------------------------------ helpers

    private fun signIn(email: String = FakeBackend.DEMO_EMAIL, password: String = FakeBackend.DEMO_PASSWORD) {
        waitFor { tagShown(TestTags.AUTH_EMAIL) }
        compose.onNodeWithTag(TestTags.AUTH_EMAIL).performTextInput(email)
        compose.onNodeWithTag(TestTags.AUTH_PASSWORD).performTextInput(password)
        compose.onNodeWithTag(TestTags.AUTH_SUBMIT).performScrollTo().performClick()
    }

    /** Likes whoever is on top until someone likes back. Every third person in the demo directory does. */
    private fun likeUntilMatched(maxSwipes: Int = 12) {
        repeat(maxSwipes) {
            if (tagShown(TestTags.MATCH_DIALOG)) return
            val sentBefore = backend.requestCount("POST /v1/swipes")
            compose.onNodeWithTag(TestTags.DISCOVERY_LIKE).performClick()
            waitFor { backend.requestCount("POST /v1/swipes") > sentBefore }
            runCatching { compose.waitUntil(1_500) { tagShown(TestTags.MATCH_DIALOG) } }
        }
        check(tagShown(TestTags.MATCH_DIALOG)) { "no match after $maxSwipes likes" }
    }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(TIMEOUT_MILLIS, condition)

    private fun tagShown(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun textShown(text: String, substring: Boolean = false) =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        const val TIMEOUT_MILLIS = 15_000L
        val isRobolectric = Build.FINGERPRINT.lowercase().contains("robolectric")
    }
}
