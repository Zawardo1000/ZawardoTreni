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
     * "Soluzioni con piu' operatori (beta)": i viaggi misti.
     *
     * Spento di default, e a ragione. Aggiungono un cambio di rete che nessuna
     * fonte da sola conosce — EAV fino a Napoli, poi Italo — ma poggiano sulla
     * gamba Italo, che su date future puo' essere incompleta e di cui il prezzo
     * non si sa. E' un di piu' per chi ne ha bisogno, non il comportamento
     * normale della ricerca: chi lo accende sa cosa aspettarsi.
     */
    val viaggiMisti: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_VIAGGI_MISTI] ?: false }

    suspend fun setViaggiMisti(enabled: Boolean) {
        context.dataStore.edit { it[KEY_VIAGGI_MISTI] = enabled }
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
            val scelte = salvati
                .mapNotNull { runCatching { DataSource.valueOf(it) }.getOrNull() }
                .filterTo(HashSet()) { it.available }
            /*
             * La rete nazionale si aggiunge sempre, qualunque cosa sia salvata.
             *
             * Non e' ridondante: chi usa l'app da prima che questa distinzione
             * esistesse puo' avere Trenitalia spenta nelle sue preferenze, e
             * senza questo innesto si troverebbe l'app quasi muta dopo un
             * aggiornamento, senza un interruttore per rimediare — perche' quel
             * interruttore non c'e' piu'.
             */
            scelte + DataSource.sempreAttive
        }

    suspend fun setSourceEnabled(source: DataSource, enabled: Boolean) {
        // Una rete non ancora collegata non si accende: non c'e' cosa accendere.
        if (!source.available) return
        // E la rete nazionale non si spegne: vedi DataSource.opzionale.
        if (!source.opzionale) return
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
        val KEY_VIAGGI_MISTI = booleanPreferencesKey("viaggi_misti")
        val KEY_SOURCES = stringSetPreferencesKey("enabled_sources")
    }
}
