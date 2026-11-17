package app.connect.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.connect.core.ui.Avatar
import app.connect.core.ui.ErrorState
import app.connect.core.ui.LoadingState
import app.connect.core.ui.MessageState
import app.connect.core.ui.TestTags
import app.connect.core.ui.userMessage
import app.connect.domain.model.AppError
import app.connect.domain.model.Conversation
import app.connect.domain.model.ConversationId
import app.connect.domain.model.MessageStatus

@Composable
fun ConversationsRoute(onOpenChat: (ConversationId) -> Unit, viewModel: ConversationsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ConversationsScreen(state, onOpenChat, viewModel::retry)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    state: ConversationsUiState,
    onOpenChat: (ConversationId) -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Chats") }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.conversations.isNotEmpty() -> Column {
                    // Saved conversations stay readable when a refresh fails; the failure is a banner, not the screen.
                    state.error?.let { StaleBanner(it, onRetry) }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(state.conversations, key = { it.id.value }) { conversation ->
                            ConversationRow(conversation) { onOpenChat(conversation.id) }
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        }
                    }
                }
                state.loading -> LoadingState()
                state.error != null -> ErrorState(state.error, onRetry)
                else -> MessageState(
                    title = "No conversations yet",
                    body = "When you match with someone, your conversation shows up here.",
                    modifier = Modifier.testTag(TestTags.EMPTY),
                )
            }
            if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun StaleBanner(error: AppError, onRetry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().clickable(onClick = onRetry)) {
        Text(
            text = if (error == AppError.Offline) "You're offline. Showing saved conversations." else "Couldn't refresh. Tap to try again.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).testTag(TestTags.ERROR),
        )
    }
}

@Composable
private fun ConversationRow(conversation: Conversation, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(TestTags.CONVERSATION_ROW),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(conversation.peerName, conversation.peerPhotoUrl, size = 52.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(conversation.peerName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = conversation.lastMessagePreview ?: "Say hi",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (conversation.unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (conversation.unreadCount > 0) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                Text(
                    text = conversation.unreadCount.toString(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
fun ChatRoute(
    onBack: () -> Unit,
    /** Told which conversation is on screen, so a push for it is not also shown as a notification. */
    onVisibleConversationChange: (ConversationId?) -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DisposableEffect(viewModel.conversationId) {
        onVisibleConversationChange(viewModel.conversationId)
        onDispose { onVisibleConversationChange(null) }
    }
    ChatScreen(state, onBack, viewModel::onDraftChange, viewModel::send, viewModel::retry)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    onBack: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.peerName) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                // reverseLayout draws the first item at the bottom, so the newest message goes first.
                items(state.messages.asReversed(), key = { it.message.clientId }) { item ->
                    MessageBubble(item, onRetry)
                }
            }
            state.draftError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = onDraftChange,
                    placeholder = { Text("Message") },
                    maxLines = 4,
                    modifier = Modifier.weight(1f).testTag(TestTags.CHAT_INPUT),
                )
                IconButton(
                    onClick = onSend,
                    enabled = state.draft.isNotBlank(),
                    modifier = Modifier.testTag(TestTags.CHAT_SEND),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(item: MessageItem, onRetry: (String) -> Unit) {
    val message = item.message
    val failed = message.status == MessageStatus.FAILED
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (item.mine) Alignment.End else Alignment.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = when {
                failed -> MaterialTheme.colorScheme.errorContainer
                item.mine -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = when {
                failed -> MaterialTheme.colorScheme.onErrorContainer
                item.mine -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier
                .widthIn(max = 300.dp)
                .testTag(TestTags.CHAT_MESSAGE)
                .then(if (failed) Modifier.clickable { onRetry(message.clientId) } else Modifier),
        ) {
            Text(message.body, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
        }
        when (message.status) {
            MessageStatus.PENDING -> StatusLine("Sending…", MaterialTheme.colorScheme.onSurfaceVariant)
            MessageStatus.FAILED -> StatusLine(
                "Not sent. Tap to try again.",
                MaterialTheme.colorScheme.error,
                Modifier.testTag(TestTags.CHAT_RETRY).clickable { onRetry(message.clientId) },
            )
            MessageStatus.SENT -> Unit
        }
    }
}

@Composable
private fun StatusLine(text: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = modifier.padding(horizontal = 6.dp, vertical = 2.dp))
}
