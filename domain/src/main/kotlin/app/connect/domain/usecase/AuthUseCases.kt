package app.connect.domain.usecase

import app.connect.domain.diagnostics.Analytics
import app.connect.domain.diagnostics.AnalyticsEvent
import app.connect.domain.model.AppError
import app.connect.domain.model.Outcome
import app.connect.domain.model.Session
import app.connect.domain.model.SignUpForm
import app.connect.domain.repository.AuthRepository
import javax.inject.Inject

/** Checked on the device before any request is made, and again by the server. */
object CredentialRules {
    const val MIN_PASSWORD_LENGTH = 8
    const val MIN_AGE = 18
    const val MAX_NAME_LENGTH = 40

    private val EMAIL = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    fun email(value: String): AppError.Validation? = when {
        value.isBlank() -> AppError.Validation("email", "Enter your email")
        !EMAIL.matches(value.trim()) -> AppError.Validation("email", "That doesn't look like an email address")
        else -> null
    }

    fun newPassword(value: String): AppError.Validation? = when {
        value.length < MIN_PASSWORD_LENGTH ->
            AppError.Validation("password", "Use at least $MIN_PASSWORD_LENGTH characters")
        value.none(Char::isDigit) || value.none(Char::isLetter) ->
            AppError.Validation("password", "Use both letters and numbers")
        else -> null
    }

    fun displayName(value: String): AppError.Validation? = when {
        value.isBlank() -> AppError.Validation("displayName", "Enter your name")
        value.trim().length > MAX_NAME_LENGTH -> AppError.Validation("displayName", "Keep your name under $MAX_NAME_LENGTH characters")
        else -> null
    }

    fun age(value: Int): AppError.Validation? =
        if (value < MIN_AGE) AppError.Validation("age", "You must be $MIN_AGE or older") else null

    fun signUp(form: SignUpForm): AppError.Validation? =
        displayName(form.displayName) ?: age(form.age) ?: email(form.email) ?: newPassword(form.password)
}

class SignIn @Inject constructor(
    private val auth: AuthRepository,
    private val analytics: Analytics,
) {
    suspend operator fun invoke(email: String, password: String): Outcome<Session> {
        CredentialRules.email(email)?.let { return Outcome.Failure(it) }
        if (password.isEmpty()) return Outcome.Failure(AppError.Validation("password", "Enter your password"))

        val result = auth.signIn(email.trim().lowercase(), password)
        analytics.track(
            AnalyticsEvent.SignInCompleted(
                success = result is Outcome.Success,
                errorKind = (result as? Outcome.Failure)?.error?.kind(),
            ),
        )
        return result
    }
}

class SignUp @Inject constructor(
    private val auth: AuthRepository,
    private val analytics: Analytics,
) {
    suspend operator fun invoke(form: SignUpForm): Outcome<Session> {
        CredentialRules.signUp(form)?.let { return Outcome.Failure(it) }

        val result = auth.signUp(
            form.copy(email = form.email.trim().lowercase(), displayName = form.displayName.trim()),
        )
        analytics.track(
            AnalyticsEvent.SignUpCompleted(
                success = result is Outcome.Success,
                errorKind = (result as? Outcome.Failure)?.error?.kind(),
            ),
        )
        return result
    }
}
