package app.connect.feature.discovery

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.connect.core.ui.ErrorState
import app.connect.core.ui.InterestChips
import app.connect.core.ui.LoadingState
import app.connect.core.ui.MessageState
import app.connect.core.ui.TestTags
import app.connect.core.ui.userMessage
import app.connect.domain.model.ConversationId
import app.connect.domain.model.DiscoveryCard
import app.connect.domain.model.DiscoveryPreferences
import app.connect.domain.model.SwipeAction
import app.connect.domain.model.UserId
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun DiscoveryRoute(
    onOpenProfile: (UserId) -> Unit,
    onOpenChat: (ConversationId) -> Unit,
    viewModel: DiscoveryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DiscoveryScreen(
        state = state,
        onRetry = viewModel::retry,
        onRefresh = viewModel::refresh,
        onSwipe = viewModel::swipe,
        onOpenProfile = onOpenProfile,
        onSayHi = { conversation ->
            viewModel.dismissMatch()
            onOpenChat(conversation)
        },
        onDismissMatch = viewModel::dismissMatch,
        onDismissNotice = viewModel::dismissNotice,
        onSavePreferences = viewModel::savePreferences,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    state: DiscoveryUiState,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onSwipe: (DiscoveryCard, SwipeAction) -> Unit,
    onOpenProfile: (UserId) -> Unit,
    onSayHi: (ConversationId) -> Unit,
    onDismissMatch: () -> Unit,
    onDismissNotice: () -> Unit,
    onSavePreferences: (DiscoveryPreferences) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    val content = state as? DiscoveryUiState.Content

    LaunchedEffect(content?.notice) {
        content?.notice?.let { notice ->
            snackbar.showSnackbar(notice.userMessage())
            onDismissNotice()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Discover") },
                actions = {
                    content?.quota?.let { quota ->
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text(
                                "${quota.remaining} likes left",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).testTag(TestTags.DISCOVERY_QUOTA),
                            )
                        }
                    }
                    if (content != null) {
                        IconButton(onClick = { showFilters = true }, modifier = Modifier.testTag(TestTags.DISCOVERY_FILTERS)) {
                            Icon(Icons.Filled.Settings, contentDescription = "Filters")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                DiscoveryUiState.Loading -> LoadingState()
                is DiscoveryUiState.Error -> ErrorState(state.error, onRetry)
                is DiscoveryUiState.Content -> {
                    if (state.cards.isEmpty()) {
                        MessageState(
                            title = "You've seen everyone nearby",
                            body = "Widen your filters or check back later.",
                            action = "Refresh",
                            onAction = onRefresh,
                            modifier = Modifier.testTag(TestTags.EMPTY),
                        )
                    } else {
                        Deck(state.cards, onSwipe, onOpenProfile)
                    }
                    if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }

    content?.match?.let { match ->
        AlertDialog(
            onDismissRequest = onDismissMatch,
            modifier = Modifier.testTag(TestTags.MATCH_DIALOG),
            title = { Text("It's a match") },
            text = { Text("You and ${match.peerName} like each other.") },
            confirmButton = { Button(onClick = { onSayHi(match.conversationId) }) { Text("Say hi") } },
            dismissButton = { TextButton(onClick = onDismissMatch) { Text("Keep swiping") } },
        )
    }

    if (showFilters && content != null) {
        FiltersSheet(
            current = content.preferences,
            onDismiss = { showFilters = false },
            onSave = {
                showFilters = false
                onSavePreferences(it)
            },
        )
    }
}

@Composable
private fun Deck(
    cards: List<DiscoveryCard>,
    onSwipe: (DiscoveryCard, SwipeAction) -> Unit,
    onOpenProfile: (UserId) -> Unit,
) {
    val top = cards.first()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // The next card sits underneath so the deck never flashes empty during a swipe.
            cards.getOrNull(1)?.let { next ->
                ProfileCard(next, Modifier.fillMaxSize().graphicsLayer { scaleX = 0.96f; scaleY = 0.96f; alpha = 0.7f })
            }
            SwipeableCard(card = top, onSwipe = { onSwipe(top, it) }, onClick = { onOpenProfile(top.profile.id) })
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            OutlinedIconButton(
                onClick = { onSwipe(top, SwipeAction.PASS) },
                modifier = Modifier.size(64.dp).testTag(TestTags.DISCOVERY_PASS),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Pass on ${top.profile.displayName}")
            }
            Spacer(Modifier.width(32.dp))
            FilledIconButton(
                onClick = { onSwipe(top, SwipeAction.LIKE) },
                modifier = Modifier.size(64.dp).testTag(TestTags.DISCOVERY_LIKE),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Icon(Icons.Filled.Favorite, contentDescription = "Like ${top.profile.displayName}")
            }
        }
    }
}

@Composable
private fun SwipeableCard(card: DiscoveryCard, onSwipe: (SwipeAction) -> Unit, onClick: () -> Unit) {
    val scope = rememberCoroutineScope()
    // Keyed by the person, so a new top card starts centred instead of inheriting the last drag.
    val offset = remember(card.profile.id) { Animatable(Offset.Zero, Offset.VectorConverter) }

    ProfileCard(
        card = card,
        modifier = Modifier
            .fillMaxSize()
            .testTag(TestTags.DISCOVERY_CARD)
            .graphicsLayer {
                translationX = offset.value.x
                translationY = offset.value.y * 0.3f
                rotationZ = offset.value.x / size.width.coerceAtLeast(1f) * 12f
            }
            .pointerInput(card.profile.id) {
                // The finger's position is tracked here, synchronously. The Animatable only draws it, and
                // is updated from a coroutine, so the decision at the end of a drag must not read it back.
                var dragged = Offset.Zero
                detectDragGestures(
                    onDragStart = { dragged = offset.value },
                    onDrag = { change, delta ->
                        change.consume()
                        dragged += delta
                        val target = dragged
                        scope.launch { offset.snapTo(target) }
                    },
                    onDragEnd = {
                        if (abs(dragged.x) > size.width * SWIPE_THRESHOLD) {
                            onSwipe(if (dragged.x > 0) SwipeAction.LIKE else SwipeAction.PASS)
                        } else {
                            scope.launch { offset.animateTo(Offset.Zero, spring()) }
                        }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(Offset.Zero, spring()) } },
                )
            }
            .clickable(onClickLabel = "Open ${card.profile.displayName}'s profile", onClick = onClick),
    )
}

@Composable
private fun ProfileCard(card: DiscoveryCard, modifier: Modifier = Modifier) {
    val profile = card.profile
    Surface(modifier = modifier, shape = MaterialTheme.shapes.large, tonalElevation = 2.dp, shadowElevation = 4.dp) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (profile.photoUrl != null) {
                AsyncImage(
                    model = profile.photoUrl,
                    contentDescription = "${profile.displayName}'s photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))))
                    .padding(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 20.dp),
            ) {
                Text(
                    "${profile.displayName}, ${profile.age}",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
                Text(
                    listOfNotNull(profile.city, "${card.distanceKm} km away").joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                )
                if (profile.bio.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        profile.bio,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (profile.interests.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    InterestChips(profile.interests.take(4), highlighted = card.sharedInterests.toSet())
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FiltersSheet(
    current: DiscoveryPreferences,
    onDismiss: () -> Unit,
    onSave: (DiscoveryPreferences) -> Unit,
) {
    var ages by remember { mutableStateOf(current.minAge.toFloat()..current.maxAge.toFloat()) }
    var distance by remember { mutableFloatStateOf(current.maxDistanceKm.toFloat()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Filters", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(20.dp))
            Text("Age ${ages.start.roundToInt()} to ${ages.endInclusive.roundToInt()}", style = MaterialTheme.typography.titleMedium)
            RangeSlider(value = ages, onValueChange = { ages = it }, valueRange = 18f..60f)
            Spacer(Modifier.height(12.dp))
            Text("Within ${distance.roundToInt()} km", style = MaterialTheme.typography.titleMedium)
            Slider(value = distance, onValueChange = { distance = it }, valueRange = 1f..100f)
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    onSave(
                        DiscoveryPreferences(
                            minAge = ages.start.roundToInt(),
                            maxAge = ages.endInclusive.roundToInt().coerceAtLeast(ages.start.roundToInt()),
                            maxDistanceKm = distance.roundToInt().coerceAtLeast(1),
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Show people") }
        }
    }
}

private const val SWIPE_THRESHOLD = 0.28f
