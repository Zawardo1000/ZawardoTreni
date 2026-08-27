package it.zawardo.treni.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {

    /**
     * Quando attivo, la schermata di ricerca riapre con le stazioni dell'ultima
     * ricerca ma con **l'orario aggiornato ad adesso**: riproporre anche l'orario
     * vecchio darebbe risultati gia' passati.
     */
    val rememberLastSearch: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_REMEMBER_LAST] ?: true }

    suspend fun setRememberLastSearch(enabled: Boolean) {
        context.dataStore.edit { it[KEY_REMEMBER_LAST] = enabled }
    }

    private companion object {
        val KEY_REMEMBER_LAST = booleanPreferencesKey("remember_last_search")
    }
}
