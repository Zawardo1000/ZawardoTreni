> Rapporto d'inventario del 19/09/2026. I campioni grezzi e gli script di prova citati
> (`analisi/minori/...`) stanno fuori dal repository, in `.tools/analisi-fonti/minori/`:
> contengono cookie di sessione e pesano qualche megabyte. Panoramica e proposta in
> [`../FONTI.md`](../FONTI.md).

# Fonti minori: inventario completo degli endpoint

Ricognizione di sabato 19/09/2026, 18:50–19:45 (ora di Roma). Quattro sorgenti: **Italo**
(`italoinviaggio.italotreno.com`), **EAV**, **Ferrotramviaria** (FNB) e **orario svizzero**
(`transport.opendata.ch`, più il suo motore `search.ch`).

Metodo: per ogni sorgente si sono scaricati il sito pubblico e **tutto** il suo JavaScript
(per Italo gli 87 chunk webpack), si sono estratte le chiamate e si sono provate dal vivo,
a mezzo secondo–un secondo l'una dall'altra, senza chiavi, senza cookie inventati e senza
aggirare niente. Per Italo, come deciso il 29/08, **nessun tentativo** su `big.ntvspa.it`,
sulle pagine orari di `italotreno.com` né sulla ricerca soluzioni di `api-biglietti`.

I campioni grezzi (tagliati) stanno in `analisi/minori/{italo,eav,fnb,ch}/`; gli script
usati per le prove (`survey.py`, `det.py`, `planner.py`, `fnb.py`, `ch.py`) sono lì accanto
e si possono rilanciare.

---

## In breve: le cinque cose che valgono di più

1. **FNB ha un motore di ricerca A→B con le date e le fermate della corsa.**
   `cerca/soluzioni` risponde per qualunque giorno fino al 31/12/2026, con numero treno,
   cambi, bus sostitutivi e prezzo; `soluzioni/id/{id}` dà le fermate con orari, coordinate
   e fermate a richiesta. Oggi l'app di FNB ha solo il tabellone.
2. **EAV ha un pianificatore con ritardo e soppressione per corsa.** `planner.eavsrl.it/Home/Create`
   risponde A→B per data (fino al 31/12/2026) e, per oggi, porta il `ritardo` e il flag
   `soppressa` di ogni corsa: gli stessi minuti del tabellone (6180 a +11, 11844 a +3,
   6186 a +5). È il ritardo **per corsa** che il documento `API-EAV.md` dava per inesistente.
3. **Il tabellone Italo contiene già il percorso di ogni treno.** Il campo `InfoRoute` di
   `RicercaStazioneService`, che l'app scarta, elenca le fermate successive (partenze) o
   precedenti (arrivi) con l'orario. Funziona anche per i treni su cui `RicercaTrenoService`
   risponde `IsEmpty`.
4. **Gli arrivi svizzeri si possono aggiustare, e il numero del treno è sbagliato.** In
   modalità `arrival`, `to` è l'**origine**, non il capolinea. E il numero della corsa sta
   in `name` (`000041`), non in `number` (`72`, che è la linea): oggi tutte le Panoramic
   Express della Vigezzina escono come "PE 72".
5. **`transport.opendata.ch` nasconde le soppressioni.** Alle 19:25 l'RE 80 025835
   Lugano–Chiasso era soppresso per un guasto fra Giubiasco e Lugano (`search.ch`:
   `cancelled: true`, ritardo `X`, avviso in italiano); `transport.opendata.ch` lo dava a
   ritardo **0**, sia in `stationboard` sia in `connections`.

---

## 1. Italo — `italoinviaggio.italotreno.com`

Il sito è un'app React (bundle `common.modern.js` + 87 chunk in `/chunks/<hash>.chunk.js`).
Le chiamate passano da due helper: `Zn(url, action, params)` = **GET** con i parametri in
query (e `ntvAjaxControlAction=<action>` se c'è), `Fq(url, body, params)` = **POST** JSON.
Nessuna chiave, nessun token: l'unico cookie è quello di Akamai (`_abck`, `bm_sz`), che
queste API **non** verificano.

### Endpoint

| Metodo | Percorso | Parametri | Esempio | Restituisce | Date | Limiti | App |
|---|---|---|---|---|---|---|---|
| GET | `/api/RicercaStazioneService` | `CodiceStazione` (sigla Italo); `NomeStazione` lo manda il sito ma è ignorato | `?CodiceStazione=RMT` | `ListaTreniPartenza`, `ListaTreniArrivo`: `Numero`, `OraPassaggio`, `NuovoOrario`, `Ritardo`, `Binario`, `DescrizioneLocalita`, `Informazioni`, `Descrizione`, **`InfoRoute`**; `LastUpdate` | solo adesso | orizzonte 1,5–2 h; un treno compare solo quando è "attivo" (vedi trappole) | **sì**, senza `InfoRoute`/`Descrizione` |
| GET | `/api/RicercaTrenoService` | `TrainNumber` | `?TrainNumber=9954` | `TrainSchedule`: capolinea, `Distruption` (ritardo, `LocationCode`, `Warning`, `RunningState`), `Leg`, `StazionePartenza`, `StazioniFerme`, `StazioniNonFerme` (codice Italo, `RfiLocationCode`, orari teorici e reali, `ActualArrivalPlatform`, `StationNumber`) | solo corse in viaggio | `IsEmpty` prima della partenza, dopo l'arrivo e per le corse che finiscono dopo mezzanotte | **sì** |
| GET | `/api/RicercaTrattaService` | `Departure`, `Arrival` (sigle) | `?Departure=RMT&Arrival=MC_` | `TrainSchedules`: stesse corse del servizio precedente, con `Stations` in lista unica | solo corse in viaggio | stesso insieme di corse di `RicercaTrenoService`; include anche quelle che hanno già superato entrambe le stazioni | **sì** |
| GET | `/api/getStations` | `includeWhiteLabel=true`, `lang=it`, `ntvAjaxControlAction=getStations` | — | `dataHash`, `count`, `stations[2538]`: sigla, nome, lat/lon, `stationClass` (T/B), `isItaloStation`, `isItabusStation`, `isTrenitalia`, `macCode`/`macStations` (es. `RM0` = Roma tutte) | — | **3,4 MB** (non più 290 KB) | no (solo per costruire `ItaloStations`) |
| GET | `/api/get-places` | — | — | 3.209 stazioni RFI: `Id`, `Name`, `IsItalo`. **`Id` è lo stesso numero di `RfiLocationCode`** (Roma Termini 2416, Napoli C.le 1888, Milano C.le 1728) | — | 168 KB | no |
| POST | `/api/get-details` | JSON `{departure, arrival, departureName, arrivalName, date: "YYYY-MM-DD", hour: "HH:MM"}`; `departure`/`arrival` = `Id` di `get-places` | Pisa C.le→Roma Termini | treni **regionali Trenitalia** fra due stazioni RFI: `TrainNumber`, `TrainType` (RE), `Departure.DateTime`, `NextStops` ("LIVORNO C.LE (11:55) - …"), `Messages` | oggi … +3 giorni; +4 → 404/500 | finestra di circa un'ora che parte un'ora abbondante dopo `hour`; `Arrival.DateTime` sempre `0001-01-01`; Roma→Fiumicino 500, Milano→Bergamo "nessun treno" | no |
| GET | `api-biglietti.italotreno.com/api/v1/stations` | `pn`, `ps`, `sn` (testo), `culture`, `ds`, `onlyitalo`, `exb` | `?sn=roma&onlyitalo=true…` | autocompletamento stazioni (stessi campi di `getStations`) | — | risposto 200 senza Akamai; il resto di quell'host non è stato toccato | no |
| GET | `/api/SessionCheck`, `/api/GetProfile`, `/api/login`, `/api/Logout` | — | — | sessione e profilo Italo Più | — | richiedono login | no |
| POST | `/FormReclami/CheckPnrExistance` | PNR | — | verifica un codice prenotazione per i reclami | — | dato personale | no |
| GET | `<big>/public/api/v1/localizations/currency-converter` | — | — | cambi valuta | — | irrilevante | no |
| GET (HTML) | `/it/stazione/{slug}`, `/it/treno/{n}`, `/it/orario-treni-regionali`, `/it/italo-informa/{slug}` | — | — | pagine renderizzate lato server: contengono solo la configurazione dei componenti, **nessun dato** (niente JSON-LD utile) | — | le notizie di `italo-informa` sono 2 (lavori RFI di agosto) | no |

Mappa sigla→pagina (`station-to-url`, 62 voci) nell'HTML della home: vi compare `22187=pisa`,
una sigla numerica che il catalogo non ha.

### Cose che l'app non usa e dovrebbe

**`InfoRoute` sul tabellone: il percorso di qualunque corsa in tabellone.**
Ogni riga del tabellone porta la lista delle fermate con orario di tabella:

```
RMT, partenze, 9954  →  "Roma Tiburtina (18.48) - Firenze Santa Maria Novella (20.17) - Bologna centrale (21.03)
                         - Mediopadana R.Emilia (21.28) - Milano Rogoredo (22.09) - Milano Centrale (22.20)"
RMT, arrivi,   8158  →  "Reggio Calabria (13.15) - Villa S.Giovanni (13.34) - Rosarno (14.05) - … - Napoli (18.20)"
```

- fra le **partenze** sono le fermate **successive** con il loro orario d'**arrivo**; fra gli
  **arrivi** le fermate **precedenti** con il loro orario di **partenza** (8997: Brescia 20.11
  fra le partenze di Milano, 20.13 fra gli arrivi di Venezia);
- un arrivo più una partenza nella stessa stazione danno la corsa intera;
- vale anche per i treni su cui `RicercaTrenoService` tace: alle 19:04 l'8158, l'8968, il
  9962 e l'8143 erano in viaggio, `IsEmpty` sul dettaglio, ma col percorso completo sul
  tabellone di Roma, Firenze, Bologna, Verona, Salerno.

Oggi l'app, quando il dettaglio tace, costruisce una corsa di una sola fermata
("Italo pubblica solo i tabelloni"). Con `InfoRoute` può mostrarla tutta, a orario di tabella.

**`InfoRoute` è la tabella, `RicercaTrenoService` è la corsa di oggi: la differenza è una variazione.**
Su 97 confronti fra le due fonti, 7 non coincidono, e tutti per lo stesso motivo:

- 9948: tabellone di Bologna, "prosegue per Milano Expo Rho, Torino P. Susa, Torino P. Nuova";
  `DescrizioneLocalita` = MILANO CENTRALE; il dettaglio finisce a Milano Centrale;
- 9954: tabellone "da Salerno 16.25, via Roma Tiburtina"; il dettaglio parte da Napoli e non
  ferma a Tiburtina;
- 9950: tabellone "da Salerno 13.43"; il dettaglio parte da Napoli.

Cioè `InfoRoute` e `Descrizione` raccontano la tabella, mentre `DescrizioneLocalita` e il
dettaglio raccontano la corsa limitata. È l'unico segnale di limitazione che Italo pubblichi.

**`get-places` è il ponte `RfiLocationCode` → nome RFI.** Il commento di `ItaloStations`
dice che il `RfiLocationCode` del dettaglio "appartiene a un altro registro" e che non si può
tradurre. `get-places` lo traduce: 3.209 stazioni RFI col nome. Non è il codice `S0xxxx`
di ViaggiaTreno, ma ci si arriva per nome invece che per sigla Italo, e copre anche le
stazioni non Italo.

**`get-details`: regionali Trenitalia con data, fino a +3 giorni.** Marginale per l'app
(Le Frecce e ViaggiaTreno danno di più), ma è l'unico endpoint di `italoinviaggio` che
accetti una data. **Non riguarda i treni Italo.**

### Risposte

**(a) Percorso, binari, date future per le corse Italo senza protezione?**
- **Percorso completo: sì**, da `RicercaStazioneService.InfoRoute`, anche dove il dettaglio
  risponde `IsEmpty`; a orario di tabella, per nome e non per codice. Più `RicercaTrattaService`
  / `RicercaTrenoService` per le corse "seguite".
- **Binari: poco.** `ActualArrivalPlatform` era valorizzato su 5 fermate su 295 (solo quella
  del `Leg`, cioè la prossima). Il `Binario` del tabellone c'è dove RFI l'ha assegnato: a
  Bologna 15 partenze su 15, a Verona 5 su 5, a Salerno 4 su 4; a Roma 1 su 10, a Milano 0 su 4.
- **Date future: no.** Nessun endpoint di `italoinviaggio` accetta una data per i treni Italo.
  L'unico con una data (`get-details`) restituisce regionali Trenitalia. L'orario Italo resta
  dietro Akamai, e non è stato toccato.

**(b) Cosa restituisce `RicercaTrattaService` e quando `RicercaTrenoService` è vuoto.**
- `RicercaTrattaService?Departure=A&Arrival=B` restituisce le corse **in viaggio adesso** il
  cui percorso comprende A e poi B, con l'intero percorso in `Stations`, anche se hanno già
  passato entrambe (RMT→MC_ alle 19:00: 9 corse, fra cui il 9950 a Rogoredo). Non restituisce
  le corse non ancora partite dall'origine (l'8968 delle 19:20 da Roma non c'era) né quelle che
  il dettaglio dà vuote: i due servizi attingono allo **stesso insieme**. Una sola sigla →
  pagina HTML; tratta sconosciuta → `IsEmpty: true`, `LastUpdate: "01:00"`.
- `RicercaTrenoService` risponde `IsEmpty` in **tre** casi, verificati sui 38 treni dei
  tabelloni di 12 stazioni alle 19:04 (32 pieni, 6 vuoti):
  1. **corsa non ancora partita dall'origine** (8997 Milano 19:35, 9963 Torino 19:25);
  2. **corsa che arriva dopo mezzanotte** — le quattro in viaggio che rispondevano vuoto
     finiscono dopo le 24: 8143 (Villa S. Giovanni 0.03 nel tabellone), 9962 (Milano Rogoredo
     0.11), 8968 (Brescia 23.33, poi Milano Centrale) e, verosimilmente, 8158 (Milano Centrale
     23.20, poi Brescia; l'orario di Brescia non compare). Nessuna delle 32 corse che rispondevano
     arriva dopo mezzanotte (la più tarda: 9959 alle 23:58);
  3. **corsa arrivata**: i treni del mattino (9901, 9903, 9905, 8901, 8903, 8907, 9900, 9902…)
     alle 19:05 erano tutti vuoti. Il dato "vecchio di ore" del 27/08 oggi non si è visto.

  Ricontrollo alle 19:40 sugli stessi numeri (`italo/recheck_1940.txt`), che conferma i tre casi:
  l'**8997**, partito da Milano alle 19:35, ora risponde (`LastUpdate` 19:38, +3); il **9963**,
  partito da Torino alle 19:25 ma diretto a Roma dopo mezzanotte, resta vuoto; il **9950**,
  pieno alle 19:05 e arrivato a Milano alle 19:13, è già vuoto; 8143, 8158, 8968 e 9962
  restano vuoti per tutta la corsa.

### Trappole

- **`InfoRoute` degli arrivi è ordinato per ora del giorno**, quindi le fermate dopo mezzanotte
  finiscono **in testa**: 9963 a Milano, arrivi: `"Roma Tiburtina (0.12) - Torino Porta Nuova
  (19.25) - …"`, e Roma Tiburtina è *dopo* Milano. 9962: `"Milano Rogoredo (0.11) - Salerno
  (18.14) - …"`. Va riordinato sapendo che orari < dell'origine sono del giorno dopo.
- **Formato orario `H.MM`** (`0.12`, `18.48`) dentro `InfoRoute`, `HH:mm` altrove.
- **Nomi diversi fra le fonti**: "Napoli" (InfoRoute, dettaglio) contro "NAPOLI CENTRALE"
  (tabellone), "Bologna centrale", "Mediopadana R.Emilia", "Villa S.Giovanni",
  "Vallo d.Lucania", "Agropoli Castellabate": serve la ricerca per parole già in `codeByName`.
- **Il tabellone mostra solo i treni "attivi"**, che compaiono 30–60 minuti prima della
  partenza dall'origine: alle 19:03 Milano Centrale aveva 4 partenze, Venezia S. Lucia 0,
  Torino P. Nuova 1, mentre gli arrivi di Napoli arrivavano fino alle 23:08. Nelle stazioni
  d'origine il tabellone è cortissimo; non è un buco di servizio.
- **Segnaposto**: `"01:00"` per un orario che non esiste (arrivo all'origine, partenza al
  capolinea) e per `LastUpdate` di una risposta vuota; `ActualArrivalDateTime` vale
  `"0001-01-01T00:00:00Z"` all'origine e `"1901-01-01T16:53:00Z"` altrove, cioè data finta e ora
  spostata di un'ora (17:53 reali).
- **`Distruption.LocationCode` non è una stazione**: `MLG`, `XRG`, `PJP`, `IDI`, `E20`, `1OS`…
  sono punti RFI che il catalogo (2.538 voci) non contiene; solo pochi (`BSJ` Bagnara, `CLD`
  Caldiero, `CR_` Canaro) si traducono.
- **`RunningState`**: 0 in orario (entro ±5'), 1 in anticipo, 2 in ritardo (visti 9950 −1→1,
  8944 +13→2, 9955 +4→0).
- **`BO2`** nel dettaglio e **`BC_`** nel tabellone per Bologna (già gestito).
- `get-details`: oltre +3 giorni **404** o **500**, non una lista vuota; e l'`hour` non è l'inizio
  della finestra (08:00 → primi treni 09:20–09:21).

---

## 2. EAV — Circumvesuviana, Cumana, Circumflegrea, suburbane

Tre host utili: `orariotreni.eavsrl.it` (monitor di stazione), `planner.eavsrl.it`
(pianificatore, ASP.NET MVC + jQuery, script `Home7.js`), `www.eavsrl.it` (WordPress).

### Endpoint

| Metodo | Percorso | Parametri | Esempio | Restituisce | Date | Limiti | App |
|---|---|---|---|---|---|---|---|
| POST | `orariotreni.eavsrl.it/teleindicatori/ws_getData_pis.php` | `codLoc`, `tipoLista` P/A, `visualizzazione=mobile`; `device` e `touchpoint` ignorati (verificato: stessa risposta byte per byte) | `codLoc=1&tipoLista=P&visualizzazione=mobile` | HTML: numero, categoria (`A`, `DD`, `EXP`, `D`), destinazione (o origine negli arrivi), informazioni, binario, ora, ritardo, **colonna `blink`** (`circleOrange` = treno in banchina/in partenza), riquadro `InfoSupplementare` | solo adesso | 40 righe, orizzonte 3–6 h | **sì** (senza `blink` né `InfoSupplementare`) |
| POST | `…/teleindicatori/ws_getData_moova.php` | `codLoc=<idLocMoova>` (`TNPNTS…`), `tipoLista`, `visualizzazione` | `codLoc=TNPNTS00000000000001` | stesso HTML con "Ferma a: NAPOLI P. GARIBALDI (18:55), TORRE A.TA - OPLONTI (19:18), …" | adesso, sconfina nel giorno dopo | già documentato in `API-EAV.md`: treni passati in lista, binari e ritardi inaffidabili; col `codLoc` numerico risponde righe vuote | no |
| GET | `orariotreni.eavsrl.it/` | — | — | registro delle località in `<script id="data-localita">`: 184 voci con `id` (= `codLoc`), `idLocMoova`, `touchpoint`, `idLinea`, `linea`, `visualizzato`, `isMoova` | — | 9 linee | no (le stazioni sono già in `EavStations`) |
| POST | `planner.eavsrl.it/Home/DestinazioniFromStazione` | `id` (= `codLoc`) | `id=1` | destinazioni raggiungibili: `Codice`, `Descrizione`, `Bacino` (1 vesuviane, 2 flegree, 3 suburbane) | — | JSON | no |
| POST | **`planner.eavsrl.it/Home/Create`** | `origine`, `destinazione` (= `codLoc`), `data` `dd/MM/yyyy`, `ora` `HH:mm` | `origine=3&destinazione=1&data=19/09/2026&ora=19:05` | `CorsePercorso[]`: `partenza`/`arrivo` (`/Date(ms)/`, istanti veri), `percorsi[]`: **`codice`** (numero treno), `tipologia` (`A`, `DD`, `FAC EX`, `A fer`), `Linea`/`DLinea`, **`ritardo`**, **`soppressa`**, `bitmask` (= `service_id` del GTFS); `media_origine`/`media_destinazione` + soglie `Liv_Min/Max_*` (affollamento, −1 = nessun dato); `Origine_disabilitata`/`Descr_*` (stazione chiusa) | **qualunque data fino al 31/12/2026**; 02/01/2027 → 0; ieri accettato | finestra di circa 2 h dall'`ora`; **solo corse dirette** (Sorrento→Baiano e P. Nolana→Montesanto: 0); 4–13 risultati | **no** |
| POST | `planner.eavsrl.it/Home/StazioniFromBacino` | `id` | — | 302 verso `/Home/Error`: morto | — | — | no |
| GET | `www.wimob.it/cfile/download.php?file=google-transit.zip` | — | — | GTFS | **16/09/2026 → 31/12/2026** | 6,2 MB (non più 3,1), 13.030 corse di cui 623 ferroviarie | **sì** (imbarcato) |
| GET | `www.eavsrl.it/wp-json/wp/v2/posts?categories=<id>` | `per_page`, `_fields` | cat. 64 | notizie. Categorie: 41 infomobilità ferrovia (**ferma al 13/09**), 64 infomobilità-accessibilità (ascensori fuori servizio, **aggiornata oggi alle 19:06**), 65 impianti, 66 accessibilità treni, 17 scioperi, 18/19/20/129 per linea | — | JSON WordPress standard | no |
| GET (HTML) | `autolinee.eavsrl.it/soppressioni` | — | — | corse soppresse oggi/domani, **solo autobus** | — | — | no (fuori ambito) |

L'app ufficiale GoEAV è un'istanza di myCicero (vedi l'avviso del 05/02/2026 sugli utenti
"registrati prima dell'attacco informatico"): vuole un account, non esplorata.
`api.eavsrl.it` resta il gateway WSO2 già annotato, senza servizi pubblici.

### Fatto il 20/09/2026

`Home/Create` è **nell'app**: `EavRepository.ritardiFraStazioni` porta ritardo e
soppressione sull'elenco (`conRitardi`) e sul dettaglio della corsa
(`conRitardoDelPianificatore`, che sposta gli orari di tabella del ritardo e lo
dichiara). Tre trappole trovate sul campo, che il rapporto non aveva:

- **vuole `X-Requested-With: XMLHttpRequest`**: senza, 302 verso `/Home/Error`,
  cioè HTML dove ci si aspetta JSON (misurato: con l'intestazione 200 e quattro
  corse, senza 302 e 128 byte);
- **la finestra comincia dopo l'ora chiesta**: chiedendo le 09:13 per la corsa
  delle 09:13 tornavano le tre successive e non lei. Si chiede da dieci minuti
  prima della partenza;
- **una corsa già finita resta in elenco con ritardo zero**, che lì significa
  «non la seguo più»: quelle restano all'orario di tabella, senza dire «in orario».

Lo stesso giorno `orariotreni.eavsrl.it` ha risposto **503 su tutto** per ore,
home compresa: il pianificatore era l'unica fonte EAV viva.

### Cose che l'app non usa e dovrebbe

**Il ritardo di ogni corsa, da `Home/Create`.** Confronto alle 19:14, Garibaldi→P. Nolana:

| Treno | Tabellone Garibaldi | Planner `ritardo` |
|---|---|---|
| 6180 | +11 | 11 |
| 11844 | +3 | 3 |
| 1184 | +1 | 1 |
| 6186 | +5 | 5 |
| 1189 (Pompei→Sorrento) | +5 (Pompei) | 4 |

È il dato che serve per il dettaglio corsa EAV, dove oggi l'app ha solo il ritardo della
stazione interrogata: chiedendo il planner fra origine e capolinea di una corsa si ottiene il
suo ritardo e se è soppressa, in una chiamata. E serve alla ricerca soluzioni EAV, che oggi
l'app fa sul GTFS senza tempo reale.

**Il pianificatore rispetta la data**, e coincide col GTFS: Piscinola→Aversa lunedì 21/09 dà 6
corse (con le feriali `A fer` 2084 e 2090), sabato e domenica 4; gli stessi numeri del GTFS
imbarcato. Quindi può fare da controllo incrociato dell'orario imbarcato.

**La colonna `blink` del monitor** dice quale treno è in banchina (11909 al binario 10 alle
19:09, 9190 a Montesanto): oggi `inStation = false` fisso.

**Le notizie di accessibilità** (cat. 64/65/66, ascensori fuori servizio per stazione e giorno)
sono vive e strutturate; le notizie di circolazione (cat. 41) non più.

### Risposta (c)

Sì, oltre al monitor c'è **`planner.eavsrl.it/Home/Create`**: A→B per data fino al 31/12/2026,
con numero treno, ritardo e soppressione di oggi per ogni corsa. **Non** dà le fermate
intermedie (solo salita e discesa): quelle restano al GTFS, o, per le fermate rimanenti e a
orario di tabella, a `ws_getData_moova.php`. **Non** esiste un endpoint per numero di treno né
uno di avvisi di circolazione: gli avvisi vivono nel campo `informazioni` del monitor e nel
riquadro `InfoSupplementare`.

### Trappole

- **Campania Express non è nel GTFS.** Il tabellone di P. Nolana mostra `11918 EXP SORRENTO
  - CAMPANIA EXPRESS -`, il planner ha 10821 e 11918 (`FAC EX`, `bitmask …-17`), il GTFS no (né
  l'orario imbarcato né il feed completo scaricato oggi). Aprendo quella riga l'app non trova il
  percorso. Delle 63 corse viste sui tabelloni, è l'unica assente.
- **Il GTFS si è accorciato**: oggi copre fino al **31/12/2026** (non più maggio 2027) e pesa
  6,2 MB. `API-EAV.md` va aggiornato.
- **Orari del planner**: `CorsePercorso.partenza/arrivo` sono istanti veri (UTC in `/Date(ms)/`);
  in `percorsi[]` invece `partenza`, `arrivo`, `partenzaOrigine`, `arrivoDestinazione` sono
  orari su una data 1899/1900 con offset storici, e il sito li ricompone con `SumTime`. Usare i
  primi. Ci sono i **secondi** (19:13:30).
- **Finestra del planner**: circa due ore dall'`ora`, esclusa la corsa che parte esattamente a
  quell'ora (2080 delle 08:00 non compare chiedendo 08:00).
- **Cookie di bilanciamento** `FADC_Persistence_Cookie`: il sito lo imposta al primo GET di
  `planner.eavsrl.it/`. Tutte le prove sono state fatte così (GET, poi le POST con quel cookie);
  non si è verificato se le POST reggano senza.
- Il monitor ignora `touchpoint` come ignora `device`.

---

## 3. Ferrotramviaria — `eticket.ferrovienordbarese.it`

Il portale di vendita (`/b2c/web/index.jsp`, jQuery; script `interface*.js`, `config.js`)
chiama tutto sotto `/b2c/json/`. Il sito istituzionale `www.ferrotramviaria.it` (Liferay)
linka direttamente `index.jsp?search=1&service=T&from=S01110&to=S01141&…` e `/b2c/web/realtime`.

### Endpoint

Base `https://eticket.ferrovienordbarese.it/b2c/json/`.

| Metodo | Percorso | Parametri | Esempio | Restituisce | Date | Limiti | App |
|---|---|---|---|---|---|---|---|
| GET | `realtime/siti/{T\|B}` | — | `realtime/siti/T` | 63 fermate: 25 FTV + 38 FAL | — | — | sì (test) |
| GET | `realtime/dati` | `codSito`, `type` | `?codSito=S01110&type=T` | `partenze`, `arrivi`: `numero`, `binarioEffettivo`, `nomeDestinazione`, `servizio` (T/B/S), `partenza`/`arrivo`, `ritardo`, `soppressa`, `occupazione` | solo adesso (`when`/`time` ignorati) | orizzonte circa 1 h (Bari C.le: 6 partenze 19:20–20:18); FAL → 500 | **sì** |
| GET | `soluzioni/{T\|B}` | — | `soluzioni/T` | fermate proponibili nella ricerca (stesse 63 di `realtime/siti/T`) | — | — | no |
| GET | **`cerca/soluzioni/`** | `from`, `to` (`codSito`), `when` `YYYY-MM-DD`, `time` `HH:MM`, `service` T | `?from=S01110&to=S01180&when=2026-09-22&time=07:00&service=T` | soluzioni: `idSoluzione` (negativo), `timeP`/`timeA`, **`elencoCorse`** (`["ET 91058","AS 58"]`), `cambi`, `conBus`, **`conServizioSostitutivo`**, `prezzo` in centesimi (530, 620, 840), `descrizione`, `durataSecondi` | **oggi … 31/12/2026**; 10/01/2027 → vuoto | tutte le soluzioni del resto della giornata (13–18); FAL → 0 soluzioni | **no** |
| GET | **`soluzioni/id/{idSoluzione}`** | — | `soluzioni/id/-2907996` | `tratte[]`: `numero`, `codTratta`, `gestore`, `servizio` (T/S/B), `timePartenza`/`timeArrivo`, `note`, **`fermate[]`**: `ordine`, `sito` (`codSito`, `nome`, **`lat`/`lon`**, `servizi`, `gestori`), `timeArrivo`, `timePartenza`, `orarioIndicativo`, **`facoltativa`** (a richiesta); più tariffa, `prezzoDaPagare`, `validitaInizio/Fine` | come la ricerca | **vuole la sessione** (vedi trappole); solo il tratto percorso, non l'intera corsa | **no** |
| GET | `abbonamenti/{T}`, `cerca/abbonamenti/` | `from`, `to` | — | abbonamenti | — | — | no |
| GET/POST | `carrello/*`, `utente/*`, `transit/*`, `agenzia/info/{id}` | — | — | acquisto, account, carte contactless | — | privati | no |

Fuori dal JSON: `js/news.js` è un avviso statico del 2021; le notizie vere sono pagine HTML
del Liferay (`/web/guest/news`, es. "Lavori di potenziamento linea Corato-Andria:
sospensione prorogata"); `bacheca.ferrovienordbarese.it:10443` è il login di una VPN
Fortinet, non pubblico.

### Fatto il 20/09/2026

La ricerca A→B e il dettaglio sono **nell'app**: `FnbRepository.itinerario`
(usata da `ResultsViewModel.direttiFuoriRfi`) e `FnbRepository.dettaglioCorsa`
(usata da `CaricatoreCorsa`). Il dettaglio prova prima la corsa **intera**,
cercando da un capolinea all'altro nel verso del treno, e ripiega sul tratto
percorso dichiarandolo. Trappola trovata sul campo, che il rapporto non aveva:
**la sessione va aperta prima della ricerca**, perché gli `idSoluzione` valgono
solo dentro la sessione che li ha prodotti; aprendola dopo, il dettaglio
risponde `{}` e la ricerca esce vuota. Se le soluzioni ci sono ma i dettagli no,
l'app riapre la sessione e riprova una volta.

### Cose che l'app non usa e dovrebbe

**Ricerca A→B con data.** Bari C.le→Barletta martedì 22/09 dalle 07:00: 13 soluzioni, tutte
"ET 910xx + AS xx" con un cambio ad Andria Sud e `conServizioSostitutivo: true`, perché fra
Andria Sud e Barletta si viaggia in bus (i lavori sulla Corato–Andria). Stesso risultato a
ottobre e a dicembre. Oggi l'app non ha alcuna ricerca FNB, né un orario FNB per i giorni futuri.

**Il dettaglio della corsa, con le fermate.** ET 91058 Bari C.le 08:20 → Andria Sud 09:39: 14
fermate con arrivo e partenza (Aeroporto 08:38/08:41), poi AS 58 Andria Sud 09:46 → Barletta
10:11 con `servizio: "S"`. Chiedendo la soluzione dall'origine al capolinea si ha la corsa
intera: è il percorso che `FnbApi` dice di non avere.

**Coordinate ufficiali delle fermate**, in `sito.lat/lon` — ora usate per le
fermate che arrivano dal dettaglio, non ancora per il registro: `FnbStations` usa quelle di
OpenStreetMap perché "il GTFS è offline dal 2025". Il portale le ha (Bari C.le FNB
41.118357, 16.86919).

**Prezzo del biglietto** (`prezzo`, centesimi): 5,30 € Bari→Aeroporto, 6,20 € Bari→Barletta,
8,40 € Aeroporto→Andria.

### Risposta (d)

Sì a tutto tranne il tempo reale per corsa: **percorso** (`soluzioni/id`), **date future**
fino al 31/12/2026 e **A→B** (`cerca/soluzioni`) ci sono e rispondono senza chiavi. **Numero
treno**: nessuna ricerca diretta; il numero del tabellone e quello della ricerca si
agganciano togliendo la sigla (`91416` ↔ `ET 91416`, bus `34` ↔ `AS 34`). Ritardo e
soppressione restano solo al tabellone.

### Trappole

- **`soluzioni/id` vuole la sessione in cui è stata fatta la ricerca.** Senza cookie risponde
  `{}` con 200. Il `JSESSIONID` (path `/b2c`) lo imposta solo una pagina web: si fa un GET a
  `/b2c/web/index.jsp`, poi la ricerca e il dettaglio con lo stesso cookie. Gli
  `idSoluzione` sono negativi ed effimeri: cambiano a ogni ricerca.
- **Il dettaglio dà solo il tratto percorso**: Aeroporto→Andria restituisce l'ET 91038 da
  Aeroporto in poi (8 fermate), non da Bari.
- **Sigle nel numero**: `ET` (elettrotreno), `RV`, `AS` (autoservizio sostitutivo) nella
  ricerca; nudo nel tabellone.
- **FAL è nel registro ma non funziona da nessuna parte**: tabellone 500
  (`A JSONObject text must begin with '{'`), ricerca Bari C.le FAL→Matera 0 soluzioni.
- **`realtime/dati` ignora `when` e `time`** (provato: stessa lista).
- **Errore = lista vuota**: dopo la fine dell'orario la ricerca dà `[]`, non un errore.

---

## 4. Orario svizzero — `transport.opendata.ch` e `search.ch`

`transport.opendata.ch` è un involucro non ufficiale di **`search.ch`**, che espone la propria
API (`https://search.ch/timetable/api/…`), documentata, senza chiave, con un limite dichiarato
di **1.000 ricerche di percorso e 10.080 tabelloni al giorno**. Tutte e due si possono chiamare
dall'app; la seconda ha più dati.

### Endpoint

| Metodo | Percorso | Parametri | Esempio | Restituisce | Date | Limiti | App |
|---|---|---|---|---|---|---|---|
| GET | `transport.opendata.ch/v1/stationboard` | `id`, `limit`, `type` (departure/arrival), `datetime` `YYYY-MM-DD HH:MM`, `transportations[]` | `?id=8301003&datetime=2026-10-19 08:00` | `stationboard[]`: `category`, **`number` (linea!)**, **`name` (numero treno)**, `operator`, `to`, `stop` (orario, `delay`, `platform`, `prognosis`), **`passList`** | oggi … almeno 18/01/2027, già con l'orario di dicembre (la PE da Domodossola passa dalle 08:26 alle 08:24 fra il 19/10 e il 20/12) | nessun limite dichiarato | **sì** (solo partenze) |
| GET | `transport.opendata.ch/v1/connections` | `from`, `to` (id o nome), `date`, `time`, `limit`, `isArrivalTime`, `via[]`, `transportations[]` | `?from=8301003&to=8505470&date=2026-09-20&time=08:00` | `connections[]`: `from`/`to` (orario, binario, ritardo), `transfers`, `sections[]` con `journey` (categoria, numero, `name`, `passList` con arrivo e partenza di ogni fermata) e tratte a piedi | idem | binario solo agli estremi della sezione | no |
| GET | `transport.opendata.ch/v1/locations` | `query` / `x`,`y` | — | ricerca stazioni | — | — | no (id fissi in `SvizzeraStations`) |
| GET | **`search.ch/timetable/api/stationboard.json`** | `stop`, `date` `MM/DD/YYYY`, `time`, `mode` (depart/arrival), `limit`, `show_tracks`, `show_delays`, `show_subsequent_stops`, `show_trackchanges`, `transportation_types` | `?stop=8505470&mode=arrival&show_delays=1` | `connections[]`: `time`, `*G` categoria, `*L` linea, **`*Z` numero**, `operator`, `terminal` (capolinea, o **origine** negli arrivi), `track`, `dep_delay`/`arr_delay` (`+8`, **`X` = soppresso**), `subsequent_stops` | date libere | 10.080/giorno | no |
| GET | **`search.ch/timetable/api/route.json`** (e **`route.it.json`**) | `from`, `to`, `via[]`, `date`, `time`, `time_type`, `num`, `pre`, `show_delays`, `show_trackchanges`, `transportation_types` | `route.it.json?from=8505300&to=8505307&time=19:20&show_delays=1` | connessioni con `legs[]` (`*Z` numero, `tripid`, `track`, `stops[]` con ritardi, **`cancelled`**), **`disruptions`** con testi brevi/medi/lunghi, causa e durata, **in italiano** con `.it.json`; `occupancy` | date libere | 1.000/giorno | no |
| — | `opentransportdata.swiss` (GTFS-RT, OJP) | — | — | fonte ufficiale | — | vuole una chiave | no, per scelta |

### Cose che l'app non usa e dovrebbe

**Gli arrivi, che si possono avere.** In `type=arrival` il campo `to` è l'**origine**:
a Locarno FART l'RE 73 000181 delle 19:20 ha `to = "Domodossola (I)"`, e Locarno FART è il
capolinea, quindi non può che venire da lì; a Locarno FFS l'RE 80 025528 delle 19:38 ha
`to = "Milano Centrale"`, e l'RE 80 a Locarno finisce. L'orario d'arrivo sta in
`stop.departure` (il campo `arrival` è null). Manca il tempo reale (vedi trappole), ma con
`search.ch` (`mode=arrival`) si ha anche quello: RE 73 000181 `arr_delay: "+8"`, `terminal` =
Domodossola.

**Il percorso di ogni corsa dal tabellone.** La `passList` delle partenze **non è vuota**
(il commento di `SvizzeraApi` dice il contrario): RE 80 025535 da Locarno ha 16 elementi fino a
Milano Centrale, ognuno con arrivo, partenza, `delay` e `prognosis`; RE 73 000120 da Locarno
FART ha tutte le 16 fermate fino a Domodossola. Sono le fermate **successive**; per quelle
precedenti serve il tabellone dell'origine o `connections`.

**`connections` per il dettaglio e per i viaggi misti**, con qualunque data: Domodossola→Lugano
martedì 22/09 dà la PE 72 000041 fino a Locarno FART, la camminata fino a Locarno FFS e l'RE 80
o l'IR 46 + S 10, ciascuna con le fermate.

**Soppressioni e avvisi da `search.ch`.** *(fatto il 20/09/2026 sul tabellone:
`SearchChApi.tabellone` + `SvizzeraRepository.conSearchCh`, partenze e arrivi.)*
Tre trappole trovate allora, che il rapporto non aveva:

- **`mode=arrival` risponde 404 col tabellone giusto in corpo.** Vale sul percorso
  documentato `fahrplan`, sull'alias `timetable` e sull'host `timetable.search.ch`; ogni
  tanto la stessa richiesta torna 200. Le partenze rispondono sempre 200, e le altre grafie
  di `mode` (`arr`, `ankunft`) tornano 200 con le **partenze**. È uno stato sbagliato su un
  contenuto giusto, non un errore.
- **Gli errori veri sono riconoscibili**, e per questo non serve fidarsi dello stato: un URL
  inesistente dà `{"error": …}` senza `stop` né `connections`, una fermata inesistente dà 200
  con `{"messages": …}` e nessuna corsa. L'app accetta la risposta **solo** se contiene il
  tabellone della fermata chiesta e delle corse (`SvizzeraRepository.tabelloneDi`).
- **Il numero del treno ha gli zeri davanti** (`025115`) mentre `transport.opendata.ch` no
  (`25115`): le righe si accoppiano per numero, non per stringa.

`route.it.json` restituisce `cancelled: true` e il
testo dell'avviso ("Limitazioni sulla tratta Bellinzona - Lugano tra Giubiasco e Lugano…
Causa: guasto tecnico all'impianto ferroviario… fino almeno 22:00… Interessate: linee EC, IC2,
IC21, RE80 e S10"); `stationboard.json` segna le soppresse con ritardo `X`.

### Risposta (e)

- **`connections`**: sì, fermate complete (`passList`) su qualunque data provata, fino a
  gennaio 2027; **binari solo alla partenza e all'arrivo** di ogni sezione, nessun binario
  sulle fermate intermedie. Ritardo solo per le corse vicine.
- **`stationboard` `passList`**: sì fra le partenze, fermate successive con orari; binario solo
  nella stazione interrogata. Date future col parametro `datetime`.
- **Arrivi**: **aggiustabili**, leggendo `to` come origine e `stop.departure` come orario
  d'arrivo; senza tempo reale su `transport.opendata.ch`, con tempo reale su `search.ch`.
- **Endpoint di viaggio**: `connections` (e `search.ch/route.json`). **Nessuno** per numero di
  treno: una corsa si ricostruisce dal tabellone dell'origine o da `connections` fra i capolinea.

### Trappole

- **`number` non è il numero del treno.** Per le linee regionali è la linea (`72`, `70`, `73`,
  `80`, `10`, `20`, `26`, `46`), per gli EC/IR senza linea è il numero (`000060`). Il numero
  vero sta sempre in **`name`** (`000041`, `025535`). `SvizzeraMappers.toBoardEntry` usa
  `number`: tutte le PE della Vigezzina hanno `TrainRef.number = "72"`, tutte le RE `73`, tutti
  i regionali `70`; le corse non si distinguono fra loro.
- **Soppressioni invisibili**: RE 80 025835 soppresso (search.ch) → `delay: 0` in
  `stationboard` e in `connections` di `transport.opendata.ch`. Un ritardo 0 non vuol dire
  che il treno passi.
- **Il primo elemento della `passList` di un tabellone è sbagliato**: porta l'`id` della
  destinazione e `name: null` (Locarno, RE 80: `8301700` = Milano Centrale), con l'orario della
  stazione interrogata. In `connections` invece è corretto.
- **Negli arrivi il tempo reale è finto**: `delay` sempre null, e `prognosis.arrival` vale l'ora
  della richiesta (sei arrivi di Locarno, dalle 19:15 alle 20:15, tutti `19:21:42`).
- **Punto di confine senza orari**: in `connections` la Vigezzina passa per "Centovalli"
  (`0000133`) senza arrivo né partenza.
- **I tabelloni saltano al giorno dopo**: a Locarno FART alle 19:22 le partenze cominciano da
  domenica 05:19, perché l'ultimo treno era delle 19:21 e dopo ci sono solo autobus (FART Aut
  B 370, da un'altra fermata).
- `search.ch`: date `MM/DD/YYYY`, ritardi come stringhe (`"+0"`, `"X"`), `terminal` = origine
  in `mode=arrival`; gli avvisi ci sono in `route.json`, non in `stationboard.json`.

---

## Riepilogo delle risposte

| | Domanda | Risposta breve |
|---|---|---|
| a | Italo: percorso, binari, date future senza protezione? | Percorso sì (`InfoRoute` del tabellone, anche per i treni `IsEmpty`); binari solo dal tabellone e dove RFI li ha già assegnati; date future no |
| b | Italo: cosa dà `RicercaTrattaService`, quando `RicercaTrenoService` è vuoto | le corse in viaggio che passano per A e poi B, col percorso; vuoto prima della partenza, dopo l'arrivo e per le corse che finiscono dopo mezzanotte |
| c | EAV: oltre al monitor? | `planner.eavsrl.it/Home/Create`: A→B per data fino al 31/12, con ritardo e soppressione per corsa; niente fermate, niente ricerca per numero |
| d | FNB: dettaglio, date, A→B? | sì: `cerca/soluzioni` (fino al 31/12) e `soluzioni/id` (fermate con coordinate), in sessione |
| e | Svizzera: `connections`, `passList`, arrivi, viaggio? | fermate complete su date future, binari solo agli estremi; arrivi aggiustabili (`to` = origine); `connections` è l'endpoint di viaggio; il numero treno è in `name`; soppressioni solo su `search.ch` |
