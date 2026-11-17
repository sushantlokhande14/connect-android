package app.connect.feature.chat

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.connect.core.ui.ConnectTheme
import app.connect.core.ui.TestTags
import app.connect.domain.model.AppError
import app.connect.domain.model.ConversationId
import app.connect.domain.model.MessageStatus
import app.connect.domain.model.UserId
import app.connect.testing.Covers
import app.connect.testing.Fixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConversationsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var retries = 0
    private val opened = mutableListOf<ConversationId>()

    private fun show(state: ConversationsUiState) {
        compose.setContent { ConnectTheme { ConversationsScreen(state, { opened += it }, { retries++ }) } }
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    @Test
    fun `before the first refresh finishes an empty list is a spinner, not an empty state`() {
        show(ConversationsUiState(loading = true))

        assertThat(count(TestTags.LOADING)).isEqualTo(1)
        assertThat(count(TestTags.EMPTY)).isEqualTo(0)
    }

    @Test
    fun `no conversations after a successful refresh is the empty state`() {
        show(ConversationsUiState(loading = false))

        assertThat(count(TestTags.EMPTY)).isEqualTo(1)
    }

    @Test
    @Covers("CHAT-03")
    fun `saved conversations stay on screen when a refresh fails`() {
        show(
            ConversationsUiState(
                conversations = listOf(Fixtures.conversation(1, unread = 2), Fixtures.conversation(2)),
                loading = false,
                error = AppError.Offline,
            ),
        )

        assertThat(count(TestTags.CONVERSATION_ROW)).isEqualTo(2)
        compose.onNodeWithText("You're offline. Showing saved conversations.").assertExists().performClick()
        assertThat(retries).isEqualTo(1)
    }

    @Test
    @Covers("CHAT-03")
    fun `with nothing saved a failed refresh is a full error with retry`() {
        show(ConversationsUiState(loading = false, error = AppError.Timeout))

        compose.onNodeWithText("That took too long. Try again.").assertExists()
        compose.onNodeWithTag(TestTags.RETRY).performClick()
        assertThat(retries).isEqualTo(1)
    }

    @Test
    fun `tapping a row opens that conversation`() {
        show(ConversationsUiState(conversations = listOf(Fixtures.conversation(3)), loading = false))

        compose.onNodeWithTag(TestTags.CONVERSATION_ROW).performClick()

        assertThat(opened).containsExactly(ConversationId("c_3"))
    }
}

@RunWith(RobolectricTestRunner::class)
class ChatScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var sends = 0
    private val retried = mutableListOf<String>()

    private fun item(id: String, body: String, status: MessageStatus, mine: Boolean = true) = MessageItem(
        Fixtures.message(clientId = id, body = body, status = status, sender = if (mine) Fixtures.meId else UserId("u_1")),
        mine = mine,
    )

    private fun show(state: ChatUiState) {
        compose.setContent { ConnectTheme { ChatScreen(state, {}, {}, { sends++ }, { retried += it }) } }
    }

    @Test
    @Covers("CHAT-01")
    fun `messages are shown with the other person's name in the bar`() {
        show(
            ChatUiState(
                peerName = "Maya",
                messages = listOf(item("m_1", "hi", MessageStatus.SENT, mine = false), item("m_2", "hey", MessageStatus.SENT)),
            ),
        )

        compose.onNodeWithText("Maya").assertExists()
        compose.onNodeWithText("hi").assertExists()
        compose.onNodeWithText("hey").assertExists()
        assertThat(compose.onAllNodesWithTag(TestTags.CHAT_RETRY).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    @Covers("CHAT-02")
    fun `a failed message says so and tapping it asks for a retry of that message`() {
        show(ChatUiState(messages = listOf(item("m_1", "delivered", MessageStatus.SENT), item("m_2", "stuck", MessageStatus.FAILED))))

        compose.onNodeWithText("Not sent. Tap to try again.").assertExists()
        compose.onNodeWithTag(TestTags.CHAT_RETRY).performClick()

        assertThat(retried).containsExactly("m_2")
    }

    @Test
    @Covers("CHAT-01")
    fun `a message on its way is labelled as sending`() {
        show(ChatUiState(messages = listOf(item("m_1", "on its way", MessageStatus.PENDING))))

        compose.onNodeWithText("Sending…").assertExists()
    }

    @Test
    fun `send is only possible with something to send`() {
        show(ChatUiState(draft = ""))
        compose.onNodeWithTag(TestTags.CHAT_SEND).assertIsNotEnabled()
    }

    @Test
    fun `send reports back when there is a draft`() {
        show(ChatUiState(draft = "hello"))

        compose.onNodeWithTag(TestTags.CHAT_SEND).assertIsEnabled().performClick()

        assertThat(sends).isEqualTo(1)
    }

    @Test
    fun `a rejected draft shows the reason above the composer`() {
        show(ChatUiState(draft = "x", draftError = "Messages can be up to 1000 characters"))

        compose.onNodeWithText("Messages can be up to 1000 characters").assertExists()
    }
}
