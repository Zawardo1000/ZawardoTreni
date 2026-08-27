package it.zawardo.treni.data.remote.italo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Le risposte di `italoinviaggio.italotreno.com`, il sito con cui NTV pubblica
 * lo stato delle proprie corse.
 *
 * Serve perche' Italo, nelle altre tre fonti, **non esiste**: ViaggiaTreno non
 * lo pubblica (a Roma Termini e Milano Centrale non compare una riga, e
 * `cercaNumeroTreno` sull'8907 non trova nulla), il BFF Le Frecce vende
 * Trenitalia e Trenord e' il regionale lombardo. Senza questa fonte, per l'app
 * meta' dell'alta velocita' italiana semplicemente non circola.
 *
 * I nomi dei campi sono i loro: mezzi in inglese, mezzi in italiano.
 */

/** Tabellone di stazione: `RicercaStazioneService?CodiceStazione=RMT`. */
@Serializable
data class ItaloStationDto(
    @SerialName("IsEmpty") val empty: Boolean = false,
    /** Ora dell'ultimo aggiornamento, `HH:mm`. */
    @SerialName("LastUpdate") val lastUpdate: String? = null,
    @SerialName("ListaTreniArrivo") val arrivals: List<ItaloBoardTrainDto> = emptyList(),
    @SerialName("ListaTreniPartenza") val departures: List<ItaloBoardTrainDto> = emptyList(),
)

@Serializable
data class ItaloBoardTrainDto(
    /** Destinazione per le partenze, origine per gli arrivi. */
    @SerialName("DescrizioneLocalita") val direction: String? = null,
    @SerialName("Numero") val number: String? = null,
    @SerialName("Ritardo") val delay: Int = 0,
    /** Orario di tabella, `HH:mm`. */
    @SerialName("OraPassaggio") val scheduledTime: String? = null,
    /** Orario aggiornato col ritardo: uguale al precedente quando non c'e'. */
    @SerialName("NuovoOrario") val actualTime: String? = null,
    /** Vuoto finche' il binario non e' assegnato. */
    @SerialName("Binario") val platform: String? = null,
    /** Avvisi di servizio, es. "CARROZZA 1 IN TESTA AL TRENO". */
    @SerialName("Informazioni") val notice: String? = null,
)

/**
 * Risposta di `RicercaTrattaService?Departure=RMT&Arrival=NAC`: tutte le corse
 * che quel servizio sta seguendo su quella tratta, con il percorso per intero.
 *
 * E' la fonte migliore per il dettaglio di una corsa Italo: `RicercaTrenoService`
 * risponde per pochissimi treni, questa restituisce le fermate di tutti quelli
 * che conosce.
 */
@Serializable
data class ItaloRouteDto(
    @SerialName("IsEmpty") val empty: Boolean = false,
    @SerialName("LastUpdate") val lastUpdate: String? = null,
    @SerialName("TrainSchedules") val schedules: List<ItaloScheduleDto> = emptyList(),
)

/** Stato di una corsa: `RicercaTrenoService?TrainNumber=8907`. */
@Serializable
data class ItaloTrainDto(
    @SerialName("IsEmpty") val empty: Boolean = false,
    /**
     * Quando la fotografia e' stata scattata, `HH:mm`.
     *
     * Non e' un dettaglio: il servizio conserva l'ultimo stato conosciuto e lo
     * restituisce anche ore dopo. Un treno arrivato a mezzogiorno alle otto di
     * sera risponde ancora, con i dati delle otto del mattino. Va detto a chi
     * guarda, non nascosto.
     */
    @SerialName("LastUpdate") val lastUpdate: String? = null,
    @SerialName("TrainSchedule") val schedule: ItaloScheduleDto? = null,
)

@Serializable
data class ItaloScheduleDto(
    @SerialName("TrainNumber") val number: String? = null,
    @SerialName("RfiTrainNumber") val rfiNumber: String? = null,
    @SerialName("DepartureStation") val originCode: String? = null,
    @SerialName("DepartureStationDescription") val origin: String? = null,
    @SerialName("ArrivalStation") val destinationCode: String? = null,
    @SerialName("ArrivalStationDescription") val destination: String? = null,
    @SerialName("Distruption") val disruption: ItaloDisruptionDto? = null,
    @SerialName("StazionePartenza") val originStop: ItaloStopDto? = null,
    /** Fermate gia' effettuate. */
    @SerialName("StazioniFerme") val doneStops: List<ItaloStopDto> = emptyList(),
    /** Fermate ancora da fare. */
    @SerialName("StazioniNonFerme") val futureStops: List<ItaloStopDto> = emptyList(),
    /**
     * Nel percorso di tratta le fermate arrivano tutte in una lista sola, e a
     * dire dove sia arrivato il treno e' [leg].
     */
    @SerialName("Stations") val stations: List<ItaloStopDto> = emptyList(),
    @SerialName("Leg") val leg: ItaloLegDto? = null,
)

/** La tratta che il treno sta percorrendo adesso: dice fin dove e' arrivato. */
@Serializable
data class ItaloLegDto(
    @SerialName("ArrivalStation") val arrivalStation: String? = null,
    @SerialName("ArrivalStationDescription") val arrivalStationName: String? = null,
    @SerialName("ActualArrivalTime") val actualArrival: String? = null,
)

@Serializable
data class ItaloDisruptionDto(
    /** Minuti; negativo = anticipo, come nel resto dell'app. */
    @SerialName("DelayAmount") val delayMinutes: Int = 0,
    /** Dove il treno e' stato rilevato l'ultima volta, in codice Italo. */
    @SerialName("LocationCode") val locationCode: String? = null,
    @SerialName("Warning") val warning: Boolean = false,
    @SerialName("RunningState") val runningState: Int = 0,
)

@Serializable
data class ItaloStopDto(
    /** Codice Italo, es. `RMT`. Il ponte verso i codici RFI e' in [ItaloStations]. */
    @SerialName("LocationCode") val code: String? = null,
    @SerialName("LocationDescription") val name: String? = null,
    @SerialName("EstimatedArrivalTime") val scheduledArrival: String? = null,
    @SerialName("EstimatedDepartureTime") val scheduledDeparture: String? = null,
    /**
     * Sulle fermate gia' fatte e' l'orario reale; su quelle future e' una stima
     * loro. La differenza la fa il ramo in cui la fermata arriva, non il campo.
     */
    @SerialName("ActualArrivalTime") val actualArrival: String? = null,
    @SerialName("ActualDepartureTime") val actualDeparture: String? = null,
    @SerialName("ActualArrivalPlatform") val platform: String? = null,
    /** Progressivo lungo la corsa: e' l'unico ordinamento affidabile. */
    @SerialName("StationNumber") val index: Int = 0,
)
