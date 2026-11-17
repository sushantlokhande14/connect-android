package app.connect.feature.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.connect.core.ui.TestTags
import app.connect.core.ui.userMessage
import app.connect.domain.model.AppError

@Composable
fun AuthRoute(sessionExpired: Boolean, viewModel: AuthViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AuthScreen(
        state = state,
        sessionExpired = sessionExpired,
        onEmailChange = viewModel::onEmailChange,
        onPasswordChange = viewModel::onPasswordChange,
        onDisplayNameChange = viewModel::onDisplayNameChange,
        onAgeChange = viewModel::onAgeChange,
        onToggleMode = viewModel::toggleMode,
        onSubmit = viewModel::submit,
    )
}

/** Stateless: everything it shows comes in, everything the user does goes out. That is what makes it testable alone. */
@Composable
fun AuthScreen(
    state: AuthUiState,
    sessionExpired: Boolean,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onAgeChange: (String) -> Unit,
    onToggleMode: () -> Unit,
    onSubmit: () -> Unit,
) {
    val signingUp = state.mode == AuthMode.SIGN_UP
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 48.dp),
        ) {
            Text("Connect", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(
                if (signingUp) "Create your account" else "Welcome back",
                style = MaterialTheme.typography.titleLarge,
            )
            if (sessionExpired && state.error == null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "You were signed out because your session ended. Sign in to pick up where you left off.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(28.dp))

            if (signingUp) {
                Field(
                    value = state.displayName,
                    onValueChange = onDisplayNameChange,
                    label = "Name",
                    error = state.fieldErrors["displayName"],
                    tag = TestTags.AUTH_NAME,
                    enabled = !state.submitting,
                )
                Field(
                    value = state.age,
                    onValueChange = onAgeChange,
                    label = "Age",
                    error = state.fieldErrors["age"],
                    tag = TestTags.AUTH_AGE,
                    enabled = !state.submitting,
                    keyboardType = KeyboardType.Number,
                )
            }
            Field(
                value = state.email,
                onValueChange = onEmailChange,
                label = "Email",
                error = state.fieldErrors["email"],
                tag = TestTags.AUTH_EMAIL,
                enabled = !state.submitting,
                keyboardType = KeyboardType.Email,
            )
            Field(
                value = state.password,
                onValueChange = onPasswordChange,
                label = "Password",
                error = state.fieldErrors["password"],
                tag = TestTags.AUTH_PASSWORD,
                enabled = !state.submitting,
                keyboardType = KeyboardType.Password,
                isPassword = true,
                imeAction = ImeAction.Done,
                onDone = onSubmit,
            )

            state.error?.let { error ->
                Text(
                    text = error.authMessage(state.mode),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp).testTag(TestTags.AUTH_ERROR),
                )
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onSubmit,
                enabled = !state.submitting,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag(TestTags.AUTH_SUBMIT),
            ) {
                if (state.submitting) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(if (signingUp) "Create account" else "Sign in")
                }
            }
            TextButton(
                onClick = onToggleMode,
                enabled = !state.submitting,
                modifier = Modifier.fillMaxWidth().testTag(TestTags.AUTH_TOGGLE_MODE),
            ) {
                Text(if (signingUp) "I already have an account" else "Create an account")
            }
        }
    }
}

/** On the sign-in form a 401 means the credentials were wrong, not that a session ended. */
private fun AppError.authMessage(mode: AuthMode): String =
    if (this == AppError.Unauthorized && mode == AuthMode.SIGN_IN) "Wrong email or password." else userMessage()

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
    tag: String,
    enabled: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag(tag),
    )
}
