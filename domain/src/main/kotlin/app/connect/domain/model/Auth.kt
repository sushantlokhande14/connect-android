package app.connect.domain.model

data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
)

data class Session(val userId: UserId, val tokens: AuthTokens)

enum class SignOutReason { USER_REQUESTED, SESSION_EXPIRED }

/**
 * Authentication is a state machine with one owner (AuthRepository). Screens
 * never infer it from a failed request; they observe this.
 */
sealed interface AuthState {
    /** Persisted session not read yet. The app shows nothing behind this. */
    data object Restoring : AuthState

    data class SignedOut(val reason: SignOutReason? = null) : AuthState

    data class SignedIn(val userId: UserId) : AuthState
}

data class SignUpForm(
    val email: String,
    val password: String,
    val displayName: String,
    val age: Int,
)
