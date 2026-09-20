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

    /**
     * Chi fa il treno: 1 Frecce, 2 regionale Trenitalia, 4 IC, 18 Tper,
     * 63 Trenord, 64 DB-OBB. Vedi `Imprese` e `TrainStatus.impresa`: serve a non
     * chiedere a Trenord le corse che non sono sue.
     */
    val codiceCliente: Int? = null,

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

    /**
     * 0 = regolare da fare, 1 = regolare effettuata, 2 = straordinaria, 3 =
     * soppressa. Il 2 non dice se sia stata fatta: vedi `toStop`.
     */
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

/**
 * Una voce di `news/infomobility`, la versione JSON delle notizie.
 *
 * E' la strada principale per il «perche'» di un ritardo: la pagina RSS dipende
 * dalle classi del sito, questa no. Il [description] arriva con l'HTML
 * sfuggito (`&lt;p&gt;`) e le lettere accentate come entita' (`&egrave;`):
 * `InfomobilitaParser.daJson` le scioglie.
 *
 * [trainTags] sono i treni che un evento elenca **senza scriverli nel testo**:
 * il 19/09/2026 «Linea AV Roma - Firenze» ne aveva otto, e la pagina nessuno.
 */
@Serializable
data class NotiziaInfomobilitaDto(
    val title: String? = null,
    /** Quando e' stata pubblicata, in millisecondi. */
    val pubDate: Long? = null,
    val description: String? = null,
    /** I numeri dei treni coinvolti, quando l'evento li elenca. */
    val trainTags: List<String> = emptyList(),
)

/**
 * Una nota SmartCaring: il «perche'» dei regionali Trenitalia, e l'unica fonte
 * che risponda anche per i **giorni futuri**.
 *
 * Si chiede per numero e giorno. Le note portano una validita' propria
 * ([startValidity], [endValidity], in millisecondi) che puo' essere piu' larga
 * del giorno chiesto, quindi va ricontrollata in casa; e i [trains] dicono a
 * quali corse si riferisce, con l'origine — necessaria perche' lo stesso numero
 * puo' essere di due treni diversi.
 */
@Serializable
data class NotaSmartCaringDto(
    val id: Int? = null,
    /** Il testo da mostrare. */
    val infoNote: String? = null,
    val startValidity: Long? = null,
    val endValidity: Long? = null,
    val trains: List<TrenoSmartCaringDto> = emptyList(),
)

/** La corsa a cui una nota si riferisce: numero commerciale e codice d'origine. */
@Serializable
data class TrenoSmartCaringDto(
    val commercialTrainNumber: String? = null,
    val originCode: String? = null,
)

/*
 * Qui stavano `StazioneDto` e `LocalitaDto`, cioe' la forma di
 * `/elencoStazioni/{codReg}`. Sono usciti insieme all'endpoint: la loro KDoc
 * diceva «per popolare il DB offline», ma quel database lo riempie
 * `SearchStore.cache` con le stazioni di Le Frecce, e nessuno li leggeva.
 */
