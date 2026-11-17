package app.connect.feature.auth

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.connect.core.ui.ConnectTheme
import app.connect.core.ui.TestTags
import app.connect.domain.model.AppError
import app.connect.testing.Covers
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The screen alone, given a state. No ViewModel, no repository: what it draws for each state and what it reports back. */
@RunWith(RobolectricTestRunner::class)
class AuthScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var submits = 0
    private var toggles = 0
    private var typedEmail = ""

    private fun show(state: AuthUiState, sessionExpired: Boolean = false) {
        compose.setContent {
            ConnectTheme {
                AuthScreen(
                    state = state,
                    sessionExpired = sessionExpired,
                    onEmailChange = { typedEmail = it },
                    onPasswordChange = {},
                    onDisplayNameChange = {},
                    onAgeChange = {},
                    onToggleMode = { toggles++ },
                    onSubmit = { submits++ },
                )
            }
        }
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test
    @Covers("AUTH-01")
    fun `sign in asks for email and password only`() {
        show(AuthUiState())

        assertThat(exists(TestTags.AUTH_EMAIL)).isTrue()
        assertThat(exists(TestTags.AUTH_PASSWORD)).isTrue()
        assertThat(exists(TestTags.AUTH_NAME)).isFalse()
        assertThat(exists(TestTags.AUTH_AGE)).isFalse()
        compose.onNodeWithText("Sign in").assertExists()
    }

    @Test
    @Covers("AUTH-03")
    fun `sign up adds name and age and shows each field's error under it`() {
        show(
            AuthUiState(
                mode = AuthMode.SIGN_UP,
                fieldErrors = mapOf("displayName" to "Enter your name", "password" to "Use at least 8 characters"),
            ),
        )

        assertThat(exists(TestTags.AUTH_NAME)).isTrue()
        assertThat(exists(TestTags.AUTH_AGE)).isTrue()
        compose.onNodeWithText("Enter your name").assertExists()
        compose.onNodeWithText("Use at least 8 characters").assertExists()
        compose.onNodeWithText("Create account").assertExists()
    }

    @Test
    @Covers("AUTH-02")
    fun `a rejected sign in reads as a wrong password, not as an ended session`() {
        show(AuthUiState(error = AppError.Unauthorized))

        compose.onNodeWithTag(TestTags.AUTH_ERROR).assertTextEquals("Wrong email or password.")
    }

    @Test
    fun `being offline is explained in plain words`() {
        show(AuthUiState(error = AppError.Offline))

        compose.onNodeWithTag(TestTags.AUTH_ERROR)
            .assertTextEquals("You're offline. Check your connection and try again.")
    }

    @Test
    @Covers("AUTH-01")
    fun `the button is disabled while a request is in flight`() {
        show(AuthUiState(submitting = true))

        compose.onNodeWithTag(TestTags.AUTH_SUBMIT).assertIsNotEnabled()
        compose.onNodeWithTag(TestTags.AUTH_TOGGLE_MODE).assertIsNotEnabled()
    }

    @Test
    @Covers("AUTH-06")
    fun `a forced sign out is explained on the form`() {
        show(AuthUiState(), sessionExpired = true)

        compose.onNodeWithText("your session ended", substring = true).assertExists()
    }

    @Test
    fun `typing and tapping report back to the caller`() {
        show(AuthUiState())

        compose.onNodeWithTag(TestTags.AUTH_EMAIL).performTextInput("sam@example.com")
        compose.onNodeWithTag(TestTags.AUTH_SUBMIT).assertIsEnabled().performScrollTo().performClick()
        compose.onNodeWithTag(TestTags.AUTH_TOGGLE_MODE).performScrollTo().performClick()

        assertThat(typedEmail).isEqualTo("sam@example.com")
        assertThat(submits).isEqualTo(1)
        assertThat(toggles).isEqualTo(1)
    }
}
