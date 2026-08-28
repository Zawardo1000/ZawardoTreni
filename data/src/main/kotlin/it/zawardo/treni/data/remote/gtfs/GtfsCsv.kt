package it.zawardo.treni.data.remote.gtfs

import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.LocalDate
import java.util.zip.ZipFile

/**
 * Lettura di un archivio GTFS, per le sorgenti che pubblicano solo l'orario.
 *
 * Esiste separato da [it.zawardo.treni.data.remote.eav.EavGtfsParser] per una
 * differenza che non e' cosmetica: quello indirizza le colonne per posizione
 * (`c[4]` e' `route_type`), il che va benissimo finche' si legge un feed solo e
 * si sa com'e' fatto. Su ARST quella stessa colonna e' la quinta, e sui feed che
 * verranno sara' un'altra ancora — **il GTFS non fissa l'ordine delle colonne**,
 * solo i nomi. Qui si legge l'intestazione e si cercano i nomi.
 *
 * Quando l'orario EAV verra' rifatto su questa base, quel parser potra' sparire.
 */
internal object GtfsCsv {

    /**
     * Una tabella del GTFS, con le colonne indirizzabili per nome.
     *
     * Non tiene le righe in memoria: le passa una per volta a chi legge. Il
     * `stop_times.txt` di ARST ha 176.000 righe di cui ne servono 859, e
     * materializzarle tutte per buttarne il 99,5% sarebbe uno spreco che su un
     * telefono si sente.
     */
    class Tabella(private val intestazione: List<String>) {
        /** L'indice di una colonna, -1 se il feed non ce l'ha. */
        fun col(nome: String): Int = intestazione.indexOf(nome)

        /** L'indice di una colonna obbligatoria; null se manca, per fermarsi subito. */
        fun richiesta(nome: String): Int? = col(nome).takeIf { it >= 0 }
    }

    /**
     * Scorre una tabella dell'archivio.
     *
     * [azione] riceve l'intestazione una volta sola e poi ogni riga. Restituisce
     * falso se la tabella non c'e': diversi feed omettono file opzionali, e
     * `calendar.txt` e' opzionale quando c'e' `calendar_dates.txt`.
     */
    fun scorri(
        archivio: ZipFile,
        nome: String,
        azione: (tabella: Tabella, campi: List<String>) -> Unit,
    ): Boolean {
        val entry = archivio.getEntry(nome) ?: return false
        BufferedReader(InputStreamReader(archivio.getInputStream(entry), Charsets.UTF_8)).use { r ->
            val prima = r.readLine() ?: return false
            // Il BOM di UTF-8 finisce dentro il nome della prima colonna e la
            // rende introvabile: "route_id" diventa "﻿route_id".
            val tabella = Tabella(campi(prima.removePrefix("﻿")).map { it.trim() })
            while (true) {
                val l = r.readLine() ?: break
                if (l.isNotBlank()) azione(tabella, campi(l))
            }
        }
        return true
    }

    /**
     * Divide una riga CSV rispettando le virgolette.
     *
     * I nomi di fermata contengono virgole — "Torre Annunziata, Oplonti" — e uno
     * `split(",")` sfaserebbe tutta la riga senza dare errore: il guasto si
     * vedrebbe come orari attribuiti alla fermata sbagliata.
     */
    fun campi(riga: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var dentro = false
        for (ch in riga) {
            when {
                ch == '"' -> dentro = !dentro
                ch == ',' && !dentro -> { out += sb.toString(); sb.setLength(0) }
                else -> sb.append(ch)
            }
        }
        out += sb.toString()
        return out
    }

    /** Il campo [i] della riga, ripulito; stringa vuota se la colonna non c'e'. */
    fun List<String>.campo(i: Int): String = if (i in indices) this[i].trim() else ""

    /** `yyyyMMdd` come lo scrivono i GTFS. */
    fun data(s: String): LocalDate? = runCatching {
        LocalDate.of(s.substring(0, 4).toInt(), s.substring(4, 6).toInt(), s.substring(6, 8).toInt())
    }.getOrNull()

    /**
     * `HH:MM:SS` in minuti dalla mezzanotte.
     *
     * Le ore oltre 24 restano tali: il GTFS esprime cosi' le corse che scavalcano
     * la mezzanotte, e riportarle a zero farebbe arrivare un treno prima di
     * essere partito.
     */
    fun minuti(s: String): Int? {
        val p = s.trim().split(':')
        if (p.size < 2) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        return h * 60 + m
    }
}
