package app.connect.testing

import app.connect.domain.model.AppError
import app.connect.domain.model.AuthState
import app.connect.domain.model.Conversation
import app.connect.domain.model.ConversationId
import app.connect.domain.model.DeckPage
import app.connect.domain.model.DiscoveryPreferences
import app.connect.domain.model.LikeQuota
import app.connect.domain.model.Message
import app.connect.domain.model.MessageStatus
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
import app.connect.domain.repository.AuthRepository
import app.connect.domain.repository.ChatRepository
import app.connect.domain.repository.DiscoveryRepository
import app.connect.domain.repository.Freshness
import app.connect.domain.repository.ProfileRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/*
 * Fakes, not mocks: each one is a small working implementation whose answers
 * and delays a test can set. Delays use kotlinx `delay`, so under runTest they
 * advance virtual time and a test can assert how long a load took.
 */

class FakeAuthRepository(initial: AuthState = AuthState.SignedOut()) : AuthRepository {
    private val state = MutableStateFlow(initial)
    override val authState: StateFlow<AuthState> = state

    var signInResult: Outcome<Session> = Outcome.Success(Fixtures.session)
    var signUpResult: Outcome<Session> = Outcome.Success(Fixtures.session)
    var restoredState: AuthState = AuthState.SignedOut()
    var delayMillis = 0L
    val signInCalls = mutableListOf<Pair<String, String>>()
    val signUpCalls = mutableListOf<SignUpForm>()
    var signOutCalls = 0

    fun emit(next: AuthState) {
        state.value = next
    }

    override suspend fun restoreSession() {
        delay(delayMillis)
        state.value = restoredState
    }

    override suspend fun signIn(email: String, password: String): Outcome<Session> {
        signInCalls += email to password
        delay(delayMillis)
        return signInResult.also { if (it is Outcome.Success) state.value = AuthState.SignedIn(it.value.userId) }
    }

    override suspend fun signUp(form: SignUpForm): Outcome<Session> {
        signUpCalls += form
        delay(delayMillis)
        return signUpResult.also { if (it is Outcome.Success) state.value = AuthState.SignedIn(it.value.userId) }
    }

    override suspend fun signOut(reason: SignOutReason) {
        signOutCalls++
        state.value = AuthState.SignedOut(reason)
    }
}

class FakeProfileRepository : ProfileRepository {
    var meResult: Outcome<Profile> = Outcome.Success(Fixtures.me)
    var meDelayMillis = 0L
    var getMeCalls = 0
    val profiles = mutableMapOf<UserId, Outcome<Profile>>()
    var profileDelayMillis = 0L
    val getProfileCalls = mutableListOf<UserId>()
    var updateResult: ((ProfileUpdate) -> Outcome<Profile>)? = null
    val updates = mutableListOf<ProfileUpdate>()

    private val meFlow = MutableStateFlow<Profile?>(null)

    override fun observeMe(): Flow<Profile?> = meFlow

    override suspend fun getMe(freshness: Freshness): Outcome<Profile> {
        getMeCalls++
        delay(meDelayMillis)
        return meResult.also { if (it is Outcome.Success) meFlow.value = it.value }
    }

    override suspend fun getProfile(id: UserId, freshness: Freshness): Outcome<Profile> {
        getProfileCalls += id
        delay(profileDelayMillis)
        return profiles[id] ?: Outcome.Failure(AppError.Rejected(404, "not_found"))
    }

    override suspend fun updateMe(update: ProfileUpdate): Outcome<Profile> {
        updates += update
        updateResult?.let { return it(update) }
        val current = (meResult as? Outcome.Success)?.value ?: return meResult
        val next = current.copy(
            displayName = update.displayName ?: current.displayName,
            bio = update.bio ?: current.bio,
            interests = update.interests ?: current.interests,
        )
        meResult = Outcome.Success(next)
        meFlow.value = next
        return Outcome.Success(next)
    }
}

class FakeDiscoveryRepository : DiscoveryRepository {
    var preferencesResult: Outcome<DiscoveryPreferences> = Outcome.Success(DiscoveryPreferences())
    var preferencesDelayMillis = 0L
    var deckResults: MutableMap<String?, Outcome<DeckPage>> = mutableMapOf(null to Outcome.Success(Fixtures.deck()))
    var deckDelayMillis = 0L
    var quotaResult: Outcome<LikeQuota> = Outcome.Success(Fixtures.quota)
    var quotaDelayMillis = 0L
    var swipeResult: Outcome<SwipeResult> = Outcome.Success(SwipeResult(matched = false, conversationId = null))

    var getPreferencesCalls = 0
    val getDeckCalls = mutableListOf<String?>()
    var getQuotaCalls = 0
    val swipes = mutableListOf<Pair<UserId, SwipeAction>>()
    val savedPreferences = mutableListOf<DiscoveryPreferences>()

    override suspend fun getPreferences(freshness: Freshness): Outcome<DiscoveryPreferences> {
        getPreferencesCalls++
        delay(preferencesDelayMillis)
        return preferencesResult
    }

    override suspend fun updatePreferences(preferences: DiscoveryPreferences): Outcome<DiscoveryPreferences> {
        savedPreferences += preferences
        preferencesResult = Outcome.Success(preferences)
        return preferencesResult
    }

    override suspend fun getDeck(cursor: String?, freshness: Freshness): Outcome<DeckPage> {
        getDeckCalls += cursor
        delay(deckDelayMillis)
        return deckResults[cursor] ?: Outcome.Success(DeckPage(emptyList(), null))
    }

    override suspend fun getQuota(): Outcome<LikeQuota> {
        getQuotaCalls++
        delay(quotaDelayMillis)
        return quotaResult
    }

    override suspend fun swipe(target: UserId, action: SwipeAction): Outcome<SwipeResult> {
        swipes += target to action
        return swipeResult
    }
}

class FakeChatRepository : ChatRepository {
    val conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val messages = MutableStateFlow<List<Message>>(emptyList())

    var unreadResult: Outcome<Int> = Outcome.Success(0)
    var unreadDelayMillis = 0L
    var fetchUnreadCalls = 0
    var refreshConversationsResult: Outcome<Unit> = Outcome.Success(Unit)
    var refreshMessagesResult: Outcome<Unit> = Outcome.Success(Unit)
    var refreshConversationsCalls = 0
    val refreshMessagesCalls = mutableListOf<ConversationId>()
    var sendError: AppError? = null
    val sent = mutableListOf<Pair<ConversationId, String>>()
    val retried = mutableListOf<String>()
    val markedRead = mutableListOf<ConversationId>()
    val pushes = mutableListOf<PushPayload>()
    var me: UserId = Fixtures.meId
    private var nextId = 1

    override fun observeConversations(): Flow<List<Conversation>> = conversations

    override fun observeMessages(conversationId: ConversationId): Flow<List<Message>> =
        messages.map { all -> all.filter { it.conversationId == conversationId } }

    override fun observeUnreadCount(): Flow<Int> = conversations.map { list -> list.sumOf { it.unreadCount } }

    override suspend fun refreshConversations(): Outcome<Unit> {
        refreshConversationsCalls++
        return refreshConversationsResult
    }

    override suspend fun refreshMessages(conversationId: ConversationId): Outcome<Unit> {
        refreshMessagesCalls += conversationId
        return refreshMessagesResult
    }

    override suspend fun fetchUnreadCount(): Outcome<Int> {
        fetchUnreadCalls++
        delay(unreadDelayMillis)
        return unreadResult
    }

    override suspend fun send(conversationId: ConversationId, body: String): Outcome<Message> {
        sent += conversationId to body
        val error = sendError
        val message = Message(
            clientId = "local_${nextId++}",
            conversationId = conversationId,
            senderId = me,
            body = body,
            sentAtMillis = nextId * 1_000L,
            status = if (error == null) MessageStatus.SENT else MessageStatus.FAILED,
        )
        messages.update { it + message }
        return if (error == null) Outcome.Success(message) else Outcome.Failure(error)
    }

    override suspend fun retry(clientId: String): Outcome<Message> {
        retried += clientId
        val error = sendError
        val existing = messages.value.firstOrNull { it.clientId == clientId }
            ?: return Outcome.Failure(AppError.Unexpected("no such message"))
        val updated = existing.copy(status = if (error == null) MessageStatus.SENT else MessageStatus.FAILED)
        messages.update { list -> list.map { if (it.clientId == clientId) updated else it } }
        return if (error == null) Outcome.Success(updated) else Outcome.Failure(error)
    }

    override suspend fun markRead(conversationId: ConversationId) {
        markedRead += conversationId
        conversations.update { list -> list.map { if (it.id == conversationId) it.copy(unreadCount = 0) else it } }
    }

    override suspend fun onPush(payload: PushPayload) {
        pushes += payload
    }
}
