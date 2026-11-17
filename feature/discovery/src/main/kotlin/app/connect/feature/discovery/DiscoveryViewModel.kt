package app.connect.feature.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.connect.domain.diagnostics.Analytics
import app.connect.domain.diagnostics.AnalyticsEvent
import app.connect.domain.diagnostics.FeatureFlags
import app.connect.domain.diagnostics.Flag
import app.connect.domain.model.AppError
import app.connect.domain.model.ConversationId
import app.connect.domain.model.DiscoveryCard
import app.connect.domain.model.DiscoveryPreferences
import app.connect.domain.model.LikeQuota
import app.connect.domain.model.Outcome
import app.connect.domain.model.Profile
import app.connect.domain.model.SwipeAction
import app.connect.domain.repository.DiscoveryRepository
import app.connect.domain.repository.Freshness
import app.connect.domain.usecase.LoadDiscoveryScreen
import app.connect.domain.usecase.LoadDiscoveryScreen.Companion.toCard
import app.connect.domain.usecase.SwipeOnCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MatchEvent(val peerName: String, val conversationId: ConversationId)

sealed interface DiscoveryUiState {
    data object Loading : DiscoveryUiState

    data class Error(val error: AppError) : DiscoveryUiState

    data class Content(
        val cards: List<DiscoveryCard>,
        val preferences: DiscoveryPreferences,
        /** Null when the quota could not be loaded or the flag hides it. The counter is simply not drawn. */
        val quota: LikeQuota?,
        val refreshing: Boolean = false,
        val loadingMore: Boolean = false,
        /** A failure that does not replace the screen: a swipe that did not go through, a refresh that failed. */
        val notice: AppError? = null,
        val match: MatchEvent? = null,
    ) : DiscoveryUiState
}

@HiltViewModel
class DiscoveryViewModel @Inject constructor(
    private val loadScreen: LoadDiscoveryScreen,
    private val swipeOnCard: SwipeOnCard,
    private val discovery: DiscoveryRepository,
    private val flags: FeatureFlags,
    private val analytics: Analytics,
) : ViewModel() {
    private val mutable = MutableStateFlow<DiscoveryUiState>(DiscoveryUiState.Loading)
    val state: StateFlow<DiscoveryUiState> = mutable.asStateFlow()

    private var loadJob: Job? = null
    private var viewer: Profile? = null
    private var nextCursor: String? = null

    init {
        analytics.track(AnalyticsEvent.ScreenViewed("discovery"))
        load(Freshness.CACHE_FIRST, replaceContent = true)
    }

    fun retry() {
        analytics.track(AnalyticsEvent.RetryTapped("discovery"))
        load(Freshness.NETWORK_FIRST, replaceContent = true)
    }

    fun refresh() = load(Freshness.NETWORK_FIRST, replaceContent = false)

    private fun load(freshness: Freshness, replaceContent: Boolean) {
        // Latest request wins. Cancelling the job cancels its in-flight requests too.
        loadJob?.cancel()
        val shown = mutable.value
        mutable.value = if (!replaceContent && shown is DiscoveryUiState.Content) {
            shown.copy(refreshing = true, notice = null)
        } else {
            DiscoveryUiState.Loading
        }

        loadJob = viewModelScope.launch {
            when (val result = loadScreen(freshness)) {
                is Outcome.Success -> {
                    val data = result.value
                    viewer = data.me
                    nextCursor = data.nextCursor
                    mutable.value = DiscoveryUiState.Content(
                        cards = data.cards,
                        preferences = data.preferences,
                        quota = data.quota.takeIf { flags.isEnabled(Flag.SHOW_LIKE_QUOTA) },
                    )
                }
                is Outcome.Failure -> {
                    analytics.track(AnalyticsEvent.ErrorShown("discovery", result.error.kind()))
                    val current = mutable.value
                    // A failed refresh keeps the cards that are already on screen.
                    mutable.value = if (current is DiscoveryUiState.Content) {
                        current.copy(refreshing = false, notice = result.error)
                    } else {
                        DiscoveryUiState.Error(result.error)
                    }
                }
            }
        }
    }

    /**
     * Optimistic: the card leaves the deck at once and the request follows. If the request fails the card
     * is put back on top and the like is refunded, so the screen never shows something the server refused.
     */
    fun swipe(card: DiscoveryCard, action: SwipeAction) {
        val before = mutable.value as? DiscoveryUiState.Content ?: return
        // A second tap on a card that is already on its way out is ignored.
        if (before.cards.none { it.profile.id == card.profile.id }) return
        val spendsLike = action == SwipeAction.LIKE

        mutable.value = before.copy(
            cards = before.cards.filterNot { it.profile.id == card.profile.id },
            quota = before.quota?.let { if (spendsLike) it.copy(remaining = (it.remaining - 1).coerceAtLeast(0)) else it },
            notice = null,
        )
        loadMoreIfRunningLow()

        viewModelScope.launch {
            when (val result = swipeOnCard(card.profile.id, action)) {
                is Outcome.Success -> {
                    val conversation = result.value.conversationId
                    if (result.value.matched && conversation != null) {
                        updateContent { it.copy(match = MatchEvent(card.profile.displayName, conversation)) }
                    }
                }
                is Outcome.Failure -> updateContent { current ->
                    current.copy(
                        cards = if (current.cards.any { it.profile.id == card.profile.id }) current.cards else listOf(card) + current.cards,
                        quota = current.quota?.let { if (spendsLike) it.copy(remaining = it.remaining + 1) else it },
                        notice = result.error,
                    )
                }
            }
        }
    }

    private fun loadMoreIfRunningLow() {
        val content = mutable.value as? DiscoveryUiState.Content ?: return
        val cursor = nextCursor ?: return
        val me = viewer ?: return
        if (content.loadingMore || content.cards.size > LOAD_MORE_THRESHOLD) return

        updateContent { it.copy(loadingMore = true) }
        viewModelScope.launch {
            when (val page = discovery.getDeck(cursor)) {
                is Outcome.Success -> {
                    nextCursor = page.value.nextCursor
                    updateContent { current ->
                        val shown = current.cards.map { it.profile.id }.toSet()
                        val added = page.value.candidates.map { it.toCard(me) }.filterNot { it.profile.id in shown }
                        current.copy(cards = current.cards + added, loadingMore = false)
                    }
                }
                // Not worth interrupting the user: the next swipe tries again.
                is Outcome.Failure -> updateContent { it.copy(loadingMore = false) }
            }
        }
    }

    fun savePreferences(preferences: DiscoveryPreferences) {
        viewModelScope.launch {
            when (val result = discovery.updatePreferences(preferences)) {
                is Outcome.Success -> load(Freshness.NETWORK_FIRST, replaceContent = true)
                is Outcome.Failure -> updateContent { it.copy(notice = result.error) }
            }
        }
    }

    fun dismissMatch() = updateContent { it.copy(match = null) }

    fun dismissNotice() = updateContent { it.copy(notice = null) }

    private inline fun updateContent(change: (DiscoveryUiState.Content) -> DiscoveryUiState.Content) {
        mutable.update { if (it is DiscoveryUiState.Content) change(it) else it }
    }

    private companion object {
        const val LOAD_MORE_THRESHOLD = 2
    }
}
