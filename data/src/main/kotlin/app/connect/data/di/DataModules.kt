package app.connect.data.di

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.connect.data.diagnostics.AnalyticsSink
import app.connect.data.diagnostics.DataStoreFeatureFlags
import app.connect.data.diagnostics.DiagnosticsLog
import app.connect.data.diagnostics.FileCrashReporter
import app.connect.data.diagnostics.RecordingTracer
import app.connect.data.diagnostics.RingBreadcrumbs
import app.connect.data.diagnostics.ValidatingAnalytics
import app.connect.data.local.CacheDao
import app.connect.data.local.ChatDao
import app.connect.data.local.ConnectDatabase
import app.connect.data.local.ProfileDao
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
import app.connect.domain.diagnostics.Analytics
import app.connect.domain.diagnostics.Breadcrumbs
import app.connect.domain.diagnostics.CrashReporter
import app.connect.domain.diagnostics.FeatureFlags
import app.connect.domain.diagnostics.Tracer
import app.connect.domain.repository.AuthRepository
import app.connect.domain.repository.ChatRepository
import app.connect.domain.repository.DiscoveryRepository
import app.connect.domain.repository.ProfileRepository
import app.connect.domain.util.Clock
import app.connect.domain.util.DispatcherProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID
import javax.inject.Qualifier
import javax.inject.Singleton

/** Base URL of the API. Provided by the app module, which knows which build flavor is running. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BaseUrl

/** Interceptors that stand in for the network. Empty in production; the demo build adds its backend. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TransportInterceptors

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlainClient

/** A scope that lives as long as the process, for work that must outlive any screen. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SessionPrefs

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FlagPrefs

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    abstract fun auth(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    abstract fun profiles(impl: ProfileRepositoryImpl): ProfileRepository

    @Binds
    abstract fun discovery(impl: DiscoveryRepositoryImpl): DiscoveryRepository

    @Binds
    abstract fun chat(impl: ChatRepositoryImpl): ChatRepository

    @Multibinds
    @TransportInterceptors
    abstract fun transportInterceptors(): Set<Interceptor>

    @Multibinds
    abstract fun analyticsSinks(): Set<AnalyticsSink>
}

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {
    @Provides
    @Singleton
    fun dispatchers(): DispatcherProvider = object : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Main.immediate
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
    }

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Wall clock: values are stored and compared across process restarts, so it cannot be a monotonic clock. */
    @Provides
    @Singleton
    fun clock(): Clock = Clock { System.currentTimeMillis() }

    @Provides
    @Singleton
    fun idGenerator(): IdGenerator = IdGenerator { UUID.randomUUID().toString() }
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun json(): Json = NetworkFactory.json()

    @Provides
    @Singleton
    @PlainClient
    fun plainClient(
        @TransportInterceptors transport: Set<@JvmSuppressWildcards Interceptor>,
    ): OkHttpClient = NetworkFactory.plainClient(transport = transport, logging = Log.isLoggable("ConnectHttp", Log.DEBUG))

    @Provides
    @Singleton
    fun authedClient(@PlainClient plain: OkHttpClient, auth: AuthInterceptor): OkHttpClient =
        NetworkFactory.authedClient(plain, auth)

    @Provides
    @Singleton
    fun authApi(@BaseUrl baseUrl: String, @PlainClient client: OkHttpClient, json: Json): AuthApi =
        NetworkFactory.retrofit(baseUrl, client, json).create(AuthApi::class.java)

    @Provides
    @Singleton
    fun connectApi(@BaseUrl baseUrl: String, client: OkHttpClient, json: Json): ConnectApi =
        NetworkFactory.retrofit(baseUrl, client, json).create(ConnectApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): ConnectDatabase = ConnectDatabase.persistent(context)

    @Provides
    fun profileDao(database: ConnectDatabase): ProfileDao = database.profiles()

    @Provides
    fun chatDao(database: ConnectDatabase): ChatDao = database.chat()

    @Provides
    fun cacheDao(database: ConnectDatabase): CacheDao = database.cache()

    @Provides
    @Singleton
    @SessionPrefs
    fun sessionPrefs(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("session") }

    @Provides
    @Singleton
    @FlagPrefs
    fun flagPrefs(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("flags") }

    @Provides
    @Singleton
    fun sessionStore(@SessionPrefs dataStore: DataStore<Preferences>): SessionStore = DataStoreSessionStore(dataStore)
}

@Module
@InstallIn(SingletonComponent::class)
object DiagnosticsModule {
    @Provides
    @Singleton
    fun breadcrumbs(clock: Clock): Breadcrumbs = RingBreadcrumbs(clock)

    @Provides
    @Singleton
    fun diagnosticsLog(): DiagnosticsLog = DiagnosticsLog()

    @Provides
    @IntoSet
    fun logSink(log: DiagnosticsLog): AnalyticsSink = log

    @Provides
    @ElementsIntoSet
    fun logcatSink(): Set<AnalyticsSink> =
        if (Log.isLoggable("ConnectAnalytics", Log.DEBUG)) {
            setOf(AnalyticsSink { name, params -> Log.d("ConnectAnalytics", "$name $params") })
        } else {
            emptySet()
        }

    @Provides
    @Singleton
    fun analytics(sinks: Set<@JvmSuppressWildcards AnalyticsSink>, breadcrumbs: Breadcrumbs): Analytics =
        ValidatingAnalytics(sinks, breadcrumbs)

    /** Durations use the monotonic clock, so a wall-clock adjustment mid-load cannot produce a negative span. */
    @Provides
    @Singleton
    fun tracer(log: DiagnosticsLog, breadcrumbs: Breadcrumbs): Tracer =
        RecordingTracer(Clock { SystemClock.elapsedRealtime() }, log, breadcrumbs)

    @Provides
    @Singleton
    fun crashReporter(
        @ApplicationContext context: Context,
        breadcrumbs: Breadcrumbs,
        clock: Clock,
    ): FileCrashReporter = FileCrashReporter(File(context.filesDir, "crash-reports"), breadcrumbs, clock)

    @Provides
    fun crashReporterPort(reporter: FileCrashReporter): CrashReporter = reporter

    @Provides
    @Singleton
    fun dataStoreFlags(
        @FlagPrefs dataStore: DataStore<Preferences>,
        @ApplicationScope scope: CoroutineScope,
    ): DataStoreFeatureFlags = DataStoreFeatureFlags(dataStore, scope)

    @Provides
    fun featureFlags(flags: DataStoreFeatureFlags): FeatureFlags = flags
}
