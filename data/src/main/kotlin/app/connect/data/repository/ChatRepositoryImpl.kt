package app.connect.data.repository

import app.connect.data.local.ChatDao
import app.connect.data.local.ConversationEntity
import app.connect.data.local.MessageEntity
import app.connect.data.network.ApiCaller
import app.connect.data.network.ConnectApi
import app.connect.data.network.MessageDto
import app.connect.data.network.SendMessageRequest
import app.connect.data.session.SessionStore
import app.connect.domain.model.AppError
import app.connect.domain.model.Conversation
import app.connect.domain.model.ConversationId
import app.connect.domain.model.Message
import app.connect.domain.model.MessageStatus
import app.connect.domain.model.Outcome
import app.connect.domain.model.PushPayload
import app.connect.domain.model.map
import app.connect.domain.repository.ChatRepository
import app.connect.domain.util.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

fun interface IdGenerator {
    fun next(): String
}

/**
 * Room is the source of truth for chat. The UI only ever observes the
 * database; network results and pushes are written into it. That is what
 * makes conversations readable offline and keeps a message visible while it
 * is being sent, after it fails, and after the app restarts.
 */
@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val api: ConnectApi,
    private val dao: ChatDao,
    private val sessions: SessionStore,
    private val caller: ApiCaller,
    private val clock: Clock,
    private val ids: IdGenerator,
) : ChatRepository {

    override fun observeConversations(): Flow<List<Conversation>> =
        dao.observeConversations().map { list -> list.map(ConversationEntity::toDomain) }

    override fun observeMessages(conversationId: ConversationId): Flow<List<Message>> =
        dao.observeMessages(conversationId.value).map { list -> list.map(MessageEntity::toDomain) }

    override fun observeUnreadCount(): Flow<Int> = dao.observeUnreadCount()

    override suspend fun refreshConversations(): Outcome<Unit> =
        caller.call("GET /v1/conversations") { api.conversations() }.map { dto ->
            dao.replaceConversations(dto.conversations.map { ConversationEntity.from(it.toDomain()) })
        }

    override suspend fun refreshMessages(conversationId: ConversationId): Outcome<Unit> =
        caller.call("GET /v1/conversations/{id}/messages") { api.messages(conversationId.value) }.map { dto ->
            // Upsert, not replace: messages still PENDING or FAILED on this device are not on the server yet
            // and must stay. A message the server does have overwrites its local row and becomes SENT, which
            // also repairs a send whose response was lost.
            dao.upsertMessages(dto.messages.map { it.toEntity() })
        }

    override suspend fun fetchUnreadCount(): Outcome<Int> =
        when (val remote = caller.call("GET /v1/conversations/unread-count") { api.unreadCount() }) {
            is Outcome.Success -> Outcome.Success(remote.value.count)
            is Outcome.Failure ->
                // The local count is a good enough answer when the server cannot be reached.
                if (remote.error.isTransient) Outcome.Success(dao.unreadCount()) else remote
        }

    override suspend fun send(conversationId: ConversationId, body: String): Outcome<Message> {
        val me = sessions.current()?.userId ?: return Outcome.Failure(AppError.Unauthorized)
        val pending = Message(
            clientId = ids.next(),
            conversationId = conversationId,
            senderId = me,
            body = body,
            sentAtMillis = clock.nowMillis(),
            status = MessageStatus.PENDING,
        )
        dao.upsertMessage(MessageEntity.from(pending))
        dao.touchConversation(conversationId.value, body, pending.sentAtMillis, unreadDelta = 0)
        return deliver(pending)
    }

    override suspend fun retry(clientId: String): Outcome<Message> {
        val stored = dao.message(clientId)?.toDomain()
            ?: return Outcome.Failure(AppError.Unexpected("message $clientId is not on this device"))
        if (stored.status == MessageStatus.SENT) return Outcome.Success(stored)
        val pending = stored.copy(status = MessageStatus.PENDING)
        dao.upsertMessage(MessageEntity.from(pending))
        return deliver(pending)
    }

    /**
     * Sends with the message's client id as the idempotency key. If the first attempt reached the server but
     * its response was lost, the retry gets the stored message back instead of creating a second one.
     */
    private suspend fun deliver(pending: Message): Outcome<Message> {
        val result = caller.call("POST /v1/conversations/{id}/messages") {
            api.sendMessage(pending.conversationId.value, SendMessageRequest(pending.clientId, pending.body))
        }
        return when (result) {
            is Outcome.Success -> {
                val sent = result.value.toEntity()
                dao.upsertMessage(sent)
                Outcome.Success(sent.toDomain())
            }
            is Outcome.Failure -> {
                dao.upsertMessage(MessageEntity.from(pending.copy(status = MessageStatus.FAILED)))
                result
            }
        }
    }

    override suspend fun markRead(conversationId: ConversationId) {
        // Local first so the badge clears immediately; the server call is best effort.
        dao.clearUnread(conversationId.value)
        caller.callEmpty("POST /v1/conversations/{id}/read") { api.markRead(conversationId.value) }
    }

    override suspend fun onPush(payload: PushPayload) {
        when (payload) {
            is PushPayload.NewMessage -> {
                // A push can arrive twice, or after a refresh already stored the message.
                val alreadyStored = dao.message(payload.clientId) != null
                dao.upsertMessage(
                    MessageEntity(
                        clientId = payload.clientId,
                        conversationId = payload.conversationId.value,
                        senderId = payload.senderId.value,
                        body = payload.body,
                        sentAtMillis = payload.sentAtMillis,
                        status = MessageStatus.SENT.name,
                    ),
                )
                if (!alreadyStored) {
                    val updated = dao.touchConversation(
                        id = payload.conversationId.value,
                        preview = payload.body,
                        atMillis = payload.sentAtMillis,
                        unreadDelta = 1,
                    )
                    if (updated == 0) {
                        // First message of a conversation this device has not listed yet.
                        dao.upsertConversations(
                            listOf(
                                ConversationEntity(
                                    id = payload.conversationId.value,
                                    peerId = payload.senderId.value,
                                    peerName = payload.senderName,
                                    peerPhotoUrl = null,
                                    lastMessagePreview = payload.body,
                                    lastMessageAtMillis = payload.sentAtMillis,
                                    unreadCount = 1,
                                ),
                            ),
                        )
                    }
                }
            }
            is PushPayload.NewMatch -> {
                if (dao.conversation(payload.conversationId.value) == null) {
                    dao.upsertConversations(
                        listOf(
                            ConversationEntity(
                                id = payload.conversationId.value,
                                peerId = payload.peerId.value,
                                peerName = payload.peerName,
                                peerPhotoUrl = null,
                                lastMessagePreview = null,
                                lastMessageAtMillis = clock.nowMillis(),
                                unreadCount = 0,
                            ),
                        ),
                    )
                }
            }
        }
    }

    private fun MessageDto.toEntity() = MessageEntity(
        clientId = clientId,
        conversationId = conversationId,
        senderId = senderId,
        body = body,
        sentAtMillis = sentAt,
        status = MessageStatus.SENT.name,
    )
}
