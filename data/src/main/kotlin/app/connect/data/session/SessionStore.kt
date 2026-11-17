package app.connect.data.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.connect.domain.model.AuthTokens
import app.connect.domain.model.Session
import app.connect.domain.model.SignOutReason
import app.connect.domain.model.UserId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SessionState {
    /** Disk has not been read yet. */
    data object NotLoaded : SessionState

    data class None(val reason: SignOutReason? = null) : SessionState

    data class Active(val session: Session) : SessionState
}

/**
 * The single owner of the session. Holds it in memory for the interceptor,
 * which runs on OkHttp threads and cannot suspend, and persists it so a
 * restarted process is still signed in.
 */
interface SessionStore {
    val state: StateFlow<SessionState>

    fun current(): Session? = (state.value as? SessionState.Active)?.session

    suspend fun load()

    suspend fun save(session: Session)

    suspend fun clear(reason: SignOutReason?)
}

class DataStoreSessionStore(private val dataStore: DataStore<Preferences>) : SessionStore {
    private val mutable = MutableStateFlow<SessionState>(SessionState.NotLoaded)
    override val state: StateFlow<SessionState> = mutable
    private val loadLock = Mutex()

    override suspend fun load() {
        loadLock.withLock {
            if (mutable.value != SessionState.NotLoaded) return
            val prefs = dataStore.data.first()
            val userId = prefs[USER_ID]
            val access = prefs[ACCESS]
            val refresh = prefs[REFRESH]
            mutable.value = if (userId != null && access != null && refresh != null) {
                SessionState.Active(Session(UserId(userId), AuthTokens(access, refresh, prefs[EXPIRES_AT] ?: 0L)))
            } else {
                SessionState.None()
            }
        }
    }

    override suspend fun save(session: Session) {
        // Memory first: a request racing this call must already see the new token.
        mutable.value = SessionState.Active(session)
        dataStore.edit { prefs ->
            prefs[USER_ID] = session.userId.value
            prefs[ACCESS] = session.tokens.accessToken
            prefs[REFRESH] = session.tokens.refreshToken
            prefs[EXPIRES_AT] = session.tokens.expiresAtMillis
        }
    }

    override suspend fun clear(reason: SignOutReason?) {
        mutable.value = SessionState.None(reason)
        dataStore.edit { prefs ->
            prefs.remove(USER_ID)
            prefs.remove(ACCESS)
            prefs.remove(REFRESH)
            prefs.remove(EXPIRES_AT)
        }
    }

    private companion object {
        val USER_ID = stringPreferencesKey("session_user_id")
        val ACCESS = stringPreferencesKey("session_access_token")
        val REFRESH = stringPreferencesKey("session_refresh_token")
        val EXPIRES_AT = longPreferencesKey("session_expires_at")
    }
}
