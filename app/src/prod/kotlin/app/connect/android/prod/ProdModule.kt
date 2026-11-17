package app.connect.android.prod

import app.connect.android.AppStartup
import app.connect.android.BuildConfig
import app.connect.android.debug.DebugControls
import app.connect.android.debug.NoDebugControls
import app.connect.android.push.PushHandler
import app.connect.android.push.PushSource
import app.connect.data.di.ApplicationScope
import app.connect.data.di.BaseUrl
import app.connect.data.network.ApiCaller
import app.connect.data.network.ConnectApi
import app.connect.data.network.DeviceRequest
import app.connect.data.network.PushDto
import app.connect.data.session.SessionState
import app.connect.data.session.SessionStore
import app.connect.domain.diagnostics.Breadcrumbs
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ProdBackendModule {
    @Provides
    @BaseUrl
    fun baseUrl(): String = BuildConfig.API_BASE_URL

    @Provides
    fun debugControls(): DebugControls = NoDebugControls
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ProdBindingsModule {
    @Binds
    abstract fun pushSource(impl: FcmPushSource): PushSource
}

/** Registers this device's FCM token with the backend each time a session becomes active. */
@Singleton
class FcmPushSource @Inject constructor(
    private val sessions: SessionStore,
    private val api: ConnectApi,
    private val caller: ApiCaller,
    private val breadcrumbs: Breadcrumbs,
) : PushSource {
    override fun start(scope: CoroutineScope) {
        scope.launch {
            sessions.state.map { it is SessionState.Active }.distinctUntilChanged().filter { it }.collect {
                registerDevice(scope)
            }
        }
    }

    private fun registerDevice(scope: CoroutineScope) {
        val messaging = try {
            FirebaseMessaging.getInstance()
        } catch (notConfigured: IllegalStateException) {
            // Built without google-services.json. The app works; it just receives no pushes.
            breadcrumbs.leave("push", "Firebase is not configured; pushes are off")
            return
        }
        messaging.token.addOnSuccessListener { token ->
            scope.launch { caller.callEmpty("POST /v1/devices") { api.registerDevice(DeviceRequest(token)) } }
        }
    }
}

@AndroidEntryPoint
class ConnectMessagingService : FirebaseMessagingService() {
    @Inject
    lateinit var startup: AppStartup

    @Inject
    lateinit var handler: PushHandler

    @Inject
    lateinit var api: ConnectApi

    @Inject
    lateinit var caller: ApiCaller

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onMessageReceived(message: RemoteMessage) {
        startup.start()
        val push = PushDto.fromData(message.data) ?: return
        // FCM keeps the process alive only while this method runs, so the work is done inside it,
        // with a bound well under the platform's limit for a data message.
        runBlocking { withTimeoutOrNull(HANDLE_TIMEOUT_MILLIS) { handler.handle(push) } }
    }

    override fun onNewToken(token: String) {
        scope.launch { caller.callEmpty("POST /v1/devices") { api.registerDevice(DeviceRequest(token)) } }
    }

    private companion object {
        const val HANDLE_TIMEOUT_MILLIS = 8_000L
    }
}
