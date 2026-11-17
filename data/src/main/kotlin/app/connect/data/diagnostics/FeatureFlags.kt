package app.connect.data.diagnostics

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import app.connect.domain.diagnostics.FeatureFlags
import app.connect.domain.diagnostics.Flag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Checks a flag payload before any of it is applied. A remote config with a
 * typo or a wrong type should be rejected at the door, not discovered when a
 * code path reads a flag that silently fell back to its default.
 */
object FlagPayloadValidator {
    data class Result(val accepted: Map<Flag, Boolean>, val problems: List<String>) {
        val isClean: Boolean get() = problems.isEmpty()
    }

    fun validate(payload: String): Result {
        val root = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return Result(emptyMap(), listOf("payload is not a JSON object"))

        val byKey = Flag.entries.associateBy { it.key }
        val accepted = LinkedHashMap<Flag, Boolean>()
        val problems = ArrayList<String>()
        root.forEach { (key, value) ->
            val flag = byKey[key]
            // A JSON string "true" is not a boolean; isString distinguishes the two.
            val boolean = (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            when {
                flag == null -> problems += "unknown flag '$key'"
                boolean == null -> problems += "flag '$key' must be true or false"
                else -> accepted[flag] = boolean
            }
        }
        return Result(accepted, problems)
    }
}

class DataStoreFeatureFlags(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) : FeatureFlags {
    private val defaults = Flag.entries.associateWith { it.default }

    override val values: StateFlow<Map<Flag, Boolean>> = dataStore.data
        .map { prefs -> Flag.entries.associateWith { prefs[it.preferenceKey()] ?: it.default } }
        .stateIn(scope, SharingStarted.Eagerly, defaults)

    override suspend fun override(flag: Flag, enabled: Boolean?) {
        dataStore.edit { prefs ->
            if (enabled == null) prefs.remove(flag.preferenceKey()) else prefs[flag.preferenceKey()] = enabled
        }
    }

    /** Applies a remote payload if, and only if, every entry in it is valid. Returns what was wrong otherwise. */
    suspend fun applyRemote(payload: String): FlagPayloadValidator.Result {
        val result = FlagPayloadValidator.validate(payload)
        if (result.isClean) {
            dataStore.edit { prefs -> result.accepted.forEach { (flag, value) -> prefs[flag.preferenceKey()] = value } }
        }
        return result
    }

    private fun Flag.preferenceKey() = booleanPreferencesKey("flag_$key")
}
