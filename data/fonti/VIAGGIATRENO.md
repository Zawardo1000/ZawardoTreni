> Rapporto d'inventario del 19/09/2026. I campioni grezzi e gli script di prova citati
> (`analisi/viaggiatreno/...`) stanno fuori dal repository, in `.tools/analisi-fonti/viaggiatreno/`:
> contengono cookie di sessione e pesano qualche megabyte. Panoramica e proposta in
> [`../FONTI.md`](../FONTI.md).

# ViaggiaTreno: inventario completo delle API

Sondato dal vivo sabato 19/09/2026 fra le 18:50 e le 19:30 (Europe/Rome). Circa 620
chiamate, 0,45 s di pausa fra una e l'altra: nessun 429 o 403, nessun blocco.
I campioni grezzi stanno in `analisi/viaggiatreno/samples/`: quelli oltre 30 KB
sono ridotti alle prime righe, e l'elenco è in `_campioni_tagliati.txt`. Gli script
`t01…t24` rifanno ogni prova, e `calls.log` registra ogni chiamata con stato,
latenza e dimensione.

## Da dove viene l'elenco

Il sito non nasconde niente. `index.jsp` carica **`/infomobilita/rest-jsapi`**, il
client JavaScript che RESTEasy genera da solo a partire dalle risorse registrate
sul server. È quindi l'elenco completo e autorevole: **29 metodi**, 27 sotto
`/resteasy/viaggiatreno/` e 2 sotto `/resteasy/news/`. A questi si aggiungono tre
cose che i JS del sito chiamano a mano, fuori da quel client:

- `soluzioniViaggioNew` (in `app.js` e in `soluzioniViaggio.js`): **risponde 404,
  è stato rimosso**;
- la servlet **`POST /infomobilita/StampaTreno`**, il certificato di ritardo;
- il vecchio sito WAP `/vt_pax_internet/mobile/*`, in HTML.

Il sito per smartphone (`mobile.viaggiatreno.it` → `/infomobilitamobile/`) usa lo
stesso `rest-jsapi`, con gli stessi 29 metodi. La sua copia
`/infomobilitamobile/resteasy/...` risponde come quella principale, con una
differenza: i suoi 404 sono verbosi e dicono se la risorsa esiste.

Base: `http://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno/`. Solo HTTP:
l'HTTPS risponde 301 verso HTTP.

## Tabella degli endpoint

Legenda delle date: **0** = oggi, **−1** = ieri, **+n** = fra n giorni. Latenze
misurate: mediana e p90. Tutti gli endpoint sono in GET, salvo dove indicato.

| # | Percorso | Parametri | Esempio | Cosa restituisce | Date coperte | Limiti, latenza | Nell'app |
|---|---|---|---|---|---|---|---|
| 1 | `andamentoTreno/{codOrigine}/{numero}/{ms}` | origine esatta; `ms` = qualunque istante del giorno | `andamentoTreno/S11781/9588/1789768800000` | Corsa completa: `fermate[]` con orari di tabella e reali, binari programmati ed effettivi, `actualFermataType`, `nextTrattaType`, `orientamento`; più `codiceCliente`, `tipoProdotto`, `descOrientamento`, `origineEstera`/`oraPartenzaEstera`, `cambiNumero`, `compRitardo` | **Solo 0**. Da −1 solo le corse con `h24`. **+1, +3, +30 → 204** | 0,38 / 0,66 s; 11 KB | sì |
| 2 | `tratteCanvas/{codOrigine}/{numero}/{ms}` | come sopra | `tratteCanvas/S11781/9588/…` | Le stesse fermate di (1), incapsulate in `{stazione, fermata, stazioneCorrente, first, last, trattaType, previousTrattaType, nextTrattaType}` | Identiche a (1): **204 per il futuro** | 0,41 / 0,57 s; 22 KB | no |
| 3 | `cercaNumeroTrenoTrenoAutocomplete/{n}` | numero | `…/795` | `text/plain`, una riga per corsa: `795 - TORINO PORTA NUOVA - 18/09/26\|795-S00219-1789682400000` | Corse di oggi, più quelle di ieri con `h24` | 0,34 / 0,70 s | sì |
| 4 | `cercaNumeroTreno/{n}` | numero | `…/1963` | JSON con **una sola** corsa: `codLocOrig`, `dataPartenza`, `millisDataPartenza`, **`corsa`** (il numero di produzione più una lettera: `37327A`), **`h24`**, `tipo`. 204 se il numero non esiste | Solo 0 | 0,35 s | no |
| 5 | `partenze/{cod}/{orario}` | `orario` = `Date.toString()` JS | `partenze/S01700/Tue Sep 22 2026 08:00:00 GMT+0200` | Righe di tabellone: numero, `codOrigine`, `dataPartenzaTreno`, `orarioPartenza` (ms), `compOrarioPartenza`, binario programmato ed effettivo, `ritardo`, `provvedimento`, `nonPartito`, `circolante`, `arrivato`, `partenzaTreno`, `ultimoRilev`, **`codiceCliente`**, `orientamento`, `destinazioneEstera` | **Oggi solo dal vivo; da +1 a +8** (fino a domenica 27 compresa); −1 e −7 vuoti; +14 e oltre vuoti | 0,76 / 1,31 s; 40–140 KB | sì |
| 6 | `arrivi/{cod}/{orario}` | come sopra | `arrivi/S12328/Sun Sep 20 2026 09:00:00 GMT+0200` | Come (5), con `origine`, `compOrarioArrivo` e `binarioProgrammatoArrivoDescrizione` | Come (5): **da +1 a +8 funziona**, binario d'arrivo compreso | 0,58 / 1,33 s | sì |
| 7 | `dettaglioViaggio/{codDa}/{codA}` | due codici `S…` (con i codici numerici risponde `[]`) | `dettaglioViaggio/S08409/S09218` | Treni diretti da A a B **in viaggio adesso**: partenza da A, arrivo in B, ritardo. `origine` contiene il nome di A, non l'origine vera, che sta in `codOrigine` | Solo adesso: circa 2 h 15 prima e 30 min dopo; nessuna data | 1,26 / 1,96 s | no |
| 8 | `soluzioniViaggioNew/{da}/{a}/{yyyy-MM-ddTHH:mm:ss}` | codici senza `S` e senza zeri | `…/1700/5043/2026-09-20T08:00:00` | **404, per ogni data e ogni variante** di codice e formato. Sul mobile: «Could not find resource», cioè la risorsa non è registrata | Nessuna | — | no |
| 9 | `autocompletaStazione/{testo}` | prefisso | `…/MILANO` | `text/plain` `NOME\|S01700` | — | 0,24 s | no (tolto il 20/09: mai cablato) |
| 10 | `autocompletaStazioneImpostaViaggio/{testo}` | prefisso | `…/MILANO` | Identico a (9) | — | 1,2 s | no |
| 11 | `autocompletaStazioneNTS/{testo}` | prefisso | `…/SARONNO` | `NOME\|830025119`: codici a 9 cifre, 83 più 7 cifre. Per RFI sono le cifre dell'`S…`; per FNM una numerazione propria (Saronno `S01933` diventa `…25119`, Cadorna `…25001`) | — | 0,50 s | no |
| 12 | `cercaStazione/{testo}` | prefisso | `…/MILANO` | JSON `[{nomeLungo, nomeBreve, label, id}]` | — | 0,29 s | no |
| 13 | `elencoStazioni/{reg}` | 0…22 | `elencoStazioni/1` | Registro con coordinate, `tipoStazione` e zoom della mappa | — | 0,59 s; fino a 170 KB | no (tolto il 20/09: il registro lo riempie Le Frecce) |
| 14 | `elencoStazioniCitta/{cod}` | codice `S…` (un nome dà `[]`) | `…/S01700` | Le stazioni della stessa città (Milano: 13) | — | 0,34 s | no |
| 15 | `dettaglioStazione/{cod}/{reg}` | codice e regione | `…/S01700/1` | Come una voce di (13), più `latMappaCitta` e `mappaCitta` (vuoti) | — | 0,24 s | no |
| 16 | `getCoordinateStazione/{cod}` | codice | `…/S01700` | `lat` e `lon`: le stesse di (13) | — | 0,25 s | no |
| 17 | `coordinateCitta/{cod}` | codice | `…/S01700` | `{latitudine, longitudine}`: le stesse di (13) | — | 0,45 s | no |
| 18 | `regione/{cod}` | codice | `…/S12328` | Il codice regione, come testo (`14`). **204** per le stazioni che stanno solo nella regione 0 | — | 0,23 s | no |
| 19 | `infomobilitaRSS/{bool}` | `false` = notizie, `true` = lavori | `…/true` | HTML. `false`: 5 sezioni, con link per corsa (`cercaTreno.jsp?treno=&origine=&datapartenza=`). `true`: **21 sezioni «INFOLAVORI \<regione\>»** con PDF e nessun link per corsa, 88 KB | Oggi | 0,5–0,7 s | solo `false` |
| 20 | `infomobilitaRSSBox/{bool}` | come sopra | — | Solo i titoli delle sezioni | Oggi | 0,6 s | no |
| 21 | `infomobilitaTicker` | — | — | `<ul><li>CIRCOLAZIONE REGOLARE</li></ul>` | Oggi | 0,5 s | no |
| 22 | `news/{reg}/{lingua}` | `0/it` | — | Una notizia del **2019**; le altre combinazioni danno `[]` | — | 0,5 s | no |
| 23 | `statistiche/{ts}` | un timestamp qualsiasi | — | `{treniGiorno: 6607, treniCircolanti: 791, ultimoAggiornamento}` | Adesso | 0,28 s | no |
| 24 | `datimeteo/{reg}` | 0, 1 | — | `{}`: vuoto | — | 0,5 s | no |
| 25 | `language/{lingua}` | `it`, `en`, … | — | Le etichette del sito, 12 KB, fra cui il significato di `orientamento` («Executive in coda», «Carrozze A in testa…») | — | 0,3 s | no |
| 26 | `property/{nome}` | — | — | 204 per ogni nome provato | — | — | no |
| 27 | `elencoTratte/{reg}/{zoom}/{cat}/{catAV}/{ts}` | `cat` = `ES*,IC,EXP,EC,EN,REG,MET`; `catAV` = `null` | `…/1/8/…/null/{ts}` | Segmenti della mappa: `nodoA`, `nodoB`, `trattaAB`, `trattaBA`, coordinate, `occupata` | Adesso | 0,72 s | no |
| 28 | `dettagliTratta/{reg}/{AB}/{BA}/{cat}/{catAV}` | gli id di (27) | `…/1/1/83/…/null` | I treni che sono **adesso** su quel segmento: righe come nel tabellone, **con `codDestinazione`**, che il tabellone lascia vuoto | Adesso | 0,35 s | no |
| 29 | `/resteasy/news/infomobility` | query `trainNumber`, `region`, `evidence`; header `correlationId` facoltativo | `?trainNumber=9588` | JSON `[{title, link, pubDate, description (HTML con entità), trainTags[], regionTags[], evidenzia}]`. Senza parametri: 26 voci, **lavori compresi**. `trainNumber` filtra lato server | Oggi | 0,54 / 0,90 s | no |
| 30 | `/resteasy/news/smartcaring` | query `commercialTrainNumber`, `searchDate` (`yyyy-MM-dd`), `originCode`, `productionTrainNumber`, `destinationCode`, `idNoteInfopush` | `?commercialTrainNumber=20175&searchDate=2026-09-19` | **Note per corsa dei regionali Trenitalia**: `infoNote`, `infoNoteEn`, `insertTimestamp`, `startValidity`/`endValidity`, `daysOfWeek`, `startTime`/`endTime`, e `trains[]` con numero commerciale, numero di produzione e origine | **Qualunque data dentro la validità della nota**: 19, 20, 22 e 28/09 sì, 25/12 no | 0,47 / 0,67 s | no |
| 31 | **`POST /infomobilita/StampaTreno`** | form `numTreno`, `locArrivo` (`S…`), `locArrivoDesc`, `date` (`dd-MM-yyyy`); **serve il cookie `JSESSIONID`** di `index.jsp` | `numTreno=9588&locArrivo=S00219&date=18-09-2026` | `{"comunicazione": "…è arrivato alla stazione di TORINO PORTA NUOVA alle ore 00:53 con 229 minuti di ritardo.", "pdf": true}` | **Da −7 a 0**; oggi solo a treno arrivato. −8 dà «non valido», +1 «non disponibili» | 0,48 s | no |
| 32 | WAP `/vt_pax_internet/mobile/{numero,stazione}` (POST) | form | — | HTML: gli stessi dati di (1) e (5), solo oggi | Oggi | 0,6–1 s | no |
| 33 | WAP `/vt_pax_internet/mobile/{programmato,tragitto}` (POST) | form | — | `programmato`: «nessuna soluzione» per ogni input. `tragitto`: 500 «servizio non disponibile». **Morti tutti e due** | — | — | no |

Codici regione di `elencoStazioni`, ricavati dalle stazioni che contengono: 0 nodi
nazionali, 1 Lombardia, 2 Liguria, 3 Piemonte, 4 Valle d'Aosta, 5 Lazio, 6 Umbria,
7 Molise, 8 Emilia-Romagna, 9 Trentino-Alto Adige, 10 Friuli-Venezia Giulia,
11 Marche, 12 Veneto, 13 Toscana, 14 Sicilia, 15 Basilicata, 16 Puglia,
17 Calabria, 18 Campania, 19 Abruzzo, 20 Sardegna, 21 e 22 residui di 2 e 4
stazioni. In tutto 3.354 codici distinti; 164 compaiono in più di una regione.

`codiceCliente`, cioè l'operatore della corsa, contato su 541 righe di tabellone:

| Codice | Operatore | Righe |
|---|---|---|
| 1 | Trenitalia: Frecce ed EC | FR 145, EC 18, FB 1 |
| 2 | Trenitalia Regionale | 103 |
| 4 | Trenitalia Intercity | IC 30, ICN 8 |
| 18 | Trenitalia Tper | 53 (Bologna, Parma, Vignola, Bazzano) |
| 63 | **Trenord**, TILO compresi | 182 |
| 64 | EC DB-ÖBB | EC 88 per il Brennero |

## Risposte alle domande

### (a) Le fermate complete di una corsa futura

**No, nessun endpoint di ViaggiaTreno le dà.** `andamentoTreno` e `tratteCanvas`
rispondono 204 a +1, +3 e +30, per treni che circolano ogni giorno (9588, 10911,
2987) come per quelli solo festivi (22297 domenica: 204). Ho provato anche altri
formati di data (`2026-09-20`, `20-09-2026`): sempre 204. `soluzioniViaggioNew`,
che era l'unico candidato con una data, è morto (404). Il WAP `programmato` non
trova soluzioni per nessun input.

Per il futuro esistono solo i **tabelloni**, che danno l'orario della corsa **in
quella stazione**. L'unica strada è ibrida: le fermate dalla corsa di oggi (o da
un'altra fonte), e orari e binari del giorno dal tabellone futuro delle stazioni
che interessano, cioè salita e discesa.

### (b) Binari programmati sui tabelloni futuri, arrivi compresi

**Sì.** `arrivi` futuri riportano `binarioProgrammatoArrivoDescrizione`: 34 righe
su 34 a Milano Centrale il 27/09, e ad Acireale il 20/09 il REG 22297 era sul 3,
l'IC 734 sul 2. L'orizzonte è lo stesso per partenze e arrivi, **+8 giorni**: oggi,
sabato 19, arriva fino a domenica 27 compresa. Il 28/09 dà `[]` anche alle 00:05.
Non ho potuto verificare se il limite sia sempre «+8 giorni» o «fino alla domenica
successiva»: va rimisurato un giorno feriale.

**I tabelloni seguono il calendario di quel giorno**, e contengono quindi anche i
treni che oggi non circolano. Il REG 22297, solo festivo, compare domenica. Fra le
08:00 e le 09:45 di Milano Centrale, l'EC 40, l'FR 9615, il REG 2418 e il REG 3017
ci sono martedì 22 e non domenica 20; il REG 2408, il 2657, il 3069 e il 96236
viceversa.

**Il binario programmato cambia da un giorno all'altro.** Alle 19:00 a Milano
Centrale, sulle stesse 33 corse, differiva da oggi in 3 casi il 20/09, in 6 il
22/09 e in 10 il 27/09. L'FR 9661, per esempio, è sul 17 oggi e sul 10 domenica.

### (c) `soluzioniViaggioNew`

È **morto**. Risponde 404 per −1, 0, +1, +3, +8, +30 e +90, su due coppie di
stazioni, con qualunque variante di codice (`1700`, `01700`, `S01700`) e di
formato. Su `/infomobilitamobile` risponde «Could not find resource», come un
percorso inventato: la risorsa non è più registrata, non è un problema di
parametri. Il sito la chiama ancora, e dice «servizio non disponibile». Dal codice
del sito si vede cosa restituiva: `{soluzioni: [{durata, vehicles: [{numeroTreno,
categoria, categoriaDescrizione, origine, destinazione, orarioPartenza,
orarioArrivo}]}], errore}`. Solo mezzi e orari, senza fermate né binari, e la
scheda del treno era apribile solo per oggi.

### (d) Cause di ritardo, provvedimenti e soppressioni oltre `infomobilitaRSS`

- **`/resteasy/news/smartcaring`: il perché dei regionali Trenitalia, corsa per
  corsa.** Alle 19:00 del 19/09 il REG 20175 aveva «Per un guasto ad un passaggio
  a livello tra le stazioni di CECCHINA e PAVONA, i treni del Regionale della
  relazione ROMA-VELLETRI e viceversa, potranno subire ritardi fino a 30 minuti»,
  inserita alle 07:30 e valida solo quel giorno, con l'elenco delle 18 corse
  interessate. 12 corse regionali su 40 avevano almeno una nota: lavori, fermate
  sostituite, cantieri. Frecce, Intercity e Trenord: 0 note. Questo **smentisce**
  la frase del CLAUDE.md «Dei regionali Trenitalia il perché non lo pubblica
  nessuno per corsa».
- **`/resteasy/news/infomobility`**: lo stesso contenuto di `infomobilitaRSS`, ma
  in JSON e con **`trainTags`**, i numeri dei treni citati in ogni notizia: dodici
  numeri per l'evento Paola–Reggio. `?trainNumber=` filtra lato server. Senza
  parametri comprende anche i 21 «INFOLAVORI».
- **`infomobilitaRSS/true`**: i lavori programmati per regione, con i PDF di
  Trenitalia.
- In `andamentoTreno`, su 90 corse di oggi, i campi `provvedimenti`, `anormalita`,
  `segnalazioni`, `motivoRitardoPrevalente`, `descrizioneVCO`, `cambiNumero` e
  `subTitle` erano **vuoti in tutte e 90**. Nessuna corsa era variata in quel
  campione.
- **Soppressioni future: nessun segnale.** Sui tabelloni futuri ogni riga ha
  `provvedimento` 0, `ritardo` 0 e `nonPartito` vero. Per il 5895 del 22/09, di
  cui SmartCaring annuncia «variazioni di fermata, modifiche di orario e di
  percorso» per i lavori di Roma Termini del 21–26/09, il tabellone futuro mostra
  l'orario normale, 19:56 da Termini.

### (e) Origine e data per `andamentoTreno` in un giorno futuro

`cercaNumeroTreno` e `cercaNumeroTrenoTrenoAutocomplete` conoscono **solo le corse
di oggi**, più quelle di ieri con `h24`. Il 22297, solo festivo, sabato dà una
risposta vuota e 204, benché sia sul tabellone di domenica. Anche il 2247 delle
00:05 del 20/09, già presente sul tabellone delle 23:00 di oggi, alle 19:15 risultava
solo col 19/09, e `andamentoTreno` per la corsa del 20/09 rispondeva 204.

**Il tabellone futuro invece risolve il riferimento:** ogni riga ha `codOrigine`,
`dataPartenzaTreno` e `numeroTreno`, cioè esattamente la chiave che vuole
`andamentoTreno`, già per +1…+8. La chiave serve però solo da quel giorno in poi,
perché prima di allora `andamentoTreno` risponde 204.

### (f) Quanto indietro e quanto avanti, quante righe, come si pagina

- **Finestra fissa di 2 ore: `[Tq − 15 min, Tq + 105 min]`**, estremi compresi,
  dove **Tq è l'ora chiesta arrotondata per difetto al quarto d'ora**. Le 08:03,
  08:07 e 08:14 danno tutte 07:45–09:45; le 08:16 danno 08:00–10:00. Verificato
  contro un riferimento di 231 righe a Bologna.
- **Nessun limite di righe** (fino a 65 a Bologna, 49 a Roma Termini) e **nessun
  parametro di paginazione**. Una giornata intera richiede 12 chiamate, a Tq =
  00:15, 02:15, …; i bordi si sovrappongono, quindi si deduplica su
  (`numeroTreno`, `codOrigine`, `dataPartenzaTreno`).
- **Una risposta vuota non vuol dire che non ci siano altri treni.** Acireale alle
  01:00 dà `[]` e non anticipa il primo treno del mattino.
- **Indietro: solo dal vivo.** Chiedendo le 10:00, le 14:00 o le 16:00 di oggi alle
  19:00 si ottiene `[]`. Una partenza sparisce circa 10–20 minuti dopo quella
  **effettiva**: il REG 25530 partito alle 18:44 c'era ancora alle 18:58, non più
  alle 19:06. Un arrivo resta di più, circa 25–30 minuti. Un treno fermo resta
  finché non parte: il REG 3077 delle 18:25, partito alle 19:02, c'era ancora. Ieri
  e −7 danno `[]`, salvo le righe fantasma (vedi Trappole).
- **Avanti: fino a +8 giorni**, come in (b).
- Oltre la mezzanotte la finestra prosegue nel giorno dopo: le 23:00 danno fino
  alle 00:15, con `dataPartenzaTreno` del 20.

## Cose che l'app non usa e dovrebbe

In ordine di utilità stimata. I punti 1, 2, 3 e 4 sono **fatti il 19-20/09/2026**;
vedi `data/FONTI.md` e il riassunto del «perché» in `CLAUDE.md`.

Sui **giorni della settimana di SmartCaring**: il server non li applica (il 18686,
`daysOfWeek` `1010010`, risponde uguale per nove giorni di fila), e nemmeno il sito.
Nel campione del 20/09/2026 — 14 note distinte di 110 regionali — erano tutte
`1111111`, e i giorni veri stavano scritti nel testo («solo il sabato», «Il 26/9,
27/9, 3/10 e 4/10»). Senza sapere l'ordine dei sette bit, filtrarli scarterebbe note
buone: l'app non li guarda, e controlla invece `startValidity`/`endValidity`.

1. **SmartCaring: il perché dei regionali, corsa per corsa, anche per date
   future.** Si chiama con `?commercialTrainNumber=N&searchDate=yyyy-MM-dd&originCode=S…`,
   e l'header `correlationId` è facoltativo. Le prove sono in (d). Le note hanno
   una validità, `startValidity`…`endValidity` più `daysOfWeek`, e rispondono
   quindi anche per date future: il 18686 il 20/09 e il 28/09 sì, il 25/12 no.
   Serve due casi che l'app oggi lascia muti: il ritardo di un regionale
   Trenitalia e il «perché» di un viaggio pianificato durante dei lavori.
   Cautele: le note sono spesso **duplicate** (stesso testo, `id` diverso), il
   testo è libero, e **senza `searchDate` arriva l'intero storico**: 50 note e
   348 KB per il 18686.
2. **Il tabellone futuro come fonte del binario di tabella del giorno.** Oggi
   `CaricatoreCorsa.conBinariDiTabellaDaOggi` prende il binario dalla corsa di
   oggi, ma quello del giorno può essere diverso (in (b): 10 su 33 il 27/09). Per
   la stazione di salita e quella di discesa bastano due chiamate a
   `partenze`/`arrivi` alla data giusta, entro +8 giorni. Per lo stesso motivo
   l'elenco dei risultati di un altro giorno potrebbe riempire il binario, che
   oggi resta vuoto.
3. **`codiceCliente` sulle righe del tabellone e sulla corsa.** 63 vuol dire
   Trenord. Oggi il tabellone scarica l'orario di stazione Trenord anche per sapere
   quali treni siano suoi; il dato è già nella riga di ViaggiaTreno. Serve anche a
   distinguere Tper (18) e gli EC DB-ÖBB (64). Il DTO lo legge sulla corsa (`codiceCliente`), non sulla riga di tabellone.
4. **`/resteasy/news/infomobility` in JSON con `trainTags`**, al posto di
   ricostruire le corse dai link dell'HTML (`InfomobilitaParser`): è strutturato,
   filtrabile lato server e comprende i lavori. Limite: `trainTags` ha solo il
   numero, senza origine né data. Per i numeri doppi (il 178 è sia EC sia REG
   Trenord) i link dell'HTML restano più precisi.
5. **`StampaTreno`: lo storico dei ritardi degli ultimi 7 giorni.** Dà orario
   d'arrivo e ritardo in qualunque stazione servita. Il 9588 a Torino P.N.: +229
   il 18/09, +52 il 15, +86 il 12, +76 il 13. Funziona per Trenitalia, Tper
   compresa (il 2479 a Bologna: 2 minuti di anticipo), ma non per Trenord
   («treno non valido»). Serve a mostrare la puntualità abituale di un treno. Costa
   una chiamata per treno, giorno e stazione; il testo va letto con una regex; e
   richiede il cookie di sessione. È un endpoint pensato per il certificato di
   ritardo: va usato con parsimonia.
6. **Orari in millisecondi sulle righe del tabellone** (`orarioPartenza`,
   `orarioArrivo`). Il DTO legge solo `compOrario…` («HH:mm») e `dataPartenzaTreno`,
   che è la data d'**origine**: per un notturno che passa dopo la mezzanotte i due
   insieme danno il giorno sbagliato. Sempre sulle righe: `partenzaTreno` è la
   partenza reale **dall'origine**, `ultimoRilev` l'ultimo rilevamento.
7. **`orientamento` e `descOrientamento`**: la posizione di Executive o della
   prima classe sulle Frecce, per corsa e per fermata («Executive in coda»,
   «Carrozze A in testa…»). Popolato in 15 corse su 90. È un'informazione utile in
   banchina.
8. **`origineEstera`, `destinazioneEstera`, `oraPartenzaEstera` e `oraArrivoEstera`**
   (millisecondi dalla mezzanotte) sugli EC: l'EC 173 viene da BASEL SBB, l'EC 126
   va a ZUERICH HB.
9. **`corsa` e `h24` di `cercaNumeroTreno`.** `corsa` è il numero di produzione:
   l'ICN 1963 è `37327A`, e `dettaglioViaggio` lo mostra proprio come «ICN 37327».
   `h24` dice se la corsa scavalca la mezzanotte, cioè quando la corsa di ieri
   sarà disponibile.
10. **`autocompletaStazioneNTS`**: codici a 9 cifre, 83 più 7, e per le stazioni
    FNM una numerazione propria (`…25xxx`). Potrebbero fare da ponte verso i codici
    UIC 83xxxxx usati da `transport.opendata.ch`. Da verificare, non l'ho provato.
11. **`infomobilitaRSS/true`**: i lavori programmati per regione, se si vuole un
    avviso «lavori sulla linea» per un viaggio futuro.

Marginali: `dettaglioViaggio` (i diretti A→B in viaggio adesso, con l'arrivo in B,
in una chiamata); `nextTrattaType`/`stazioneCorrente` per la posizione (0 =
percorsa, 1 = in corso, 2 = da fare, dedotto dalle distribuzioni);
`statistiche`; `dettagliTratta`, che ha `codDestinazione`.

Inutili: `datimeteo` (`{}`), `news/{reg}/{lingua}` (una notizia del 2019),
`property` (204), `language` (solo etichette), `infomobilitaRSSBox` e
`infomobilitaTicker` (solo titoli). `getCoordinateStazione`, `coordinateCitta` e
`dettaglioStazione` danno le **stesse** coordinate di `elencoStazioni`, segnaposto
compresi. Morti: `soluzioniViaggioNew`, WAP `programmato` e `tragitto`.

## Trappole

1. **Il formato dell'orario dei tabelloni.** Si accetta solo il `Date.toString()`
   di JavaScript. ISO (`2026-09-22T08:00:00`) e millisecondi danno **400**
   «Error». Senza `GMT+0200` vale l'ora locale del server. `GMT+0000` sposta la
   finestra di 2 ore: le 08:00 diventano 09:45–11:45. Il giorno della settimana è
   ignorato (`Mon Sep 22` funziona). Il suffisso fra parentesi del browser è
   tollerato.
2. **Il 204 di `andamentoTreno` non prova che il treno non circoli.** Risponde 204
   anche con un formato di data sbagliato (`2026-09-20`, `0`) e con un'origine
   diversa da quella esatta: 9588 con `S08409`, Roma, al posto di `S11781`, Reggio.
   Qualunque istante dentro il giorno va bene (`…08:40` = `…00:00`).
3. **La corsa di ieri esiste solo se è `h24`, cioè scavalca la mezzanotte in
   tabella.** Non basta che sia arrivata oggi: il 9588 del 18/09, arrivato a
   Torino alle 00:53 del 19 con +229, alle 18:50 del 19 dava 204. Il 2987, l'ICN
   1963, il 764 e il 795 del 18/09, tutti `h24`, davano 200. Questo precisa la
   frase del CLAUDE.md «ViaggiaTreno dà la corsa di ieri solo se è arrivata oggi».
   Resta da verificare se nelle prime ore del mattino le corse non `h24` ci siano.
4. **`cercaNumeroTreno` restituisce una sola corsa.** Per il 795, l'autocomplete
   ne elenca tre (Torino del 18, Milano Cadorna del 19, Torino del 19), mentre
   `cercaNumeroTreno` dà solo quella di Cadorna. Per risolvere un numero non va
   mai usato. Se il numero non esiste risponde 204 `text/plain`.
5. **Righe fantasma.** Il tabellone di ieri, chiesto oggi, conteneva ancora l'FR
   9642 del 18/09 delle 18:10 con `provvedimento` 2 e `nonPartito` vero, origine
   `S09818`, mentre oggi parte da Reggio. `andamentoTreno` per quella corsa dà 204.
6. **Un tabellone per l'ora passata è vuoto.** Non è un errore, è il
   comportamento: vedi (f). Chi vuole il passato deve usare `StampaTreno`.
7. **La data limite è quella della richiesta, non quella del treno.** Il 28/09
   alle 00:05 dà `[]`, benché la finestra includerebbe i treni del 27 dalle 23:45.
8. **Le righe future sono sempre pulite**: `ritardo` 0, `provvedimento` 0,
   `nonPartito` vero, `riprogrammazione` «N». Le cancellazioni programmate non
   compaiono.
9. **Sulle righe future l'effettivo può esserci.** A Bologna le Frecce del 22/09
   hanno l'effettivo `" AV"`: è il piazzale, non un'assegnazione. Lo gestisce già
   `effettivoUtile`, ma su un giorno futuro nessun effettivo va letto come un
   cambio.
10. **Il binario programmato del giorno non è quello di oggi**: vedi (b).
11. **`dataPartenzaTreno` è la data d'origine.** Gli arrivi del 22/09 alle 08:00
    includono corse partite il 21. La chiave di una corsa è (numero, `codOrigine`,
    `dataPartenzaTreno`), mai la data del tabellone.
12. **Le stazioni FNM sono coperte ma senza binari.** Milano Cadorna, Saronno,
    Iseo e Malpensa T1 hanno tabelloni pieni di corse Trenord (`codiceCliente` 63),
    ma con 0 binari.
13. **Le etichette sono scritte in modi diversi.** `compNumeroTreno` può essere
    `" FR 9588"` (con lo spazio in testa) o `"FR FR 8823"` (in `dettaglioViaggio`).
    Per le Frecce `categoria` è `""` e `categoriaDescrizione` `" FR"`. In
    `dettaglioViaggio`, `origine` è il nome della stazione A, non l'origine.
14. **«Mancato rilevamento».** Il 9588, con `ritardo` 159 e ultimo rilevamento a
    Roma alle 18:34, aveva `compRitardo[0]` = «Mancato rilevamento»: il ritardo
    numerico era fermo e non andava mostrato come attuale.
15. **Coordinate segnaposto.** Su 3.354 codici, 260 hanno coordinate inventate:
    **183 in (0,0; 4,51126)** e **77 in (−9,01938; 4,51125)**, due punti nel Golfo
    di Guinea (Perugia S.Anna, Taviano, Casoli, Gallipoli…). Nessun altro endpoint
    le corregge, e per quelle stazioni `regione` risponde 204. Ci sono anche coppie
    vere a coordinate identiche: Bologna Centrale e Bologna C.le/AV, Bari Centrale e
    Bari Terminal Bus.
16. **`StampaTreno` senza il cookie `JSESSIONID`** risponde sempre «Impossibile
    effettuare la richiesta»; il sito mobile ne vuole uno suo. Una stazione dove il
    treno non ferma dà `{}`. Il testo ha entità HTML (`&egrave;`), in ISO-8859-1.
17. **Le trappole di SmartCaring.** `region` di `news/infomobility` è ignorato: dà
    `[]` per ogni valore provato. `evidence=true` dà solo la voce «in evidenza».
    `infoNoteEn` a volte non è una traduzione: comincia con «Buongiorno, …». Il sito
    non chiama SmartCaring per `codiceCliente` 63 e 64 né per FR, FB, FA e IC.
18. **I 404 dicono poco.** Su `/infomobilita` un percorso sconosciuto e una risorsa
    rimossa danno lo stesso 404 di 6 byte, «Error». Per capire quale dei due sia, la
    copia `/infomobilitamobile/resteasy/...` risponde «Could not find resource for
    relative : …».
19. **Solo HTTP**: l'HTTPS risponde 301 verso HTTP. È già noto, e c'è la deroga.

## Latenze (mediana / p90 / massimo, in secondi)

andamentoTreno 0,38 / 0,66 / 1,11 · partenze 0,76 / 1,31 / 2,48 · arrivi 0,58 /
1,33 / 1,55 · tratteCanvas 0,41 / 0,57 · cercaNumeroTreno 0,35 / 0,48 ·
autocomplete 0,34 / 0,70 · smartcaring 0,47 / 0,67 / 3,13 (quest'ultimo senza
`searchDate`) · news/infomobility 0,54 / 0,90 / 1,82 · dettaglioViaggio 1,26 /
1,96 / 2,54 · elencoStazioni 0,59 / 0,77 · StampaTreno 0,48 / 0,63. Su circa 560
chiamate registrate: 466 con 200, 44 con 204, 21 con 404 (tutti
`soluzioniViaggioNew` o percorsi di prova), 2 con 400 (formati d'orario sbagliati
di proposito), 1 con 500 (WAP `tragitto`).
