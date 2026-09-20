> Rapporto d'inventario del 19/09/2026. I campioni grezzi e gli script di prova citati
> (`analisi/trenord/...`) stanno fuori dal repository, in `.tools/analisi-fonti/trenord/`:
> contengono cookie di sessione e pesano qualche megabyte. Panoramica e proposta in
> [`../FONTI.md`](../FONTI.md).

# Trenord: inventario delle API pubbliche

Sabato 19/09/2026, prove fra le 19:00 e le 19:40 (ora di Roma). 157 chiamate distanziate
di 4 secondi, **nessun 403 né "Access Denied"**. Campioni decifrati e tagliati in
`analisi/trenord/samples/`, script in `analisi/trenord/s*.py` (`tn.py` fa GET cortesi e
decifra), registro delle chiamate con latenze in `analisi/trenord/calls.log`.

## Come è stato fatto, e cosa manca

- **Fonti lette.** Il sito TYPO3 (`merged-….js.gzip`, dove sta `Trenord.infoMobilita`),
  i widget Angular del sito (`trenord-web-components.js`: `tn-wc-train-info`,
  `tn-wc-breaking-news`, …), lo store (`/store/main…js` più i suoi **38 chunk** caricati
  a richiesta), `store-integration/main.js` e il sito dei cantieri
  (`cantieri.trenord.it`, fatto con Yext Pages). L'elenco dei bundle con dimensioni e
  hash sta in `js_index.txt`, i percorsi estratti in `paths_all.txt`.
- **Configurazione dei client** (`AytR` nello store e costanti nei widget):
  `bffAPIUrl = https://www.trenord.it/mia/bff/`, `miaAPIUrl = https://www.trenord.it/mia/`,
  `apiUrl = /store-api/`, `storeManagementApiUrl = https://www.trenord.it/mgmt/store-management-api/`,
  `mobilityApiUrl = …/mia/mobility/`, `obfKey = 8hI&WK=1NQ55*f^yyZkdEGWYyN{S`. C'è anche
  lo specchio `https://www.malpensaexpress.it/mia/bff/`.
- **Non letta: l'app mobile ufficiale.** Non ho decompilato l'APK: da Play non si
  scarica, e i mirror non ufficiali li ho esclusi. Lo store web chiama però **lo stesso
  BFF** (`bffAPIUrl`), e nel BFF i percorsi che il web usa sono solo `hafas/v2` e
  `train/{n}`. `…/mia/bff/` e `…/mia/bff/documentations/json` rispondono 404: non
  c'è una documentazione pubblica.
- **Escluso per regola:** tutto ciò che richiede login (`auth/*`, `orders*`, `wallet`,
  `profile*`, `crm*`, `cards`, `tickets`, …). Esclusi anche gli endpoint che lo store
  chiama col JWT `apiSecret` ("Trenord Management") dentro il bundle: è una
  credenziale, e non l'ho usata. Serve comunque a poco: `stazioni_v2` risponde anche
  senza, come la chiamano i widget.

## Tabella degli endpoint

Legenda della colonna **App**: **Sì** = la usa già ZawardoTreni; **No** = non la usa.
Latenze e byte misurati oggi. "wire" sono i byte trasferiti, compressione compresa.

### A. BFF cifrato (`https://www.trenord.it/mia/bff/`, risposta AES-ECB, header `Referer`)

| Metodo | Percorso | Parametri | Esempio | Restituisce | Date coperte | Limiti, latenza | App |
|---|---|---|---|---|---|---|---|
| GET | `hafas/v2` | `orig`, `dest` (HAFAS `83`+MIR), `departure_date` `yyyyMMdd`, `departure_hour` `HH:mm`, `products=tickets`, `transfers`, `live_data`, `with_routes`, `language`, `no_changes` (usato dai rimborsi) | `hafas/v2?orig=8301700&dest=8309999&departure_date=20260922&departure_hour=08:00&products=tickets&transfers=1&live_data=true&with_routes=true&language=it` | 5 soluzioni: `journey_list` con `train`, e `pass_list` **solo della tratta percorsa** (`start/pass/end`, anche con `with_routes=false`); prezzi in `ticket_routes`, vendibilità in `saleability`, `hafas_alerts`, `crowding` (solo corse passate) | ieri (con `PAST_DEPARTURE_DATE`), oggi, domani, +3, +30. **Si ferma al 12/12/2026**, fine dell'orario: il 15/12/2026 e il 15/01/2027 danno 0 soluzioni | 0,4–3,2 s (mediana 0,9); 18–68 KB **non compressi** | Sì |
| GET | `train/{numero}` | `date` `yyyy-MM-dd` (senza data vale oggi) | `train/24564?date=2026-10-19` | array di corse: **tutte le fermate con gli orari di tabella**, `schedule` (i giorni in cui circola), `direttrice`, dati reali per fermata, stato e ritardo, soppressioni e avvisi | ieri, oggi, domani, +3, +30, +62 (fino all'11/12/2026). Ma vedi le trappole 1–4 | 0,4–2,1 s (mediana 0,7); 8–25 KB non compressi | Sì |

### B. Le stesse risorse **in chiaro** (`https://www.trenord.it/mgmt/store-management-api/mia/`)

Stesso host, stessi dati, JSON normale compresso con gzip. Il sito oggi chiama da qui
solo `direttrici/` (widget `tn-wc-train-info`, `getDirettriciNews`); `train/` e `hafas/v2`
rispondono ma il sito non li chiama.

| Metodo | Percorso | Parametri | Esempio | Restituisce | Date | Limiti, latenza | App |
|---|---|---|---|---|---|---|---|
| GET | `train/{n}` | `date` come sopra | `mgmt/store-management-api/mia/train/24564?date=2026-09-22` | identico a `bff/train` | identiche | 0,4–1,4 s; **1,5–2,4 KB contro 8–25 KB** del BFF | No |
| GET | `hafas/v2` | come sopra | `mgmt/…/mia/hafas/v2?orig=8301700&dest=8309999&…` | identico campo per campo al BFF, prezzi compresi (confronto su 5 soluzioni: tutte uguali) | identiche | 0,6–0,8 s; **2,2–2,8 KB contro ~35 KB** | No |
| GET | `hafas` (senza v2) | come sopra | | array di soluzioni senza l'involucro `solutions`/`hafas_alerts` | | 0,7 s; 2,8 KB | No |
| GET | `direttrici/` | — | | come `mia/direttrici/`, vedi C | — | 0,7 s; 6 KB | No |
| GET | `https://admin.trenord.it/store-management-api/mia/train/{n}` | — | | identico. Non lo chiama il sito: l'ho trovato nel codice di terzi (`berry-13/treno`) | | 1,7 s; 2,4 KB | No |

### C. MIA pubblico in chiaro (`https://www.trenord.it/mia/`)

| Metodo | Percorso | Parametri | Esempio | Restituisce | Limiti, latenza | App |
|---|---|---|---|---|---|---|
| GET | `direttrici/` | `?nome=D027` per una sola direttrice (widget breaking-news) | `mia/direttrici/` | 42 direttrici `{nome, descrizione, alert, severity_code, news:[{date, severity_code, severity_description, description}]}`. **Le notizie di circolazione, anche quelle in tempo reale**, per direttrice | 0,6 s; 6 KB (51 KB grezzi) | No |
| GET | `linee/` | `?code=S5` | `mia/linee/?code=S5` | 67 linee `{code, name, direttrice, lineType SUB/REG/…}`: il ponte fra linea e direttrice | 0,7 s; 4 KB | No |
| GET | `v2/stazioni_v2/` | `_q` (filtro alla Mongo, es. `{"CodiceMIR":{"$in":["S01933"]}}`), `_p` (campi), `_s` (ordinamento), `include_ignored_for_user_search`, `ignore_during_search` | | 732 stazioni: `CodiceMIR`, `hafasCodes`, `Location`, `Direttrici`, `platforms` (i nomi dei binari della stazione, es. Rho `1…5, I, IIIFM`), `tariff_zone`, `locIdSbme`, `country` (32 in CH) | 1 s | Sì (test e sonde) |
| GET | `catalogue/products` | `origin`, `destination` (MIR); varianti `…/cards`, `…/ivol?language=`, `…/discovery/?category=&origin=&destination=` | `mia/catalogue/products?origin=S01933&destination=S01066` | **Tariffario senza data**: `ordinary` adulto/ragazzo/anziano × classe × `train_category` (Saronno–Cadorna adulto 2ª: 3,30 €, validità 360 min), abbonamenti settimanali, mensili e annuali, carnet, IVOL; `distance_km`, `zones` | 0,9 s; 5 KB (46 KB grezzi) | No |
| GET | `v2/ticket-offices/?online_pass_activation=true` | | | biglietterie con orari, DAB, Pay&Go | 0,6 s; 40 KB | No |
| GET | `v2/appconfiguration/?_l=1&_sk=0&_s=-createdAt&_st=PUBLIC`, `feature-toggle/config?key=…&client=trenord-www` | | | configurazione dello store (limiti dei preferiti, link reclami) | 0,5 s | No, e non serve |
| GET | `location/provinces`, `location/cities`, `location/nationalities`, `v2/tariffs/`, `v2/catalogue-categories/`, `v2/subscribers-conventions-configs/` | | | anagrafiche commerciali | non provati | No |

### D. REST del sito (`https://www.trenord.it/rest/render/…`, JSON con dentro HTML)

| Metodo | Percorso | Parametri | Restituisce | Date | Limiti, latenza | App |
|---|---|---|---|---|---|---|
| GET | `station-details` | `mirCode`, `mxp`, `L`, `map_zoom` | `{partenza, arrivo, stationMap}` in HTML: linea, numero, `CodiceTrasporto` (`124973`), ora, destinazione | **solo le prossime ~2 ore** (Saronno 19:05–21:03, 48 partenze; Centrale 19:05–20:55, 17). `date=` viene ignorato | 6,6–15 s; 10–20 KB (0,4–1 MB grezzi); niente binari, ritardi o soppressioni | Sì |
| GET | `train-sub-detail` | `trainId` (5 cifre) | `{CodiceTrasporto: html}`: fermate con orari di tabella, più le notizie della direttrice | oggi | 2,3 s; niente binari né ritardi | No |
| GET | `shoulder-lines?no_cache=1` | `mxp`, `L` | 65 linee con **stato**: `green-line` "Circolazione Regolare", `critical` "Circolazione con criticità" (oggi S5), più `data-code` e `data-direttrice` | adesso | 5,8 s; 6 KB | No |
| GET | `line-details` | `code` (es. `S5`, `RE_13`), `L` | stato della linea, notizie, stazioni, link al PDF degli orari e alla mappa | adesso | **20 s**; 10 KB | No |
| POST | `next-trains` | `direction=to-malpensa\|from-malpensa`, `trainDescription`, `L` | prossimi Malpensa Express con "In orario" o ritardo, fermate, prezzo 15,00 € | adesso | 9,9 s; 63 KB (700 KB grezzi) | No |
| POST | `trains` | `orig`, `dest`, `departure_date`, `departure_hour`, `direction`, `movement` | ricerca della pagina Malpensa | | non provato | No |
| GET | `search-train-list` | `query`, `mpx` | autocompletamento per numero di treno | | **vuoto** sia con 2456 sia con 24564 | No |
| GET | `stationslist` | `query`, `template` | autocompletamento stazioni (HTML) | | 1,6 s | No |
| GET | `infomob-{train\|station}-history`, `user-recent-search`, `suggest`, `search`, `airportslist`, `airlineslist`, `pay-go`, `passengershoulder` | | cronologia di sessione, ricerca nel sito, voli, Pay&Go | | irrilevanti | No |

### E. Store legacy (`https://www.trenord.it/store-api/`, JSON in chiaro)

| Metodo | Percorso | Parametri | Restituisce | Limiti, latenza | App |
|---|---|---|---|---|---|
| GET | `departures`, `destinations` | `name` (+ `lat`, `lon`) | stazioni per nome con `codice_mir`, `meta_stazione` ("SARONNO (tutte le stazioni)"), `tariff_zone` | 0,5–0,9 s | No |
| GET | `solutions` | `departure`, `destination` (**per nome**), `date` `yyyyMMdd`, `fromHour`, `toHour` | ricerca in chiaro, stesso formato HAFAS, **senza prezzi** (`model: "NOT AVAILABLE"`) e senza binari | 0,6 s; 2 KB | No |
| GET | `station/{MIR}`, `stations` | | anagrafica, comprese le stazioni svizzere | 0,6–0,8 s | No |
| GET | `directors`, `directors/{code}` | | codici e nomi delle direttrici | 1,8 s | No |
| GET | `news/full` (200, schede loyalty), `news/card` (**500**) | | marketing | | No |
| GET | `carnet`, `stibm/modal`, `validities/`, `tariffs/book` | | commerciali | non provati | No |
| GET | `/fileadmin/templates/data/station_meta_map.json` | | `locIdSbme` → nome | 1 s | No |

### F. Cantieri (`cantieri.trenord.it`, dati su Yext)

| Metodo | Percorso | Parametri | Restituisce | Limiti, latenza | App |
|---|---|---|---|---|---|
| GET | `https://prod-cdn.us.yextapis.com/v2/accounts/me/search/vertical/query` | `experienceKey=moodifica-circolazione`, `verticalKey=ricercaperstazioni`, `api_key` (la chiave client pubblica scritta nella pagina, `c10d9d4d…`), `v=20220511`, `version=PRODUCTION`, `locale=it`, `input`, `limit`, `offset` | 25 cantieri: `name`, `c_periodiLavori` [{`dataInizio`, `dataFine`}], `c_lineaCantiere`, `c_stazioniConCantieriAttivi`, `c_avvisoCantiere` (PDF), coordinate | 0,9 s; 24 KB (190 KB grezzi) | No |

### G. Open data (non è un'API Trenord, ma sono dati Trenord)

| Risorsa | Contenuto | App |
|---|---|---|
| `https://www.dati.lombardia.it/download/3z4k-mxz9/application/zip` (`trenord_gtfs.zip`, 1,66 MB, **aggiornato il 19/09/2026**) | GTFS completo: 8470 corse con `trip_short_name` = numero del treno, `stop_id` = MIR, `calendar_dates` dal 26/07/2026 al 12/12/2026, bus sostitutivi (`TN_Bus`). **Niente binari** (`stops.txt` senza `platform_code`), niente tempo reale | No |

## Le prove per data

### `train/{n}` (BFF e mgmt danno risposte identiche)

| Treno | ieri 18/09 | oggi 19/09 | domani 20/09 | +3 (22/09) | +30 (19/10) |
|---|---|---|---|---|---|
| S5 24564 Treviglio–Varese | 30 fermate, **nessun dato reale**; però `status` V, `delay` 15 e `actual_station` BIVIO LAMBRO vengono da **oggi** | 30 fermate, 9 binari effettivi, 8 rilevamenti, stime per le fermate successive | 30 fermate, 0 binari | 30 fermate, 0 binari | 30 fermate (altra variante: arrivo 20:21 invece di 20:18), 0 binari |
| RE 2987 Gallarate–Centrale | ti dà **la corsa di oggi** (`date` 20260919) | 8 fermate, **5 binari programmati** (Gallarate 1, T2 2, T1 2, Busto N. 2, Saronno 5) | **`[]`**, eppure circola (la ricerca la trova 22:54→00:07) e `schedule` elenca il 20/09 | 7 fermate, finisce a P. Garibaldi (altra variante), 0 binari | 8 fermate, 0 binari |
| REG 178 Como Lago–Cadorna | — | 15 fermate, **15 binari programmati** | 15 fermate, 0 binari | 15 fermate, 0 binari | — |
| RE (MXP) 383 Cadorna–Malpensa | — | 7 binari programmati | 0 binari | — | — |
| EC 41, REG 2457 (Trenitalia, Trenitalia-TPER) | — | `[]` | `[]` | `[]` | — |
| RE80 25532/25534 (TILO) | — | 6 fermate, **finisce a Chiasso**; la ricerca prosegue fino a Lugano (9 fermate) | | | |
| RE 2617 / 2619 (partiti stamattina) | — | `status` A, `delay` 2 / 11, binari effettivi, **nessun orario reale**: al loro posto `*_estimated_time` = tabella + ritardo finale | | | |

Il campo `schedule` elenca i giorni di circolazione della variante: 22 date per la
variante di 24564 fino al 05/10, 68 per quella dal 06/10 al 12/12. **L'ultima data di
ogni variante non viene servita**: 24564 il 05/10 e il 12/12, 178 il 12/12, 2987 il
20/09 danno `[]`, mentre la ricerca quelle corse le trova (verificato su 24564 il 05/10
e su 178 il 12/12).

### `hafas/v2`

| Tratta | ieri | oggi | domani | +3 | +30 |
|---|---|---|---|---|---|
| Cadorna–Malpensa T1 | 5 corse `PAST_DEPARTURE_DATE`, `crowding` medio, e **il bus 1469A delle 21:56 al posto del treno** (la chiusura serale di ieri) | 5 MXP, binari programmati sulle corse con `has_live_info` | 5 corse, nessun binario | 5 | 5 |
| Centrale–Brescia | 5 corse `PAST_DEPARTURE_DATE` con `crowding` (RE 2641 HIGH 51,9 %) | 5 RE/REG, 8,40 € | compare REG 2457 `Trenitalia-TPER` (come tratta) | compare EC 41 `TRENITALIA`, `OTHER_OPERATOR`, senza prezzo | uguale a +3 |

### `station-details`

Una sola finestra: le prossime due ore. Col parametro `date=2026-09-22` la risposta è
byte per byte la stessa di quella senza.

## Risposte alle domande

**(a) C'è l'elenco completo delle fermate con gli orari di tabella per un giorno
futuro? E coi binari programmati?** Sì per le fermate: `train/{n}?date=` restituisce
l'intera corsa, con la variante giusta per quella data, per qualunque giorno fino
all'11/12/2026 (provati +1, +3, +30, +62). Non vale l'ultimo giorno di ogni variante,
che torna `[]` (trappola 1). La ricerca dà solo la tratta percorsa. **I binari invece
no:** per domani, +3 e +30 zero binari su tutti i treni provati, anche sulle linee FNM
che oggi li hanno tutti.

**(b) L'orario di stazione (`station-details`) funziona per i giorni futuri? Ha i
binari?** No a tutte e due. Copre le prossime ~2 ore di oggi, il parametro `date` viene
ignorato, e dentro non ci sono binari, ritardi né soppressioni. Negli arrivi manca
perfino l'origine: al posto della provenienza c'è la destinazione. L'unico orario di
stazione futuro è il GTFS regionale (G), che i binari non li ha.

**(c) Esiste un endpoint coi binari programmati?** Sì, `train/{n}` e `hafas/v2`
(`platform` con `is_actual_platform=false`), ma **solo per le corse di oggi e solo
sulle fermate della rete FNM**: su 12 treni di oggi l'hanno tutte le fermate di S3 876,
REG 4079 (Cadorna–Laveno) e REG 178, le fermate FNM di S1, S9, REG 4273 e RE 2974
(compresa Gallarate), mentre nessuna fermata RFI l'aveva (RE 2241 Bergamo, RE 2840
Tirano, RE 2643 Verona, REG 10911). Su RFI arriva solo l'effettivo, poco prima del
treno: 24564 l'aveva già alla fermata successiva, Forlanini. Il catalogo
`stazioni_v2` ha `platforms`, ma è l'elenco dei binari della stazione, non di un treno.
Il mapper dell'app legge già `is_actual_platform=false` come binario programmato
(`TrenordMappers.kt:453`); la novità è che quel dato esiste solo per oggi.

**(d) Ci sono endpoint su circolazione e disservizi che non usiamo?** Sì, quattro:
1. `mia/direttrici/`: notizie per direttrice con gravità (0 e 1 regolare, 2 critico, 3
   gravemente critico, lo dice il widget). Contiene anche **avvisi in tempo reale sul
   singolo treno**, per esempio: "Il treno 24564 (TREVIGLIO 18:10 - VARESE 20:21) sta
   viaggiando in ritardo a causa di un guasto che ha richiesto un intervento tecnico"
   (severity 2, 16:22Z). Si aggancia alla corsa con `train.direttrice` (`D027`), che
   `train/{n}` già restituisce.
2. `mia/linee/`: dalla linea alla direttrice.
3. `rest/render/shoulder-lines`: lo stato di ogni linea, verde oppure critico. Oggi
   l'S5 era "Circolazione con criticità".
4. I cantieri su Yext: lavori programmati con i periodi, le linee e le stazioni
   coinvolte. *(fatto il 20/09/2026: `CantieriApi`, avvisi in cima ai risultati
   per le ricerche che toccano una stazione Trenord. Due note dal campo: i campi
   `c_avvisoCantiere` e `c_descrizioneInterventoCantiere` **non sono stringhe** —
   il primo e' una lista di allegati, il secondo testo formattato — e le liste di
   stazioni contengono solo quelle **con lavori attivi**, non tutta la linea,
   quindi si accoppia su una stazione sola.)*

A questi si aggiungono gli `hafas_alerts` della ricerca, che l'app già usa (esempio:
bus Domodossola–Arona dal 18 al 20/09, con link a `cantieri.trenord.it`).

**(e) C'è qualcosa di utile per i treni di altre imprese (Trenitalia) in Lombardia?**
Poco. La ricerca include treni Trenitalia come tratte di una soluzione (EC 41, EC 28,
RV 2045, REG 2457 `Trenitalia-TPER`), ma **solo come orario**: niente dati in tempo
reale, niente prezzo (`OTHER_OPERATOR`), niente binari. `train/{n}` su un numero
Trenitalia risponde `[]`, e `station-details` elenca solo corse Trenord, comprese le
RE80 TILO sul lato italiano. L'unica cosa trasversale sono le notizie delle direttrici
e i cantieri, che riguardano i lavori RFI e quindi tutti.

## Cose che l'app non usa e dovrebbe

1. **Il gemello in chiaro sotto `mgmt/store-management-api/mia/`.** Stessi dati del BFF
   (verificato campo per campo su `train` e `hafas/v2`), ma compressi: **2,3 KB invece
   di 25 KB** per una corsa e **2,7 KB invece di 35 KB** per una ricerca. Il BFF cifrato
   non viene compresso (wire = lunghezza), perché i byte cifrati non si comprimono. Su
   «Segui treno», che interroga di continuo, la differenza è un ordine di grandezza di
   dati mobili, e in più non serve la decifratura. **Rischio:** il sito da lì chiama
   solo `direttrici/`, quindi `train/` e `hafas/v2` potrebbero sparire senza avviso.
   Andrebbe usato con il BFF come riserva, e presidiato da un test dal vivo. Host e
   destinatario non cambiano (`www.trenord.it`), quindi l'informativa resta corretta.
2. **`mia/direttrici/` per spiegare il perché.** *(fatto il 19/09/2026:
   `NotizieDirettrici`. Si riconosce la corsa da numero e ora di partenza
   dall'origine; il giorno da quello scritto nel testo, e se il testo nomina un
   giorno che non sappiamo leggere la notizia non vale per nessuno, invece di
   finire sulla corsa del giorno di pubblicazione.)* Il CLAUDE.md dice che il perché dei
   regionali per corsa "non lo pubblica nessuno". Per i treni Trenord a volte lo
   pubblica Trenord, dentro la notizia della direttrice e col numero del treno nel
   testo. Costa una chiamata da 6 KB, valida per tutte le corse. Per collegarla basta
   `train.direttrice`. Le notizie vanno filtrate: il "TRENO STORICO" (severity 1
   `warning`) era su tutte le 42 direttrici. Per l'aggiornamento conta `news[].date`:
   `updatedAt` della direttrice è fermo al 2019.
3. **Lo stato della linea.** Si ricava da `direttrici` (massimo `severity_code` fra le
   notizie) in 0,6 s, invece dei 5,8 s di `shoulder-lines`. Potrebbe diventare un
   bollino sul dettaglio corsa o sul tabellone.
4. **`train.schedule`: i giorni in cui la corsa circola.** È ciò che il sito usa per
   offrire le date future fino a 30 giorni (`getSchedule` in `tn-wc-train-info`).
   Serve a dire "questo treno domenica non circola" senza fare una ricerca, e a
   scegliere la data giusta per `train/{n}?date=`, tenendo conto della trappola 1.
5. **`mia/catalogue/products?origin&destination`: il prezzo senza cercare.** Dà il
   tariffario completo fra due MIR, in chiaro e senza data (Saronno–Cadorna adulto 2ª
   3,30 €, lo stesso della ricerca). Può coprire le righe in cui la ricerca Trenord non
   c'è o arriva senza `ticket_routes`.
6. **I cantieri (F)**: "lavori su questa linea dal … al …" con le stazioni coinvolte.
   **Decisione da prendere prima:** la chiave Yext è quella client pubblica già nella
   pagina di Trenord, ma il CLAUDE.md ha scartato `opentransportdata.swiss` proprio
   perché "una chiave in un'app open source è una chiave pubblicata". Qui la chiave è
   di Trenord e già pubblica, come l'`obfKey` che l'app usa, però il dominio è di terzi
   (`yextapis.com`) e l'informativa privacy oggi non lo nomina.
7. **Il GTFS regionale (G)**: tutte le corse Trenord fino al 12/12/2026, offline e senza
   i buchi dell'ultimo giorno di variante. Si potrebbe imbarcare come EAV e ARST (1,66
   MB compressi, aggiornato oggi). Non ha binari.
8. (Minore) **`crowding` delle corse passate** (`source: AVERAGE`, LOW/MODERATE/HIGH con
   percentuale) su `hafas/v2` di ieri. Si potrebbe usare come stima per la stessa corsa
   di oggi. Non c'è mai per oggi né per il futuro.

## Trappole

1. **`train/{n}?date=D` risponde `[]` l'ultimo giorno di ogni variante di `schedule`.**
   È successo a 2987 il 20/09, a 24564 il 05/10 e il 12/12, a 178 il 12/12, e la
   ricerca quelle corse le trova. Un `[]` non vuol dire "non circola": va riprovato con
   `hafas/v2`.
2. **Su una data passata, i campi di corsa parlano di oggi.** `train/24564?date=2026-09-18`
   restituisce `date` 20260918 ma `status` V, `delay` 15, `actual_station` BIVIO LAMBRO
   con `actual_time` 2026-09-19T17:00Z, `train_operator` `TRENORD$:$FNM3`. Nella stessa
   risposta ci sono fermate con `date` 20260919. Lo stesso per 10911 e 2833 del 18/09.
   È la stessa famiglia di `ritardoDichiarato`.
3. **Una data passata può restituire un altro giorno.** `train/2987?date=2026-09-18` ha
   dato la corsa del 19/09.
4. **Dopo l'arrivo gli orari reali per fermata spariscono.** Su 2617 e 2619 di
   stamattina `status` era A, `delay` finale, `has_live_info` falso, `pass_id` -1, i
   binari restavano effettivi, ma `actual_data` conteneva solo
   `dep/arr_estimated_time` = tabella + ritardo finale (2619: 09:36 a Centrale, +11
   ovunque). Letti come orari reali darebbero un ritardo uniforme inventato. Trenord
   non conserva lo storico della corsa.
5. **Lo stato nella ricerca è inaffidabile per le corse già passate di oggi.** 2619
   nella ricerca era `status` N e `has_live_info` falso, mentre `train/2619` diceva A
   con +11.
6. **La categoria cambia da un endpoint all'altro.** 383 è "MXP" nella ricerca di oggi
   e "RE" in `train/383`, nella ricerca di ieri e sul tabellone. `train_operator` vale
   `TRENORD` o `TRENORD$:$FNM3` a seconda della data e del tempo reale. Sul tabellone
   c'è anche la categoria "S        24966", con gli spazi.
7. **`delay` di corsa non coincide coi ritardi di fermata.** 24564 dichiarava `delay`
   15 con fermate a +16…+21. `actual_station` può non essere una stazione (BIVIO
   LAMBRO `S01719`, un bivio).
8. **`train/{n}` si ferma al confine**: la RE80 25534 finisce a Chiasso, mentre nella
   ricerca prosegue fino a Lugano.
9. **Treni Trenitalia**: in `train/{n}` rispondono `[]`. In ricerca compaiono come
   tratte senza tempo reale e senza prezzo, e l'impresa si scrive in più modi
   (`TRENITALIA`, `Trenitalia-TPER`).
10. **`station-details`**: solo le prossime ~2 ore, `date` ignorato, 6–15 s, fino a 1 MB
    grezzo. Negli **arrivi c'è la destinazione, non l'origine** ("Diretto a MILANO
    CENTRALE" per tutti gli arrivi a Centrale, "S3 871 → SARONNO" a Saronno). Il primo
    orario del blocco è un segnaposto (00:49), `data-direzione` vale sempre
    "da-milano-centrale", e non c'è alcun segno di soppressione. Chi usa `direction`
    dagli arrivi (`TrenordBoardParser`) mostra la destinazione come provenienza.
11. **Binari programmati solo per oggi**, anche sulle linee FNM. Domani non ce n'è
    nessuno. Quando compaiano per il giorno dopo (a mezzanotte? all'alba?) non l'ho
    potuto verificare di sera.
12. **L'orario finisce il 12/12/2026**: dal 15/12 ricerca vuota e `train` `[]`. Al cambio
    orario la risposta vuota vuol dire "orario non ancora caricato", non "nessun treno".
13. **La ricerca non dà mai la corsa intera**: `pass_list` è solo la tratta percorsa,
    con `with_routes` vero o falso. L'intera corsa sta solo in `train/{n}`.
14. **Le linee hanno due codici.** `mia/linee` e `shoulder-lines` usano `RE_13`, `RE_80`
    e simili, mentre `train_category` dice "RE". Il ponte affidabile è la direttrice,
    non la linea.
15. **`search-train-list` risponde vuoto** anche con numeri validi, e
    `store-api/news/card` risponde 500. Da non usare.
16. **L'`apiSecret`** (JWT "Trenord Management") è nel bundle dello store e lo store lo
    manda come header `secret` a `stazioni_v2`. Non serve (i widget non lo mandano) e
    non va usato.
17. **I formati di data** (già noti): `hafas/v2` vuole `yyyyMMdd`, `train` vuole
    `yyyy-MM-dd`.
18. **L'estimated in avanti c'è**: durante la corsa le fermate future hanno
    `arr/dep_estimated_time` (24564: Varese 20:36 contro 20:21 di tabella), e l'app già
    lo legge. Dopo l'arrivo, però, vedi la trappola 4.

## Fonti esterne consultate

- [berry-13/treno](https://github.com/berry-13/treno) (`packages/providers/src/mia.ts`: `admin.trenord.it/store-management-api/mia/train/{n}`, e l'URL del GTFS regionale)
- [MarcoBuster/railway-opendata, issue 2](https://github.com/MarcoBuster/railway-opendata/issues/2) (lo stesso endpoint `admin.trenord.it`)
- [Mia-Platform, caso Trenord](https://mia-platform.eu/case-history/trenord-the-digital-platform-for-mobility-in-lombardia/) (spiega il nome "mia" nei percorsi)
- metadati del dataset `3z4k-mxz9` su dati.lombardia.it
