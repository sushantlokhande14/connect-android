package app.connect.feature.chat

import androidx.lifecycle.SavedStateHandle
import app.connect.domain.diagnostics.AnalyticsEvent
import app.connect.domain.model.AppError
import app.connect.domain.model.AuthState
import app.connect.domain.model.MessageStatus
import app.connect.domain.model.Outcome
import app.connect.domain.model.UserId
import app.connect.domain.usecase.SendMessage
import app.connect.testing.Covers
import app.connect.testing.FakeAuthRepository
import app.connect.testing.FakeChatRepository
import app.connect.testing.Fixtures
import app.connect.testing.MainDispatcherRule
import app.connect.testing.RecordingAnalytics
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class ConversationsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val chat = FakeChatRepository()
    private val analytics = RecordingAnalytics()

    @Test
    @Covers("CHAT-03")
    fun `the list comes from storage and the first refresh ends the loading state`() = runTest(main.dispatcher) {
        chat.conversations.value = listOf(Fixtures.conversation(1, unread = 2), Fixtures.conversation(2))

        val viewModel = ConversationsViewModel(chat, analytics)
        assertThat(viewModel.state.value.loading).isTrue()
        advanceUntilIdle()

        assertThat(viewModel.state.value.loading).isFalse()
        assertThat(viewModel.state.value.conversations).hasSize(2)
        assertThat(viewModel.state.value.error).isNull()
        assertThat(chat.refreshConversationsCalls).isEqualTo(1)
    }

    @Test
    @Covers("CHAT-03")
    fun `a failed refresh keeps the saved conversations and reports the failure`() = runTest(main.dispatcher) {
        chat.conversations.value = listOf(Fixtures.conversation(1))
        chat.refreshConversationsResult = Outcome.Failure(AppError.Offline)

        val viewModel = ConversationsViewModel(chat, analytics)
        advanceUntilIdle()

        assertThat(viewModel.state.value.conversations).hasSize(1)
        assertThat(viewModel.state.value.error).isEqualTo(AppError.Offline)
        assertThat(analytics.of<AnalyticsEvent.ErrorShown>()).containsExactly(AnalyticsEvent.ErrorShown("conversations", "Offline"))
    }

    @Test
    @Covers("CHAT-03")
    fun `retry refreshes again and clears the failure`() = runTest(main.dispatcher) {
        chat.refreshConversationsResult = Outcome.Failure(AppError.Timeout)
        val viewModel = ConversationsViewModel(chat, analytics)
        advanceUntilIdle()

        chat.refreshConversationsResult = Outcome.Success(Unit)
        viewModel.retry()
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isNull()
        assertThat(chat.refreshConversationsCalls).isEqualTo(2)
        assertThat(analytics.of<AnalyticsEvent.RetryTapped>()).hasSize(1)
    }

    @Test
    @Covers("CHAT-04")
    fun `a conversation added later appears without a refresh`() = runTest(main.dispatcher) {
        val viewModel = ConversationsViewModel(chat, analytics)
        advanceUntilIdle()
        assertThat(viewModel.state.value.conversations).isEmpty()

        // What a push does: a row is written to storage.
        chat.conversations.value = listOf(Fixtures.conversation(7, unread = 1))
        advanceUntilIdle()

        assertThat(viewModel.state.value.conversations.single().unreadCount).isEqualTo(1)
        assertThat(chat.refreshConversationsCalls).isEqualTo(1)
    }
}

class ChatViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val chat = FakeChatRepository().apply { conversations.value = listOf(Fixtures.conversation(1)) }
    private val auth = FakeAuthRepository(AuthState.SignedIn(Fixtures.meId))
    private val analytics = RecordingAnalytics()
    private val peer = UserId("u_1")

    private fun viewModel() = ChatViewModel(
        SavedStateHandle(mapOf(ChatViewModel.ARG_CONVERSATION_ID to Fixtures.conversationId.value)),
        chat,
        SendMessage(chat, analytics),
        auth,
        analytics,
    )

    @Test
    @Covers("CHAT-01")
    fun `opening a conversation refreshes it and marks it read`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(chat.refreshMessagesCalls).containsExactly(Fixtures.conversationId)
        assertThat(chat.markedRead).containsExactly(Fixtures.conversationId)
        assertThat(viewModel.state.value.peerName).isEqualTo("Person 1")
    }

    @Test
    fun `messages are attributed to me or to the other person`() = runTest(main.dispatcher) {
        chat.messages.value = listOf(
            Fixtures.message("m_1", "hi", sender = peer, at = 1_000),
            Fixtures.message("m_2", "hey", sender = Fixtures.meId, at = 2_000),
        )
        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.messages.map { it.message.body to it.mine })
            .containsExactly("hi" to false, "hey" to true).inOrder()
    }

    @Test
    @Covers("CHAT-01")
    fun `sending clears the composer and the message appears in the list`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onDraftChange("hello there")
        viewModel.send()
        assertThat(viewModel.state.value.draft).isEmpty()
        advanceUntilIdle()

        assertThat(chat.sent).containsExactly(Fixtures.conversationId to "hello there")
        assertThat(viewModel.state.value.messages.single().message.status).isEqualTo(MessageStatus.SENT)
    }

    @Test
    fun `a blank draft is not sent`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        viewModel.onDraftChange("   ")

        viewModel.send()
        advanceUntilIdle()

        assertThat(chat.sent).isEmpty()
    }

    @Test
    fun `a message that is too long is handed back with the reason`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        val tooLong = "x".repeat(SendMessage.MAX_LENGTH + 1)
        viewModel.onDraftChange(tooLong)

        viewModel.send()
        advanceUntilIdle()

        assertThat(viewModel.state.value.draft).isEqualTo(tooLong)
        assertThat(viewModel.state.value.draftError).contains("up to ${SendMessage.MAX_LENGTH}")
        assertThat(chat.sent).isEmpty()
    }

    @Test
    @Covers("CHAT-02")
    fun `a failed send stays in the list as failed and can be retried`() = runTest(main.dispatcher) {
        chat.sendError = AppError.Offline
        val viewModel = viewModel()
        viewModel.onDraftChange("hello")
        viewModel.send()
        advanceUntilIdle()

        val failed = viewModel.state.value.messages.single().message
        assertThat(failed.status).isEqualTo(MessageStatus.FAILED)
        // The text is in the list now, so the composer does not get it back.
        assertThat(viewModel.state.value.draft).isEmpty()

        chat.sendError = null
        viewModel.retry(failed.clientId)
        advanceUntilIdle()

        assertThat(chat.retried).containsExactly(failed.clientId)
        assertThat(viewModel.state.value.messages.single().message.status).isEqualTo(MessageStatus.SENT)
        assertThat(analytics.of<AnalyticsEvent.MessageSendCompleted>().last())
            .isEqualTo(AnalyticsEvent.MessageSendCompleted(success = true, wasRetry = true))
    }

    @Test
    @Covers("CHAT-04")
    fun `a message that arrives while the conversation is open is marked read`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        assertThat(chat.markedRead).hasSize(1)

        chat.messages.update { it + Fixtures.message("m_9", "you there?", sender = peer, at = 9_000) }
        advanceUntilIdle()

        assertThat(chat.markedRead).hasSize(2)
        assertThat(viewModel.state.value.messages.single().mine).isFalse()
    }

    @Test
    fun `my own messages do not trigger another mark as read`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onDraftChange("hello")
        viewModel.send()
        advanceUntilIdle()

        assertThat(chat.markedRead).hasSize(1)
    }

    @Test
    fun `a failed refresh is reported and the stored messages stay`() = runTest(main.dispatcher) {
        chat.messages.value = listOf(Fixtures.message("m_1", "saved", sender = peer))
        chat.refreshMessagesResult = Outcome.Failure(AppError.Offline)

        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.refreshError).isEqualTo(AppError.Offline)
        assertThat(viewModel.state.value.messages.single().message.body).isEqualTo("saved")
    }
}
