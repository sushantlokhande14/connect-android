package app.connect.feature.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.connect.domain.diagnostics.Analytics
import app.connect.domain.diagnostics.AnalyticsEvent
import app.connect.domain.model.AppError
import app.connect.domain.model.Outcome
import app.connect.domain.model.Profile
import app.connect.domain.model.ProfileUpdate
import app.connect.domain.model.UserId
import app.connect.domain.repository.AuthRepository
import app.connect.domain.repository.Freshness
import app.connect.domain.repository.ProfileRepository
import app.connect.domain.usecase.UpdateMyProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ProfileDetailUiState {
    data object Loading : ProfileDetailUiState

    data class Error(val error: AppError) : ProfileDetailUiState

    data class Content(val profile: Profile) : ProfileDetailUiState
}

@HiltViewModel
class ProfileDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val profiles: ProfileRepository,
    private val analytics: Analytics,
) : ViewModel() {
    /** Read from the navigation arguments, which also survive process death. */
    private val userId = UserId(checkNotNull(savedStateHandle.get<String>(ARG_USER_ID)) { "missing $ARG_USER_ID" })

    private val mutable = MutableStateFlow<ProfileDetailUiState>(ProfileDetailUiState.Loading)
    val state: StateFlow<ProfileDetailUiState> = mutable.asStateFlow()

    init {
        analytics.track(AnalyticsEvent.ScreenViewed("profile_detail"))
        load()
    }

    fun retry() {
        analytics.track(AnalyticsEvent.RetryTapped("profile_detail"))
        load()
    }

    private fun load() {
        mutable.value = ProfileDetailUiState.Loading
        viewModelScope.launch {
            mutable.value = when (val result = profiles.getProfile(userId, Freshness.CACHE_FIRST)) {
                is Outcome.Success -> ProfileDetailUiState.Content(result.value)
                is Outcome.Failure -> {
                    analytics.track(AnalyticsEvent.ErrorShown("profile_detail", result.error.kind()))
                    ProfileDetailUiState.Error(result.error)
                }
            }
        }
    }

    companion object {
        const val ARG_USER_ID = "userId"
    }
}

data class MyProfileUiState(
    val profile: Profile? = null,
    val loadError: AppError? = null,
    val bioDraft: String = "",
    val interestsDraft: String = "",
    val saving: Boolean = false,
    val saveError: AppError? = null,
    val saved: Boolean = false,
) {
    val dirty: Boolean
        get() = profile != null &&
            (bioDraft != profile.bio || interestsDraft.toInterests() != profile.interests)
}

internal fun String.toInterests(): List<String> =
    split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

@HiltViewModel
class MyProfileViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val updateMyProfile: UpdateMyProfile,
    private val auth: AuthRepository,
    private val analytics: Analytics,
) : ViewModel() {
    private val mutable = MutableStateFlow(MyProfileUiState())
    val state: StateFlow<MyProfileUiState> = mutable.asStateFlow()

    init {
        analytics.track(AnalyticsEvent.ScreenViewed("my_profile"))
        // The stored profile is the source of truth: an edit saved here, or a refresh triggered by another
        // screen, arrives through the same flow.
        viewModelScope.launch {
            profiles.observeMe().filterNotNull().collect { profile ->
                mutable.update { current ->
                    val untouched = current.profile == null || !current.dirty
                    current.copy(
                        profile = profile,
                        loadError = null,
                        // Never overwrite what the user is in the middle of typing.
                        bioDraft = if (untouched) profile.bio else current.bioDraft,
                        interestsDraft = if (untouched) profile.interests.joinToString(", ") else current.interestsDraft,
                    )
                }
            }
        }
        load()
    }

    fun retry() = load()

    private fun load() {
        viewModelScope.launch {
            val result = profiles.getMe(Freshness.CACHE_FIRST)
            if (result is Outcome.Failure && mutable.value.profile == null) {
                mutable.update { it.copy(loadError = result.error) }
            }
        }
    }

    fun onBioChange(value: String) = mutable.update { it.copy(bioDraft = value, saveError = null, saved = false) }

    fun onInterestsChange(value: String) = mutable.update { it.copy(interestsDraft = value, saveError = null, saved = false) }

    fun save() {
        val current = mutable.value
        if (current.saving || !current.dirty) return
        mutable.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            val result = updateMyProfile(
                ProfileUpdate(bio = current.bioDraft, interests = current.interestsDraft.toInterests()),
            )
            mutable.update {
                when (result) {
                    is Outcome.Success -> it.copy(
                        saving = false,
                        saved = true,
                        profile = result.value,
                        bioDraft = result.value.bio,
                        interestsDraft = result.value.interests.joinToString(", "),
                    )
                    is Outcome.Failure -> it.copy(saving = false, saveError = result.error)
                }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch { auth.signOut() }
    }
}
