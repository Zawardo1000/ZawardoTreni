package it.zawardo.treni.data.remote.viaggiatreno

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Risposta di `/andamentoTreno/{codOrigine}/{numeroTreno}/{millisDataPartenza}`.
 *
 * Attenzione: l'endpoint risponde **204 No Content** per qualunque giorno diverso da
 * quello corrente. Il realtime esiste solo per la giornata in corso.
 */
@Serializable
data class AndamentoTrenoDto(
    val numeroTreno: Int = 0,
    val categoria: String? = null,
    val compNumeroTreno: String? = null,
    val origine: String? = null,
    val destinazione: String? = null,
    val idOrigine: String? = null,
    val idDestinazione: String? = null,

    /** Ritardo corrente in minuti; negativo = anticipo. */
    val ritardo: Int = 0,

    val circolante: Boolean = false,
    val nonPartito: Boolean = false,
    val arrivato: Boolean = false,
    val inStazione: Boolean = false,

    /** 0 = regolare, 1 = soppresso, 2 = variato/deviato. */
    val provvedimento: Int = 0,

    /** PG = regolare, ST = soppresso totalmente, PP = soppresso parzialmente. */
    val tipoTreno: String? = null,

    /** Testo libero con eventuali avvisi (deviazioni, sostituzioni bus...). */
    val subTitle: String? = null,

    /** "--" quando il treno non e' ancora stato rilevato. */
    val stazioneUltimoRilevamento: String? = null,
    val oraUltimoRilevamento: Long? = null,
    val compOraUltimoRilevamento: String? = null,

    val orarioPartenza: Long? = null,
    val orarioArrivo: Long? = null,
    val compOrarioPartenza: String? = null,
    val compOrarioArrivo: String? = null,
    val compDurata: String? = null,

    val fermate: List<FermataDto> = emptyList(),
    val fermateSoppresse: List<FermataDto> = emptyList(),
)

@Serializable
data class FermataDto(
    val stazione: String? = null,
    val id: String? = null,
    val progressivo: Int = 0,

    /** P = partenza (capolinea), F = fermata intermedia, A = arrivo (capolinea). */
    val tipoFermata: String? = null,

    /** 0 = futura, 1 = effettuata, 2 = in corso, 3 = soppressa. */
    val actualFermataType: Int = 0,

    // --- arrivo: snake_case nel payload originale ---
    @SerialName("arrivo_teorico") val arrivoTeorico: Long? = null,
    val arrivoReale: Long? = null,
    val ritardoArrivo: Int = 0,

    // --- partenza ---
    @SerialName("partenza_teorica") val partenzaTeorica: Long? = null,
    val partenzaReale: Long? = null,
    val ritardoPartenza: Int = 0,

    val binarioProgrammatoArrivoDescrizione: String? = null,
    val binarioEffettivoArrivoDescrizione: String? = null,
    val binarioProgrammatoPartenzaDescrizione: String? = null,
    val binarioEffettivoPartenzaDescrizione: String? = null,
)

/** Elemento dei tabelloni `/partenze/{id}/{data}` e `/arrivi/{id}/{data}`. */
@Serializable
data class TabelloneVoceDto(
    val numeroTreno: Int = 0,
    val categoria: String? = null,
    val compNumeroTreno: String? = null,
    val origine: String? = null,
    val destinazione: String? = null,
    val codOrigine: String? = null,
    val dataPartenzaTreno: Long? = null,
    val ritardo: Int = 0,
    val provvedimento: Int = 0,
    val circolante: Boolean = false,
    val nonPartito: Boolean = false,
    val inStazione: Boolean = false,
    val compOrarioPartenza: String? = null,
    val compOrarioArrivo: String? = null,
    val binarioProgrammatoPartenzaDescrizione: String? = null,
    val binarioEffettivoPartenzaDescrizione: String? = null,
    val binarioProgrammatoArrivoDescrizione: String? = null,
    val binarioEffettivoArrivoDescrizione: String? = null,
)

/** Elemento di `/elencoStazioni/{codReg}`, usato per popolare il DB offline. */
@Serializable
data class StazioneDto(
    val codStazione: String? = null,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val codReg: Int = 0,
    /** 1 = principale, 3 = fermata minore, 4 = non presenziata. */
    val tipoStazione: Int = 0,
    val localita: LocalitaDto? = null,
)

@Serializable
data class LocalitaDto(
    val nomeLungo: String? = null,
    val nomeBreve: String? = null,
    val id: String? = null,
)
