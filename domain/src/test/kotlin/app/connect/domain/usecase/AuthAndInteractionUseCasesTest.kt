package app.connect.domain.usecase

import app.connect.domain.diagnostics.AnalyticsEvent
import app.connect.domain.model.AppError
import app.connect.domain.model.Outcome
import app.connect.domain.model.ProfileUpdate
import app.connect.domain.model.SignUpForm
import app.connect.domain.model.errorOrNull
import app.connect.domain.model.getOrNull
import app.connect.testing.Covers
import app.connect.testing.FakeAuthRepository
import app.connect.testing.FakeChatRepository
import app.connect.testing.FakeProfileRepository
import app.connect.testing.Fixtures
import app.connect.testing.RecordingAnalytics
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CredentialRulesTest {
    @Test
    fun `well formed emails pass and malformed ones name the field`() {
        assertThat(CredentialRules.email("sam@example.com")).isNull()
        assertThat(CredentialRules.email("  sam@example.com ")).isNull()
        listOf("", "sam", "sam@", "@example.com", "sam@example").forEach { bad ->
            assertThat(CredentialRules.email(bad)?.field).isEqualTo("email")
        }
    }

    @Test
    fun `new passwords need length letters and digits`() {
        assertThat(CredentialRules.newPassword("hunter2hunter2")).isNull()
        assertThat(CredentialRules.newPassword("short1")?.reason).contains("at least 8")
        assertThat(CredentialRules.newPassword("onlyletters")?.reason).contains("letters and numbers")
        assertThat(CredentialRules.newPassword("1234567890")?.reason).contains("letters and numbers")
    }

    @Test
    @Covers("AUTH-03")
    fun `sign up reports the first invalid field in form order`() {
        val valid = SignUpForm("sam@example.com", "hunter2hunter2", "Sam", 27)

        assertThat(CredentialRules.signUp(valid)).isNull()
        assertThat(CredentialRules.signUp(valid.copy(displayName = " "))?.field).isEqualTo("displayName")
        assertThat(CredentialRules.signUp(valid.copy(age = 17))?.field).isEqualTo("age")
        assertThat(CredentialRules.signUp(valid.copy(email = "nope"))?.field).isEqualTo("email")
        assertThat(CredentialRules.signUp(valid.copy(password = "x"))?.field).isEqualTo("password")
        assertThat(CredentialRules.signUp(valid.copy(displayName = "", age = 3))?.field).isEqualTo("displayName")
    }
}

class SignInTest {
    private val auth = FakeAuthRepository()
    private val analytics = RecordingAnalytics()
    private val signIn = SignIn(auth, analytics)

    @Test
    @Covers("AUTH-01")
    fun `valid credentials are normalised and sent`() = runTest {
        val result = signIn("  Sam@Example.com ", "hunter2hunter2")

        assertThat(result.getOrNull()).isEqualTo(Fixtures.session)
        assertThat(auth.signInCalls).containsExactly("sam@example.com" to "hunter2hunter2")
        assertThat(analytics.events).containsExactly(AnalyticsEvent.SignInCompleted(success = true))
    }

    @Test
    @Covers("AUTH-02")
    fun `a rejected sign in is reported with its kind and no personal data`() = runTest {
        auth.signInResult = Outcome.Failure(AppError.Unauthorized)

        val result = signIn("sam@example.com", "wrong-password1")

        assertThat(result.errorOrNull()).isEqualTo(AppError.Unauthorized)
        val event = analytics.of<AnalyticsEvent.SignInCompleted>().single()
        assertThat(event.params).containsExactly("success", false, "error_kind", "Unauthorized")
    }

    @Test
    @Covers("AUTH-03")
    fun `malformed input never reaches the network`() = runTest {
        assertThat(signIn("not-an-email", "whatever1").errorOrNull())
            .isEqualTo(AppError.Validation("email", "That doesn't look like an email address"))
        assertThat(signIn("sam@example.com", "").errorOrNull())
            .isEqualTo(AppError.Validation("password", "Enter your password"))

        assertThat(auth.signInCalls).isEmpty()
        assertThat(analytics.events).isEmpty()
    }
}

class SignUpTest {
    private val auth = FakeAuthRepository()
    private val analytics = RecordingAnalytics()
    private val signUp = SignUp(auth, analytics)

    @Test
    @Covers("AUTH-03")
    fun `an invalid form is rejected locally`() = runTest {
        val result = signUp(SignUpForm("sam@example.com", "short", "Sam", 27))

        assertThat((result.errorOrNull() as AppError.Validation).field).isEqualTo("password")
        assertThat(auth.signUpCalls).isEmpty()
    }

    @Test
    @Covers("AUTH-01")
    fun `a valid form is trimmed and submitted`() = runTest {
        val result = signUp(SignUpForm(" Sam@Example.com", "hunter2hunter2", "  Sam ", 27))

        assertThat(result.getOrNull()).isEqualTo(Fixtures.session)
        assertThat(auth.signUpCalls.single()).isEqualTo(SignUpForm("sam@example.com", "hunter2hunter2", "Sam", 27))
        assertThat(analytics.events).containsExactly(AnalyticsEvent.SignUpCompleted(success = true))
    }

    @Test
    fun `an address that is already registered surfaces the server reason`() = runTest {
        auth.signUpResult = Outcome.Failure(AppError.Rejected(409, "email_taken"))

        val result = signUp(SignUpForm("sam@example.com", "hunter2hunter2", "Sam", 27))

        assertThat(result.errorOrNull()).isEqualTo(AppError.Rejected(409, "email_taken"))
        assertThat(analytics.of<AnalyticsEvent.SignUpCompleted>().single().errorKind).isEqualTo("Rejected")
    }
}

class SendMessageTest {
    private val chat = FakeChatRepository()
    private val analytics = RecordingAnalytics()
    private val send = SendMessage(chat, analytics)

    @Test
    @Covers("CHAT-01")
    fun `the body is trimmed before it is sent`() = runTest {
        val result = send(Fixtures.conversationId, "  hello there \n")

        assertThat(result.getOrNull()?.body).isEqualTo("hello there")
        assertThat(chat.sent).containsExactly(Fixtures.conversationId to "hello there")
        assertThat(analytics.events).containsExactly(AnalyticsEvent.MessageSendCompleted(success = true, wasRetry = false))
    }

    @Test
    fun `blank and oversized bodies are rejected without a request`() = runTest {
        assertThat((send(Fixtures.conversationId, "   ").errorOrNull() as AppError.Validation).field).isEqualTo("body")
        assertThat(send(Fixtures.conversationId, "x".repeat(SendMessage.MAX_LENGTH + 1)).errorOrNull())
            .isInstanceOf(AppError.Validation::class.java)
        assertThat(chat.sent).isEmpty()
    }

    @Test
    @Covers("CHAT-02")
    fun `a retry is recorded as a retry`() = runTest {
        chat.sendError = AppError.Offline
        send(Fixtures.conversationId, "hello")
        val failed = chat.messages.value.single()
        chat.sendError = null

        val result = send.retry(failed.clientId)

        assertThat(result).isInstanceOf(Outcome.Success::class.java)
        assertThat(analytics.events.last()).isEqualTo(AnalyticsEvent.MessageSendCompleted(success = true, wasRetry = true))
    }
}

class UpdateMyProfileTest {
    private val profiles = FakeProfileRepository()
    private val update = UpdateMyProfile(profiles)

    @Test
    @Covers("PROF-02")
    fun `interests are normalised and de-duplicated before saving`() = runTest {
        val result = update(ProfileUpdate(bio = " New bio ", interests = listOf("Film", " film", "Chess", "")))

        assertThat(result.getOrNull()?.interests).containsExactly("film", "chess").inOrder()
        assertThat(profiles.updates.single().bio).isEqualTo("New bio")
    }

    @Test
    @Covers("PROF-02")
    fun `limits are enforced before any request`() = runTest {
        val tooLong = update(ProfileUpdate(bio = "x".repeat(UpdateMyProfile.MAX_BIO_LENGTH + 1)))
        val tooMany = update(ProfileUpdate(interests = (1..9).map { "interest$it" }))
        val noName = update(ProfileUpdate(displayName = "  "))

        assertThat((tooLong.errorOrNull() as AppError.Validation).field).isEqualTo("bio")
        assertThat((tooMany.errorOrNull() as AppError.Validation).field).isEqualTo("interests")
        assertThat((noName.errorOrNull() as AppError.Validation).field).isEqualTo("displayName")
        assertThat(profiles.updates).isEmpty()
    }
}
