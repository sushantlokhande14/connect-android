package app.connect.android.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.connect.android.MainActivity
import app.connect.android.R
import app.connect.android.Routes
import app.connect.data.network.PushDto
import app.connect.domain.diagnostics.Breadcrumbs
import app.connect.domain.model.AuthState
import app.connect.domain.model.ConversationId
import app.connect.domain.model.PushPayload
import app.connect.domain.repository.AuthRepository
import app.connect.domain.repository.ChatRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Whatever delivers pushes in this build: Firebase in prod, a poller against the in-app backend in demo. */
interface PushSource {
    fun start(scope: CoroutineScope)
}

/** The conversation currently on screen, if any. A message for it is shown in place, not as a notification. */
@Singleton
class VisibleConversation @Inject constructor() {
    @Volatile
    var id: ConversationId? = null
}

interface Notifier {
    fun createChannels()

    fun showMessage(payload: PushPayload.NewMessage)

    fun showMatch(payload: PushPayload.NewMatch)

    companion object {
        const val EXTRA_PUSH_KIND = "app.connect.push_kind"
    }
}

/**
 * The one path every push takes, whichever transport delivered it. Storing
 * comes first and does not depend on the notification: even when notifications
 * are disabled, the message is in the conversation the next time it is opened.
 */
@Singleton
class PushHandler @Inject constructor(
    private val auth: AuthRepository,
    private val chat: ChatRepository,
    private val notifier: Notifier,
    private val visible: VisibleConversation,
    private val breadcrumbs: Breadcrumbs,
) {
    suspend fun handle(dto: PushDto) {
        val payload = dto.toDomain()
        if (payload == null) {
            // A newer server may send types this version does not know. Ignoring them is the safe default.
            breadcrumbs.leave("push", "ignored push of type '${dto.type}'")
            return
        }

        // A push can be what started the process, so the session may not have been read yet.
        auth.restoreSession()
        if (auth.authState.first { it != AuthState.Restoring } !is AuthState.SignedIn) {
            breadcrumbs.leave("push", "dropped push: signed out")
            return
        }

        chat.onPush(payload)
        when (payload) {
            is PushPayload.NewMessage -> if (visible.id != payload.conversationId) notifier.showMessage(payload)
            is PushPayload.NewMatch -> notifier.showMatch(payload)
        }
    }
}

@Singleton
class SystemNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : Notifier {
    override fun createChannels() {
        val channel = NotificationChannel(
            CHANNEL_MESSAGES,
            context.getString(R.string.channel_messages),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.channel_messages_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun showMessage(payload: PushPayload.NewMessage) = show(
        conversation = payload.conversationId,
        title = payload.senderName.ifBlank { "New message" },
        text = payload.body,
        kind = "new_message",
    )

    override fun showMatch(payload: PushPayload.NewMatch) = show(
        conversation = payload.conversationId,
        title = "It's a match",
        text = "You and ${payload.peerName} like each other. Say hi.",
        kind = "new_match",
    )

    private fun show(conversation: ConversationId, title: String, text: String, kind: String) {
        if (!canNotify()) return
        // Explicit intent: only this app's activity can receive it. The URI is resolved by the navigation graph.
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(Routes.chatDeepLink(conversation)), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(Notifier.EXTRA_PUSH_KIND, kind)
        val pending = PendingIntent.getActivity(
            context,
            conversation.value.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .build()
        // One notification per conversation: a second message replaces the first instead of stacking.
        NotificationManagerCompat.from(context).notify(conversation.value.hashCode(), notification)
    }

    private fun canNotify(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val CHANNEL_MESSAGES = "messages"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NotifierModule {
    @Binds
    abstract fun notifier(impl: SystemNotifier): Notifier
}
