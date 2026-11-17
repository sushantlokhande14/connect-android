package app.connect.data

import app.connect.domain.model.AppError
import app.connect.domain.model.ConversationId
import app.connect.domain.model.Message
import app.connect.domain.model.MessageStatus
import app.connect.domain.model.Outcome
import app.connect.domain.model.PushPayload
import app.connect.domain.model.UserId
import app.connect.domain.model.errorOrNull
import app.connect.domain.model.getOrNull
import app.connect.fakebackend.Fault
import app.connect.testing.Covers
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatRepositoryTest {
    private val harness = DataHarness()
    private var conversation = ConversationId("unset")
    private val sendRoute = "POST /v1/conversations/{id}/messages"

    @Before
    fun setUp() = runTestBlocking {
        harness.backend.simulatePeerReplies = false
        harness.signInDemo()
        conversation = harness.matchWithFirstPerson()
        harness.chat.refreshConversations()
        Unit
    }

    @After
    fun tearDown() = harness.close()

    private suspend fun stored(): List<Message> = harness.chat.observeMessages(conversation).first()

    @Test
    @Covers("CHAT-01")
    fun `a sent message is stored on the server and marked sent locally`() = runTestBlocking {
        val result = harness.chat.send(conversation, "hello")

        assertThat(result.getOrNull()?.status).isEqualTo(MessageStatus.SENT)
        assertThat(stored().map { it.body to it.status }).containsExactly("hello" to MessageStatus.SENT)
        assertThat(harness.backend.storedMessageBodies(conversation.value)).containsExactly("hello")
    }

    @Test
    @Covers("CHAT-02")
    fun `a failed send stays in the conversation as failed`() = runTestBlocking {
        harness.backend.faults.fail(sendRoute, Fault.Http(503))

        val result = harness.chat.send(conversation, "hello")

        assertThat(result.errorOrNull()).isEqualTo(AppError.Server(503))
        assertThat(stored().single().status).isEqualTo(MessageStatus.FAILED)
        assertThat(harness.backend.storedMessageBodies(conversation.value)).isEmpty()
    }

    @Test
    @Covers("CHAT-02")
    fun `retrying a failed message sends it once`() = runTestBlocking {
        harness.backend.faults.failUntilCleared(sendRoute, Fault.Disconnect)
        harness.chat.send(conversation, "hello")
        val failed = stored().single()
        assertThat(failed.status).isEqualTo(MessageStatus.FAILED)
        harness.backend.faults.clear()

        val retried = harness.chat.retry(failed.clientId)

        assertThat(retried.getOrNull()?.status).isEqualTo(MessageStatus.SENT)
        assertThat(stored().map { it.clientId to it.status }).containsExactly(failed.clientId to MessageStatus.SENT)
        assertThat(harness.backend.storedMessageBodies(conversation.value)).containsExactly("hello")
    }

    @Test
    @Covers("CHAT-02")
    fun `a send whose response was lost is not duplicated by the retry`() = runTestBlocking {
        // The server stores the message, then the connection drops. The device believes the send failed.
        harness.backend.faults.failUntilCleared(sendRoute, Fault.LostResponse)
        val first = harness.chat.send(conversation, "hello")
        assertThat(first).isInstanceOf(Outcome.Failure::class.java)
        assertThat(harness.backend.storedMessageBodies(conversation.value)).containsExactly("hello")
        harness.backend.faults.clear()

        harness.chat.retry(stored().single().clientId)

        // Same client id, so the server returns the message it already has.
        assertThat(harness.backend.storedMessageBodies(conversation.value)).containsExactly("hello")
        assertThat(stored().single().status).isEqualTo(MessageStatus.SENT)
    }

    @Test
    @Covers("CHAT-02")
    fun `a send the HTTP client replays on its own is still stored once`() = runTestBlocking {
        // One dropped connection. OkHttp may replay the POST on a new connection without reporting a
        // failure, or it may surface the failure; which one happens depends on connection pooling.
        // Either way the server must end up with one copy and the device must settle on SENT.
        harness.backend.faults.fail(sendRoute, Fault.LostResponse)

        val first = harness.chat.send(conversation, "hello")
        if (first is Outcome.Failure) harness.chat.retry(stored().single().clientId)

        assertThat(harness.backend.storedMessageBodies(conversation.value)).containsExactly("hello")
        assertThat(stored().single().status).isEqualTo(MessageStatus.SENT)
    }

    @Test
    @Covers("CHAT-02")
    fun `a refresh repairs a lost response without any retry`() = runTestBlocking {
        harness.backend.faults.failUntilCleared(sendRoute, Fault.LostResponse)
        harness.chat.send(conversation, "hello")
        assertThat(stored().single().status).isEqualTo(MessageStatus.FAILED)
        harness.backend.faults.clear()

        harness.chat.refreshMessages(conversation)

        assertThat(stored().single().status).isEqualTo(MessageStatus.SENT)
    }

    @Test
    fun `retrying a message that was already sent does nothing`() = runTestBlocking {
        val sent = harness.chat.send(conversation, "hello").getOrNull()!!
        harness.backend.clearRequestLog()

        val result = harness.chat.retry(sent.clientId)

        assertThat(result.getOrNull()).isEqualTo(sent)
        assertThat(harness.backend.requests()).isEmpty()
    }

    @Test
    fun `refreshing keeps messages that have not reached the server`() = runTestBlocking {
        harness.chat.send(conversation, "delivered")
        harness.backend.faults.fail(sendRoute, Fault.Http(500))
        harness.chat.send(conversation, "stuck")

        harness.chat.refreshMessages(conversation)

        assertThat(stored().map { it.body to it.status })
            .containsExactly("delivered" to MessageStatus.SENT, "stuck" to MessageStatus.FAILED)
    }

    @Test
    @Covers("CHAT-03")
    fun `conversations stay readable when the server cannot be reached`() = runTestBlocking {
        harness.chat.send(conversation, "hello")
        harness.chat.refreshConversations()
        harness.backend.faults.failUntilCleared("GET /v1/conversations", Fault.Disconnect)

        val refresh = harness.chat.refreshConversations()
        val conversations = harness.chat.observeConversations().first()

        assertThat(refresh.errorOrNull()).isEqualTo(AppError.Offline)
        assertThat(conversations.single().id).isEqualTo(conversation)
        assertThat(conversations.single().lastMessagePreview).isEqualTo("hello")
        assertThat(stored().single().body).isEqualTo("hello")
    }

    @Test
    @Covers("CHAT-03")
    fun `the unread count falls back to the local count when offline`() = runTestBlocking {
        harness.backend.peerSends(conversation.value, "are you there?")
        harness.chat.refreshConversations()
        harness.backend.faults.failUntilCleared("GET /v1/conversations/unread-count", Fault.Disconnect)

        assertThat(harness.chat.fetchUnreadCount().getOrNull()).isEqualTo(1)
    }

    @Test
    @Covers("CHAT-04")
    fun `a pushed message is stored and counted as unread`() = runTestBlocking {
        val push = PushPayload.NewMessage(conversation, "srv_1", UserId("u_1"), "Maya", "hi!", sentAtMillis = harness.clock.now + 5)

        harness.chat.onPush(push)

        assertThat(stored().single().body).isEqualTo("hi!")
        val listed = harness.chat.observeConversations().first().single()
        assertThat(listed.unreadCount).isEqualTo(1)
        assertThat(listed.lastMessagePreview).isEqualTo("hi!")
        assertThat(harness.chat.observeUnreadCount().first()).isEqualTo(1)
    }

    @Test
    @Covers("CHAT-04")
    fun `a push delivered twice is stored and counted once`() = runTestBlocking {
        val push = PushPayload.NewMessage(conversation, "srv_1", UserId("u_1"), "Maya", "hi!", sentAtMillis = harness.clock.now + 5)

        harness.chat.onPush(push)
        harness.chat.onPush(push)

        assertThat(stored()).hasSize(1)
        assertThat(harness.chat.observeUnreadCount().first()).isEqualTo(1)
    }

    @Test
    @Covers("CHAT-04")
    fun `a push for a conversation this device has not listed creates it`() = runTestBlocking {
        val unknown = ConversationId("c_new")

        harness.chat.onPush(PushPayload.NewMessage(unknown, "srv_9", UserId("u_7"), "Ines", "hey", sentAtMillis = harness.clock.now + 9))

        val created = harness.chat.observeConversations().first().first { it.id == unknown }
        assertThat(created.peerName).isEqualTo("Ines")
        assertThat(created.unreadCount).isEqualTo(1)
    }

    @Test
    fun `a match push adds an empty conversation`() = runTestBlocking {
        harness.chat.onPush(PushPayload.NewMatch(ConversationId("c_match"), UserId("u_4"), "Leo"))

        val created = harness.chat.observeConversations().first().first { it.id.value == "c_match" }
        assertThat(created.peerName).isEqualTo("Leo")
        assertThat(created.lastMessagePreview).isNull()
    }

    @Test
    fun `opening a conversation clears its unread count here and on the server`() = runTestBlocking {
        harness.backend.peerSends(conversation.value, "one")
        harness.backend.peerSends(conversation.value, "two")
        harness.chat.refreshConversations()
        assertThat(harness.chat.observeUnreadCount().first()).isEqualTo(2)

        harness.chat.markRead(conversation)

        assertThat(harness.chat.observeUnreadCount().first()).isEqualTo(0)
        assertThat(harness.chat.fetchUnreadCount().getOrNull()).isEqualTo(0)
    }

    @Test
    fun `messages are ordered by time`() = runTestBlocking {
        harness.chat.send(conversation, "first")
        harness.clock.advance(1_000)
        harness.backend.peerSends(conversation.value, "second")
        harness.clock.advance(1_000)
        harness.chat.send(conversation, "third")

        harness.chat.refreshMessages(conversation)

        assertThat(stored().map { it.body }).containsExactly("first", "second", "third").inOrder()
    }
}
