package it.zawardo.treni.data.remote.trenord

import it.zawardo.treni.domain.model.BoardEntry
import it.zawardo.treni.domain.model.TrainRef
import it.zawardo.treni.domain.model.TrainState

/**
 * Estrae le righe del tabellone dai frammenti HTML di Trenord.
 *
 * Analizzare HTML con espressioni regolari e' fragile per definizione, ma qui
 * l'alternativa sarebbe aggiungere un parser HTML all'app per un solo endpoint.
 * Il compromesso e' renderlo **innocuo quando fallisce**: se il markup cambia,
 * le righe semplicemente non vengono trovate e il tabellone resta quello di
 * ViaggiaTreno, senza errori ne' dati inventati.
 */
internal object TrenordBoardParser {

    /** Ogni treno e' un blocco che comincia cosi'. */
    private val BLOCK = Regex("""(?=<div\s+data-direzione)""")

    /** `<h4 class="text-train-prossimi"><b>S1</b> 24042</h4>` */
    private val LINE_AND_NUMBER =
        Regex("""<h4[^>]*class="text-train-prossimi"[^>]*>\s*<b>([^<]*)</b>\s*([0-9A-Za-z]+)""")

    /** `Diretto a <p class="station">MILANO BOVISA POLITECNICO</p>` */
    private val DIRECTION =
        Regex("""(?:Diretto a|Proveniente da)\s*<[^>]*>?\s*<p class="station"[^>]*>([^<]*)</p>""")

    /** `Partenza alle <b>13:36</b>` */
    private val TIME = Regex("""(?:Partenza|Arrivo) alle\s*<b>([0-9]{1,2}:[0-9]{2})</b>""")

    fun parse(html: String?, departureDateMillis: Long): List<BoardEntry> {
        if (html.isNullOrBlank()) return emptyList()
        return html.split(BLOCK)
            .mapNotNull { block -> parseBlock(block, departureDateMillis) }
    }

    private fun parseBlock(block: String, departureDateMillis: Long): BoardEntry? {
        val ln = LINE_AND_NUMBER.find(block) ?: return null
        val line = ln.groupValues[1].trim()
        val number = ln.groupValues[2].trim().ifBlank { return null }
        val time = TIME.find(block)?.groupValues?.get(1)?.trim()
        val direction = DIRECTION.find(block)?.groupValues?.get(1)?.trim()

        return BoardEntry(
            trainRef = TrainRef(
                number = number,
                // Trenord non espone il codice origine: il dettaglio risolve
                // comunque per numero e data.
                originCode = "",
                departureDateMillis = departureDateMillis,
            ),
            label = listOf(line, number).filter { it.isNotBlank() }.joinToString(" "),
            category = line.ifBlank { null },
            direction = direction,
            scheduledTime = time,
            // Questo tabellone e' orario teorico: non porta ritardi, soppressioni
            // ne' binari. Dichiararli a zero sarebbe una bugia, restano assenti.
            delayMinutes = 0,
            scheduledPlatform = null,
            actualPlatform = null,
            state = TrainState.REGULAR,
            inStation = false,
            // Questo tabellone e' orario teorico. Se la corsa risulta tracciata,
            // il repository lo corregge interrogandone il dettaglio.
            hasRealtime = false,
        )
    }
}
