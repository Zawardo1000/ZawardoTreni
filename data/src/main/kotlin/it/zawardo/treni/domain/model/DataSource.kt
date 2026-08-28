package it.zawardo.treni.domain.model

/**
 * Le reti che l'app interroga, come le vede chi cerca un treno.
 *
 * Non e' un dettaglio tecnico: le fonti sono diventate tante, e ognuna e' una o
 * piu' chiamate di rete a ogni ricerca. Chi non esce mai dalla Lombardia non ha
 * bisogno che si interroghi Italo; chi prende solo l'alta velocita' non ha
 * bisogno di Trenord. Spegnere quello che non serve rende la ricerca piu' veloce.
 *
 * [available] falso e' una rete annunciata ma non ancora collegata: compare in
 * elenco perche' la si aspetta, ma non si puo' accendere finche' non risponde.
 */
enum class DataSource(val label: String, val detail: String, val available: Boolean) {
    /** Le Frecce per gli itinerari, ViaggiaTreno per il tempo reale. */
    TRENITALIA("Trenitalia", "Rete nazionale, Frecce e Intercity", available = true),
    TRENORD("Trenord", "Regionale lombardo e linee S del Passante", available = true),
    ITALO("Italo", "Alta velocita' NTV", available = true),
    EAV("EAV", "Circumvesuviana e rete campana", available = false);

    companion object {
        /**
         * Le reti accese quando non si e' ancora scelto nulla: tutte quelle
         * pronte. Le future nascono spente, e si accendono da qui quando ci sono.
         */
        val defaultEnabled: Set<DataSource> get() = entries.filterTo(HashSet()) { it.available }
    }
}
