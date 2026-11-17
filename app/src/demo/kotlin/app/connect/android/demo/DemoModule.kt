package app.connect.android.demo

import app.connect.android.debug.DebugControls
import app.connect.android.push.PushHandler
import app.connect.android.push.PushSource
import app.connect.data.di.BaseUrl
import app.connect.data.di.TransportInterceptors
import app.connect.data.network.ApiCaller
import app.connect.data.network.ConnectApi
import app.connect.data.session.SessionState
import app.connect.data.session.SessionStore
import app.connect.domain.diagnostics.FeatureFlags
import app.connect.domain.diagnostics.Flag
import app.connect.domain.model.Outcome
import app.connect.fakebackend.FakeBackend
import app.connect.fakebackend.FakeBackendInterceptor
import app.connect.fakebackend.Fault
import app.connect.fakebackend.NetworkProfile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The demo build is the whole product with nothing to set up: no server, no
 * Firebase project. The backend runs inside the app and is plugged in at the
 * very bottom of the HTTP client, so every layer above it is the production
 * code path.
 */
@Module
@InstallIn(SingletonComponent::class)
object DemoBackendModule {
    @Provides
    @Singleton
    fun backend(): FakeBackend = FakeBackend(network = NetworkProfile.WIFI)

    @Provides
    @IntoSet
    @TransportInterceptors
    fun transport(backend: FakeBackend): Interceptor = FakeBackendInterceptor(backend)

    /** Never resolved, because the interceptor answers first. ".invalid" is reserved, so a wiring mistake cannot reach a real host. */
    @Provides
    @BaseUrl
    fun baseUrl(): String = "https://demo.connect.invalid/"
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DemoBindingsModule {
    @Binds
    abstract fun pushSource(impl: DemoPushPoller): PushSource

    @Binds
    abstract fun debugControls(impl: DemoDebugControls): DebugControls
}

/**
 * Stands in for Firebase Cloud Messaging. The backend queues what it would
 * have pushed; this drains the queue while a session is active and hands each
 * item to the same PushHandler that FCM feeds in the prod build.
 */
@Singleton
class DemoPushPoller @Inject constructor(
    private val api: ConnectApi,
    private val caller: ApiCaller,
    private val sessions: SessionStore,
    private val handler: PushHandler,
    private val flags: FeatureFlags,
    private val backend: FakeBackend,
) : PushSource {
    override fun start(scope: CoroutineScope) {
        scope.launch {
            flags.values.collect { backend.simulatePeerReplies = it[Flag.SIMULATED_PEER_REPLIES] ?: true }
        }
        scope.launch {
            sessions.state.map { it is SessionState.Active }.distinctUntilChanged().collectLatest { active ->
                while (active) {
                    delay(POLL_INTERVAL_MILLIS)
                    val result = caller.call("GET /v1/me/pushes") { api.pushes() }
                    if (result is Outcome.Success) result.value.pushes.forEach { handler.handle(it) }
                }
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 1_500L
    }
}

@Singleton
class DemoDebugControls @Inject constructor(private val backend: FakeBackend) : DebugControls {
    override val available = true
    override val networkProfiles: List<String> = (listOf(NetworkProfile.INSTANT) + NetworkProfile.ALL).map { it.name }

    private val profile = MutableStateFlow(backend.network.name)
    override val networkProfile: StateFlow<String> = profile

    override fun setNetworkProfile(name: String) {
        backend.network = NetworkProfile.byName(name)
        profile.value = name
    }

    override fun failNextDiscoveryLoad() = backend.faults.fail("GET /v1/discovery", Fault.Http(503))

    override fun expireAccessTokens() = backend.expireAccessTokens()

    override fun revokeSession() {
        backend.expireAccessTokens()
        backend.revokeRefreshTokens()
    }
}
