package app.connect.data.network

import app.connect.domain.model.AuthTokens
import app.connect.domain.model.Candidate
import app.connect.domain.model.Conversation
import app.connect.domain.model.ConversationId
import app.connect.domain.model.DeckPage
import app.connect.domain.model.DiscoveryPreferences
import app.connect.domain.model.LikeQuota
import app.connect.domain.model.Profile
import app.connect.domain.model.PushPayload
import app.connect.domain.model.Session
import app.connect.domain.model.SwipeResult
import app.connect.domain.model.UserId
import kotlinx.serialization.Serializable

/*
 * Wire types. They stay in the data layer: nothing above the repositories
 * sees a DTO, so a change to the API's JSON never reaches a ViewModel.
 */

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class SignUpRequest(val email: String, val password: String, val displayName: String, val age: Int)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class SessionDto(val userId: String, val accessToken: String, val refreshToken: String, val expiresAt: Long) {
    fun toDomain() = Session(UserId(userId), AuthTokens(accessToken, refreshToken, expiresAt))
}

@Serializable
data class ProfileDto(
    val id: String,
    val displayName: String,
    val age: Int,
    val bio: String = "",
    val interests: List<String> = emptyList(),
    val photoUrl: String? = null,
    val city: String? = null,
) {
    fun toDomain() = Profile(UserId(id), displayName, age, bio, interests, photoUrl, city)
}

@Serializable
data class ProfilePatch(
    val displayName: String? = null,
    val bio: String? = null,
    val interests: List<String>? = null,
)

@Serializable
data class PreferencesDto(val minAge: Int, val maxAge: Int, val maxDistanceKm: Int) {
    fun toDomain() = DiscoveryPreferences(minAge, maxAge, maxDistanceKm)

    companion object {
        fun from(preferences: DiscoveryPreferences) =
            PreferencesDto(preferences.minAge, preferences.maxAge, preferences.maxDistanceKm)
    }
}

@Serializable
data class QuotaDto(val remaining: Int, val resetsAt: Long) {
    fun toDomain() = LikeQuota(remaining, resetsAt)
}

@Serializable
data class CandidateDto(val profile: ProfileDto, val distanceKm: Int)

@Serializable
data class DeckDto(val candidates: List<CandidateDto>, val nextCursor: String? = null) {
    fun toDomain() = DeckPage(candidates.map { Candidate(it.profile.toDomain(), it.distanceKm) }, nextCursor)
}

@Serializable
data class SwipeRequest(val targetId: String, val action: String)

@Serializable
data class SwipeDto(val matched: Boolean, val conversationId: String? = null) {
    fun toDomain() = SwipeResult(matched, conversationId?.let(::ConversationId))
}

@Serializable
data class ConversationDto(
    val id: String,
    val peerId: String,
    val peerName: String,
    val peerPhotoUrl: String? = null,
    val lastMessagePreview: String? = null,
    val lastMessageAt: Long = 0,
    val unreadCount: Int = 0,
) {
    fun toDomain() = Conversation(
        id = ConversationId(id),
        peerId = UserId(peerId),
        peerName = peerName,
        peerPhotoUrl = peerPhotoUrl,
        lastMessagePreview = lastMessagePreview,
        lastMessageAtMillis = lastMessageAt,
        unreadCount = unreadCount,
    )
}

@Serializable
data class ConversationsDto(val conversations: List<ConversationDto>)

@Serializable
data class UnreadDto(val count: Int)

@Serializable
data class MessageDto(
    val id: String,
    val clientId: String,
    val conversationId: String,
    val senderId: String,
    val body: String,
    val sentAt: Long,
)

@Serializable
data class MessagesDto(val messages: List<MessageDto>)

@Serializable
data class SendMessageRequest(val clientId: String, val body: String)

@Serializable
data class DeviceRequest(val token: String)

@Serializable
data class ErrorDto(val code: String? = null, val message: String? = null)

/** Shape of a push, both as an FCM data payload and as the demo backend's push queue. */
@Serializable
data class PushDto(
    val type: String,
    val conversationId: String,
    val clientId: String? = null,
    val senderId: String? = null,
    val senderName: String? = null,
    val body: String? = null,
    val sentAt: Long? = null,
    val peerId: String? = null,
    val peerName: String? = null,
) {
    /** Null when the payload is of a type this version does not know, or is missing a field it needs. */
    fun toDomain(): PushPayload? = when (type) {
        "new_message" ->
            if (clientId == null || senderId == null || body == null) {
                null
            } else {
                PushPayload.NewMessage(
                    conversationId = ConversationId(conversationId),
                    clientId = clientId,
                    senderId = UserId(senderId),
                    senderName = senderName.orEmpty(),
                    body = body,
                    sentAtMillis = sentAt ?: 0L,
                )
            }
        "new_match" ->
            if (peerId == null) {
                null
            } else {
                PushPayload.NewMatch(ConversationId(conversationId), UserId(peerId), peerName.orEmpty())
            }
        else -> null
    }

    companion object {
        /** FCM delivers data payloads as a flat string map. */
        fun fromData(data: Map<String, String>): PushDto? {
            val type = data["type"] ?: return null
            val conversationId = data["conversationId"] ?: return null
            return PushDto(
                type = type,
                conversationId = conversationId,
                clientId = data["clientId"],
                senderId = data["senderId"],
                senderName = data["senderName"],
                body = data["body"],
                sentAt = data["sentAt"]?.toLongOrNull(),
                peerId = data["peerId"],
                peerName = data["peerName"],
            )
        }
    }
}

@Serializable
data class PushesDto(val pushes: List<PushDto>)
