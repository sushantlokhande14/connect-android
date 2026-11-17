package app.connect.domain.repository

import app.connect.domain.model.AuthState
import app.connect.domain.model.Conversation
import app.connect.domain.model.ConversationId
import app.connect.domain.model.DeckPage
import app.connect.domain.model.DiscoveryPreferences
import app.connect.domain.model.LikeQuota
import app.connect.domain.model.Message
import app.connect.domain.model.Outcome
import app.connect.domain.model.Profile
import app.connect.domain.model.ProfileUpdate
import app.connect.domain.model.PushPayload
import app.connect.domain.model.Session
import app.connect.domain.model.SignOutReason
import app.connect.domain.model.SignUpForm
import app.connect.domain.model.SwipeAction
import app.connect.domain.model.SwipeResult
import app.connect.domain.model.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * How fresh the caller needs a read to be. The repository decides where the
 * answer comes from; the caller only states its tolerance.
 */
enum class Freshness {
    /** Serve a cached value that is still inside its time-to-live; otherwise fetch. */
    CACHE_FIRST,

    /** Always fetch. If the fetch fails and a cached value exists, serve the cached value. */
    NETWORK_FIRST,

    /** Always fetch and never fall back. Used by the baseline loader in the benchmark. */
    NETWORK_ONLY,
}

interface AuthRepository {
    val authState: StateFlow<AuthState>

    /** Reads the persisted session. Idempotent; safe to call from more than one place. */
    suspend fun restoreSession()

    suspend fun signIn(email: String, password: String): Outcome<Session>

    suspend fun signUp(form: SignUpForm): Outcome<Session>

    /** Clears tokens and every piece of user data cached on the device. */
    suspend fun signOut(reason: SignOutReason = SignOutReason.USER_REQUESTED)
}

interface ProfileRepository {
    fun observeMe(): Flow<Profile?>

    suspend fun getMe(freshness: Freshness = Freshness.CACHE_FIRST): Outcome<Profile>

    suspend fun getProfile(id: UserId, freshness: Freshness = Freshness.CACHE_FIRST): Outcome<Profile>

    suspend fun updateMe(update: ProfileUpdate): Outcome<Profile>
}

interface DiscoveryRepository {
    suspend fun getPreferences(freshness: Freshness = Freshness.CACHE_FIRST): Outcome<DiscoveryPreferences>

    suspend fun updatePreferences(preferences: DiscoveryPreferences): Outcome<DiscoveryPreferences>

    /** The first page (cursor == null) is cached briefly so returning to the screen does not refetch it. */
    suspend fun getDeck(cursor: String?, freshness: Freshness = Freshness.CACHE_FIRST): Outcome<DeckPage>

    suspend fun getQuota(): Outcome<LikeQuota>

    suspend fun swipe(target: UserId, action: SwipeAction): Outcome<SwipeResult>
}

interface ChatRepository {
    /** Room is the source of truth for everything observed here; refreshes write into it. */
    fun observeConversations(): Flow<List<Conversation>>

    fun observeMessages(conversationId: ConversationId): Flow<List<Message>>

    fun observeUnreadCount(): Flow<Int>

    suspend fun refreshConversations(): Outcome<Unit>

    suspend fun refreshMessages(conversationId: ConversationId): Outcome<Unit>

    suspend fun fetchUnreadCount(): Outcome<Int>

    /** Stores the message as PENDING first, then sends it. A failed send stays in the list as FAILED. */
    suspend fun send(conversationId: ConversationId, body: String): Outcome<Message>

    /** Re-sends a FAILED message with its original clientId, so the server stores it at most once. */
    suspend fun retry(clientId: String): Outcome<Message>

    suspend fun markRead(conversationId: ConversationId)

    suspend fun onPush(payload: PushPayload)
}
