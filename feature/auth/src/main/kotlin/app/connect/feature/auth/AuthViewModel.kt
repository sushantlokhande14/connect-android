package app.connect.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.connect.domain.diagnostics.Analytics
import app.connect.domain.diagnostics.AnalyticsEvent
import app.connect.domain.model.AppError
import app.connect.domain.model.Outcome
import app.connect.domain.model.SignUpForm
import app.connect.domain.usecase.SignIn
import app.connect.domain.usecase.SignUp
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AuthMode { SIGN_IN, SIGN_UP }

data class AuthUiState(
    val mode: AuthMode = AuthMode.SIGN_IN,
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
    val age: String = "",
    /** Problems tied to one field, keyed by the field name the validation reported. */
    val fieldErrors: Map<String, String> = emptyMap(),
    /** A problem with the attempt as a whole: wrong password, offline, server error. */
    val error: AppError? = null,
    val submitting: Boolean = false,
)

/**
 * The form's state and the one action that matters. It does not navigate: a
 * successful sign-in changes the app-wide auth state, and the app reacts to
 * that. The ViewModel never needs to know what comes after the form.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val signIn: SignIn,
    private val signUp: SignUp,
    private val analytics: Analytics,
) : ViewModel() {
    private val mutable = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = mutable.asStateFlow()

    fun onEmailChange(value: String) = edit("email") { copy(email = value) }

    fun onPasswordChange(value: String) = edit("password") { copy(password = value) }

    fun onDisplayNameChange(value: String) = edit("displayName") { copy(displayName = value) }

    fun onAgeChange(value: String) = edit("age") { copy(age = value.filter(Char::isDigit).take(3)) }

    fun toggleMode() = mutable.update {
        it.copy(
            mode = if (it.mode == AuthMode.SIGN_IN) AuthMode.SIGN_UP else AuthMode.SIGN_IN,
            fieldErrors = emptyMap(),
            error = null,
        )
    }

    fun submit() {
        val form = mutable.value
        // A second tap while a request is in flight must not send a second request.
        if (form.submitting) return
        mutable.update { it.copy(submitting = true, error = null, fieldErrors = emptyMap()) }

        viewModelScope.launch {
            val result = when (form.mode) {
                AuthMode.SIGN_IN -> signIn(form.email, form.password)
                AuthMode.SIGN_UP -> signUp(
                    SignUpForm(form.email, form.password, form.displayName, form.age.toIntOrNull() ?: 0),
                )
            }
            when (result) {
                is Outcome.Success ->
                    // Do not keep a password in memory after it has done its job.
                    mutable.update { AuthUiState(mode = it.mode, email = it.email) }
                is Outcome.Failure -> {
                    val error = result.error
                    if (error is AppError.Validation) {
                        mutable.update { it.copy(submitting = false, fieldErrors = mapOf(error.field to error.reason)) }
                    } else {
                        analytics.track(AnalyticsEvent.ErrorShown("auth", error.kind()))
                        mutable.update { it.copy(submitting = false, error = error) }
                    }
                }
            }
        }
    }

    /** Editing a field clears that field's error and any form-level error, and nothing else. */
    private fun edit(field: String, change: AuthUiState.() -> AuthUiState) = mutable.update {
        it.change().copy(fieldErrors = it.fieldErrors - field, error = null)
    }
}
