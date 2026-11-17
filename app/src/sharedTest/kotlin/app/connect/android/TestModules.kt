package app.connect.android

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.connect.android.push.Notifier
import app.connect.android.push.NotifierModule
import app.connect.data.di.FlagPrefs
import app.connect.data.di.SessionPrefs
import app.connect.data.di.StorageModule
import app.connect.data.local.CacheDao
import app.connect.data.local.ChatDao
import app.connect.data.local.ConnectDatabase
import app.connect.data.local.ProfileDao
import app.connect.data.session.DataStoreSessionStore
import app.connect.data.session.SessionStore
import app.connect.domain.model.PushPayload
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Storage that belongs to one test: an in-memory database and preference
 * files with unique names. Without this, tests would share the session on
 * disk, and two graphs in one process would open the same DataStore file,
 * which DataStore refuses.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [StorageModule::class])
object TestStorageModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): ConnectDatabase = ConnectDatabase.inMemory(context)

    @Provides
    fun profileDao(database: ConnectDatabase): ProfileDao = database.profiles()

    @Provides
    fun chatDao(database: ConnectDatabase): ChatDao = database.chat()

    @Provides
    fun cacheDao(database: ConnectDatabase): CacheDao = database.cache()

    @Provides
    @Singleton
    @SessionPrefs
    fun sessionPrefs(@ApplicationContext context: Context): DataStore<Preferences> = uniqueStore(context, "session")

    @Provides
    @Singleton
    @FlagPrefs
    fun flagPrefs(@ApplicationContext context: Context): DataStore<Preferences> = uniqueStore(context, "flags")

    @Provides
    @Singleton
    fun sessionStore(@SessionPrefs dataStore: DataStore<Preferences>): SessionStore = DataStoreSessionStore(dataStore)

    private fun uniqueStore(context: Context, name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { File(context.cacheDir, "$name-${UUID.randomUUID()}.preferences_pb") }
}

/** Records what would have been shown as a system notification. */
@Singleton
class RecordingNotifier @Inject constructor() : Notifier {
    val messages = CopyOnWriteArrayList<PushPayload.NewMessage>()
    val matches = CopyOnWriteArrayList<PushPayload.NewMatch>()

    override fun createChannels() = Unit

    override fun showMessage(payload: PushPayload.NewMessage) {
        messages += payload
    }

    override fun showMatch(payload: PushPayload.NewMatch) {
        matches += payload
    }
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [NotifierModule::class])
abstract class TestNotifierModule {
    @Binds
    abstract fun notifier(impl: RecordingNotifier): Notifier
}
