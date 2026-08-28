package it.zawardo.treni.domain.model

/**
 * Le reti che l'app interroga, come le vede chi cerca un treno.
 *
 * Non e' un dettaglio tecnico: le fonti sono diventate tante, e ognuna e' una o
 * piu' chiamate di rete a ogni ricerca. Chi non esce mai dalla Lombardia non ha
 * bisogno che si interroghi Italo; chi prende solo l'alta velocita' non ha
 * bisogno di Trenord. Spegnere quello che non serve rende la ricerca piu' veloce.
 *
 * [available] falso e' una rete annunciata ma non ancora collegata: compare in
 * elenco perche' la si aspetta, ma non si puo' accendere finche' non risponde.
 *
 * [opzionale] falso e' la rete che non si sceglie: c'e' sempre, non compare fra
 * le impostazioni e non si puo' spegnere. Ne esiste una sola, ed e' Trenitalia.
 * Non e' un privilegio ma una constatazione: Le Frecce e ViaggiaTreno coprono
 * la rete nazionale, cioe' quasi tutti i treni italiani. Spegnerla non
 * renderebbe la ricerca piu' veloce, la renderebbe vuota — e offrire una scelta
 * il cui unico esito e' rompere l'app non e' offrire una scelta.
 *
 * [accesaDiDefault] distingue, fra le reti che si possono scegliere, quelle che
 * nascono accese. **Nessuna lo e' tranne Trenord.** E' deliberato: ogni rete
 * accesa e' traffico che parte dal telefono di qualcuno — chiamate a ogni
 * ricerca, e per due di loro archivi da scaricare — e accenderle tutte
 * significa deciderlo al posto suo. Chi apre l'app aggiunge quello che gli
 * serve, una volta, e da li' in poi paga solo quello.
 */
enum class DataSource(
    val label: String,
    val detail: String,
    val available: Boolean,
    val accesaDiDefault: Boolean = available,
    val opzionale: Boolean = true,
    /**
     * Ha un elenco di stazioni **proprio**, fuori dal registro RFI, che si puo'
     * interrogare in locale per i suggerimenti. E' il flag che rende dinamico il
     * filtro: [FiltroFonti.fontiLocali] deriva da qui, cosi' aggiungere una rete
     * con stazioni sue non costringe a toccare nessun `when` sparso nei
     * ViewModel — basta accendere questo e registrarne la ricerca in ServiceLocator.
     */
    val stazioniProprie: Boolean = false,
) {
    /**
     * Le Frecce per gli itinerari, ViaggiaTreno per il tempo reale.
     *
     * L'unica che non si spegne, e che infatti non compare fra le impostazioni:
     * e' la rete nazionale, cioe' il grosso di quello che l'app sa dire. Tutte
     * le altre aggiungono qualcosa a questa.
     */
    TRENITALIA(
        "Trenitalia",
        "Rete nazionale, Frecce e Intercity",
        available = true,
        opzionale = false,
    ),
    /**
     * L'unica delle reti opzionali accesa a installazione nuova.
     *
     * Copre il suburbano lombardo e le linee S del Passante, che sulla rete
     * nazionale non compaiono: senza di lei, a Milano, mancherebbe la meta' dei
     * treni che la gente prende davvero.
     */
    TRENORD("Trenord", "Regionale lombardo e linee S del Passante", available = true),
    ITALO("Italo", "Alta velocita' NTV", available = true, accesaDiDefault = false),
    EAV("EAV", "Circumvesuviana e rete campana", available = true, accesaDiDefault = false, stazioniProprie = true),

    /**
     * Le due reti che seguono costano poco anche accese: hanno un elenco di
     * fermate proprio, e fuori da quello rispondono di no senza toccare la rete.
     */
    FNB(
        "Ferrotramviaria",
        "Bari - Barletta e aeroporto di Bari",
        available = true,
        accesaDiDefault = false,
        stazioniProprie = true,
    ),
    /**
     * Le due ferrovie transfrontaliere che si chiedono all'orario svizzero.
     *
     * Stanno insieme perche' la fonte e' una sola: stessa API, stessa chiamata.
     * Sono la Vigezzina - Centovalli (Domodossola - Locarno) e le linee S del
     * Ticino, e quello che portano e' il **lato svizzero** — Lugano, Mendrisio,
     * Locarno, la Val Vigezzo — che nessun'altra sorgente ha.
     */
    SVIZZERA(
        "Ferrovie svizzere",
        "Vigezzina-Centovalli e linee S del Ticino",
        available = true,
        accesaDiDefault = false,
        stazioniProprie = true,
    ),

    /**
     * ARST e' l'unica rete dell'elenco **senza tempo reale**.
     *
     * Non pubblica tabelloni ne' API: c'e' solo il suo GTFS, quindi di quelle
     * corse si sa l'orario previsto e mai il ritardo. Accenderla comporta anche
     * lo scarico periodico dell'orario, ed e' il motivo per cui il dettaglio lo
     * dice: chi la accende deve sapere tutte e due le cose.
     */
    ARST(
        "ARST Sardegna",
        "Solo orari previsti, senza tempo reale",
        available = true,
        /*
         * Come tutte le altre nasce spenta, ma qui pesa il doppio: accenderla
         * comporta anche scaricare periodicamente 19,7 MB di archivio GTFS per
         * quattro linee sarde. Chi in Sardegna ci viaggia fa un ottimo affare —
         * non ha nessun'altra fonte — ma dev'essere una sua scelta.
         */
        accesaDiDefault = false,
        stazioniProprie = true,
    );

    companion object {
        /**
         * Le reti accese quando non si e' ancora scelto nulla.
         *
         * Le non opzionali ci sono sempre; delle altre, solo quelle che nascono
         * accese. Vedi [opzionale] e [accesaDiDefault].
         */
        val defaultEnabled: Set<DataSource>
            get() = entries.filterTo(HashSet()) {
                it.available && (!it.opzionale || it.accesaDiDefault)
            }

        /**
         * Le reti che ci sono comunque, qualunque cosa sia stato salvato.
         *
         * Servono a chi legge le impostazioni: una preferenza salvata prima che
         * questa distinzione esistesse potrebbe non contenerle, e senza questo
         * innesto un aggiornamento dell'app spegnerebbe la rete nazionale a chi
         * l'aveva disattivata mesi fa.
         */
        val sempreAttive: Set<DataSource>
            get() = entries.filterTo(HashSet()) { it.available && !it.opzionale }
    }
}
