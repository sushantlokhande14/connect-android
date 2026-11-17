package app.connect.feature.auth

import app.connect.domain.diagnostics.AnalyticsEvent
import app.connect.domain.model.AppError
import app.connect.domain.model.AuthState
import app.connect.domain.model.Outcome
import app.connect.domain.model.SignUpForm
import app.connect.domain.usecase.SignIn
import app.connect.domain.usecase.SignUp
import app.connect.testing.Covers
import app.connect.testing.FakeAuthRepository
import app.connect.testing.Fixtures
import app.connect.testing.MainDispatcherRule
import app.connect.testing.RecordingAnalytics
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class AuthViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val auth = FakeAuthRepository()
    private val analytics = RecordingAnalytics()
    private val viewModel by lazy { AuthViewModel(SignIn(auth, analytics), SignUp(auth, analytics), analytics) }

    private fun fillSignIn(email: String = "sam@example.com", password: String = "hunter2hunter2") {
        viewModel.onEmailChange(email)
        viewModel.onPasswordChange(password)
    }

    @Test
    @Covers("AUTH-01")
    fun `a successful sign in changes the auth state and forgets the password`() = runTest(main.dispatcher) {
        fillSignIn()

        viewModel.submit()
        advanceUntilIdle()

        assertThat(auth.authState.value).isEqualTo(AuthState.SignedIn(Fixtures.meId))
        assertThat(viewModel.state.value.password).isEmpty()
        assertThat(viewModel.state.value.submitting).isFalse()
        assertThat(viewModel.state.value.error).isNull()
    }

    @Test
    @Covers("AUTH-02")
    fun `rejected credentials become a form level error`() = runTest(main.dispatcher) {
        auth.signInResult = Outcome.Failure(AppError.Unauthorized)
        fillSignIn()

        viewModel.submit()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.error).isEqualTo(AppError.Unauthorized)
        assertThat(state.fieldErrors).isEmpty()
        assertThat(state.submitting).isFalse()
        // The form keeps what was typed so the user can correct it.
        assertThat(state.email).isEqualTo("sam@example.com")
        assertThat(analytics.of<AnalyticsEvent.ErrorShown>()).containsExactly(AnalyticsEvent.ErrorShown("auth", "Unauthorized"))
    }

    @Test
    @Covers("AUTH-03")
    fun `an invalid field is flagged on that field and nothing is sent`() = runTest(main.dispatcher) {
        fillSignIn(email = "not-an-email")

        viewModel.submit()
        advanceUntilIdle()

        assertThat(viewModel.state.value.fieldErrors.keys).containsExactly("email")
        assertThat(viewModel.state.value.error).isNull()
        assertThat(auth.signInCalls).isEmpty()
    }

    @Test
    @Covers("AUTH-03")
    fun `editing a field clears that field's error and leaves the others`() = runTest(main.dispatcher) {
        viewModel.toggleMode()
        viewModel.submit()
        advanceUntilIdle()
        assertThat(viewModel.state.value.fieldErrors.keys).containsExactly("displayName")

        viewModel.onEmailChange("sam@example.com")
        assertThat(viewModel.state.value.fieldErrors.keys).containsExactly("displayName")

        viewModel.onDisplayNameChange("Sam")
        assertThat(viewModel.state.value.fieldErrors).isEmpty()
    }

    @Test
    @Covers("AUTH-01")
    fun `a second tap while a request is in flight sends nothing`() = runTest(main.dispatcher) {
        auth.delayMillis = 500
        fillSignIn()

        viewModel.submit()
        runCurrent()
        assertThat(viewModel.state.value.submitting).isTrue()
        viewModel.submit()
        viewModel.submit()
        advanceUntilIdle()

        assertThat(auth.signInCalls).hasSize(1)
    }

    @Test
    @Covers("AUTH-01")
    fun `sign up submits the whole form`() = runTest(main.dispatcher) {
        viewModel.toggleMode()
        viewModel.onDisplayNameChange("Noor")
        viewModel.onAgeChange("2a9")
        viewModel.onEmailChange("noor@example.com")
        viewModel.onPasswordChange("hunter2hunter2")

        viewModel.submit()
        advanceUntilIdle()

        assertThat(viewModel.state.value.mode).isEqualTo(AuthMode.SIGN_UP)
        assertThat(auth.signUpCalls).containsExactly(SignUpForm("noor@example.com", "hunter2hunter2", "Noor", 29))
    }

    @Test
    fun `switching between sign in and sign up clears errors and keeps what was typed`() = runTest(main.dispatcher) {
        auth.signInResult = Outcome.Failure(AppError.Offline)
        fillSignIn()
        viewModel.submit()
        advanceUntilIdle()
        assertThat(viewModel.state.value.error).isEqualTo(AppError.Offline)

        viewModel.toggleMode()

        assertThat(viewModel.state.value.mode).isEqualTo(AuthMode.SIGN_UP)
        assertThat(viewModel.state.value.error).isNull()
        assertThat(viewModel.state.value.email).isEqualTo("sam@example.com")
    }
}
