> Rapporto d'inventario del 19/09/2026. I campioni grezzi e gli script di prova citati
> (`analisi/lefrecce/...`) stanno fuori dal repository, in `.tools/analisi-fonti/lefrecce/`:
> contengono cookie di sessione e pesano qualche megabyte. Panoramica e proposta in
> [`../FONTI.md`](../FONTI.md).

# Le Frecce: inventario delle API pubbliche (sito e app)

Sondate dal vivo sabato 19/09/2026, fra le 18:50 e le 19:57, senza login e con una pausa di almeno 0,7 s
fra una chiamata e l'altra. Script, uscite e risposte grezze (tagliate) stanno in `analisi/lefrecce/`:
ogni affermazione qui sotto rimanda al file `out_sNN.txt` che la prova.

## Le risposte in breve

| Domanda | Risposta | Prova |
|---|---|---|
| (a) Binari di tabella per un giorno futuro? | **No.** Nessuna risposta pubblica ha un binario. Nel sito la parola non compare in nessun template (c'e' solo l'etichetta i18n `generic.platform` = "Binario", mai usata, e un'icona nella galleria del tema). Nell'app i campi `plannedPlatform`/`actualPlatform` esistono ma sono **sempre null**: ieri, domani, oggi 5 minuti prima della partenza e sui treni partiti da 20-25 minuti. | out_s03, out_s15 |
| (b) Tutte le fermate di una corsa? | **Non con una chiamata sola**: `stops` da' solo la tratta percorsa, per qualunque data. **Si' con una seconda ricerca**: `ticket/solutions` da `bdoOrigin` al capolinea, `noChanges: true`, filtrata sul numero, e poi `stops`. Il capolinea Le Frecce non lo dice: si prende dalla corsa di oggi su ViaggiaTreno (`idDestinazione`). Verificato: FR 9583 del 19/10 Torino P.N. - Reggio Calabria, 15 fermate su 15 come la corsa di oggi; RE 2643 del 22/09, 11 su 11. Se quel giorno il treno non circola non esce (RE 10943 di domenica 20/09). | out_s02, out_s14, out_s19, out_s22 |
| (c) Treno per numero in una data futura? | **Non direttamente.** La ricerca non ha filtro per numero (`criteria` non ha un campo treno). Nel bundle ci sono `boTrainDelay/train/delay/search?trainNumber&date` e `cambio/deroga/train/search?trainNumber&date`, ma sono del **backoffice** (non provati, per regola). In pratica si': origine e capolinea dalla corsa di oggi (ViaggiaTreno), poi la ricerca del punto (b) sulla data voluta, filtrata sul numero. Non funziona per un treno che oggi non circola. | servizi_sito.txt, out_s22 |
| (d) Stato in tempo reale (ritardi, posizione)? | **No.** Il sito, per "Stato treno" e "Infomobilita'", apre `viaggiatreno.it`. La porta app ha solo i campi vuoti (`transportMeanEvents`, `updateTime`, `updateLocation`) e il flag `showTrainStatus`; nessuna risorsa `trainstatus`/`realtime`/`infomobility` esiste (404). Pero' Le Frecce da' **la chiave esatta per ViaggiaTreno**: `bdoOrigin` + `transportMeanDate` -> `andamentoTreno`, 12 treni su 12 senza passare da `cercaNumeroTreno`. | out_s08, out_s09, out_s16 |
| (e) Avvisi per soluzione o per treno? | **In parte, e non di circolazione.** Per soluzione: `messages[]` ("Il treno non effettua servizio viaggiatori", "Posti Esauriti sul treno 9588", "due convogli non comunicanti", "giorno successivo", motivi di non vendita). Per tratta: `services[]` di `stops` ("Treno garantito in caso di sciopero", materiale ETR, bus FrecciaLink). Nessun ritardo, guasto o sciopero in corso: `news/latest` risponde `rss.not.found`, `information/company/message` `[]`, `highlightedMessages` vuoto in 32 ricerche su 32. | out_s13, out_s17 |

## Le due porte

Lo stesso BFF si presenta con due facce, e si comportano in modo diverso anche negli errori.

| | Porta del sito | Porta dell'app |
|---|---|---|
| Base | `https://www.lefrecce.it/Channels.Website.BFF.WEB/website/` | `https://app.lefrecce.it/Channels.Website.BFF.WEB/app/` |
| Stile | a servizi, parametri in query, stato nel **carrello** (`cartId`) | REST annidato sotto `search/{searchId}` |
| Sessione | cookie `WSESSIONID` lega il `cartId` | cookie `ASESSIONID` lega il `searchId` (scade dopo 15 min, `expirationDate` nella risposta) |
| Path inesistente | **400** generico "Per favore riprova piu' tardi", uguale a un guasto | **404** con corpo vuoto: si riconosce |
| Errori | JSON `{type:"ERROR", reason, message}`; `reason` "SESSION EXPIRED" o "NPE" | testo semplice, **in lingua a caso** (IT/EN/FR) |
| Codici stazione | `locationId` (8300xxxxx) senza `bdoCode` | `locationId` **e** `bdoCode` (S01700) e coordinate |
| Filtro anti-bot | Akamai Bot Manager (cookie `_abck`, `bm_sz`, `bm_s`, script sensore nella pagina) | cookie Akamai `_abck`, `ak_bmsc`, `bm_sz` |

## Endpoint

"Usato" = gia' chiamato da ZawardoTreni oggi. Latenze misurate il 19/09 sera. Le date: "passato" vuol dire
almeno -60 giorni (non vendibile, ma risponde); "regionali" fino al 12/12/2026, "Frecce" fino al 20/03/2027 circa.

### Porta del sito — ricerca e soluzioni

| Metodo | Path | Parametri | Restituisce | Date | Limiti / note | Usato |
|---|---|---|---|---|---|---|
| POST | `ticket/solutions` (`?bestFare=true` se `bestFare`) | corpo: `departureLocationId`, `arrivalLocationId`, `departureTime` (locale senza fuso), `adults`, `children`, `criteria{frecceOnly, regionalOnly, intercityOnly, tourismOnly, noChanges, order, offset, limit}`, `advancedSearchRequest{bestFare, bikeFilter, offerIds, loyaltyCodes, essentialServices, forwardDiscountCodes...}`; opzionali `returnDepartureTime`, `cartId`, `forwardSolutionId` | `searchId`, **`cartId`**, `highlightedMessages`, `solutions[]`: `solution{id, origin, destination, departureTime, arrivalTime, duration, status, trains[], price, nodes[{id, origin, **bdoOrigin**, destination, orari, salable, train}]}`, `messages[]`, `nextDaySolution`, `co2Emission`, `stopList` (sempre vuoto) | passato, oggi (anche orari gia' passati), regionali fino al 12/12/2026, Frecce fino al 20/03/2027 (27/03 no) | 10 per pagina a qualunque `limit`; `order` = `DEPARTURE_DATE`, `ARRIVAL_DATE`, `FASTEST`, `CHEAPEST`; `status` = `SALEABLE`, `NOT_SALEABLE`, `SOLD_OUT`, `INHIBITED`; oltre l'orizzonte 400 `silent:true` "Non abbiamo trovato nessuna soluzione"; 1,4-4,3 s | si' (prezzi) |
| GET | `stops` | `cartId`, `solutionId`, opz. `nodeId` = id della **griglia** (`grids[].id`, con la "x"), non del nodo | una voce per tratta: `summary{name, description, duration, highlightedMessage, urban, vehicleInfo (sempre null), trainInfo{acronym, name, denomination, description, logoId, trainCategory}, **bdoOrigin**, showInfomobilityLink}`, `stops[{location{id, name, displayName, timezone, multistation, centroidId}, arrivalTime, departureTime, trainNumber, **transportMeanDate**}]`, `services[{description}]` | tutte quelle della ricerca (provati -7, -3, -2, -1, oggi, +1, +3, +30, +90, +120, +180) | **solo tratta percorsa**; niente binari; `showInfomobilityLink` solo oggi; `nodeId` di un nodo di una soluzione a piu' treni -> 400 NPE (la griglia raggruppa i treni di un biglietto: il REG 2217+2699 ha una griglia sola); 0,5-1,2 s | no |
| GET | `grid` | `cartId`, `solutionId` | per tratta: livelli di servizio con prezzo minimo, offerte, `summaries` (con `bdoOrigin`) | come la ricerca | 1,4 s | no |
| GET | `customize`, `customize/nosearch` | `cartId`, `solutionId` | soluzione + `elements[]` con prezzi e riassunti | come la ricerca | risposta grossa (270 KB) | no |
| GET | `seatmap/{cartId}` | `solutionId`, `nodeId` | carrozze del livello scelto (numero, tipo, posti con `available`), `etrName` | come la ricerca | FR 9583 di domani: carrozze 7-11, liberi 7/68, 7/68, 12/68, 4/68, 12/68 | no |
| GET | `route/details` | `cartId`, `solutionId`, `offerId` | dettaglio della tratta degli **abbonamenti** (template `subscription/route-detail`) | - | su un biglietto normale 400 NPE | no |
| GET | `cart` | `cartId` | carrello (vuoto finche' non si compra) | - | - | no |
| GET | `informative/{cartId}`, `ancillaries/solution/list`, `travellers`, `promo/{cartId}` | `solutionId`... | note, accessori, viaggiatori, promo del carrello | - | 400 NPE finche' la soluzione non e' nel carrello: fasi d'acquisto | no |

### Porta del sito — stazioni

| Metodo | Path | Parametri | Restituisce | Note | Usato |
|---|---|---|---|---|---|
| GET | `locations/search` | `name`, `limit` | `[{id, name, displayName, timezone, multistation, centroidId}]` | niente `bdoCode`, niente coordinate; 0,45 s | no |
| GET | `locations/search/location` | `id` | una stazione | 0,4 s | no |
| GET | `locations/closest` | **`latitude`, `longitude`** | la stazione piu' vicina | con `lat`/`lon` risponde 200 con "KOUSSAIR (LB)" | no |
| GET | `locations/search/byzone`, `.../byzonename` | `name`, `zone`, `limit` | - | nel bundle ma mai chiamati; non provati | no |

### Porta del sito — configurazione e contenuti

| Metodo | Path | Esito | Contenuto utile |
|---|---|---|---|
| GET | `channel/config` | 200 | `customerArea.trainInfo = {trainStatus: true, infoMobility: true}`: sono solo **link** a viaggiatreno.it e a trenitalia.com |
| GET | `channel/default`, `info/properties`, `grid/getGridColumn` | 200 | canale 41 WEB, baseline 1188: nulla di utile |
| GET | `ticket/offers`, `ticket/groups/offers`, `ticket/fast/searchpage`, `subscription/searchpage` | 200 | catalogo offerte e abbonamenti |
| GET | `news/latest` | 400 | `"rss.not.found"`: il feed notizie del sito e' spento |
| GET | `information/company/message`, `promo/all` | 200 | `[]` |
| GET | `info/cache` | 400 | - |
| POST | `content/management/banners` | 400 | corpo non indovinato; sono banner commerciali |
| GET | `travel/solutions/tpl` | timeout 40 s | - |

### Porta del sito — esistono ma non si usano

- **Backoffice** (operatori Trenitalia, non provati): `boTrainDelay/train/delay/search?trainNumber&date`,
  `boTrainDelay/train/delay/detail?trainNumber&date&departureId&arrivalId`, `boTrainDelay/exportDelayList`,
  `cambio/deroga/train/search?trainNumber&date`, `backoffice/viewTrainTrendInformation?resourceId`,
  `delays/cluster/*` (17 metodi), `emv/searchInfrastructureUnavailabilityEvents` (e' il backoffice dei
  validatori tap&tap, non la rete).
- **Con login**: `travel/*` (viaggi acquistati, `travel/detail`, `travel/next/solutions`, `travel/history`),
  `profile/*`, `loyalty/*`, `travel/notification/{resourceId}`.
- **Acquisto**: `cart/*`, `reservation/create`, `payment/*`, `seatmap/{cartId}/update`...

Inventario completo, 460 metodi di 68 servizi con verbo, path e parametri: `analisi/lefrecce/servizi_sito.txt`
(estratto da `estrai_servizi.py` sui 173 chunk del sito, dalle stringhe di debug `XxxService - metodo(...)`).

### Porta dell'app

| Metodo | Path | Parametri | Restituisce | Date | Limiti / note | Usato |
|---|---|---|---|---|---|---|
| GET | `locations` | `name`, `limit`, `multi`, `zonaFrecce` | stazioni con `locationId`, `bdoCode`, coordinate, alias | - | 0,44 s | si' |
| GET | `locations/closest` | **`lat`, `lon`**, `withbdo` | una stazione | - | con `latitude`/`longitude` risponde 200 con "Cartagena" | si' |
| GET | `search` | `startlocationid`, `endlocationid`, `departure_time` **con fuso** (`...000+02:00`), `arflag`, `adultno`, `childno`, `direction`, `frecce`, `regional`, `intercity` | `searchId`, `totalSolutions`, `expirationDate` (+15 min), `searchCriteria` | passato, oggi, futuro | senza fuso cerca **da mezzanotte**; 1-2 s | si' |
| GET | `search/{searchId}/solutions` | `offset`, `limit` | soluzioni con `xmlId`, `solutionNodes[]` (`idXml`, stazioni con `bdoCode`, `offeredTransportMeanDeparture`, `transitNodes`, `plannedPlatform`/`actualPlatform`/`transportMeanEvents`/`updateTime`/`updateLocation` **sempre vuoti**, `showTrainStatus`), prezzi, `travelSolutionMessages`, `inhibitedMessages`, `saleabilityMessages` | come sopra | **Milano C.le -> Malpensa T1: 0 soluzioni** (il sito ne da' 10) | si' |
| GET | `search/{searchId}/solutions/{xmlId}` | - | **nuovo**: `travelSolution` completa: `selectedOffers` (prezzo, `availableAmount`, validita'), `solutionServices`, `co2Emission`, prezzo per nodo | come sopra | 0,6 s | no |
| GET | `search/{searchId}/solutions/{xmlId}/nodes/{idXml}/stops` | - | **nuovo**: `stops[{location{locationId, **bdoCode**, name, coordinate, alias}, arrivalTime, departureTime}]`, `services[{servicedesc, imagedata (PNG base64), imagetype}]` | provati -1, oggi, +1, +30, +120 | solo tratta percorsa; niente `transportMeanDate`, `bdoOrigin`, `trainNumber`, binari; nodo urbano -> 500; 0,5-1,4 s | no |
| GET | `search/{searchId}/solutions/{xmlId}/grid` | - | offerte per tratta | - | 0,4 s | no |
| GET | `search/{searchId}/solutions/{xmlId}/customize` | - | elementi personalizzabili | - | 1,1 s | no |
| GET | `search/{searchId}/solutions/{xmlId}/nodes/{idXml}/seatmap` | - | carrozze e posti | - | 0,9 s | no |
| GET | `configuration` | - | flag: `nextDeparturesEnabled: false`, `busSearchEnabled: false`, `transportMeanSearchFrequency: "180"`, `gpsTrackingFrequency: "120"`... | - | la ricerca "per mezzo" e i "prossimi treni" dell'app ufficiale sono spenti o altrove | no |
| GET | `cart` | - | crea un carrello vuoto che scade dopo 30 min | - | - | no |
| GET | `stops`, `grid`, `customize` (alla radice) | qualunque | sempre 500 "No travel solution selected" | - | non e' la strada: la strada e' la risorsa annidata | no |
| GET | `travels`, `profile` | - | 401 | - | viaggi acquistati: con login | no |

Cercati e **non esistenti** (404) sulla porta app: `trainstatus`, `trainStatus`, `train/status`, `trains`,
`trains/{n}`, `status`, `infomobility`, `news`, `alerts`, `realtime`, `timetable(s)`, `stations`, `departures`,
`arrivals`, `board(s)`, `nextdepartures`, `transportmean(s)[/search]`, `train/search`, `gps`, `tracking`,
`disruptions`, `strikes`, e sotto la soluzione/il nodo: `nodes/{id}`, `.../trainstatus`, `.../status`,
`.../realtime`, `.../info`, `.../services`, `informative`, `messages`, `offers`. Elenco completo in
`out_s07.txt`, `out_s08.txt`, `out_s09.txt`.

Come si sono trovate le risorse annidate: la porta app risponde 404 vuoto ai path che non conosce e un
messaggio a quelli che conosce, quindi si distinguono; `stops` alla radice diceva "No travel solution
selected", e la forma REST di `search/{searchId}/solutions` suggeriva il resto (out_s05, out_s06).

## Durata e legame della sessione

- Il `cartId` del sito **vale solo col suo cookie** `WSESSIONID`: con una sessione nuova, un carrello aperto un
  secondo prima risponde 400 "SESSION EXPIRED" (il messaggio mente: non e' scaduto, manca il cookie).
  Copiando a mano il cookie torna 200 (out_s11).
- Il `searchId` dell'app vale solo col suo `ASESSIONID`: senza, 410 "Oops! The session has expired" anche su
  `nodes/.../stops` (out_s11). `expirationDate` = ricerca + 15 minuti.
- Il carrello del sito delle 10:52 di stamattina, alle 18:55, dava "SESSION EXPIRED" (ma era anche senza cookie).
- **Quanto vivono**, misurato col proprio cookie (out_s12a, out_s20; sette sessioni aperte alle 19:32:57):

  | | toccato ogni 4 min | fermo |
  |---|---|---|
  | `cartId` del sito | vivo a 4, 8, 12 min; **morto a 16** | vivo a 8 e a 13 min; morto a 18 e a 21 |
  | `searchId` dell'app (scadenza dichiarata 19:47:47) | vivo a 4, 8, 12 e **16 min** (45 s oltre la scadenza); morto a 20 | vivo a 13; morto a 16 |

  Il carrello del sito ha una vita **fissa fra 13 e 16 minuti**: usarlo non la allunga. Il `searchId` dell'app
  muore a `expirationDate` (15 min) se non lo si tocca, e toccarlo gli concede al piu' qualche minuto di grazia.
  Regola pratica per entrambi: 15 minuti dalla ricerca, poi si ricerca. Riusare un `cartId` dopo 20 minuti,
  come chiedeva la verifica, non funziona in nessun caso.

## Cose che l'app non usa e dovrebbe

### 1. `bdoOrigin` + `transportMeanDate`: la corsa di ViaggiaTreno senza indovinarla

Oggi `TreniRepository.resolveFor` chiama `cercaNumeroTreno` e, se le corse sono piu' d'una, `andamentoTreno`
su ciascuna per vedere quale passa dalla stazione di salita all'ora giusta. Le Frecce la chiave la dice gia':

- `bdoOrigin` sta **dentro `ticket/solutions`**, nodo per nodo — la stessa risposta che l'app chiede gia' al sito
  per i prezzi, quindi non costa chiamate;
- `transportMeanDate` sta in `stops` (una chiamata per soluzione) ed e' **la data di partenza dall'origine**, non
  quella della salita: NI 758, salita a Bologna il 20/09 alle 04:56, `transportMeanDate` 19/09, origine S11145.

Controllo del 19/09 (out_s16): per 12 treni di oggi (Trenord RE 10941/10943/2641/2643/2974/2976/379/383,
FR 9427/9551, RE 5895/21437), `andamentoTreno/{bdoOrigin}/{numero}/{mezzanotte di transportMeanDate}` ha risposto
200 con la corsa giusta **12 volte su 12**, e il codice coincideva ogni volta con quello di
`cercaNumeroTrenoTrenoAutocomplete`. Per i treni di domani ViaggiaTreno risponde 204, come previsto. Anche i treni
FNM passano: RE 379 ha origine S01066 (Milano Cadorna). Sul nodo urbano o a piedi `bdoOrigin` e' null.

Vale soprattutto dove `resolveFor` oggi e' debole: numeri doppi nello stesso giorno (il 178 EuroCity e
regionale), corse notturne partite ieri, e il giorno dopo per chi ha salvato un viaggio.

### 2. Le fermate di tabella di qualunque giorno, anche futuro

`stops` (sito) e `nodes/{idXml}/stops` (app) danno le fermate della tratta percorsa con orari di arrivo e
partenza **per la data chiesta**, da almeno 60 giorni fa a fine orario (regionali fino al 12/12/2026, Frecce
fino al 20/03/2027 circa). ViaggiaTreno per un giorno futuro non da' niente. La versione dell'app ha gia' il
`bdoCode` su ogni fermata; quella del sito ha il `locationId`, da cui il codice RFI con la relazione nota
(`830000000 + cifre`), piu' `trainNumber` e `transportMeanDate` per fermata.

Per avere **la corsa intera** di un giorno futuro bastano l'origine e il capolinea: una ricerca
`bdoOrigin -> capolinea` con `noChanges: true`, il filtro sul numero, e `stops` sulla soluzione trovata. L'origine
la da' Le Frecce (`bdoOrigin`); il capolinea no, e si prende dalla corsa di oggi su ViaggiaTreno
(`idDestinazione` di `andamentoTreno`), che per un treno di tabella e' quasi sempre lo stesso.

- FR 9583 del 19/10 (out_s22): Torino P.N. 08:00 - Torino P. Susa - Milano C.le 09:02/09:10 - Rogoredo - Reggio
  Emilia AV - Bologna - Firenze SMN - Roma T. 12:49/13:01 - Napoli C.le - Salerno - Paola - Lamezia - Rosarno -
  Villa S. Giovanni - Reggio Calabria C.le 19:11: 15 fermate, le stesse 15 della corsa di oggi su ViaggiaTreno.
- RE 2643 del 22/09: Milano C.le 21:25 - ... - Verona P.N. 23:17, 11 su 11.
- RE 10943 di domenica 20/09: non esce, e la corsa successiva e' il 10945. Un treno che quel giorno non circola
  non viene inventato — ma va distinto da una ricerca con l'ora sbagliata: le soluzioni sono 10 per pagina.

Senza ViaggiaTreno (treno che oggi non circola) si ottiene comunque il tratto dall'origine alla discesa, con la
ricerca `bdoOrigin -> stazione di discesa` (out_s14).

### 3. I messaggi della soluzione, che dicono perche'

Censimento su 32 ricerche, 8 tratte e 4 date (out_s13), campo `messages[]` del sito:

| `status`/`imageId` | Testo | Utile perche' |
|---|---|---|
| INFO | "**Il treno non effettua servizio viaggiatori**" | FR 9716 Venezia S.L. - Mestre del 20/09, soluzione `NOT_SALEABLE`: una tratta non commerciale che nessun'altra fonte segnala in anticipo |
| INFO | "**Posti Esauriti sul treno 9588**. Soluzione non acquistabile." | dice **quale** treno della soluzione e' esaurito |
| WARNING/attention | "Il treno 721 e' composto da due convogli non comunicanti tra loro (carrozze da 1 a 4 e da 11 a 14)..." | salire sulla parte giusta |
| WARNING/calendar | "La soluzione fa riferimento al giorno successivo", "L'orario di arrivo fa riferimento al giorno successivo" | gia' deducibile dagli orari |
| INFO | "Le soluzioni di viaggio regionali non sono vendibili con anticipo inferiore ai 5 minuti", "Impossibile acquistare un viaggio precedente alla data corrente.", "Soluzione temporaneamente non acquistabile" | il perche' del "non in vendita" |
| WARNING/family | "Area Family presente in carrozza 3 su InterCity 1545" | marginale |

E in `services[]` di `stops`, per tratta: "**Treno garantito in caso di sciopero** nei giorni feriali e festivi"
(26 volte), "Treno garantito in caso di sciopero nazionale per il rinnovo del contratto..." (16), materiale
("Treno effettuato con ETR 1000 / ETR500 / ETR 600"), "Carrozza letti/cuccette", indirizzi delle fermate bus
FrecciaLink, "Servizio di Trenord". Nei giorni di sciopero la prima e' esattamente la domanda che la gente fa.

### 4. Minori

- `seatmap`: posti liberi per carrozza nel livello scelto; un indice di affollamento per le Frecce. Dato
  commerciale, da usare con prudenza.
- `availableAmount` nelle offerte del dettaglio app (48 sul FR 9505 di domani): probabilmente posti a quel
  prezzo; non verificato.

## Trappole

1. **La porta app vuole il fuso nell'ora** (`2026-09-20T08:00:00.000+02:00`): senza, cerca da mezzanotte e
   restituisce i treni dell'alba. Il codice lo sa gia' (`TreniRepository.bffFormat`), ma il KDoc di
   `LefrecceApi.search` dice ancora "ISO locale senza offset, es. 2026-08-28T08:00:00.000": va corretto. La porta
   del sito invece vuole l'ora **senza** fuso.
2. **Parametri col nome sbagliato ignorati in silenzio.** `locations/closest` vuole `latitude`/`longitude` sul
   sito e `lat`/`lon` sull'app; coi nomi dell'altra porta risponde 200 con una stazione a caso ("KOUSSAIR (LB)",
   "Cartagena").
3. **Sul sito un path inesistente e un guasto danno lo stesso 400** "Per favore riprova piu' tardi". Un endpoint
   scritto male sembra un servizio instabile. `reason: "NPE"` vuol dire di solito "manca uno stato del carrello".
4. **"SESSION EXPIRED" non vuol dire scaduto**: vuol dire anche "cookie mancante". Stessa cosa per il 410
   dell'app.
5. **La lingua dei testi dell'app e' a caso**: con `Accept-Language: it-IT` la stessa sessione ha risposto in
   italiano, inglese e francese ("Espace famille disponible dans la voiture 3"). I testi da mostrare vanno presi
   dal sito, che risponde in italiano.
6. **Orizzonte diverso per prodotto**: dal 13/12/2026 (cambio orario) i regionali spariscono ("Non abbiamo
   trovato nessuna soluzione", 400 `silent:true`), le Frecce restano fino al 20/03/2027 circa. Un regionale che
   manca a gennaio non e' soppresso: non e' ancora caricato.
7. **`stops` e' la tratta percorsa**, non la corsa: la prima fermata ha anche l'`arrivalTime` (la sosta alla
   stazione di salita), l'ultima non ha `departureTime`.
8. **`transportMeanDate` e' la data d'origine**: per `andamentoTreno` va usata quella, non la data della salita,
   o i notturni saltano di un giorno.
9. **Il `nodeId` di `stops` non e' l'id del nodo ma quello della griglia** (`grids[].id` della soluzione). Coincidono
   solo quando la soluzione ha un treno solo: per questo una prova su un FR diretto passa e una su REG 2217 + REG
   2699 da' 400 NPE con entrambi gli id dei nodi, e 200 con l'id della griglia, che li restituisce tutti e due
   (out_s21). Senza la "x" iniziale, 400 NPE. Il sito non lo passa mai: chiama `stops` senza `nodeId` e riceve
   tutte le tratte, che e' anche la cosa piu' semplice da fare.
10. **La porta app perde soluzioni che il sito ha**: Milano Centrale -> Malpensa T1 del 20/09 alle 09:00 e del 19/09
    alle 20:00, `totalSolutions: 0`; il sito ne da' 10 (il 2932 diretto, e il 327 da Cadorna col tratto urbano Centrale-Cadorna).
    Saronno -> Malpensa sull'app funziona (out_s18).
11. **I campi di binario e di stato dell'app non si riempiono mai** in una ricerca anonima: provato fino a 5
    minuti dalla partenza e su treni partiti da 20-25 minuti. `showTrainStatus` diventa `true` vicino alla partenza
    su FR, NI e RV, ma non porta dati. Plausibile che si riempiano solo nei viaggi acquistati (`travels`, 401).
12. **La ricerca risponde anche per il passato** (almeno 60 giorni, `NOT_SALEABLE`/`INHIBITED`) e per orari di
    oggi gia' passati: comodo, ma una soluzione restituita non vuol dire un treno ancora da prendere.
13. **`showInfomobilityLink` e' vero solo per oggi**, e il sito lo usa per aprire
    `viaggiatreno.it/infomobilitamobile/pages/cercaTreno/cercaTreno.jsp?treno=N&origine=bdoOrigin&datapartenza=ms`:
    la conferma, dal codice di Trenitalia stessa, che `bdoOrigin` e' il codice d'origine di ViaggiaTreno.
14. **Akamai davanti a www.lefrecce.it**: le chiamate semplici passano (nessun blocco in alcune centinaia di chiamate a
    ritmo di una al secondo), ma il sito carica uno script sensore anti-bot; una raffica potrebbe cambiare le cose.
15. `travel/solutions/tpl` resta appeso 40 secondi: non va chiamato con timeout lunghi.

## Non verificato, e come si verificherebbe

- **Gli endpoint dell'app ufficiale Trenitalia** oltre a quelli trovati sondando: la porta app e' stata
  esplorata per tentativi (404 = non c'e'), non dal codice dell'app. La configurazione nomina una ricerca "per
  mezzo" (`transportMeanSearchFrequency`) e un tracciamento GPS che qui non si sono trovati; lo stato treno
  dell'app vive probabilmente nei viaggi acquistati (`travels`, 401). Per saperlo davvero servirebbe l'APK.
- **`bdoOrigin` di una corsa limitata o deviata**: e' l'origine di tabella; che ViaggiaTreno risponda sulla stessa
  chiave quando la corsa del giorno parte altrove (il REG 2987 da Saronno) non e' stato provato.
- **`highlightedMessages` in un giorno di sciopero o di interruzione**: vuoto in tutte le ricerche del 19/09; non
  si sa se Trenitalia lo usi per quello.
- **`availableAmount`** del dettaglio app, e i due `locations/search/byzone*` del sito (mai chiamati dal sito).
- **Gli endpoint di backoffice** con numero e data (`boTrainDelay/...`, `cambio/deroga/train/search`): esclusi per
  regola, non si sa se rispondano senza credenziali.

## File

- `analisi/lefrecce/servizi_sito.txt` — tutti i metodi dei servizi del sito (460), con verbo, path, parametri, chunk.
- `analisi/lefrecce/lf.py` — sessioni, pause, salvataggio; `sNN_*.py` le sonde, `out_sNN.txt` le loro uscite.
- `analisi/lefrecce/raw_*.json` — risposte grezze, liste tagliate a 1-3 elementi dove erano grandi.
  Le piu' utili: `raw_stops_reg_+0.json` (stops con tratta urbana e Trenord), `raw_stops_notte.json`
  (transportMeanDate del notturno), `raw_stops_da_origine_9583.json`, `raw_app_nodes_stops_FR.json`,
  `raw_app_solution_detail.json`, `raw_soluzione_non_effettua.json`, `raw_app_configuration.json`,
  `raw_channel_config.json`, `raw_sito_seatmap.json`.
