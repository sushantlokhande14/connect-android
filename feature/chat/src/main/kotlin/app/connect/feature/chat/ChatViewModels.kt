package app.connect.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.connect.domain.diagnostics.Analytics
import app.connect.domain.diagnostics.AnalyticsEvent
import app.connect.domain.model.AppError
import app.connect.domain.model.AuthState
import app.connect.domain.model.Conversation
import app.connect.domain.model.ConversationId
import app.connect.domain.model.Message
import app.connect.domain.model.Outcome
import app.connect.domain.repository.AuthRepository
import app.connect.domain.repository.ChatRepository
import app.connect.domain.usecase.SendMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConversationsUiState(
    val conversations: List<Conversation> = emptyList(),
    /** True until the first refresh has finished, so an empty list is not shown as "no conversations" too early. */
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    /** The last refresh failed. With saved conversations on screen this is a banner; without, it is the screen. */
    val error: AppError? = null,
)

/**
 * The list is whatever Room holds. Refreshing only writes into Room, so the
 * screen has the same code path online, offline, and when a push adds a row.
 */
@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val chat: ChatRepository,
    private val analytics: Analytics,
) : ViewModel() {
    private val mutable = MutableStateFlow(ConversationsUiState())
    val state: StateFlow<ConversationsUiState> = mutable.asStateFlow()

    init {
        analytics.track(AnalyticsEvent.ScreenViewed("conversations"))
        viewModelScope.launch {
            chat.observeConversations().collect { list -> mutable.update { it.copy(conversations = list) } }
        }
        refresh(userInitiated = false)
    }

    fun retry() {
        analytics.track(AnalyticsEvent.RetryTapped("conversations"))
        refresh(userInitiated = true)
    }

    fun refresh(userInitiated: Boolean = true) {
        if (mutable.value.refreshing) return
        mutable.update { it.copy(refreshing = userInitiated, error = null) }
        viewModelScope.launch {
            val result = chat.refreshConversations()
            if (result is Outcome.Failure) analytics.track(AnalyticsEvent.ErrorShown("conversations", result.error.kind()))
            mutable.update {
                it.copy(loading = false, refreshing = false, error = (result as? Outcome.Failure)?.error)
            }
        }
    }
}

data class MessageItem(val message: Message, val mine: Boolean)

data class ChatUiState(
    val peerName: String = "",
    val messages: List<MessageItem> = emptyList(),
    val draft: String = "",
    val draftError: String? = null,
    val refreshError: AppError? = null,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chat: ChatRepository,
    private val sendMessage: SendMessage,
    auth: AuthRepository,
    private val analytics: Analytics,
) : ViewModel() {
    val conversationId =
        ConversationId(checkNotNull(savedStateHandle.get<String>(ARG_CONVERSATION_ID)) { "missing $ARG_CONVERSATION_ID" })

    private val me = (auth.authState.value as? AuthState.SignedIn)?.userId

    private val mutable = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = mutable.asStateFlow()

    init {
        analytics.track(AnalyticsEvent.ScreenViewed("chat"))

        viewModelScope.launch {
            combine(chat.observeMessages(conversationId), chat.observeConversations()) { messages, conversations ->
                messages.map { MessageItem(it, mine = it.senderId == me) } to
                    conversations.firstOrNull { it.id == conversationId }?.peerName.orEmpty()
            }.collect { (items, peerName) ->
                mutable.update { it.copy(messages = items, peerName = peerName.ifEmpty { it.peerName }) }
            }
        }

        // The conversation is on screen, so whatever arrives is read. Re-run whenever another message from
        // the other person shows up, which is how a push received while chatting clears its own badge.
        viewModelScope.launch {
            chat.observeMessages(conversationId)
                .map { messages -> messages.count { it.senderId != me } }
                .distinctUntilChanged()
                .collect { chat.markRead(conversationId) }
        }

        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val result = chat.refreshMessages(conversationId)
            mutable.update { it.copy(refreshError = (result as? Outcome.Failure)?.error) }
        }
    }

    fun onDraftChange(value: String) = mutable.update { it.copy(draft = value, draftError = null) }

    fun send() {
        val draft = mutable.value.draft
        if (draft.isBlank()) return
        // Clear the field at once. From here the message lives in the list, where it shows as sending,
        // sent or failed; the composer is free for the next one.
        mutable.update { it.copy(draft = "", draftError = null) }
        viewModelScope.launch {
            val result = sendMessage(conversationId, draft)
            val error = (result as? Outcome.Failure)?.error
            if (error is AppError.Validation) {
                // Nothing was stored, so give the text back instead of losing it.
                mutable.update { it.copy(draft = draft, draftError = error.reason) }
            }
        }
    }

    fun retry(clientId: String) {
        analytics.track(AnalyticsEvent.RetryTapped("chat"))
        viewModelScope.launch { sendMessage.retry(clientId) }
    }

    companion object {
        const val ARG_CONVERSATION_ID = "conversationId"
    }
}
