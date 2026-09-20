package it.zawardo.treni.data.remote.fnb

import kotlinx.serialization.Serializable

/**
 * Le risposte del portale Ferrotramviaria.
 *
 * I nomi dei campi sono i loro, in italiano, e si tengono cosi': tradurli
 * renderebbe piu' difficile confrontare questo file con la risposta vera quando
 * qualcosa non torna.
 *
 * Quasi tutto e' opzionale, e non per prudenza: il portale **omette** i campi
 * invece di mandarli vuoti. Una corsa senza binario assegnato non ha
 * `binarioEffettivo`, una corsa non ancora monitorata non ha `ritardo`. Un
 * `ritardo` assente non significa "in orario": significa "non si sa".
 */

/** Una fermata del registro: `realtime/siti/T`. */
@Serializable
data class FnbSitoDto(
    /** Codice nativo del portale, nella forma `S01110`. */
    val codSito: String? = null,
    /** `FTV` per Ferrotramviaria, `FAL` per Ferrovie Appulo Lucane. */
    val gestore: String? = null,
    val nome: String? = null,
)

/**
 * Il tabellone di una fermata: `realtime/dati?codSito=…`.
 *
 * Arrivi e partenze arrivano insieme, il che risparmia una chiamata rispetto a
 * tutte le altre sorgenti.
 */
@Serializable
data class FnbBoardDto(
    val arrivi: List<FnbCorsaDto> = emptyList(),
    val partenze: List<FnbCorsaDto> = emptyList(),
)

/** Una corsa in tabellone. */
@Serializable
data class FnbCorsaDto(
    val numero: String? = null,
    /** Il binario vero. Assente finche' non e' assegnato. */
    val binarioEffettivo: String? = null,
    /**
     * Il nome inganna: e' la destinazione fra le partenze, ma l'**origine**
     * fra gli arrivi.
     *
     * Verificato su Andria Centrale: il treno 21 compare fra gli arrivi con
     * `Barletta Centrale` e fra le partenze, allo stesso minuto, con
     * `Andria Sud`. Fa Barletta - Andria Sud e ad Andria Centrale ci passa in
     * mezzo. Preso alla lettera, un tabellone degli arrivi direbbe a chi
     * aspetta che il treno viene da dove invece sta andando.
     */
    val nomeDestinazione: String? = null,
    /** `FTV` o `FAL`. */
    val gestore: String? = null,
    /**
     * Che mezzo fa la corsa: `T` treno, `B` autobus, `S` treno e bus insieme.
     *
     * `S` non e' un dettaglio da nascondere. La Andria - Barletta e' da anni in
     * lavori, e su quella relazione la corsa e' treno fino a un certo punto e
     * bus oltre. Mostrarla come un treno qualunque significa far aspettare un
     * treno a chi dovra' salire su un pullman.
     */
    val servizio: String? = null,
    /** `yyyyMMddHHmmss`, valorizzato fra le partenze. */
    val partenza: String? = null,
    /** `yyyyMMddHHmmss`, valorizzato fra gli arrivi. */
    val arrivo: String? = null,
    /** Minuti. Assente quando la corsa non e' ancora monitorata. */
    val ritardo: Int? = null,
    /** `Y` oppure `N`. */
    val soppressa: String? = null,
    /** Affollamento, quando il portale lo pubblica. Non usato. */
    val occupazione: String? = null,
)

/**
 * Una soluzione di `cerca/soluzioni/`: la ricerca A→B, che il tabellone non sa
 * fare e che per questa rete e' l'unico orario esistente — anche per i giorni
 * futuri, fino alla fine dell'orario.
 */
@Serializable
data class FnbSoluzioneDto(
    /**
     * L'id con cui chiederne le fermate. E' negativo ed **effimero**: cambia a
     * ogni ricerca, e vale solo nella sessione che l'ha prodotto.
     */
    val idSoluzione: Long? = null,
    /** `yyyyMMddHHmmss`. */
    val timeP: String? = null,
    val timeA: String? = null,
    val nomeP: String? = null,
    val nomeA: String? = null,
    /** I mezzi, con la sigla: `["ET 91008", "AS 8"]`. */
    val elencoCorse: List<String> = emptyList(),
    val cambi: Int = 0,
    val conBus: Boolean = false,
    /** Vero quando un tratto e' fatto in bus al posto del treno. */
    val conServizioSostitutivo: Boolean = false,
    /** Centesimi di euro: 620 sono 6,20. */
    val prezzo: Int? = null,
    val durataSecondi: Int? = null,
)

/**
 * Il dettaglio di una soluzione: `soluzioni/id/{id}`.
 *
 * **Vuole la sessione** della ricerca che l'ha prodotta: senza il cookie
 * risponde `{}` con 200, che e' indistinguibile da una soluzione senza tratte.
 * Vedi `FnbApi.apriSessione`.
 */
@Serializable
data class FnbDettaglioDto(
    val idSoluzione: Long? = null,
    val tratte: List<FnbTrattaDto> = emptyList(),
    /** Centesimi, come nella ricerca. */
    val prezzo: Int? = null,
)

/** Un mezzo dentro una soluzione, con le sue fermate. */
@Serializable
data class FnbTrattaDto(
    /** Numero con la sigla: `ET 91008`, `AS 8`. */
    val numero: String? = null,
    /** `T` treno, `B` bus, `S` autoservizio sostitutivo. */
    val servizio: String? = null,
    val gestore: String? = null,
    val timePartenza: String? = null,
    val timeArrivo: String? = null,
    val sitoPartenza: FnbSitoRifDto? = null,
    val sitoArrivo: FnbSitoRifDto? = null,
    val fermate: List<FnbFermataDto> = emptyList(),
)

/** Una fermata dentro una tratta. */
@Serializable
data class FnbFermataDto(
    val ordine: Int? = null,
    val sito: FnbSitoRifDto? = null,
    /** Assente alla prima fermata. */
    val timeArrivo: String? = null,
    /** Assente all'ultima. */
    val timePartenza: String? = null,
    /** `Y` quando l'orario e' indicativo. */
    val orarioIndicativo: String? = null,
    /** `Y` quando la fermata e' a richiesta. */
    val facoltativa: String? = null,
)

/** La fermata come la scrive il dettaglio: col codice nativo e le coordinate vere. */
@Serializable
data class FnbSitoRifDto(
    val codSito: String? = null,
    val nome: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
)

