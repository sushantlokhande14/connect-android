package app.connect.android.push

import app.connect.data.network.PushDto
import app.connect.domain.model.AuthState
import app.connect.domain.model.ConversationId
import app.connect.domain.model.PushPayload
import app.connect.testing.Covers
import app.connect.testing.FakeAuthRepository
import app.connect.testing.FakeChatRepository
import app.connect.testing.Fixtures
import app.connect.testing.RecordingBreadcrumbs
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PushHandlerTest {
    private class FakeNotifier : Notifier {
        val messages = mutableListOf<PushPayload.NewMessage>()
        val matches = mutableListOf<PushPayload.NewMatch>()

        override fun createChannels() = Unit

        override fun showMessage(payload: PushPayload.NewMessage) {
            messages += payload
        }

        override fun showMatch(payload: PushPayload.NewMatch) {
            matches += payload
        }
    }

    private val signedIn = AuthState.SignedIn(Fixtures.meId)
    private val auth = FakeAuthRepository(signedIn).apply { restoredState = signedIn }
    private val chat = FakeChatRepository()
    private val notifier = FakeNotifier()
    private val visible = VisibleConversation()
    private val breadcrumbs = RecordingBreadcrumbs()
    private val handler = PushHandler(auth, chat, notifier, visible, breadcrumbs)

    private val message = PushDto(
        type = "new_message",
        conversationId = "c_1",
        clientId = "srv_1",
        senderId = "u_1",
        senderName = "Maya",
        body = "hi!",
        sentAt = 10,
    )

    @Test
    @Covers("CHAT-04")
    fun `a message for a conversation that is not on screen is stored and notified`() = runTest {
        handler.handle(message)

        assertThat(chat.pushes).hasSize(1)
        assertThat(notifier.messages.single().body).isEqualTo("hi!")
    }

    @Test
    @Covers("CHAT-04")
    fun `a message for the conversation on screen is stored but not notified`() = runTest {
        visible.id = ConversationId("c_1")

        handler.handle(message)

        assertThat(chat.pushes).hasSize(1)
        assertThat(notifier.messages).isEmpty()
    }

    @Test
    fun `a message for a different conversation is still notified`() = runTest {
        visible.id = ConversationId("c_other")

        handler.handle(message)

        assertThat(notifier.messages).hasSize(1)
    }

    @Test
    fun `a match is stored and notified`() = runTest {
        handler.handle(PushDto(type = "new_match", conversationId = "c_2", peerId = "u_4", peerName = "Leo"))

        assertThat(chat.pushes.single()).isInstanceOf(PushPayload.NewMatch::class.java)
        assertThat(notifier.matches.single().peerName).isEqualTo("Leo")
    }

    @Test
    fun `a push of a type this version does not know is ignored and leaves a trace`() = runTest {
        handler.handle(PushDto(type = "promo", conversationId = "c_1"))

        assertThat(chat.pushes).isEmpty()
        assertThat(notifier.messages).isEmpty()
        assertThat(breadcrumbs.snapshot().single().message).isEqualTo("ignored push of type 'promo'")
    }

    @Test
    fun `a push that arrives after sign out is dropped`() = runTest {
        auth.restoredState = AuthState.SignedOut()

        handler.handle(message)

        assertThat(chat.pushes).isEmpty()
        assertThat(notifier.messages).isEmpty()
    }

    @Test
    @Covers("CHAT-04")
    fun `a push that started the process restores the session before storing`() = runTest {
        // Nothing has read the session from disk yet, which is the state of a process FCM just woke up.
        auth.emit(AuthState.Restoring)

        handler.handle(message)

        assertThat(auth.authState.value).isEqualTo(signedIn)
        assertThat(chat.pushes).hasSize(1)
    }
}
