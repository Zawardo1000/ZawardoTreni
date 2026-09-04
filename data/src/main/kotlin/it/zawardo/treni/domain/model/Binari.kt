package it.zawardo.treni.domain.model

import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * Quanto possono discostarsi gli orari di tabella di due letture della stessa
 * fermata. ViaggiaTreno arrotonda al minuto, Trenord scrive anche i secondi:
 * Busto Arsizio Nord e' "10:39" per l'uno e "10:39:30" per l'altro. Tre minuti
 * assorbono questo e qualunque altro arrotondamento, e restano molto meno
 * dell'intervallo fra due corse diverse nella stessa stazione.
 */
private val SCARTO_AMMESSO: Duration = Duration.ofMinutes(3)

private val Stop.orarioDiTabella: LocalDateTime?
    get() = scheduledDeparture ?: scheduledArrival

/**
 * La stessa fermata della stessa corsa, non soltanto la stessa stazione.
 *
 * Il controllo sull'orario non e' pedanteria: **lo stesso numero puo' essere di
 * due treni diversi**. Il 04/09/2026 il 178 era insieme l'EuroCity delle 10:10
 * Milano Centrale - Chiasso e il regionale Trenord delle 19:46 Como Lago -
 * Milano Cadorna. Con il solo codice di stazione, il binario dell'uno sarebbe
 * finito sulla fermata dell'altro ovunque i due percorsi si sfiorino — e un
 * binario sbagliato e' peggio di un binario assente.
 *
 * Si confronta l'ora del giorno e non l'istante, perche' una corsa che scavalca
 * la mezzanotte le due fonti possono datarla in modo diverso.
 */
private fun Stop.eLaStessaFermataDi(altra: Stop): Boolean {
    val qui = orarioDiTabella ?: return false
    val la = altra.orarioDiTabella ?: return false
    val secondi = abs(Duration.between(qui.toLocalTime(), la.toLocalTime()).seconds)
    return minOf(secondi, 86_400 - secondi) <= SCARTO_AMMESSO.seconds
}

/**
 * Riempie i binari mancanti con quelli di un'altra lettura della stessa corsa.
 *
 * **Le fonti non si sovrappongono, si completano.** Misurato il 04/09/2026 sul
 * REG 2932 Milano Centrale - Gallarate: ViaggiaTreno pubblicava il binario
 * soltanto alle prime due fermate, Trenord alle otto stazioni FNM che
 * ViaggiaTreno lascia vuote. E all'inverso, sul REG 2934, ViaggiaTreno dava il
 * binario programmato a Milano Centrale mentre Trenord li' non aveva niente:
 * sulla rete RFI Trenord pubblica solo il binario vero, e solo da quando viene
 * assegnato.
 *
 * Serve anche a poter dire **"cambiato"**: quello e' un confronto fra due
 * valori, e una fonte che ne pubblichi uno solo non potrebbe mai farlo. Con le
 * due letture unite, il programmato di ViaggiaTreno e l'effettivo di Trenord
 * stanno finalmente sulla stessa fermata.
 *
 * Il ponte fra le due letture e' il **codice RFI di stazione** piu' l'orario di
 * tabella (vedi [eLaStessaFermataDi]). Non i nomi, che non coincidono:
 * ViaggiaTreno scrive "MALPENSA AEROPORTO TERMINAL 1" dove Trenord scrive
 * "MALPENSA AEROPORTO T1".
 *
 * Riempie e basta: un binario gia' noto non viene mai sostituito. Chi chiama
 * sceglie quale delle due letture sia quella buona mettendola come ricevente, e
 * questa funzione non puo' ribaltargli la scelta.
 */
fun TrainStatus.conBinariDa(altra: TrainStatus): TrainStatus {
    if (stops.none { it.scheduledPlatform == null || it.actualPlatform == null }) return this

    val altrove = altra.stops
        .filter { it.scheduledPlatform != null || it.actualPlatform != null }
        .groupBy { it.stationCode?.trim()?.uppercase().orEmpty() }
        .filterKeys { it.isNotEmpty() }
    if (altrove.isEmpty()) return this

    return copy(
        stops = stops.map { fermata ->
            if (fermata.scheduledPlatform != null && fermata.actualPlatform != null) return@map fermata
            val chiave = fermata.stationCode?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            // Piu' d'una quando la corsa ripassa dalla stessa stazione: e'
            // l'orario a dire di quale dei due passaggi si stia parlando.
            val fonte = chiave?.let { altrove[it] }
                ?.firstOrNull { fermata.eLaStessaFermataDi(it) }
                ?: return@map fermata
            fermata.copy(
                scheduledPlatform = fermata.scheduledPlatform ?: fonte.scheduledPlatform,
                actualPlatform = fermata.actualPlatform ?: fonte.actualPlatform,
            )
        },
    )
}
