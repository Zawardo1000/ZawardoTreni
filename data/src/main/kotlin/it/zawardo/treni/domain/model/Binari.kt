package it.zawardo.treni.domain.model

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Quanto possono discostarsi gli orari di tabella di due letture della stessa
 * fermata. ViaggiaTreno arrotonda al minuto, Trenord scrive anche i secondi:
 * Busto Arsizio Nord e' "10:39" per l'uno e "10:39:30" per l'altro. Tre minuti
 * assorbono questo e qualunque altro arrotondamento, e restano molto meno
 * dell'intervallo fra due corse diverse nella stessa stazione.
 */
private val SCARTO_AMMESSO: Duration = Duration.ofMinutes(3)

private val Stop.orarioDiTabella: LocalDateTime?
    get() = scheduledDeparture ?: scheduledArrival

/**
 * La stessa fermata della stessa corsa, non soltanto la stessa stazione.
 *
 * Il controllo sull'orario non e' pedanteria: **lo stesso numero puo' essere di
 * due treni diversi**. Il 04/09/2026 il 178 era insieme l'EuroCity delle 10:10
 * Milano Centrale - Chiasso e il regionale Trenord delle 19:46 Como Lago -
 * Milano Cadorna. Con il solo codice di stazione, il binario dell'uno sarebbe
 * finito sulla fermata dell'altro ovunque i due percorsi si sfiorino — e un
 * binario sbagliato e' peggio di un binario assente.
 *
 * Si confronta l'ora del giorno e non l'istante, perche' una corsa che scavalca
 * la mezzanotte le due fonti possono datarla in modo diverso.
 */
private fun Stop.eLaStessaFermataDi(altra: Stop): Boolean {
    val qui = orarioDiTabella ?: return false
    val la = altra.orarioDiTabella ?: return false
    return secondiCircolari(qui.toLocalTime(), la.toLocalTime()) <= SCARTO_AMMESSO.seconds
}

/**
 * Riempie i binari mancanti con quelli di un'altra lettura della stessa corsa.
 *
 * **Le fonti non si sovrappongono, si completano.** Misurato il 04/09/2026 sul
 * REG 2932 Milano Centrale - Gallarate: ViaggiaTreno pubblicava il binario
 * soltanto alle prime due fermate, Trenord alle otto stazioni FNM che
 * ViaggiaTreno lascia vuote. E all'inverso, sul REG 2934, ViaggiaTreno dava il
 * binario programmato a Milano Centrale mentre Trenord li' non aveva niente:
 * sulla rete RFI Trenord pubblica solo il binario vero, e solo da quando viene
 * assegnato.
 *
 * Serve anche a poter dire **"cambiato"**: quello e' un confronto fra due
 * valori, e una fonte che ne pubblichi uno solo non potrebbe mai farlo. Con le
 * due letture unite, il programmato di ViaggiaTreno e l'effettivo di Trenord
 * stanno finalmente sulla stessa fermata.
 *
 * Il ponte fra le due letture e' il **codice RFI di stazione** piu' l'orario di
 * tabella (vedi [eLaStessaFermataDi]). Non i nomi, che non coincidono:
 * ViaggiaTreno scrive "MALPENSA AEROPORTO TERMINAL 1" dove Trenord scrive
 * "MALPENSA AEROPORTO T1".
 *
 * Riempie e basta: un binario gia' noto non viene mai sostituito. Chi chiama
 * sceglie quale delle due letture sia quella buona mettendola come ricevente, e
 * questa funzione non puo' ribaltargli la scelta.
 */
fun TrainStatus.conBinariDa(altra: TrainStatus): TrainStatus {
    if (stops.none { it.scheduledPlatform == null || it.actualPlatform == null }) return this

    val altrove = altra.stops
        .filter { it.scheduledPlatform != null || it.actualPlatform != null }
        .groupBy { codiceStazione(it.stationCode).orEmpty() }
        .filterKeys { it.isNotEmpty() }
    if (altrove.isEmpty()) return this

    return copy(
        stops = stops.map { fermata ->
            if (fermata.scheduledPlatform != null && fermata.actualPlatform != null) return@map fermata
            val chiave = codiceStazione(fermata.stationCode)
            // Piu' d'una quando la corsa ripassa dalla stessa stazione: e'
            // l'orario a dire di quale dei due passaggi si stia parlando.
            val fonte = chiave?.let { altrove[it] }
                ?.firstOrNull { fermata.eLaStessaFermataDi(it) }
                ?: return@map fermata
            fermata.copy(
                scheduledPlatform = fermata.scheduledPlatform ?: fonte.scheduledPlatform,
                actualPlatform = fermata.actualPlatform ?: fonte.actualPlatform,
            )
        },
    )
}

/**
 * Porta su una corsa il perche' della sua variazione, e gli avvisi di
 * circolazione, da un'altra lettura della stessa corsa.
 *
 * Il percorso lo sa ViaggiaTreno, il perche' solo Trenord: nel REG 2833 del
 * 18/09/2026, deviato su Sesto S.Giovanni, ViaggiaTreno scriveva cosa cambiava
 * e Trenord "Richiesta Impresa Ferroviaria". Si prende con la stessa chiamata
 * dei binari, quindi non costa niente.
 *
 * Solo se le due letture sono **la stessa corsa**: almeno una fermata in
 * comune, per stazione e orario di tabella, come in [conBinariDa]. Lo stesso
 * numero puo' essere di due treni diversi, e il motivo della soppressione di un
 * altro treno sarebbe peggio di nessun motivo.
 */
fun TrainStatus.conAvvisiDa(altra: TrainStatus): TrainStatus {
    if (altra.motivo == null && altra.avvisi.isEmpty()) return this
    val stessaCorsa = stops.any { fermata ->
        altra.stops.any { stessaStazione(fermata.stationCode, it.stationCode) && fermata.eLaStessaFermataDi(it) }
    }
    if (!stessaCorsa) return this
    return copy(
        motivo = motivo ?: altra.motivo,
        avvisi = (avvisi + altra.avvisi).distinct(),
    )
}

/**
 * Il binario di una riga di tabellone, aggiornato con quello della corsa stessa.
 *
 * **Il tabellone arriva dopo il dettaglio.** Confrontate il 14/09/2026 le
 * partenze di Milano Centrale, Roma Termini, Napoli Centrale e Catania Centrale
 * con `andamentoTreno` delle stesse corse: 43 righe su 46 identiche, e le tre
 * diverse erano tutte binari appena assegnati, che la corsa aveva e il
 * tabellone non ancora. Il REG 22096 a Catania Centrale, alle 09:50, era "2"
 * programmato e nient'altro sul tabellone, "2" programmato e "2" effettivo nel
 * dettaglio: nero da una parte e verde dall'altra, sullo stesso treno nello
 * stesso minuto. Il 9524 a Roma Termini aveva il "6" nel dettaglio e niente sul
 * tabellone. E' proprio il momento in cui chi guarda il tabellone si alza per
 * andare in banchina.
 *
 * Per questo qui, al contrario di [conBinariDa], **vince la corsa** dove dice
 * qualcosa: e' la lettura piu' fresca delle due. Quel che la corsa tace resta
 * com'era sul tabellone.
 *
 * La corsa e' gia' quella giusta — [BoardEntry.trainRef] la identifica — e la
 * fermata si cerca con [fermataA]: l'orario serve solo se la corsa ripassa
 * dalla stessa stazione.
 */
fun BoardEntry.conBinarioDa(corsa: TrainStatus, stazione: String): BoardEntry {
    val orario = scheduledTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
    val fermata = corsa.fermataA(stazione, orario) ?: return this
    return copy(
        scheduledPlatform = fermata.scheduledPlatform ?: scheduledPlatform,
        actualPlatform = fermata.actualPlatform ?: actualPlatform,
    )
}

/**
 * Da I a XXXIX, che copre ogni stazione italiana. Solo I, V e X: le lettere
 * oltre la X su una banchina non compaiono, e accettarle vorrebbe dire
 * riscrivere come numeri delle sigle che numeri non sono.
 */
private val ROMANO = Regex("(X{0,3})(IX|IV|V?I{0,3})", RegexOption.IGNORE_CASE)

/**
 * Dove una scrittura di binario si spezza in parole: gli spazi, e il trattino
 * che precede una lettera. Bologna Centrale, 14/09/2026: "III-EST" per il "3 EST"
 * di tabella, "IV-PO" per un binario del piazzale ovest. Col trattino attaccato
 * il numero romano non si riconosceva e ogni conferma diventava un cambio. Un
 * trattino fra due cifre invece resta: non se n'e' visto nessuno, e non si
 * indovina.
 */
private val SPAZI = Regex("""\s+|-(?=[A-Za-z])""")

/** Un numero con la sua qualifica attaccata: "IItr", "1Tr", "Iw". */
private val NUMERO_E_CODA = Regex("""([IVX0-9]+)([A-Za-z]+)""", RegexOption.IGNORE_CASE)

/**
 * Le parole con cui una fonte qualifica un binario, ridotte a una grafia sola.
 *
 * Non e' pulizia per il gusto di pulire. Sondato `andamentoTreno` il
 * 07/09/2026 su 22 corse fra Milano, Napoli, Roma e Treviglio, i binari tronchi
 * di Milano Centrale uscivano scritti in **cinque modi diversi** — "1 Tronco
 * OVEST", "2 Tronco Ovest", "2 TR Ovest", "IITR O", "IItr" — piu' un "Iw". Non
 * sono cinque banchine: e' la stessa, e chi confronta il programmato con
 * l'effettivo la leggeva come un cambio di binario.
 *
 * La "w" e' l'unica lettura dedotta e non vista scritta per esteso: sta dove
 * gli altri valori della stessa stazione mettono "O" o "Ovest", e su un binario
 * tronco di Milano Centrale non puo' voler dire altro. Se un giorno volesse
 * dire altro, il danno sarebbe una parola sbagliata a schermo, non un binario
 * sbagliato: "1 ovest" e "1" restano comunque due binari distinti.
 */
private val QUALIFICATORI = mapOf(
    "TR" to "tronco",
    "TRONCO" to "tronco",
    "O" to "ovest",
    "W" to "ovest",
    "OVEST" to "ovest",
    "E" to "est",
    "EST" to "est",
    // Scritti per esteso come EST e OVEST, e visti cosi' il 18/09/2026: "7 Sud"
    // a Salerno, "1 NORD" a Ivrea. Solo le parole intere: "N" e "S" da sole
    // nessuno le ha ancora viste su un binario.
    "SUD" to "sud",
    "NORD" to "nord",
    // Roma Termini, 14/09/2026: "20 BIS", un binario che si chiama cosi'.
    "BIS" to "bis",
    // Bologna Centrale, 14/09/2026: i binari del piazzale ovest scritti "IV-PO",
    // "VI-PO" dove il programmato dice "2 OVEST". PO sta per piazzale ovest: e'
    // una lettura dedotta, come la "w" di Milano, e col suo stesso danno massimo
    // — una parola sbagliata a schermo, non un binario sbagliato.
    "PO" to "ovest",
    // La stazione AV sotterranea di Bologna: "19 AV". Una sigla, resta maiuscola.
    "AV" to "AV",
)

private fun String.romanoInCifre(): String? {
    val m = ROMANO.matchEntire(this) ?: return null
    val decine = m.groupValues[1].length * 10
    val unita = when (val u = m.groupValues[2].uppercase()) {
        "IX" -> 9
        "IV" -> 4
        else -> (if (u.startsWith("V")) 5 else 0) + u.count { it == 'I' }
    }
    return (decine + unita).takeIf { it > 0 }?.toString()
}

/**
 * Stacca la qualifica dal numero, ma **solo se e' una qualifica conosciuta**:
 * "IItr" sono due parole, "1B" e' un binario che si chiama cosi' e resta
 * intero.
 */
private fun spezza(token: String): List<String> {
    val m = NUMERO_E_CODA.matchEntire(token) ?: return listOf(token)
    val coda = m.groupValues[2]
    if (coda.uppercase() !in QUALIFICATORI) return listOf(token)
    return listOf(m.groupValues[1], coda)
}

private fun canonico(token: String): String =
    token.romanoInCifre() ?: QUALIFICATORI[token.uppercase()] ?: token

/**
 * Il binario come lo scrive l'app, da qualunque fonte arrivi: in cifre arabe,
 * con le qualifiche per esteso, e **null quando non c'e'**.
 *
 * **Le cifre romane non sono un vezzo di Trenord: le scrive anche
 * ViaggiaTreno**, e nello stesso campo in cui altrove scrive in cifre arabe.
 * Sondato il 07/09/2026, il tabellone di Napoli Centrale dava il binario
 * programmato come "XV", "XIV", "XVI", "IX", "II" e quello effettivo della
 * stessa corsa come "15", "3", "13": ogni treno di quella stazione, appena
 * assegnato il binario, dichiarava un cambio che non era avvenuto. Lo stesso a
 * Torino Porta Nuova, dove un "XVII" stava in mezzo a tredici numeri arabi. La
 * segnalazione era arrivata da un caso piu' piccolo — il REG 24526 del
 * 07/09/2026, "bin. 2" barrato e "II" in evidenza a Melzo — ma il fenomeno era
 * molto piu' largo di quel treno.
 *
 * Si converte invece di limitarsi a confrontare, cosi' la cifra romana sparisce
 * anche dallo schermo: la stessa banchina non si legge in due modi a seconda di
 * chi l'abbia detta, e in stazione i cartelli sono in cifre arabe.
 *
 * Si converte il numero e le qualifiche di [QUALIFICATORI], nient'altro: "1"
 * e "1 tronco" restano due binari diversi, come sono. Il prezzo e' che un
 * ipotetico binario chiamato "V" uscirebbe come "5" — in Italia i binari si
 * numerano, non si nominano.
 */
fun binarioPulito(raw: String?): String? {
    val testo = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return testo.split(SPAZI).flatMap { spezza(it) }.joinToString(" ") { canonico(it) }
}

/**
 * Vero se le due scritture indicano la stessa banchina: "2" e "II" lo sono, e
 * anche "1 N" e "1N" — Venezia Santa Lucia, il 14/09/2026, scriveva l'uno nelle
 * partenze e l'altro negli arrivi.
 */
fun stessoBinario(uno: String?, altro: String?): Boolean =
    binarioPulito(uno)?.replace(" ", "").equals(binarioPulito(altro)?.replace(" ", ""), ignoreCase = true)

/**
 * L'effettivo, ma solo se dice qualcosa che il programmato non dice gia'.
 *
 * A Bologna Centrale, sondato il 14/09/2026, le Frecce della stazione AV
 * sotterranea avevano "19 AV" programmato e soltanto "AV" effettivo: il
 * piazzale, senza il numero. Letto come un binario, ogni Freccia di Bologna
 * dichiarava "AV, nuovo: era 19 AV", in rosso. Un effettivo senza cifre, fatto
 * solo di parole che il programmato contiene gia', non e' un binario diverso:
 * e' lo stesso detto con meno parole, e si tace. Da solo, senza programmato,
 * resta quello che c'e'. E un numero diverso resta un cambio vero: il 9618 del
 * giorno stesso passava dal 17 AV al 16 AV.
 */
private fun effettivoUtile(programmato: String?, effettivo: String?): String? {
    val vero = binarioPulito(effettivo)?.takeUnless { soloPiazzale(it) } ?: return null
    val annunciato = binarioPulito(programmato) ?: return vero
    if (vero.any { it.isDigit() }) return vero
    val parole = annunciato.lowercase().split(' ').toSet()
    return vero.takeUnless { v -> v.lowercase().split(' ').all { it in parole } }
}

/**
 * Solo parole di piazzale, nessun numero: "AV", "ovest", "tronco". Dice dove,
 * non su quale banchina.
 *
 * **Non e' un binario, in nessuno dei due campi.** Sondato il tabellone di
 * Bologna Centrale il 14/09/2026 fino a sera: " AV" era l'effettivo di ogni
 * Freccia della giornata, anche due ore prima della partenza, e su qualcuna
 * anche il programmato. Letto come binario ingannava in tre modi, tutti visti:
 *
 *  - come effettivo contro un programmato col numero: "AV, nuovo: era 19";
 *  - come programmato contro l'effettivo vero: "16, nuovo: era AV", un cambio
 *    inventato proprio sulla prima assegnazione;
 *  - uguale in tutti e due i campi: verde, confermato, due ore prima.
 *
 * Il terzo e il primo si erano mescolati: il dettaglio della corsa scrive "19"
 * senza "AV", il tabellone "AV" senza "19", e con le due letture unite la regola
 * precedente — "si tace se il programmato contiene gia' quelle parole" — non
 * reggeva piu'. Questa non guarda cosa c'e' dall'altra parte. Si mostra solo
 * quando non c'e' altro, e nero.
 */
private fun soloPiazzale(binario: String): Boolean =
    binario.none { it.isDigit() } &&
        binario.split(' ').all { parola -> QUALIFICATORI.values.any { it.equals(parola, ignoreCase = true) } }

/** Il programmato, se indica una banchina: vedi [soloPiazzale]. */
private fun programmatoVero(programmato: String?): String? =
    binarioPulito(programmato)?.takeUnless { soloPiazzale(it) }

/**
 * Il binario da mostrare: quello vero se c'e' e dice qualcosa, altrimenti quello
 * di tabella, e solo in mancanza d'altro il piazzale che una delle due ha scritto.
 */
fun binarioDaMostrare(programmato: String?, effettivo: String?): String? =
    effettivoUtile(programmato, effettivo) ?: binarioPulito(programmato) ?: binarioPulito(effettivo)

/**
 * Il binario vero non e' quello annunciato.
 *
 * Vale solo dove esistono **entrambi** i valori: una fonte che ne pubblichi uno
 * solo — Italo, EAV, Ferrotramviaria — non puo' dire "cambiato", puo' solo dire
 * qual e'. Un piazzale senza numero non e' un valore: vedi [soloPiazzale].
 */
fun binarioCambiato(programmato: String?, effettivo: String?): Boolean =
    programmatoVero(programmato) != null &&
        effettivoUtile(programmato, effettivo) != null &&
        !stessoBinario(programmato, effettivo)

/**
 * Il binario effettivo e' noto, e non contraddice quello annunciato.
 *
 * E' l'altra meta' di [binarioCambiato], e copre due casi:
 *
 *  - l'effettivo e' arrivato ed e' lo stesso del programmato: la conferma vera;
 *  - l'effettivo c'e' e un programmato con cui confrontarlo no. Italo, EAV,
 *    Ferrotramviaria e Trenord sulle stazioni FNM pubblicano un binario solo.
 *
 * Il secondo caso fino all'11/09/2026 restava nero, come un binario soltanto
 * previsto: "una fonte sola dice qual e', non che sia stato riconfermato". Da
 * quella data e' verde, deciso con l'utente: e' il binario su cui il treno
 * arriva, non una previsione, e il nero lo faceva sembrare meno certo di quanto
 * sia. Resta vero che una fonte sola non puo' dire "cambiato": vedi
 * [binarioCambiato].
 *
 * Serve perche' "4" e "4 confermato" non sono la stessa notizia. Chi guarda lo
 * schermo un quarto d'ora prima della partenza sta aspettando proprio quello, e
 * un tempo il binario confermato si leggeva identico a quello ancora solo
 * previsto: nero in tutti e due i casi, senza modo di sapere se ci si potesse
 * gia' andare.
 */
fun binarioConfermato(programmato: String?, effettivo: String?): Boolean =
    // Un piazzale senza numero non conferma e non si confronta: vedi [soloPiazzale].
    effettivoUtile(programmato, effettivo) != null &&
        (programmatoVero(programmato) == null || stessoBinario(programmato, effettivo))
