package it.zawardo.treni.data.remote.trenord

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Risposta di `hafas/v2`.
 *
 * Trenord non espone un'API propria: dietro c'e' **HAFAS**, il motore di
 * pianificazione viaggi usato in mezza Europa. Da qui i nomi dei campi.
 */
@Serializable
data class TrenordSearchDto(
    val solutions: List<TrenordSolutionDto> = emptyList(),
    /**
     * Il canale degli avvisi: lavori, sospensioni di linea, servizi sostitutivi.
     * E' l'unica fonte che spieghi *perche'* una tratta oggi non abbia treni.
     */
    @SerialName("hafas_alerts") val alerts: List<TrenordAlertDto> = emptyList(),
)

@Serializable
data class TrenordAlertDto(
    val severity: String? = null,
    @SerialName("title_it") val title: String? = null,
    @SerialName("message_it") val message: String? = null,
)

@Serializable
data class TrenordSolutionDto(
    /** Formato `yyyyMMdd`. */
    val date: String? = null,
    @SerialName("dep_time") val departureTime: String? = null,
    @SerialName("arr_time") val arrivalTime: String? = null,
    @SerialName("dep_station") val departureStation: TrenordStationDto? = null,
    @SerialName("arr_station") val arrivalStation: TrenordStationDto? = null,
    /** `HH:mm:ss`. */
    val duration: String? = null,
    /** Numero di cambi, come stringa. */
    val change: String? = null,
    /** Ritardo in minuti; valorizzato solo quando [delayDefined] e' vero. */
    val delay: Int? = null,
    @SerialName("delay_defined") val delayDefined: Boolean = false,
    val cancelled: Boolean = false,
    /** Giorni di scarto rispetto a [date]: le corse dopo mezzanotte hanno 1. */
    @SerialName("dep_day_offset") val departureDayOffset: Int = 0,
    @SerialName("arr_day_offset") val arrivalDayOffset: Int = 0,
    @SerialName("journey_list") val journeys: List<TrenordJourneyDto> = emptyList(),
    /**
     * I titoli di viaggio validi per questa soluzione, raggruppati per tratta.
     *
     * Trenord vende, quindi il prezzo ce l'ha: e' una lista perche' un viaggio
     * con cambio puo' richiedere piu' biglietti, uno per tratta, e perche' per
     * ciascuna offre piu' tipi — ordinario, giornaliero, e altri.
     */
    @SerialName("ticket_routes") val ticketRoutes: List<TrenordTicketRouteDto> = emptyList(),
    /** Dichiara se quella soluzione sia effettivamente acquistabile. */
    val saleability: TrenordSaleabilityDto? = null,
)

@Serializable
data class TrenordTicketRouteDto(
    @SerialName("route_index") val routeIndex: Int = 0,
    val products: List<TrenordProductDto> = emptyList(),
)

/**
 * Un titolo di viaggio.
 *
 * [type] distingue cosa si sta comprando: `ordinary` e' la corsa singola, che e'
 * l'unica confrontabile col prezzo delle altre sorgenti. `daily` e i suoi
 * fratelli sono abbonamenti giornalieri e simili: costano di piu' e valgono di
 * piu', e metterli sulla stessa riga di un biglietto di corsa semplice farebbe
 * sembrare Trenord tre volte piu' cara di quanto sia.
 */
@Serializable
data class TrenordProductDto(
    val name: String? = null,
    val type: String? = null,
    val category: String? = null,
    /** Numero, non stringa: qui il prezzo arriva come `2.2`. */
    val price: Double? = null,
    @SerialName("localized_name") val localizedName: String? = null,
)

@Serializable
data class TrenordSaleabilityDto(
    val saleable: Boolean? = null,
)

@Serializable
data class TrenordStationDto(
    /** Codice RFI, es. "S01700": e' il ponte verso il resto dell'app. */
    @SerialName("station_id") val stationId: String? = null,
    @SerialName("station_ori_name") val name: String? = null,
    @SerialName("station_externalStationNr") val hafasCode: String? = null,
)

@Serializable
data class TrenordJourneyDto(
    val train: TrenordTrainDto? = null,
    @SerialName("pass_list") val stops: List<TrenordStopDto> = emptyList(),
    @SerialName("journey_type") val journeyType: String? = null,
)

@Serializable
data class TrenordTrainDto(
    @SerialName("train_id") val id: String? = null,
    @SerialName("train_name") val name: String? = null,
    /** S5, S9, RE, REG, BUS, MXP... */
    @SerialName("train_category") val category: String? = null,
    val direction: String? = null,
    val line: String? = null,
    val delay: Int? = null,
    /** Falso quando la corsa non e' tracciata: non va spacciata per "in orario". */
    @SerialName("has_live_info") val hasLiveInfo: Boolean = false,
    @SerialName("train_operator") val operator: String? = null,
)

@Serializable
data class TrenordStopDto(
    val station: TrenordStationDto? = null,
    @SerialName("arr_time") val scheduledArrival: String? = null,
    @SerialName("dep_time") val scheduledDeparture: String? = null,
    /**
     * Dove cade questa fermata rispetto al TUO viaggio, non rispetto alla corsa.
     *
     * `O` origine della corsa, `F` fermata prima della salita o dopo la discesa,
     * `start` dove sali, `pass` fermate che percorri, `end` dove scendi.
     */
    val type: String? = null,
    val cancelled: Boolean = false,
    @SerialName("actual_data") val actual: TrenordActualDto? = null,
)

/** Dati in tempo reale della singola fermata; tutti null finche' non c'e' rilevamento. */
@Serializable
data class TrenordActualDto(
    @SerialName("arr_actual_time") val actualArrival: String? = null,
    @SerialName("dep_actual_time") val actualDeparture: String? = null,
    @SerialName("arr_estimated_time") val estimatedArrival: String? = null,
    @SerialName("dep_estimated_time") val estimatedDeparture: String? = null,
    @SerialName("arr_delay_actual") val arrivalDelay: Int? = null,
    @SerialName("dep_delay_actual") val departureDelay: Int? = null,
    /** Codice RFI dell'ultima stazione dove il treno e' stato rilevato. */
    @SerialName("actual_station_mir") val lastDetectionCode: String? = null,
    @SerialName("actual_station_name") val lastDetectionName: String? = null,
)
