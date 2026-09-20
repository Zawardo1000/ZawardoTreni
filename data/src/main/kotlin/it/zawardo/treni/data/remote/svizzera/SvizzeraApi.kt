package it.zawardo.treni.data.remote.svizzera

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * La Vigezzina - Svizzera, Domodossola - Locarno, attraverso il sistema
 * svizzero.
 *
 * La linea e' a scartamento metrico e la esercitano in due: SSIF sul versante
 * italiano, FART su quello svizzero. Non e' rete RFI, quindi ViaggiaTreno non
 * la conosce; ma **e' dentro l'orario svizzero per intero**, stazioni italiane
 * comprese. Malesco, Druogno, Re, Santa Maria Maggiore e Trontano hanno un id
 * svizzero e un tabellone come Locarno.
 *
 * Ed e' il motivo per cui questa linea, a differenza di tutte le altre reti
 * regionali italiane, si integra senza reverse engineering: la si chiede
 * all'orario di un altro paese, che la pubblica per bene.
 *
 * ## Su quale endpoint, e perche' questo
 *
 * `transport.opendata.ch` si dichiara **non ufficiale**, e non e' un dettaglio
 * da nascondere. La fonte istituzionale e' `opentransportdata.swiss`, che
 * pubblica GTFS-RT e OJP; ma vuole una chiave, e una chiave in un'app open
 * source distribuita su Play e' una chiave pubblicata. Qui non serve nulla, la
 * risposta e' JSON semplice, e il rischio e' lo stesso che l'app gia' corre con
 * ViaggiaTreno, Trenord e Italo: un servizio non documentato che puo' cambiare
 * senza preavviso.
 *
 * Se un giorno smettesse di rispondere, la strada e' quella ufficiale con una
 * chiave configurabile, non un ripiego su pagine da grattare.
 *
 * ## Quello che questo endpoint non fa
 *
 * - **negli arrivi (`type=arrival`) il campo `to` e' l'origine**, e l'orario sta
 *   in `stop.departure`; il tempo reale di quel tabellone e' finto (ritardo null,
 *   previsione uguale all'ora della richiesta). La `passList` delle partenze
 *   invece non e' vuota: porta le fermate successive, a parte il primo elemento
 *   che ha l'id della destinazione. Vedi `data/fonti/MINORI.md`.
 * - **nasconde le soppressioni**: il 19/09/2026 l'RE 80 025835 soppresso per un
 *   guasto usciva a ritardo 0. `search.ch` le segna.
 * - **mescola i vettori.** A Domodossola risponde con SBB, BLS e Trenitalia
 *   insieme a FART. Vanno filtrati, o si duplica cio' che ViaggiaTreno gia'
 *   copre.
 * - **distingue "in orario" da "non si sa" con un null**, e va rispettato.
 */
interface SvizzeraApi {

    companion object {
        const val BASE_URL = "https://transport.opendata.ch/v1/"

    }

    /**
     * Il tabellone di una fermata.
     *
     * [id] e' l'id svizzero della stazione, quello di [SvizzeraStations].
     * Si passa l'id e non il nome perche' i nomi sono ambigui: "Re" da solo
     * pesca mezza Svizzera, e "Domodossola" pesca la stazione RFI.
     *
     * [limit] va chiesto alto: il filtro sul vettore scarta molto, e a
     * Domodossola una decina di corse sono quasi tutte di qualcun altro.
     */
    @GET("stationboard")
    suspend fun stationboard(
        @Query("id") id: String,
        @Query("limit") limit: Int = 40,
        @Query("type") type: String = "departure",
    ): SvizzeraBoardDto
}
