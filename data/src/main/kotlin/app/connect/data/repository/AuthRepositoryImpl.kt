package app.connect.data.repository

import app.connect.data.local.ConnectDatabase
import app.connect.data.network.ApiCaller
import app.connect.data.network.AuthApi
import app.connect.data.network.ConnectApi
import app.connect.data.network.LoginRequest
import app.connect.data.network.SignUpRequest
import app.connect.data.session.SessionState
import app.connect.data.session.SessionStore
import app.connect.domain.diagnostics.Analytics
import app.connect.domain.diagnostics.AnalyticsEvent
import app.connect.domain.diagnostics.Breadcrumbs
import app.connect.data.di.ApplicationScope
import app.connect.domain.model.AuthState
import app.connect.domain.model.Outcome
import app.connect.domain.model.Session
import app.connect.domain.model.SignOutReason
import app.connect.domain.model.SignUpForm
import app.connect.domain.model.map
import app.connect.domain.model.onSuccess
import app.connect.domain.repository.AuthRepository
import app.connect.domain.util.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Auth state is derived from the session store and from nothing else. A
 * session can end in two ways (the user signs out, or the interceptor finds
 * the refresh token rejected) and both go through the store, so both reach
 * every observer the same way and both wipe the user's cached data.
 */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val authApi: AuthApi,
    private val api: Provider<ConnectApi>,
    private val sessions: SessionStore,
    private val database: ConnectDatabase,
    private val caller: ApiCaller,
    private val analytics: Analytics,
    private val breadcrumbs: Breadcrumbs,
    private val dispatchers: DispatcherProvider,
    @ApplicationScope scope: CoroutineScope,
) : AuthRepository {

    override val authState: StateFlow<AuthState> = sessions.state
        .map { it.toAuthState() }
        .stateIn(scope, SharingStarted.Eagerly, sessions.state.value.toAuthState())

    init {
        scope.launch {
            // Only an active session ending is a sign-out. Start-up with nothing on disk is not.
            var previous = sessions.state.value
            sessions.state.collect { current ->
                if (previous is SessionState.Active && current is SessionState.None) {
                    clearUserData()
                    analytics.track(
                        AnalyticsEvent.SignedOut((current.reason ?: SignOutReason.USER_REQUESTED).name.lowercase()),
                    )
                }
                previous = current
            }
        }
    }

    override suspend fun restoreSession() {
        sessions.load()
        breadcrumbs.leave("auth", "session restored: ${sessions.current() != null}")
    }

    // On these two endpoints a 401 means wrong credentials, not an expired session. They go through the
    // plain client, so the auth interceptor never tries to refresh on their behalf.
    override suspend fun signIn(email: String, password: String): Outcome<Session> =
        caller.call("POST /v1/auth/login") { authApi.login(LoginRequest(email, password)) }
            .map { it.toDomain() }
            .onSuccess { sessions.save(it) }

    override suspend fun signUp(form: SignUpForm): Outcome<Session> =
        caller.call("POST /v1/auth/signup") {
            authApi.signUp(SignUpRequest(form.email, form.password, form.displayName, form.age))
        }.map { it.toDomain() }.onSuccess { sessions.save(it) }

    override suspend fun signOut(reason: SignOutReason) {
        if (sessions.current() == null) return
        if (reason == SignOutReason.USER_REQUESTED) {
            // Best effort: the user is signed out locally whether or not the server hears about it.
            caller.callEmpty("POST /v1/auth/logout") { api.get().logout() }
        }
        sessions.clear(reason)
    }

    private suspend fun clearUserData() = withContext(dispatchers.io) {
        database.clearAllTables()
        breadcrumbs.leave("auth", "local user data cleared")
    }

    private fun SessionState.toAuthState(): AuthState = when (this) {
        SessionState.NotLoaded -> AuthState.Restoring
        is SessionState.None -> AuthState.SignedOut(reason)
        is SessionState.Active -> AuthState.SignedIn(session.userId)
    }
}
