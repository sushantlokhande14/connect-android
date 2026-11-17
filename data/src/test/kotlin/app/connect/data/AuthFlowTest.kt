package app.connect.data

import app.connect.data.session.SessionState
import app.connect.domain.diagnostics.AnalyticsEvent
import app.connect.domain.model.AppError
import app.connect.domain.model.AuthState
import app.connect.domain.model.Outcome
import app.connect.domain.model.SignOutReason
import app.connect.domain.model.SignUpForm
import app.connect.domain.model.errorOrNull
import app.connect.domain.model.getOrNull
import app.connect.domain.repository.Freshness
import app.connect.fakebackend.Fault
import app.connect.fakebackend.FakeBackend
import app.connect.testing.Covers
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuthFlowTest {
    private val harness = DataHarness()

    @After
    fun tearDown() = harness.close()

    @Test
    @Covers("AUTH-01")
    fun `signing in stores the session and unlocks authenticated requests`() = runTestBlocking {
        harness.sessions.load()
        assertThat(awaitAuthState { it !is AuthState.Restoring }).isEqualTo(AuthState.SignedOut())

        val session = harness.signInDemo()

        assertThat(harness.sessions.current()).isEqualTo(session)
        assertThat(awaitAuthState { it is AuthState.SignedIn }).isEqualTo(AuthState.SignedIn(session.userId))
        assertThat(harness.profiles.getMe().getOrNull()?.displayName).isEqualTo("Sam")
    }

    @Test
    @Covers("AUTH-02")
    fun `wrong credentials fail without creating a session`() = runTestBlocking {
        harness.sessions.load()

        val result = harness.auth.signIn(FakeBackend.DEMO_EMAIL, "not-the-password")

        assertThat(result.errorOrNull()).isEqualTo(AppError.Unauthorized)
        assertThat(harness.sessions.current()).isNull()
        assertThat(awaitAuthState { it !is AuthState.Restoring }).isEqualTo(AuthState.SignedOut())
        // A 401 from the login endpoint is a wrong password. It must not trigger a token refresh.
        assertThat(harness.backend.requestCount("POST /v1/auth/refresh")).isEqualTo(0)
    }

    @Test
    @Covers("AUTH-01")
    fun `signing up creates an account that can sign in again`() = runTestBlocking {
        harness.sessions.load()
        val form = SignUpForm("new@example.com", "hunter2hunter2", "Noor", 29)

        val created = harness.auth.signUp(form)
        harness.auth.signOut()
        val again = harness.auth.signIn(form.email, form.password)

        assertThat(created).isInstanceOf(Outcome.Success::class.java)
        assertThat(again.getOrNull()?.userId).isEqualTo(created.getOrNull()?.userId)
    }

    @Test
    fun `an address that is already registered is rejected with the server reason`() = runTestBlocking {
        harness.sessions.load()

        val result = harness.auth.signUp(SignUpForm(FakeBackend.DEMO_EMAIL, "hunter2hunter2", "Sam", 27))

        assertThat(result.errorOrNull()).isEqualTo(AppError.Rejected(409, "email_taken"))
    }

    @Test
    @Covers("AUTH-04")
    fun `a restarted process restores the session from disk`() = runTestBlocking {
        val session = harness.signInDemo()
        harness.close()

        // Same session file, everything else new: this is what a cold start looks like.
        val restarted = DataHarness(clock = harness.clock, backend = harness.backend, sessionFile = harness.sessionFile)
        try {
            assertThat(restarted.auth.authState.value).isEqualTo(AuthState.Restoring)

            restarted.auth.restoreSession()

            assertThat(restarted.sessions.current()).isEqualTo(session)
            assertThat(restarted.profiles.getMe().getOrNull()?.displayName).isEqualTo("Sam")
        } finally {
            restarted.close()
        }
    }

    @Test
    @Covers("AUTH-04")
    fun `restoring with nothing on disk ends signed out`() = runTestBlocking {
        harness.auth.restoreSession()

        assertThat(harness.sessions.state.value).isEqualTo(SessionState.None())
        assertThat(awaitAuthState { it is AuthState.SignedOut }).isEqualTo(AuthState.SignedOut())
    }

    @Test
    @Covers("AUTH-05")
    fun `an expired access token is refreshed and the request replayed`() = runTestBlocking {
        val before = harness.signInDemo()
        harness.backend.expireAccessTokens()

        val me = harness.profiles.getMe(Freshness.NETWORK_ONLY)

        assertThat(me.getOrNull()?.displayName).isEqualTo("Sam")
        assertThat(harness.backend.requestCount("POST /v1/auth/refresh")).isEqualTo(1)
        // Sent once with the dead token, once more with the new one.
        assertThat(harness.backend.requestCount("GET /v1/me")).isEqualTo(2)
        assertThat(harness.sessions.current()!!.tokens.accessToken).isNotEqualTo(before.tokens.accessToken)
    }

    @Test
    @Covers("AUTH-05")
    fun `requests that fail together share one token refresh`() = runTestBlocking {
        harness.signInDemo()
        harness.backend.expireAccessTokens()

        // Five authenticated requests at once, which is what the discovery screen sends.
        val results = listOf(
            async { harness.profiles.getMe(Freshness.NETWORK_ONLY) },
            async { harness.discovery.getPreferences(Freshness.NETWORK_ONLY) },
            async { harness.discovery.getDeck(null, Freshness.NETWORK_ONLY) },
            async { harness.discovery.getQuota() },
            async { harness.chat.fetchUnreadCount() },
        ).awaitAll()

        assertThat(results.filterIsInstance<Outcome.Failure>()).isEmpty()
        // Refresh tokens are single-use. A second refresh would have been rejected and signed the user out.
        assertThat(harness.backend.requestCount("POST /v1/auth/refresh")).isEqualTo(1)
        assertThat(harness.sessions.current()).isNotNull()
    }

    @Test
    @Covers("AUTH-06")
    fun `a rejected refresh ends the session and wipes cached user data`() = runTestBlocking {
        val session = harness.signInDemo()
        harness.profiles.getMe()
        assertThat(harness.database.profiles().get(session.userId.value)).isNotNull()
        harness.backend.expireAccessTokens()
        harness.backend.revokeRefreshTokens()

        val result = harness.profiles.getMe(Freshness.NETWORK_ONLY)

        assertThat(result.errorOrNull()).isEqualTo(AppError.Unauthorized)
        assertThat(awaitAuthState { it is AuthState.SignedOut })
            .isEqualTo(AuthState.SignedOut(SignOutReason.SESSION_EXPIRED))
        awaitUntil { harness.database.profiles().get(session.userId.value) == null }
        assertThat(harness.analytics.of<AnalyticsEvent.SignedOut>().single().reason).isEqualTo("session_expired")
    }

    @Test
    @Covers("AUTH-06")
    fun `a refresh that cannot reach the server keeps the session`() = runTestBlocking {
        harness.signInDemo()
        harness.backend.expireAccessTokens()
        harness.backend.faults.failUntilCleared("POST /v1/auth/refresh", Fault.Disconnect)

        val result = harness.profiles.getMe(Freshness.NETWORK_ONLY)

        // The request fails, but being offline during a refresh is not a reason to sign the user out.
        assertThat(result.errorOrNull()).isEqualTo(AppError.Unauthorized)
        assertThat(harness.sessions.current()).isNotNull()

        // Back online: the same session refreshes and the request goes through.
        harness.backend.faults.clear()
        assertThat(harness.profiles.getMe(Freshness.NETWORK_ONLY)).isInstanceOf(Outcome.Success::class.java)
    }

    @Test
    @Covers("AUTH-06")
    fun `signing out tells the server and clears local data`() = runTestBlocking {
        val session = harness.signInDemo()
        harness.profiles.getMe()

        harness.auth.signOut()

        assertThat(harness.backend.requestCount("POST /v1/auth/logout")).isEqualTo(1)
        assertThat(harness.sessions.current()).isNull()
        assertThat(awaitAuthState { it is AuthState.SignedOut })
            .isEqualTo(AuthState.SignedOut(SignOutReason.USER_REQUESTED))
        awaitUntil { harness.database.profiles().get(session.userId.value) == null }
    }

    @Test
    fun `signing out works when the server cannot be reached`() = runTestBlocking {
        harness.signInDemo()
        harness.backend.faults.failUntilCleared("POST /v1/auth/logout", Fault.Disconnect)

        harness.auth.signOut()

        assertThat(harness.sessions.current()).isNull()
    }

    private suspend fun awaitAuthState(predicate: (AuthState) -> Boolean): AuthState =
        withTimeout(5_000) { harness.auth.authState.first(predicate) }

    private suspend fun awaitUntil(condition: suspend () -> Boolean) = withTimeout(5_000) {
        while (!condition()) kotlinx.coroutines.delay(20)
    }
}
