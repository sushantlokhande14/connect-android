package app.connect.android.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.connect.data.diagnostics.DiagnosticsLog
import app.connect.data.diagnostics.FileCrashReporter
import app.connect.data.diagnostics.RecordedEvent
import app.connect.domain.diagnostics.Breadcrumb
import app.connect.domain.diagnostics.Breadcrumbs
import app.connect.domain.diagnostics.FeatureFlags
import app.connect.domain.diagnostics.Flag
import app.connect.domain.diagnostics.SpanRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Switches that only the demo build has, because only the demo build owns its
 * backend: change the simulated network, make the next load fail, end the
 * session. They are how the failure paths can be shown on a real device.
 */
interface DebugControls {
    val available: Boolean
    val networkProfiles: List<String>
    val networkProfile: StateFlow<String>

    fun setNetworkProfile(name: String)

    fun failNextDiscoveryLoad()

    fun expireAccessTokens()

    fun revokeSession()
}

object NoDebugControls : DebugControls {
    override val available = false
    override val networkProfiles = emptyList<String>()
    override val networkProfile: StateFlow<String> = MutableStateFlow("real network")

    override fun setNetworkProfile(name: String) = Unit

    override fun failNextDiscoveryLoad() = Unit

    override fun expireAccessTokens() = Unit

    override fun revokeSession() = Unit
}

data class DiagnosticsUiState(
    val flags: Map<Flag, Boolean> = emptyMap(),
    val spans: List<SpanRecord> = emptyList(),
    val events: List<RecordedEvent> = emptyList(),
    val breadcrumbs: List<Breadcrumb> = emptyList(),
    val crashReports: Int = 0,
    val controlsAvailable: Boolean = false,
    val networkProfiles: List<String> = emptyList(),
    val networkProfile: String = "",
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val flags: FeatureFlags,
    log: DiagnosticsLog,
    private val breadcrumbs: Breadcrumbs,
    private val crashReporter: FileCrashReporter,
    private val controls: DebugControls,
) : ViewModel() {
    val state: StateFlow<DiagnosticsUiState> =
        combine(flags.values, log.spans, log.events, controls.networkProfile) { flagValues, spans, events, profile ->
            DiagnosticsUiState(
                flags = flagValues,
                spans = spans.takeLast(20).reversed(),
                events = events.takeLast(30).reversed(),
                breadcrumbs = breadcrumbs.snapshot().takeLast(30).reversed(),
                crashReports = crashReporter.pendingReports().size,
                controlsAvailable = controls.available,
                networkProfiles = controls.networkProfiles,
                networkProfile = profile,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsUiState())

    fun setFlag(flag: Flag, enabled: Boolean) {
        viewModelScope.launch { flags.override(flag, enabled) }
    }

    fun setNetworkProfile(name: String) = controls.setNetworkProfile(name)

    fun failNextDiscoveryLoad() = controls.failNextDiscoveryLoad()

    fun expireAccessTokens() = controls.expireAccessTokens()

    fun revokeSession() = controls.revokeSession()
}

@Composable
fun DiagnosticsRoute(onBack: () -> Unit, viewModel: DiagnosticsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DiagnosticsScreen(
        state = state,
        onBack = onBack,
        onSetFlag = viewModel::setFlag,
        onSetNetworkProfile = viewModel::setNetworkProfile,
        onFailNextDiscoveryLoad = viewModel::failNextDiscoveryLoad,
        onExpireAccessTokens = viewModel::expireAccessTokens,
        onRevokeSession = viewModel::revokeSession,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    onBack: () -> Unit,
    onSetFlag: (Flag, Boolean) -> Unit,
    onSetNetworkProfile: (String) -> Unit,
    onFailNextDiscoveryLoad: () -> Unit,
    onExpireAccessTokens: () -> Unit,
    onRevokeSession: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { SectionTitle("Feature flags") }
            items(state.flags.entries.toList(), key = { it.key.key }) { (flag, enabled) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(flag.key, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = enabled, onCheckedChange = { onSetFlag(flag, it) })
                }
            }

            if (state.controlsAvailable) {
                item { SectionTitle("Simulated network") }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.networkProfiles.forEach { profile ->
                            FilterChip(
                                selected = profile == state.networkProfile,
                                onClick = { onSetNetworkProfile(profile) },
                                label = { Text(profile) },
                            )
                        }
                    }
                }
                item { SectionTitle("Failure injection") }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = onFailNextDiscoveryLoad) { Text("Fail the next discovery load") }
                        OutlinedButton(onClick = onExpireAccessTokens) { Text("Expire the access token") }
                        OutlinedButton(onClick = onRevokeSession) { Text("End the session on the server") }
                    }
                }
            }

            item { SectionTitle("Timed operations") }
            items(state.spans) { span -> Mono("${span.durationMillis} ms  ${span.name}  ${span.attributes}") }

            item { SectionTitle("Analytics events") }
            items(state.events) { event -> Mono("${event.name}  ${event.params}") }

            item { SectionTitle("Breadcrumbs") }
            items(state.breadcrumbs) { crumb -> Mono("[${crumb.category}] ${crumb.message}") }

            item { SectionTitle("Crash reports waiting on disk: ${state.crashReports}") }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
}

@Composable
private fun Mono(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
}
