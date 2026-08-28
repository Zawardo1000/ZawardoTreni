package it.zawardo.treni.data.repository

import it.zawardo.treni.domain.model.Station

/**
 * Una rete con stazioni proprie, interrogabile in locale per i suggerimenti.
 *
 * La implementano i repository fuori-RFI — EAV, Ferrotramviaria, le svizzere,
 * ARST — che tengono un elenco di fermate loro e rispondono all'istante, senza
 * toccare la rete. Serve a interrogarli **senza sapere quale sia**: il registro
 * in ServiceLocator mappa ogni [it.zawardo.treni.domain.model.DataSource] con
 * `stazioniProprie` alla sua ricerca, e chi propone i suggerimenti scorre il
 * registro invece di elencare le reti a mano.
 */
interface FonteStazioniLocale {
    /** Le stazioni proprie che corrispondono a [query]. */
    fun suggerisci(query: String): List<Station>
}
