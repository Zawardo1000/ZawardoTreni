package it.zawardo.treni.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import it.zawardo.treni.domain.model.DataSource
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

    /**
     * "Solo diretti": scarta le soluzioni con cambi.
     *
     * Spento di default, perche' su molte tratte i diretti non esistono e una
     * lista vuota sarebbe peggio di una lista con cambi. Ma se acceso resta
     * acceso: chi non vuole cambiare treno non lo vuole una volta sola.
     */
    val directOnly: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_DIRECT_ONLY] ?: false }

    suspend fun setDirectOnly(enabled: Boolean) {
        context.dataStore.edit { it[KEY_DIRECT_ONLY] = enabled }
    }

    /**
     * Le reti accese.
     *
     * Si salvano i nomi, non un booleano per rete: cosi' aggiungerne una nuova
     * non impone una migrazione del database delle impostazioni. Chi non ha mai
     * toccato la schermata ha `null`, e vale il default; una rete diventata
     * indisponibile viene ignorata in lettura, senza sparire dai salvati di chi
     * la riavra' un giorno.
     */
    val enabledSources: Flow<Set<DataSource>> =
        context.dataStore.data.map { prefs ->
            val salvati = prefs[KEY_SOURCES] ?: return@map DataSource.defaultEnabled
            salvati
                .mapNotNull { runCatching { DataSource.valueOf(it) }.getOrNull() }
                .filterTo(HashSet()) { it.available }
        }

    suspend fun setSourceEnabled(source: DataSource, enabled: Boolean) {
        // Una rete non ancora collegata non si accende: non c'e' cosa accendere.
        if (!source.available) return
        context.dataStore.edit { prefs ->
            val correnti = prefs[KEY_SOURCES]
                ?.mapNotNullTo(HashSet()) { runCatching { DataSource.valueOf(it) }.getOrNull() }
                ?: DataSource.defaultEnabled.toHashSet()
            if (enabled) correnti.add(source) else correnti.remove(source)
            prefs[KEY_SOURCES] = correnti.mapTo(HashSet()) { it.name }
        }
    }

    private companion object {
        val KEY_REMEMBER_LAST = booleanPreferencesKey("remember_last_search")
        val KEY_DIRECT_ONLY = booleanPreferencesKey("direct_only")
        val KEY_SOURCES = stringSetPreferencesKey("enabled_sources")
    }
}
