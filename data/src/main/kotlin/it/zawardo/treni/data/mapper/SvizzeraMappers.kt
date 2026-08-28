package it.zawardo.treni.data.mapper

import it.zawardo.treni.data.remote.svizzera.SvizzeraJourneyDto
import it.zawardo.treni.domain.model.BoardEntry
import it.zawardo.treni.domain.model.TrainRef
import it.zawardo.treni.domain.model.TrainState
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Dall'orario svizzero al modello comune, per la Vigezzina - Svizzera.
 */

/**
 * `2026-08-28T12:40:00+0200`.
 *
 * Non e' ISO_OFFSET_DATE_TIME: lo scarto orario arriva senza i due punti
 * (`+0200`, non `+02:00`), quindi il parser di libreria lo rifiuta e serve
 * questo schema.
 */
private val ORARIO_CH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")

/**
 * Le categorie svizzere, sciolte.
 *
 * `PE` e' il Panoramic Express: stesso percorso dei regionali ma servizio
 * turistico, con supplemento e prenotazione. Lasciarlo come "PE" accanto a un
 * regionale significa far salire su un treno che costa di piu' chi voleva solo
 * arrivare a Malesco.
 */
private fun etichettaCategoria(sigla: String?): String? = when (sigla?.uppercase()) {
    "R" -> "Regionale"
    "RE" -> "RegioExpress"
    "PE" -> "Panoramic Express"
    else -> sigla?.takeIf { it.isNotBlank() }
}

/**
 * Toglie il suffisso con cui l'orario svizzero marca le stazioni italiane.
 *
 * Li' `Domodossola (I)` ha senso: distingue l'estero. In un'app italiana quel
 * `(I)` non distingue niente e sembra un refuso. Si toglie solo dalla coda, per
 * non intaccare nomi che le parentesi ce le hanno per conto loro.
 */
private fun nomeItaliano(nome: String): String = nome.removeSuffix(" (I)").trim()

/**
 * Le sigle che in Svizzera si scrivono attaccate al numero.
 *
 * In banchina a Lugano c'e' scritto `S10`, non `S 10`, e lo stesso vale per
 * `RE80` e `R70`. La convenzione italiana — `REG 25123` — qui non si applica, e
 * chi confronta l'app col cartellone deve trovare la stessa cosa.
 */
private val ATTACCATE = setOf("S", "SE", "R", "RE", "RB", "IR")

/**
 * L'etichetta della corsa.
 *
 * `PE` fa eccezione e resta scritta per esteso col numero staccato: non e' una
 * sigla di linea ma un prodotto a supplemento, e "Panoramic Express 72" e'
 * l'unica forma che lo dica a chi non lo sa gia'.
 */
private fun etichetta(sigla: String?, categoria: String?, numero: String): String {
    val s = sigla?.uppercase()?.trim().orEmpty()
    if (s in ATTACCATE) return s + numero
    return listOfNotNull(categoria, numero).joinToString(" ")
}

/**
 * Vero se la corsa e' di uno dei vettori che in quella stazione interessano.
 *
 * L'elenco arriva dalla stazione e non e' lo stesso ovunque, ed e' il punto
 * delicato di questa fonte. A Domodossola il tabellone svizzero risponde anche
 * con SBB e BLS: sono gli EuroCity su rete RFI, che l'app ha gia' da
 * ViaggiaTreno, e tenerli vorrebbe dire mostrarli due volte. In Ticino invece
 * SBB e' esattamente cio' che si cerca, perche' e' sotto quel nome che
 * circolano le linee S.
 */
fun SvizzeraJourneyDto.diVettore(vettori: List<String>): Boolean {
    val op = operator?.uppercase() ?: return false
    return vettori.any { op.startsWith(it) }
}

/**
 * Una riga di tabellone.
 *
 * Null quando manca il numero o l'orario: senza, la riga non identifica niente
 * e non si puo' collocare nel tempo.
 */
fun SvizzeraJourneyDto.toBoardEntry(): BoardEntry? {
    val stop = stop ?: return null
    val grezzo = stop.departure?.takeIf { it.isNotBlank() } ?: return null
    val quando = runCatching { OffsetDateTime.parse(grezzo, ORARIO_CH) }.getOrNull() ?: return null

    /*
     * Il numero arriva con gli zeri davanti su alcune corse (`000065`) e nudo
     * su altre (`72`). Si mostra nudo: e' il numero che sta sull'orario e sul
     * fianco della carrozza.
     */
    val numero = number?.trimStart('0')?.takeIf { it.isNotBlank() }
        ?: number?.takeIf { it.isNotBlank() }
        ?: return null

    /*
     * `delay` null non e' zero: e' "non ancora rilevato", e capita su tutte le
     * corse abbastanza in la' nel tempo. Il modello comune vuole un intero,
     * quindi diventa zero, ma lo stato resta REGULAR e nessuno scrive "in
     * orario": quello che non si sa non si racconta.
     */
    val ritardo = stop.delay?.coerceAtLeast(0) ?: 0
    val categoria = etichettaCategoria(category)

    return BoardEntry(
        trainRef = TrainRef(
            number = numero,
            // L'orario svizzero non da' un codice d'origine utilizzabile qui:
            // la corsa si identifica col numero.
            originCode = "",
            departureDateMillis = quando.toLocalDate().atStartOfDay(ROME).toInstant().toEpochMilli(),
        ),
        label = etichetta(category, categoria, numero),
        category = categoria,
        direction = to?.let(::nomeItaliano)?.takeIf { it.isNotBlank() },
        scheduledTime = "%02d:%02d".format(quando.hour, quando.minute),
        delayMinutes = ritardo,
        scheduledPlatform = stop.platform?.takeIf { it.isNotBlank() },
        // Il binario vero e' quello della previsione quando c'e', altrimenti
        // resta quello di tabella: cosi' "binario cambiato" si accende solo
        // quando e' cambiato davvero.
        actualPlatform = stop.prognosis?.platform?.takeIf { it.isNotBlank() }
            ?: stop.platform?.takeIf { it.isNotBlank() },
        state = if (ritardo > 0) TrainState.DELAYED else TrainState.REGULAR,
        inStation = false,
    )
}
