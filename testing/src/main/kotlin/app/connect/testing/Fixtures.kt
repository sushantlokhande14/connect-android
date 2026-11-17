package app.connect.testing

import app.connect.domain.model.AuthTokens
import app.connect.domain.model.Candidate
import app.connect.domain.model.Conversation
import app.connect.domain.model.ConversationId
import app.connect.domain.model.DeckPage
import app.connect.domain.model.LikeQuota
import app.connect.domain.model.Message
import app.connect.domain.model.MessageStatus
import app.connect.domain.model.Profile
import app.connect.domain.model.Session
import app.connect.domain.model.UserId

object Fixtures {
    val meId = UserId("u_me")

    val me = Profile(
        id = meId,
        displayName = "Sam",
        age = 27,
        bio = "Climber, slow runner, decent cook.",
        interests = listOf("climbing", "cooking", "film"),
        photoUrl = null,
        city = "San Jose",
    )

    fun profile(n: Int, interests: List<String> = listOf("film", "hiking")) = Profile(
        id = UserId("u_$n"),
        displayName = "Person $n",
        age = 24 + n % 10,
        bio = "Bio $n",
        interests = interests,
        photoUrl = null,
        city = "Oakland",
    )

    fun deck(count: Int = 3, nextCursor: String? = "c1") = DeckPage(
        candidates = (1..count).map { Candidate(profile(it), distanceKm = it * 2) },
        nextCursor = nextCursor,
    )

    val quota = LikeQuota(remaining = 25, resetsAtMillis = 86_400_000)

    val session = Session(
        userId = meId,
        tokens = AuthTokens(accessToken = "access-1", refreshToken = "refresh-1", expiresAtMillis = 3_600_000),
    )

    val conversationId = ConversationId("c_1")

    fun conversation(n: Int = 1, unread: Int = 0) = Conversation(
        id = ConversationId("c_$n"),
        peerId = UserId("u_$n"),
        peerName = "Person $n",
        peerPhotoUrl = null,
        lastMessagePreview = "hey",
        lastMessageAtMillis = 1_000L * n,
        unreadCount = unread,
    )

    fun message(
        clientId: String = "m_1",
        body: String = "hey",
        sender: UserId = meId,
        status: MessageStatus = MessageStatus.SENT,
        at: Long = 1_000,
    ) = Message(
        clientId = clientId,
        conversationId = conversationId,
        senderId = sender,
        body = body,
        sentAtMillis = at,
        status = status,
    )
}
