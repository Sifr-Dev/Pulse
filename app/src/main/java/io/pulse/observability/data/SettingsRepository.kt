package io.pulse.observability.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepository(private val store: DataStore<Preferences>) {
    val lastTarget: Flow<String> = store.data.map { preferences -> preferences[LAST_TARGET].orEmpty() }

    suspend fun saveLastTarget(target: String) {
        store.edit { preferences -> preferences[LAST_TARGET] = target }
    }

    private companion object {
        val LAST_TARGET = stringPreferencesKey("last_target")
    }
}
