package com.astris.silenthours

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Create a DataStore instance using a delegate
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class AppSettings(private val context: Context) {

    // A companion object to hold the key for our preference
    companion object {
        val IS_BLOCKING_ENABLED_KEY = booleanPreferencesKey("is_blocking_enabled")
    }

    /**
     * This Flow emits the current blocking status.
     * It will emit a new value whenever the setting changes.
     * It defaults to 'true' if no value has been set yet.
     */
    val isBlockingEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[IS_BLOCKING_ENABLED_KEY] ?: true
        }

    /**
     * This is the function that was missing.
     * It's a suspend function because writing to DataStore is an asynchronous operation.
     * It saves the new 'isEnabled' status to our DataStore.
     */
    suspend fun setBlockingEnabled(isEnabled: Boolean) {
        context.dataStore.edit { settings ->
            settings[IS_BLOCKING_ENABLED_KEY] = isEnabled
        }
    }
}