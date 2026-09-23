package it.zawardo.treni.tools

import it.zawardo.treni.domain.model.STAZIONI_TENUTE_DISTINTE
import it.zawardo.treni.domain.model.codiceStazione
import it.zawardo.treni.domain.model.nomeStazioneNormalizzato
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Cerca le stazioni che RFI scrive con due codici e che la ricerca non
 * distingue, cioe' le coppie da aggiungere a `DOPPI` in `CodiciStazione.kt`.
 *
 * **Perche' alla release e non solo nei test.** Una coppia nuova non rompe
 * niente in modo visibile: toglie una fascia gialla, un «Sali», un binario
 * confermato, su una tratta sola. Nessuno se ne accorge finche' non ci passa
 * qualcuno — Napoli Afragola e' rimasta cosi' per mesi, fino alla segnalazione
 * del 23/09/2026. La release e' l'unico momento garantito in cui qualcuno sta
 * guardando il log, ed e' lo stesso motivo per cui li' si riscaricano gli orari
 * imbarcati.
 *
 * **Avvisa, non blocca** (deciso con l'utente il 23/09/2026). Stampa la riga
 * gia' pronta da incollare e prosegue: una coppia nuova e' un difetto piccolo e
 * circoscritto, e fermare una pubblicazione per quello — o peggio, per un
 * server di RFI che non risponde — costerebbe piu' di quanto renda. L'avviso
 * pero' va letto: e' scritto perche' salti all'occhio in mezzo al log.
 *
 * **Trova, non decide.** Le coppie le propone, non le scrive: su Genova Piazza
 * Principe questo stesso giro diceva «unisci» e la risposta giusta era «sono
 * gemelle, un piano sopra e uno sotto». Due nomi identici sono una
 * rinumerazione e si uniscono; un nome con una qualifica va guardato in faccia.
 * Vedi `CodiciStazione.kt`, dove sta anche l'elenco di quelle tenute distinte.
 *
 * Il metodo, per esteso, e' documentato accanto a `DOPPI`: catalogo intero,
 * nomi normalizzati, e per ogni gruppo la domanda alla ricerca — **se conosce
 * un codice solo, gli altri sono alias**.
 */
fun main() {
    val client = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
    val json = Json { ignoreUnknownKeys = true }

    val catalogo = runCatching { catalogoRfi(client, json) }.getOrElse {
        println("[stazioni] CONTROLLO FALLITO: catalogo RFI non leggibile ($it)")
        return
    }
    if (catalogo.size < SOGLIA_CATALOGO) {
        println("[stazioni] CONTROLLO FALLITO: solo ${catalogo.size} stazioni lette, troppe poche")
        return
    }

    val gruppi = catalogo.entries
        .groupBy { nomeStazioneNormalizzato(it.value) }
        .filterValues { it.size > 1 }
    println("[stazioni] ${catalogo.size} stazioni, ${gruppi.size} nomi con piu' codici")

    var nuove = 0
    for ((nome, voci) in gruppi) {
        val codici = voci.map { it.key }
        // Gia' risolto: tutti i codici del gruppo si riducono allo stesso.
        if (codici.map { codiceStazione(it) }.distinct().size == 1) continue

        val note = runCatching { codiciNotiAllaRicerca(client, json, nome) }.getOrElse {
            println("[stazioni] $nome: la ricerca non ha risposto, salto ($it)")
            continue
        }
        val conosciuti = codici.filter { it in note }
        val ignoti = codici.filter { it !in note && it !in STAZIONI_TENUTE_DISTINTE }
        if (conosciuti.size != 1 || ignoti.isEmpty()) continue

        nuove++
        ignoti.forEach { println("[stazioni] COPPIA NUOVA: \"$it\" to \"${conosciuti.first()}\", // $nome") }
    }

    if (nuove == 0) {
        println("[stazioni] nessuna coppia nuova: DOPPI e' aggiornato")
        return
    }
    println("[stazioni] AVVISO: $nuove non sono in DOPPI ne' in STAZIONI_TENUTE_DISTINTE.")
    println("[stazioni] Prima di incollarle, guarda cosa sono: due nomi identici sono una")
    println("[stazioni] rinumerazione e si uniscono; un nome con una qualifica (SOTTERRANEA,")
    println("[stazioni] PASSANTE) possono essere due piani di binari, e unirli manderebbe al")
    println("[stazioni] piano sbagliato chi legge il binario. Vedi CodiciStazione.kt.")
}

/** Il catalogo RFI, regione per regione: codice -> nome. */
private fun catalogoRfi(client: OkHttpClient, json: Json): Map<String, String> {
    val fuori = LinkedHashMap<String, String>()
    for (regione in 0..ULTIMA_REGIONE) {
        val corpo = runCatching { scarica(client, "$CATALOGO$regione") }.getOrNull() ?: continue
        val righe = runCatching { json.decodeFromString<List<StazioneRfi>>(corpo) }.getOrNull().orEmpty()
        for (r in righe) {
            val codice = r.codiceStazione?.takeIf { it.startsWith("S") } ?: continue
            val nome = r.localita?.nomeLungo?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            fuori.putIfAbsent(codice, nome)
        }
    }
    return fuori
}

/**
 * I codici che la ricerca conosce per quel nome.
 *
 * Si chiede col nome **base**, gia' normalizzato: Le Frecce chiama S01647
 * "Milano Porta Garibaldi Passante" mentre RFI lo dice "SOTTERRANEA", e col nome
 * di RFI sembrerebbe sconosciuto — cioe' un alias da fondere, che e' il
 * contrario del vero.
 */
private fun codiciNotiAllaRicerca(client: OkHttpClient, json: Json, nome: String): Set<String> {
    val corpo = scarica(client, RICERCA + java.net.URLEncoder.encode(nome, "UTF-8"))
    return json.decodeFromString<List<LuogoRicerca>>(corpo)
        .mapNotNull { it.bdoCode?.uppercase() }
        .toSet()
}

private fun scarica(client: OkHttpClient, url: String): String =
    client.newCall(Request.Builder().url(url).build()).execute().use { r ->
        if (!r.isSuccessful) error("HTTP ${r.code} per $url")
        r.body?.string() ?: error("corpo vuoto per $url")
    }

@Serializable
private data class StazioneRfi(
    val codiceStazione: String? = null,
    val localita: LocalitaRfi? = null,
)

@Serializable
private data class LocalitaRfi(val nomeLungo: String? = null)

@Serializable
private data class LuogoRicerca(val bdoCode: String? = null)

private const val CATALOGO =
    "http://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno/elencoStazioni/"
private const val RICERCA =
    "https://app.lefrecce.it/Channels.Website.BFF.WEB/app/locations?limit=12&name="

/** Le regioni del catalogo RFI, da 0 a 22. */
private const val ULTIMA_REGIONE = 22

/** Sotto questa soglia la lettura e' andata male, e tacere sarebbe peggio che dirlo. */
private const val SOGLIA_CATALOGO = 2000
