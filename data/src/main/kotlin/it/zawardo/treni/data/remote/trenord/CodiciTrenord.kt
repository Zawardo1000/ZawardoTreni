package it.zawardo.treni.data.remote.trenord

/**
 * Da un codice di stazione dell'app ai codici di Trenord, e ritorno.
 *
 * Trenord ha un codice suo per ogni stazione, il **MIR**, e ne ricava quello
 * che la ricerca vuole, l'HAFAS: "83" seguito dalle cinque cifre del MIR. Quasi
 * sempre il MIR e' il codice RFI, e la traduzione finisce li'. Le eccezioni sono
 * poche, ma sbagliano in silenzio — la ricerca risponde "missing destination
 * station" e la soluzione Trenord semplicemente non c'e' — e costano care:
 * Brescia, per Trenord `S09999`, e' rimasta fuori da ogni ricerca finche' il
 * 18/09/2026 non si e' visto che Milano-Brescia usciva coi regionali senza
 * prezzo. Trenord il prezzo ce l'aveva, 8,40 euro: bastava chiederglielo col
 * codice giusto.
 *
 * Il catalogo delle stazioni di Trenord (`/mia/v2/stazioni_v2/`) non basta a
 * ricavarle: per Brescia e Como Camerlata dichiara ancora un HAFAS che la
 * ricerca rifiuta. Le eccezioni qui sotto vengono dal catalogo incrociato per
 * coordinate con l'anagrafe RFI di ViaggiaTreno, e sono verificate una per una
 * contro la ricerca vera: `CodiciTrenordLiveTest` le rifa' a ogni giro di test.
 *
 * Tutto cio' che non e' un codice RFI — `EAV62`, `FNB1110`, `ARST22581`,
 * `CH8505300` — a Trenord **non si chiede**. Fino al 18/09/2026 se ne prendevano
 * le cifre: `FNB1110`, Bari Centrale, diventava `8301110`, Pino-Tronzano.
 */
internal object CodiciTrenord {

    /** Una stazione che l'app e Trenord chiamano in modo diverso. */
    private class Alias(val app: String, val mir: String, val hafas: String)

    private val ALIAS = listOf(
        // Il MIR non e' il codice RFI.
        Alias(app = "S01717", mir = "S09999", hafas = "8309999"), // BRESCIA
        Alias(app = "S01763", mir = "S09998", hafas = "8309998"), // COMO CAMERLATA
        // Stazione di confine: per la ricerca e' il suo numero svizzero.
        Alias(app = "S01110", mir = "S01110", hafas = "8513967"), // PINO-TRONZANO
        // Nell'app ha il codice RFI (vedi SvizzeraStations); per Trenord e' svizzera.
        Alias(app = "S00300", mir = "S05213", hafas = "8505213"), // BELLINZONA
    )

    private val PER_APP = ALIAS.associateBy { it.app }
    private val PER_MIR = ALIAS.associateBy { it.mir }

    /**
     * Codici RFI che Trenord da' a **un'altra** stazione. Per RFI `S05302` e'
     * Osteria Nuova; per Trenord e' Melide, sul lago di Lugano, a oltre
     * duecento chilometri. Chiederli significherebbe mostrare a Osteria Nuova i
     * treni di Melide. Lo stesso per i MIR delle eccezioni: RFI `S09999` e'
     * Piedimonte Matese, per Trenord e' Brescia.
     */
    private val ALTRA_STAZIONE: Set<String> =
        setOf(
            "S05301", // RFI Buttapietra, Trenord Lugano Paradiso
            "S05302", // RFI Osteria Nuova, Trenord Melide
            "S05303", // RFI Roncanova di Gazzo Veronese, Trenord Maroggia-Melano
            "S05306", // RFI Revere Scalo, Trenord Balerna
            "S05417", // RFI Ceregnano, Trenord Minusio
        ) + ALIAS.filter { it.app != it.mir }.map { it.mir }

    private val CODICE_RFI = Regex("""S\d{5}""")

    private fun pulito(codice: String?): String? = codice?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }

    /** Il MIR con cui Trenord conosce la stazione [codice], o null se non la conosce. */
    fun mir(codice: String?): String? {
        val c = pulito(codice) ?: return null
        PER_APP[c]?.let { return it.mir }
        return c.takeIf { CODICE_RFI.matches(it) && it !in ALTRA_STAZIONE }
    }

    /** Il codice HAFAS della ricerca Trenord per la stazione [codice], o null. */
    fun hafas(codice: String?): String? {
        val c = pulito(codice) ?: return null
        PER_APP[c]?.let { return it.hafas }
        return mir(c)?.let { "83" + it.drop(1) }
    }

    /**
     * Il codice con cui l'app conosce la stazione che Trenord chiama [mir]:
     * Brescia torna `S01717`, e cosi' si accoppia con la Brescia di ViaggiaTreno
     * e di Le Frecce — per i binari, per fondere le soluzioni, per trovare dove
     * si sale. Gli altri restano come sono.
     */
    fun perApp(mir: String?): String? {
        val c = pulito(mir) ?: return null
        return PER_MIR[c]?.app ?: c
    }
}
