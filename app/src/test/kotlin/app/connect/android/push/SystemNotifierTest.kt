package app.connect.android.push

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.connect.android.MainActivity
import app.connect.domain.model.ConversationId
import app.connect.domain.model.PushPayload
import app.connect.domain.model.UserId
import app.connect.testing.Covers
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The notification itself: what it says, where a tap goes, and that nothing is posted without permission. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SystemNotifierTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifier = SystemNotifier(context)

    private val message = PushPayload.NewMessage(ConversationId("c_1"), "srv_1", UserId("u_1"), "Maya", "hi!", sentAtMillis = 10)

    @Before
    fun setUp() {
        notifier.createChannels()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    @Covers("PUSH-01")
    fun `a message is posted with its sender and text on the messages channel`() {
        notifier.showMessage(message)

        val posted = shadowOf(manager).allNotifications.single()
        assertThat(shadowOf(posted).contentTitle.toString()).isEqualTo("Maya")
        assertThat(shadowOf(posted).contentText.toString()).isEqualTo("hi!")
        assertThat(posted.channelId).isEqualTo("messages")
        assertThat(manager.getNotificationChannel("messages")).isNotNull()
    }

    @Test
    @Covers("PUSH-01")
    fun `a tap goes to this app's activity with the conversation's link`() {
        notifier.showMessage(message)

        val pending = shadowOf(manager).allNotifications.single().contentIntent
        val intent = shadowOf(pending).savedIntent
        assertThat(intent.data).isEqualTo(Uri.parse("connect://chat/c_1"))
        // Explicit component: no other app can receive this intent.
        assertThat(intent.component?.className).isEqualTo(MainActivity::class.java.name)
        assertThat(intent.getStringExtra(Notifier.EXTRA_PUSH_KIND)).isEqualTo("new_message")
        // Immutable: whoever fires it cannot change where it goes.
        assertThat(shadowOf(pending).flags and PendingIntent.FLAG_IMMUTABLE).isNotEqualTo(0)
    }

    @Test
    @Covers("PUSH-01")
    fun `without the notification permission nothing is posted`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        notifier.showMessage(message)
        notifier.showMatch(PushPayload.NewMatch(ConversationId("c_2"), UserId("u_4"), "Leo"))

        assertThat(shadowOf(manager).allNotifications).isEmpty()
    }

    @Test
    @Covers("PUSH-01")
    fun `a second message in the same conversation replaces the first notification`() {
        notifier.showMessage(message)
        notifier.showMessage(message.copy(clientId = "srv_2", body = "still there?"))

        val posted = shadowOf(manager).allNotifications.single()
        assertThat(shadowOf(posted).contentText.toString()).isEqualTo("still there?")
    }

    @Test
    fun `messages from different conversations are separate notifications`() {
        notifier.showMessage(message)
        notifier.showMessage(message.copy(conversationId = ConversationId("c_7"), senderName = "Ines"))

        assertThat(shadowOf(manager).allNotifications).hasSize(2)
    }

    @Test
    fun `a match invites the user to say hi and opens the new conversation`() {
        notifier.showMatch(PushPayload.NewMatch(ConversationId("c_2"), UserId("u_4"), "Leo"))

        val posted = shadowOf(manager).allNotifications.single()
        assertThat(shadowOf(posted).contentTitle.toString()).isEqualTo("It's a match")
        assertThat(shadowOf(posted).contentText.toString()).isEqualTo("You and Leo like each other. Say hi.")
        assertThat(shadowOf(posted.contentIntent).savedIntent.data).isEqualTo(Uri.parse("connect://chat/c_2"))
    }
}
