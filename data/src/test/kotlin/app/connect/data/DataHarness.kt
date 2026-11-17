package app.connect.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import app.connect.data.diagnostics.RingBreadcrumbs
import app.connect.data.local.ConnectDatabase
import app.connect.data.network.ApiCaller
import app.connect.data.network.AuthApi
import app.connect.data.network.AuthInterceptor
import app.connect.data.network.ConnectApi
import app.connect.data.network.NetworkFactory
import app.connect.data.repository.AuthRepositoryImpl
import app.connect.data.repository.ChatRepositoryImpl
import app.connect.data.repository.DiscoveryRepositoryImpl
import app.connect.data.repository.IdGenerator
import app.connect.data.repository.ProfileRepositoryImpl
import app.connect.data.session.DataStoreSessionStore
import app.connect.data.session.SessionStore
import app.connect.domain.model.ConversationId
import app.connect.domain.model.Outcome
import app.connect.domain.model.Session
import app.connect.domain.model.SwipeAction
import app.connect.domain.model.UserId
import app.connect.fakebackend.FakeBackend
import app.connect.fakebackend.FakeBackendInterceptor
import app.connect.fakebackend.FakeRequest
import app.connect.fakebackend.TransportFault
import app.connect.testing.FakeClock
import app.connect.testing.RecordingAnalytics
import app.connect.testing.TestDispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import java.io.Closeable
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider

/** Serves the fake backend over a real socket, so OkHttp's connection handling and timeouts are exercised too. */
class FakeBackendDispatcher(private val backend: FakeBackend) : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
        val url = requireNotNull(request.requestUrl)
        val answer = backend.handle(
            FakeRequest(
                method = request.method.orEmpty(),
                path = url.encodedPath,
                query = url.queryParameterNames.associateWith { url.queryParameter(it).orEmpty() },
                headers = request.headers.toMap(),
                body = request.body.readUtf8(),
            ),
        )
        return when (answer.transport) {
            TransportFault.TIMEOUT -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            // Read the request, then drop the connection without answering.
            TransportFault.DISCONNECT -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            null -> MockResponse()
                .setResponseCode(answer.status)
                .setHeader("Content-Type", "application/json")
                .setBody(answer.body)
                .setHeadersDelay(answer.delayMillis, TimeUnit.MILLISECONDS)
        }
    }
}

/**
 * The production data layer, assembled by hand: real Retrofit and OkHttp
 * talking over a socket to the fake backend, real Room (in memory), real
 * DataStore. Only the clock and the id generator are replaced, so tests can
 * move time and predict ids.
 */
class DataHarness(
    val clock: FakeClock = FakeClock(now = 1_700_000_000_000),
    val backend: FakeBackend = FakeBackend(clock = { clock.nowMillis() }),
    timeouts: NetworkFactory.Timeouts = NetworkFactory.Timeouts(connectMillis = 2_000, readMillis = 1_500, callMillis = 5_000),
    sessionFile: File? = null,
    /**
     * Answer requests inside the HTTP client instead of over a socket. The tests use the socket, to
     * exercise connections and timeouts. The benchmark does not: a loopback socket adds latency of its
     * own (the mock server writes headers and body as two segments, which costs a delayed ACK of about
     * 40 ms per response) and that would be counted once per request for a sequential loader.
     */
    inProcess: Boolean = false,
) : Closeable {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nextMessageId = AtomicInteger(1)

    private val server: MockWebServer? = if (inProcess) {
        null
    } else {
        MockWebServer().apply {
            dispatcher = FakeBackendDispatcher(backend)
            start()
        }
    }
    val baseUrl: String = server?.url("/")?.toString() ?: "https://benchmark.invalid/"

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val sessionFile: File = sessionFile
        ?: File(context.cacheDir, "session-${System.nanoTime()}.preferences_pb")
    val sessions: SessionStore = DataStoreSessionStore(
        PreferenceDataStoreFactory.create(scope = dataStoreScope) { this.sessionFile },
    )

    val database: ConnectDatabase = ConnectDatabase.inMemory(context)
    val json = NetworkFactory.json()
    val breadcrumbs = RingBreadcrumbs(clock)
    val analytics = RecordingAnalytics()
    val caller = ApiCaller(json, breadcrumbs)

    private val plainClient = NetworkFactory.plainClient(
        transport = if (inProcess) setOf(FakeBackendInterceptor(backend)) else emptySet(),
        timeouts = timeouts,
    )
    val authApi: AuthApi = NetworkFactory.retrofit(baseUrl, plainClient, json).create(AuthApi::class.java)
    private val authInterceptor = AuthInterceptor(sessions, Provider { authApi }, breadcrumbs)
    val api: ConnectApi = NetworkFactory
        .retrofit(baseUrl, NetworkFactory.authedClient(plainClient, authInterceptor), json)
        .create(ConnectApi::class.java)

    val profiles = ProfileRepositoryImpl(api, database.profiles(), sessions, caller, clock)
    val discovery = DiscoveryRepositoryImpl(api, database.cache(), sessions, caller, json, clock)
    val chat = ChatRepositoryImpl(
        api, database.chat(), sessions, caller, clock,
        IdGenerator { "local-${nextMessageId.getAndIncrement()}" },
    )
    val auth = AuthRepositoryImpl(
        authApi = authApi,
        api = Provider { api },
        sessions = sessions,
        database = database,
        caller = caller,
        analytics = analytics,
        breadcrumbs = breadcrumbs,
        dispatchers = TestDispatcherProvider(Dispatchers.IO),
        scope = scope,
    )

    suspend fun signInDemo(): Session {
        sessions.load()
        val result = auth.signIn(FakeBackend.DEMO_EMAIL, FakeBackend.DEMO_PASSWORD)
        return (result as Outcome.Success).value
    }

    /** Likes the first person in the directory, who already likes every new account, and returns the new conversation. */
    suspend fun matchWithFirstPerson(): ConversationId {
        val result = discovery.swipe(UserId("u_1"), SwipeAction.LIKE)
        return checkNotNull((result as Outcome.Success).value.conversationId) { "expected a match" }
    }

    /** Ends this "process" but keeps the session file, so a second harness can restore from it. */
    override fun close() {
        server?.shutdown()
        database.close()
        scope.cancel()
        dataStoreScope.cancel()
    }
}

/** runBlocking that always returns Unit, which JUnit 4 requires of a test method. */
fun runTestBlocking(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit): Unit =
    kotlinx.coroutines.runBlocking { block() }
