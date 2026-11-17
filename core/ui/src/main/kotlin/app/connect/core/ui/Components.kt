package app.connect.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.connect.domain.model.AppError
import coil.compose.AsyncImage

/** Tags the UI tests find nodes by. Kept in one place so a rename cannot silently break a test. */
object TestTags {
    const val LOADING = "loading"
    const val ERROR = "error"
    const val RETRY = "retry"
    const val EMPTY = "empty"

    const val AUTH_EMAIL = "auth_email"
    const val AUTH_PASSWORD = "auth_password"
    const val AUTH_NAME = "auth_name"
    const val AUTH_AGE = "auth_age"
    const val AUTH_SUBMIT = "auth_submit"
    const val AUTH_TOGGLE_MODE = "auth_toggle_mode"
    const val AUTH_ERROR = "auth_error"

    const val DISCOVERY_CARD = "discovery_card"
    const val DISCOVERY_LIKE = "discovery_like"
    const val DISCOVERY_PASS = "discovery_pass"
    const val DISCOVERY_QUOTA = "discovery_quota"
    const val DISCOVERY_FILTERS = "discovery_filters"
    const val MATCH_DIALOG = "match_dialog"

    const val PROFILE_NAME = "profile_name"
    const val PROFILE_SIGN_OUT = "profile_sign_out"
    const val PROFILE_SAVE = "profile_save"
    const val PROFILE_BIO = "profile_bio"

    const val CONVERSATION_ROW = "conversation_row"
    const val CHAT_INPUT = "chat_input"
    const val CHAT_SEND = "chat_send"
    const val CHAT_MESSAGE = "chat_message"
    const val CHAT_RETRY = "chat_retry"

    const val NAV_DISCOVER = "nav_discover"
    const val NAV_CHATS = "nav_chats"
    const val NAV_ME = "nav_me"
}

/** The sentence a person sees for each kind of failure. One mapping, used by every screen. */
fun AppError.userMessage(): String = when (this) {
    AppError.Offline -> "You're offline. Check your connection and try again."
    AppError.Timeout -> "That took too long. Try again."
    AppError.Unauthorized -> "Your session has ended. Sign in again."
    is AppError.Server -> "Something went wrong on our side. Try again in a moment."
    is AppError.Rejected -> when (reason) {
        "email_taken" -> "An account with this email already exists."
        "like_quota_exhausted" -> "You're out of likes for today."
        "profile_not_found" -> "This profile is no longer available."
        else -> "That didn't work. Try again."
    }
    is AppError.Validation -> reason
    is AppError.Unexpected -> "Something went wrong. Try again."
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().testTag(TestTags.LOADING), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun ErrorState(error: AppError, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    MessageState(
        title = if (error == AppError.Offline) "No connection" else "Couldn't load this",
        body = error.userMessage(),
        action = "Try again",
        onAction = onRetry,
        modifier = modifier.testTag(TestTags.ERROR),
        actionTag = TestTags.RETRY,
    )
}

@Composable
fun MessageState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
    actionTag: String = "action",
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction, modifier = Modifier.testTag(actionTag)) { Text(action) }
        }
    }
}

private val avatarPalette = listOf(
    Color(0xFFE5484D), Color(0xFF3E63DD), Color(0xFF2B9A66), Color(0xFFE2A336), Color(0xFF8E4EC6), Color(0xFF0091FF),
)

/** A photo when there is one; otherwise the person's initial on a colour derived from their name. */
@Composable
fun Avatar(name: String, photoUrl: String?, size: Dp, modifier: Modifier = Modifier) {
    val colour = remember(name) { avatarPalette[(name.hashCode() and Int.MAX_VALUE) % avatarPalette.size] }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(colour, colour.copy(alpha = 0.65f))))
            .semantics { contentDescription = "$name's photo" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().take(1).uppercase(),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.42f).sp,
        )
        if (photoUrl != null) {
            AsyncImage(
                model = photoUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InterestChips(interests: List<String>, modifier: Modifier = Modifier, highlighted: Set<String> = emptySet()) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        interests.forEach { interest ->
            val shared = interest in highlighted
            Surface(
                shape = CircleShape,
                color = if (shared) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (shared) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(
                    text = interest,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}
