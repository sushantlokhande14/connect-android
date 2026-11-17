package app.connect.feature.profile

import androidx.lifecycle.SavedStateHandle
import app.connect.domain.model.AppError
import app.connect.domain.model.Outcome
import app.connect.domain.model.UserId
import app.connect.domain.usecase.UpdateMyProfile
import app.connect.testing.Covers
import app.connect.testing.FakeAuthRepository
import app.connect.testing.FakeProfileRepository
import app.connect.testing.Fixtures
import app.connect.testing.MainDispatcherRule
import app.connect.testing.RecordingAnalytics
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test

class ProfileDetailViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val profiles = FakeProfileRepository()
    private val analytics = RecordingAnalytics()
    private val person = Fixtures.profile(5)

    private fun viewModel(userId: String? = "u_5") = ProfileDetailViewModel(
        SavedStateHandle(if (userId == null) emptyMap() else mapOf(ProfileDetailViewModel.ARG_USER_ID to userId)),
        profiles,
        analytics,
    )

    @Test
    @Covers("PROF-01")
    fun `the profile named by the navigation argument is loaded`() = runTest(main.dispatcher) {
        profiles.profiles[UserId("u_5")] = Outcome.Success(person)

        val viewModel = viewModel()
        assertThat(viewModel.state.value).isEqualTo(ProfileDetailUiState.Loading)
        advanceUntilIdle()

        assertThat(viewModel.state.value).isEqualTo(ProfileDetailUiState.Content(person))
        assertThat(profiles.getProfileCalls).containsExactly(UserId("u_5"))
    }

    @Test
    @Covers("PROF-01")
    fun `a failed load shows the error and retry loads the profile`() = runTest(main.dispatcher) {
        profiles.profiles[UserId("u_5")] = Outcome.Failure(AppError.Offline)
        val viewModel = viewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value).isEqualTo(ProfileDetailUiState.Error(AppError.Offline))

        profiles.profiles[UserId("u_5")] = Outcome.Success(person)
        viewModel.retry()
        advanceUntilIdle()

        assertThat(viewModel.state.value).isEqualTo(ProfileDetailUiState.Content(person))
    }

    @Test
    fun `a missing navigation argument fails loudly`() {
        assertThrows(IllegalStateException::class.java) { viewModel(userId = null) }
    }
}

class MyProfileViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val profiles = FakeProfileRepository()
    private val auth = FakeAuthRepository()

    private fun viewModel() = MyProfileViewModel(profiles, UpdateMyProfile(profiles), auth, RecordingAnalytics())

    @Test
    @Covers("PROF-02")
    fun `the form starts from the stored profile and is not dirty`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.profile).isEqualTo(Fixtures.me)
        assertThat(state.bioDraft).isEqualTo(Fixtures.me.bio)
        assertThat(state.interestsDraft).isEqualTo("climbing, cooking, film")
        assertThat(state.dirty).isFalse()
    }

    @Test
    @Covers("PROF-02")
    fun `saving sends normalised interests and leaves the form clean`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onBioChange("Now also a baker.")
        viewModel.onInterestsChange("Baking,  Film , baking")
        assertThat(viewModel.state.value.dirty).isTrue()
        viewModel.save()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(profiles.updates.single().interests).containsExactly("baking", "film").inOrder()
        assertThat(state.profile?.bio).isEqualTo("Now also a baker.")
        assertThat(state.interestsDraft).isEqualTo("baking, film")
        assertThat(state.saved).isTrue()
        assertThat(state.dirty).isFalse()
    }

    @Test
    @Covers("PROF-02")
    fun `a rejected edit keeps the draft and shows why`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onBioChange("x".repeat(UpdateMyProfile.MAX_BIO_LENGTH + 1))
        viewModel.save()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat((state.saveError as AppError.Validation).field).isEqualTo("bio")
        assertThat(state.bioDraft).hasLength(UpdateMyProfile.MAX_BIO_LENGTH + 1)
        assertThat(state.saving).isFalse()
        assertThat(profiles.updates).isEmpty()
    }

    @Test
    fun `saving an unchanged form sends nothing`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.save()
        advanceUntilIdle()

        assertThat(profiles.updates).isEmpty()
    }

    @Test
    fun `a profile that changes underneath does not overwrite what is being typed`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onBioChange("half-typed")

        // Another screen refreshed the profile while this form was open.
        profiles.meResult = Outcome.Success(Fixtures.me.copy(bio = "from the server"))
        profiles.getMe()
        advanceUntilIdle()

        assertThat(viewModel.state.value.bioDraft).isEqualTo("half-typed")
        assertThat(viewModel.state.value.profile?.bio).isEqualTo("from the server")
    }

    @Test
    @Covers("PROF-01")
    fun `a failed first load shows the error and retry recovers`() = runTest(main.dispatcher) {
        profiles.meResult = Outcome.Failure(AppError.Offline)
        val viewModel = viewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value.loadError).isEqualTo(AppError.Offline)

        profiles.meResult = Outcome.Success(Fixtures.me)
        viewModel.retry()
        advanceUntilIdle()

        assertThat(viewModel.state.value.profile).isEqualTo(Fixtures.me)
        assertThat(viewModel.state.value.loadError).isNull()
    }

    @Test
    @Covers("AUTH-06")
    fun `sign out asks the auth repository and nothing else`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.signOut()
        advanceUntilIdle()

        assertThat(auth.signOutCalls).isEqualTo(1)
    }
}
