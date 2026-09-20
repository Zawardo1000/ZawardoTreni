# Le fonti: cosa danno davvero

Inventario del 19/09/2026, fatto dal codice dei client ufficiali (siti e app) e provato
dal vivo per ieri, oggi, domani, +3 e +30 giorni. È stato rifatto da capo perché due
risorse fondamentali — le fermate di un giorno qualunque da Le Frecce e i tabelloni
futuri di ViaggiaTreno — erano emerse solo dopo settimane di sviluppo.

I rapporti completi, fonte per fonte, con ogni endpoint, le prove e le trappole:

- [`fonti/LEFRECCE.md`](fonti/LEFRECCE.md) — le due porte del BFF Trenitalia, 460 metodi del sito censiti
- [`fonti/VIAGGIATRENO.md`](fonti/VIAGGIATRENO.md) — i 29 metodi registrati sul server, più tre extra
- [`fonti/TRENORD.md`](fonti/TRENORD.md) — BFF, gemello in chiaro, MIA pubblico, sito, open data
- [`fonti/MINORI.md`](fonti/MINORI.md) — Italo, EAV, Ferrotramviaria, orario svizzero

## Chi dà cosa

### Le fermate di una corsa

| | Oggi | Giorno futuro |
|---|---|---|
| **ViaggiaTreno** `andamentoTreno` | corsa intera, orari reali, binari | **no** (204); la corsa di ieri solo se in orario scavalca la mezzanotte (`h24`) |
| **Le Frecce** `stops?cartId&solutionId` (sito) o `search/{id}/solutions/{xmlId}/nodes/{idXml}/stops` (app, con `bdoCode`) | tratta percorsa, orari di tabella | **tratta percorsa, orari di quel giorno**, fino a fine orario (regionali 12/12/2026, Frecce ~20/03/2027); la corsa intera con una seconda ricerca `bdoOrigin → capolinea` |
| **Trenord** `train/{n}?date=` | corsa intera, reale, binari (programmati solo su FNM) | **corsa intera, variante di quel giorno**, fino all'11/12/2026; `[]` l'ultimo giorno di ogni variante |
| **Italo** | dettaglio (`RicercaTrenoService`) o, quando tace, `InfoRoute` del tabellone | no |
| **EAV** | GTFS imbarcato; ritardo per corsa dal pianificatore | GTFS imbarcato (fino al 31/12/2026) |
| **Ferrotramviaria** | `soluzioni/id/{id}` (tratta percorsa, in sessione) | idem, fino al 31/12/2026 |
| **Svizzera** | `connections`, `passList` | idem, fino a gennaio 2027 |

### Il binario

| | Oggi | Giorno futuro |
|---|---|---|
| **ViaggiaTreno** tabelloni `partenze`/`arrivi` | programmato ed effettivo | **programmato, da +1 a +8 giorni**, per partenze e arrivi. Cambia da un giorno all'altro: a Milano Centrale 10 corse su 33 hanno un binario diverso il 27/09 rispetto al 19 |
| **ViaggiaTreno** `andamentoTreno` | per ogni fermata | no |
| **Trenord** | effettivo, poco prima del treno; programmato solo sulle fermate FNM | **nessuno** |
| **Le Frecce** | mai: i campi esistono e sono sempre vuoti | mai |
| **Italo** | sul tabellone, dove RFI l'ha già assegnato | no |

Nessuna fonte ha il binario oltre gli 8 giorni.

### Il tempo reale e il riferimento della corsa

- **ViaggiaTreno** resta l'unica fonte di tempo reale per la rete nazionale.
- La chiave di `andamentoTreno` (origine, numero, data d'origine) la danno già **Le Frecce**
  (`bdoOrigin` nella ricerca del sito, `transportMeanDate` in `stops`: 12 su 12) e i
  **tabelloni** (`codOrigine`, `dataPartenzaTreno`). `cercaNumeroTreno` restituisce una corsa
  sola anche quando ce ne sono tre, e non va usato per risolvere.

### Il perché

| Fonte | Endpoint | Copre |
|---|---|---|
| ViaggiaTreno | `/resteasy/news/smartcaring?commercialTrainNumber&searchDate&originCode` | **regionali Trenitalia, corsa per corsa**, anche per date future: guasti, lavori, fermate sostituite |
| ViaggiaTreno | `/resteasy/news/infomobility` (JSON, `trainTags`) | Frecce, IC, EC ed eventi; lavori per regione |
| Trenord | `mia/direttrici/` | notizie per direttrice, anche sul singolo treno («Il treno 24564 … a causa di un guasto») |
| Trenord | `train/{n}` | `suppression_reason`, `alerts` (già usati) |
| Le Frecce | `messages[]` della soluzione, `services[]` di `stops` | «non effettua servizio viaggiatori», «posti esauriti sul treno N», «treno garantito in caso di sciopero» |
| search.ch | `route.it.json` | soppressioni e avvisi svizzeri in italiano, che `transport.opendata.ch` nasconde |

### Prezzi

- Le Frecce (sito): già usato.
- Trenord: ricerca (già usata) e **tariffario fra due stazioni senza data** (`mia/catalogue/products`).
- Ferrotramviaria: nella ricerca A→B.
- Italo: solo dietro Akamai, escluso per scelta (vedi [Su Italo](../README.md#su-italo-una-scelta-non-un-limite-tecnico)).

## Correzioni che l'inventario impone

Errori di oggi, indipendenti dalla riprogettazione:

1. **Svizzera: il numero del treno è la linea.** `SvizzeraMappers.toBoardEntry` legge `number`
   (`72`), il numero vero sta in `name` (`000041`): tutte le Panoramic Express della Vigezzina
   escono come «PE 72».
2. **Trenord `station-details`: negli arrivi c'è la destinazione, non l'origine.** Il tabellone
   la mostra come provenienza.
3. **Tabelloni ViaggiaTreno letti per «HH:mm»**: il rapporto lo segnala come rischio dopo la
   mezzanotte. Verificato: oggi non sbaglia, perché ogni confronto con l'ora (`minutesFrom`,
   `conRitardoDaFermo`) gira su mezza giornata e `dataPartenzaTreno` serve proprio come data
   d'origine della corsa. Gli orari in millisecondi (`orarioPartenza`, `orarioArrivo`) servono
   invece ai tabelloni futuri, dove il giorno va scritto.
4. **Il 204 di `andamentoTreno` non prova che il treno non circoli**: lo dà anche con un'origine
   o una data sbagliata.
5. **I numeri delle reti fuori-RFI si scontrano con quelli di ViaggiaTreno.** Misurato il
   20/09/2026: 3 corse EAV su 25 avevano il numero di un treno RFI in circolazione (2093
   Voghera, 5084 Salerno, 5136 Roma Ostiense). `resolveFor`, trovandone una sola, la
   restituiva senza guardare la salita, e il dettaglio di un treno EAV mostrava quello RFI.
   Corretto: salendo da EAV, Ferrotramviaria o ARST non si interroga la cascata nazionale.
6. **Documentazione da correggere**: in `CLAUDE.md`, il perché dei regionali (esiste,
   SmartCaring e direttrici) e la corsa di ieri (solo `h24`); in `API-EAV.md`, l'orizzonte del
   GTFS (31/12/2026, non maggio 2027) e il ritardo per corsa (esiste, pianificatore); il KDoc di
   `LefrecceApi.search` (vuole il fuso, non «senza offset»); il commento di `SvizzeraApi`
   (la `passList` delle partenze non è vuota).

## Dipendenze da HTML

L'HTML cambia senza avviso, e un parser che smette di trovare le righe non fa
rumore: si svuota. Dove esiste un JSON si usa il JSON (deciso il 19/09/2026).

| Dove | Serve a | Alternativa in JSON | Destino |
|---|---|---|---|
| ViaggiaTreno `infomobilitaRSS` (`InfomobilitaParser`) | le notizie con la corsa collegata | `/resteasy/news/infomobility`, con `trainTags` | **sostituita**: il JSON prima, la pagina solo se il JSON non risponde o torna vuoto (`notizieDiOggi`). Del corpo resta HTML scritto dalla redazione, in tutte e due |
| Trenord `station-details` (`TrenordBoardParser`) | sapere quali treni sono Trenord | `codiceCliente` 63 sulla corsa di ViaggiaTreno (`TrainStatus.impresa`) | **sostituita** dove la corsa dice l'impresa; resta per le corse che non la dicono |
| Trenord `station-details` | rimettere in tabellone le corse soppresse, che ViaggiaTreno cancella | nessuna trovata (il GTFS Trenord le ha, ma va imbarcato) | resta, sotto `LiveApiTest` |
| EAV `ws_getData_pis.php` (`EavBoardParser`) | il tabellone EAV | nessuna per il tabellone; il ritardo per corsa sta nel pianificatore (JSON) | resta, sotto `EavTabelloneTest` e i test dal vivo |

## Proposta di riprogettazione

> **Stato al 20/09/2026: fatta.** Quel che segue e' la proposta com'e' stata
> scritta il 19, e si legge al presente: descrive l'app *di allora*. Nel codice di
> oggi il giorno futuro si legge per quel giorno — fermate da Trenord o da Le
> Frecce (`JourneyRepository.corsaDelGiorno`), binari dai tabelloni di quella data
> (`CaricatoreCorsa.conBinariDelGiorno`) — e le funzioni nominate qui sotto non
> esistono piu'. Il documento resta perche' spiega **perche'** si e' cambiato.

### 1. Il giorno futuro, senza la corsa di oggi

Oggi fermate, orari e binari di un giorno futuro si ricavano dalla corsa di oggi con lo
stesso numero (`CaricatoreCorsa.previstoDaOggi`, `soloOrarioPrevistoPer`,
`conBinariDiTabellaDaOggi`). È sbagliato in tre modi: un treno che oggi non circola resta
senza niente (i regionali domenicali), una corsa oggi variata presta il percorso sbagliato,
e il binario di oggi non è quello di quel giorno.

Al suo posto, per la data giusta:

- **fermate e orari**: Trenord `train/{n}?date=` dove copre, altrimenti Le Frecce `stops` della
  soluzione (la tratta percorsa; la corsa intera con una seconda ricerca dall'origine);
- **binari**: i tabelloni ViaggiaTreno di quel giorno, partenze alla salita e arrivi alla
  discesa, entro +8 giorni; oltre, nessun binario, detto come tale.

Si butta la derivazione dalla corsa di oggi.

### 2. La corsa di oggi, trovata senza indovinare

Il riferimento per `andamentoTreno` viene da `bdoOrigin` e dalla data d'origine, che ricerca e
tabelloni già danno, invece di `cercaNumeroTreno` più tentativi. Risolve i numeri doppi (il 178
EuroCity e regionale) e le corse notturne.

### 3. Il perché, per tutti

SmartCaring per i regionali Trenitalia, `news/infomobility` in JSON al posto del parsing
dell'HTML dell'RSS, direttrici Trenord, messaggi e servizi delle soluzioni Le Frecce.

**Fatto il 19/09/2026**, tranne i `services[]` di `stops`, che costano una chiamata per
soluzione: `InfomobilitaParser.daJson` (coi `trainTags` degli eventi, che la pagina non ha),
`TrainStatusRepository.conNoteDelGiorno` (SmartCaring), `NotizieDirettrici` e
`notizieDiTrenord`, `avvisiDelSito` sulla scheda dell'elenco. In più `codiceCliente`
(`TrainStatus.impresa`): a Trenord non si chiede piu' un treno che non e' suo. Il riassunto
delle cinque fonti del perché sta in `CLAUDE.md`.

### 4. Reti minori

- **EAV**: ritardo e soppressione per corsa dal pianificatore *(fatto il
  20/09/2026)*; «in banchina» dal monitor *(fatto)*.
- **Ferrotramviaria**: ricerca A→B con data, fermate e prezzo (oggi c'è solo il tabellone).
- **Italo**: il percorso da `InfoRoute` quando il dettaglio tace *(fatto il 20/09/2026)*.
- **Svizzera**: arrivi *(fatto)*, e le soppressioni e i ritardi in arrivo da `search.ch`
  *(fatto il 20/09/2026)*.

### 5. Meno traffico

Trenord ha un gemello in chiaro di `train` e `hafas/v2`
(`www.trenord.it/mgmt/store-management-api/mia/`) con gli stessi dati in 2-3 KB invece di
25-35 KB: su «Segui treno» è un ordine di grandezza di dati mobili. Il sito però non lo chiama,
e potrebbe sparire: va usato col BFF attuale come riserva e presidiato da un test dal vivo.

## Decisioni (19/09/2026)

1. **Corsa intera o tratta percorsa** nel giorno futuro: la tratta costa la ricerca che c'è già
   più `stops`; la corsa intera una ricerca in più, e il capolinea va preso altrove.
   **Deciso: la corsa intera**, «dalla partenza all'arrivo anche se io uso solo 2 fermate».
   Fatto (`JourneyRepository.corsaDelGiorno`).
2. **Il gemello in chiaro di Trenord**, col rischio che sparisca. **Deciso: sì, documentato, col
   BFF cifrato di riserva.** Fatto (`TrenordRepository.inChiaroOCifrato`).
3. **I cantieri Trenord** passano da un servizio terzo (Yext, `yextapis.com`) con la chiave
   pubblica scritta nella pagina di Trenord: tocca la regola «una chiave in un'app è una chiave
   pubblicata» e l'informativa privacy, che quel dominio non lo nomina. **Deciso: dentro, con un
   ripiego se il servizio muore.** Fatto il 20/09/2026 (`CantieriApi`), con l'informativa
   aggiornata prima: la chiave è quella pubblica di lettura della loro pagina, e gli avvisi
   escono in cima ai risultati.
4. **`search.ch`** per le soppressioni svizzere: dominio nuovo per l'informativa, limite
   dichiarato di 1.000 ricerche e 10.080 tabelloni al giorno. **Deciso: sì**, solo come
   aggiunta con ripiego (il limite dichiarato non dice se sia per IP). Fatto il 20/09/2026:
   un tabellone per tabellone aperto, e il ritardo degli arrivi che l'involucro non dà.
5. **La ricerca Ferrotramviaria**: funzione nuova, oggi di quella rete c'è solo il tabellone.
   **Deciso: adesso.** Fatta il 20/09/2026: ricerca A→B per qualunque giorno, fermate della
   corsa, prezzo e bus sostitutivo (`FnbRepository.itinerario` e `dettaglioCorsa`). La
   sessione va aperta **prima** della ricerca: vedi `data/fonti/MINORI.md`.
6. **Italo**: futuro e prezzi restano dietro Akamai; la scelta di non aggirarlo resta.

Ogni dominio nuovo va dichiarato nell'informativa (`docs/privacy.html`) prima di essere
chiamato: oggi sono nuovi `planner.eavsrl.it`, `search.ch`, `yextapis.com`.
