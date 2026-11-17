package app.connect.domain.model

@JvmInline
value class UserId(val value: String)

@JvmInline
value class ConversationId(val value: String)

data class Profile(
    val id: UserId,
    val displayName: String,
    val age: Int,
    val bio: String,
    val interests: List<String>,
    val photoUrl: String?,
    val city: String?,
)

data class ProfileUpdate(
    val displayName: String? = null,
    val bio: String? = null,
    val interests: List<String>? = null,
)

data class DiscoveryPreferences(
    val minAge: Int = 18,
    val maxAge: Int = 60,
    val maxDistanceKm: Int = 50,
) {
    init {
        require(minAge in 18..maxAge) { "minAge must be between 18 and maxAge" }
        require(maxDistanceKm > 0) { "maxDistanceKm must be positive" }
    }
}

/** A profile as the server returned it for the deck. */
data class Candidate(val profile: Profile, val distanceKm: Int)

data class DeckPage(val candidates: List<Candidate>, val nextCursor: String?)

/** A candidate joined with the viewer's own profile, which is what a card shows. */
data class DiscoveryCard(
    val profile: Profile,
    val distanceKm: Int,
    val sharedInterests: List<String>,
)

data class LikeQuota(val remaining: Int, val resetsAtMillis: Long)

enum class SwipeAction { LIKE, PASS }

data class SwipeResult(val matched: Boolean, val conversationId: ConversationId?)

data class Conversation(
    val id: ConversationId,
    val peerId: UserId,
    val peerName: String,
    val peerPhotoUrl: String?,
    val lastMessagePreview: String?,
    val lastMessageAtMillis: Long,
    val unreadCount: Int,
)

enum class MessageStatus { PENDING, SENT, FAILED }

data class Message(
    /** Generated on the device before the first send attempt; doubles as the idempotency key. */
    val clientId: String,
    val conversationId: ConversationId,
    val senderId: UserId,
    val body: String,
    val sentAtMillis: Long,
    val status: MessageStatus,
)

/** What a push notification carries. Kept free of Firebase types so it can be tested on the JVM. */
sealed interface PushPayload {
    data class NewMessage(
        val conversationId: ConversationId,
        val clientId: String,
        val senderId: UserId,
        val senderName: String,
        val body: String,
        val sentAtMillis: Long,
    ) : PushPayload

    data class NewMatch(
        val conversationId: ConversationId,
        val peerId: UserId,
        val peerName: String,
    ) : PushPayload
}
