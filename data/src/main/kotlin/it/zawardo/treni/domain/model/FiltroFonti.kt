package it.zawardo.treni.domain.model

/**
 * Le decisioni di filtro che i ViewModel prendevano al volo, tirate fuori e
 * rese verificabili.
 *
 * Sono due `if` che a occhio sembrano banali ma sono proprio ciò che l'utente
 * vede: quali reti interrogare per i suggerimenti, e se comporre i viaggi misti.
 * Dentro un `ViewModel` non si testano — pescano da Android e da `ServiceLocator`
 * — quindi la logica scende qui, dove un test JUnit la blinda. Il ViewModel resta
 * un guscio che chiama questi, come già fa con [SuggerimentiStazioni].
 */
object FiltroFonti {

    /**
     * Le reti locali **accese** — le sole da interrogare per i suggerimenti.
     *
     * Non e' una lista scritta a mano: sono tutte e sole le fonti con
     * [DataSource.stazioniProprie], cioe' quelle che un elenco di fermate proprio
     * ce l'hanno (EAV, Ferrotramviaria, le svizzere, ARST). Trenitalia e Trenord
     * restano fuori — le loro stazioni arrivano dal BFF Le Frecce, non da una
     * ricerca locale — perche' non hanno quel flag. Aggiungere domani una rete
     * con stazioni sue non tocca questa funzione: basta il flag sull'enum.
     *
     * E' il filtro che deve valere sempre: una rete spenta non propone le sue
     * stazioni, o si sceglierebbe una partenza che poi non da' nulla. L'ordine di
     * dichiarazione dell'enum e' preservato, cosi' i suggerimenti escono stabili.
     */
    fun fontiLocali(accese: Set<DataSource>): List<DataSource> =
        DataSource.entries.filter { it.stazioniProprie && it in accese }

    /**
     * Se ha senso comporre i viaggi misti, prima ancora di guardare le fonti.
     *
     * Due condizioni, e servono entrambe: il flag beta acceso — la funzione è
     * dichiaratamente sperimentale — e **non** "solo diretti", perché un misto
     * un cambio ce l'ha sempre e con "solo diretti" attivo andrebbe scartato
     * dopo averlo pagato in chiamate. Che poi le due reti dello schema siano
     * accese lo controlla [it.zawardo.treni.data.repository.ViaggiMistiRepository]:
     * qui si decide solo se vale la pena provarci.
     */
    fun componiMisti(soloDiretti: Boolean, betaAttivo: Boolean): Boolean =
        betaAttivo && !soloDiretti
}
