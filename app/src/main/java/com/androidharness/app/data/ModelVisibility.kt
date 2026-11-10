package com.androidharness.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Missing provider = the original, unfiltered catalog. An empty set deliberately hides every model. */
@Serializable
data class ModelVisibilityState(val selected: Map<String, Set<String>> = emptyMap()) {
    fun includes(providerId: String, modelId: String): Boolean = selected[providerId]?.contains(modelId) ?: true

    fun selection(providerId: String, available: Collection<String>): Set<String> =
        selected[providerId] ?: available.toSet()

    fun save(providerId: String, models: Set<String>) = copy(selected = selected + (providerId to models))

    fun without(providerId: String) = copy(selected = selected - providerId)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun decode(raw: String?): ModelVisibilityState = raw?.let {
            runCatching { json.decodeFromString<ModelVisibilityState>(it) }.getOrNull()
        } ?: ModelVisibilityState()
        fun encode(value: ModelVisibilityState): String = json.encodeToString(value)
    }
}

/** Device-local presentation preferences, like favourites. They never change a chat's saved selection. */
class ModelVisibility(context: Context, name: String = "model-visibility") {
    private val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(ModelVisibilityState.decode(preferences.getString("state", null)))
    val state: StateFlow<ModelVisibilityState> = mutable

    @Synchronized fun save(providerId: String, selected: Set<String>) = update(mutable.value.save(providerId, selected))
    @Synchronized fun remove(providerId: String) = update(mutable.value.without(providerId))

    private fun update(value: ModelVisibilityState) {
        preferences.edit().putString("state", ModelVisibilityState.encode(value)).apply()
        mutable.value = value
    }

    companion object {
        @Volatile private var instance: ModelVisibility? = null
        fun get(context: Context): ModelVisibility = instance ?: synchronized(this) {
            instance ?: ModelVisibility(context).also { instance = it }
        }
    }
}
