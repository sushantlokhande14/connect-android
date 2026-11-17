package app.connect.feature.profile

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.connect.core.ui.Avatar
import app.connect.core.ui.ErrorState
import app.connect.core.ui.InterestChips
import app.connect.core.ui.LoadingState
import app.connect.core.ui.TestTags
import app.connect.core.ui.userMessage
import app.connect.domain.model.Profile

@Composable
fun ProfileDetailRoute(onBack: () -> Unit, viewModel: ProfileDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProfileDetailScreen(state, onBack, viewModel::retry)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileDetailScreen(state: ProfileDetailUiState, onBack: () -> Unit, onRetry: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text((state as? ProfileDetailUiState.Content)?.profile?.displayName.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                ProfileDetailUiState.Loading -> LoadingState()
                is ProfileDetailUiState.Error -> ErrorState(state.error, onRetry)
                is ProfileDetailUiState.Content -> ProfileBody(state.profile)
            }
        }
    }
}

@Composable
private fun ProfileBody(profile: Profile) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(profile.displayName, profile.photoUrl, size = 96.dp)
            Spacer(Modifier.width(20.dp))
            Column {
                Text(
                    "${profile.displayName}, ${profile.age}",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.testTag(TestTags.PROFILE_NAME),
                )
                profile.city?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (profile.bio.isNotBlank()) {
            Spacer(Modifier.height(24.dp))
            Text("About", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(profile.bio, style = MaterialTheme.typography.bodyLarge)
        }
        if (profile.interests.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("Interests", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            InterestChips(profile.interests)
        }
    }
}

@Composable
fun MyProfileRoute(onOpenDiagnostics: (() -> Unit)?, viewModel: MyProfileViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MyProfileScreen(
        state = state,
        onBioChange = viewModel::onBioChange,
        onInterestsChange = viewModel::onInterestsChange,
        onSave = viewModel::save,
        onSignOut = viewModel::signOut,
        onRetry = viewModel::retry,
        onOpenDiagnostics = onOpenDiagnostics,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyProfileScreen(
    state: MyProfileUiState,
    onBioChange: (String) -> Unit,
    onInterestsChange: (String) -> Unit,
    onSave: () -> Unit,
    onSignOut: () -> Unit,
    onRetry: () -> Unit,
    onOpenDiagnostics: (() -> Unit)?,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("You") },
                actions = {
                    if (onOpenDiagnostics != null) {
                        IconButton(onClick = onOpenDiagnostics) { Icon(Icons.Filled.Build, contentDescription = "Diagnostics") }
                    }
                },
            )
        },
    ) { padding ->
        val profile = state.profile
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                profile == null && state.loadError != null -> ErrorState(state.loadError, onRetry)
                profile == null -> LoadingState()
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(profile.displayName, profile.photoUrl, size = 80.dp)
                        Spacer(Modifier.width(16.dp))
                        Text(
                            "${profile.displayName}, ${profile.age}",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.testTag(TestTags.PROFILE_NAME),
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    OutlinedTextField(
                        value = state.bioDraft,
                        onValueChange = onBioChange,
                        label = { Text("About you") },
                        minLines = 3,
                        enabled = !state.saving,
                        modifier = Modifier.fillMaxWidth().testTag(TestTags.PROFILE_BIO),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.interestsDraft,
                        onValueChange = onInterestsChange,
                        label = { Text("Interests, separated by commas") },
                        enabled = !state.saving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    state.saveError?.let {
                        Text(
                            it.userMessage(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onSave,
                        enabled = state.dirty && !state.saving,
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag(TestTags.PROFILE_SAVE),
                    ) {
                        when {
                            state.saving -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            state.saved && !state.dirty -> Text("Saved")
                            else -> Text("Save")
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                    OutlinedButton(
                        onClick = onSignOut,
                        modifier = Modifier.fillMaxWidth().testTag(TestTags.PROFILE_SIGN_OUT),
                    ) { Text("Sign out") }
                }
            }
        }
    }
}
