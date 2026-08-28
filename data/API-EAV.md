# EAV — mappatura delle fonti

Ricognizione del 28/08/2026. Tre fonti indipendenti, nessuna delle quali basta da sola.

| Fonte | Cosa dà | Autenticazione | Formato |
|---|---|---|---|
| `orariotreni.eavsrl.it` | tabellone live di stazione | nessuna | HTML |
| GTFS open data | orario ufficiale fino a maggio 2027 | nessuna | ZIP di CSV |
| `unicocampania.it` | tariffe comune→comune | token CSRF di sessione | HTML |

Nessuna richiede chiavi, nessuna è offuscata. La licenza del GTFS è **Italian Open Data
Licence v2.0**, che consente esplicitamente l'uso commerciale: è la posizione più pulita
di qualunque altra fonte già integrata nell'app.

---

## 1. Tabellone live

Endpoint unico, scoperto dal codice di `/teleindicatori/`:

```
POST https://orariotreni.eavsrl.it/teleindicatori/ws_getData.php
Content-Type: application/x-www-form-urlencoded

tipoLista=P&codLoc=1&visualizzazione=mobile
```

| Parametro | Valori | Note |
|---|---|---|
| `tipoLista` | `P` partenze, `A` arrivi | obbligatorio |
| `codLoc` | id stazione (vedi §3) | obbligatorio |
| `visualizzazione` | `mobile` → 40 corse; qualunque altro valore o assente → 10 | **è un interruttore, non un numero**: `100` dà 10 |
| `device` | ignorato | il sito manda `M01T1M`, ma qualunque valore o l'assenza non cambia nulla |

**Insidie verificate:**

- **GET non funziona.** Risponde 200 con un corpo vuoto di 246 byte. Serve POST.
- **Nessun parametro di data.** `data`, `giorno`, `dataRif` vengono ignorati: la risposta
  è sempre "adesso". Per una data futura serve il GTFS.
- **Stazione inesistente non è un errore**: 200 con corpo vuoto. Va distinto dal caso
  "nessun treno in arrivo" guardando se il markup contiene la tabella.
- **La risposta è sempre impaginata al numero richiesto**, riempita con righe vuote. Una
  stazione senza servizio restituisce comunque 40 `<tr>` con tutte le celle vuote —
  verificato su `codLoc=107` (Pozzuoli). Il parser deve scartare le righe con `numTreno`
  vuoto, altrimenti conta 40 corse inesistenti.
- **Orizzonte temporale: circa 6 ore.** Misurato il 28/08/2026 alle 12:14: Porta Nolana
  copriva fino alle 18:26, Sorrento fino alle 18:50, Sant'Anastasia fino alle 20:37. Il
  limite sono le 40 corse, quindi l'orizzonte si accorcia sulle stazioni trafficate e si
  allunga su quelle secondarie.
- Il sito ripolla ogni 10 secondi e confronta la stringa intera per decidere se ridisegnare.
- `socket.io` è caricato ma **commentato** nell'HTML: serviva solo a un segnale di
  `reloadPage`, non ai dati. Non c'è websocket da agganciare.

**Risposta.** HTML, non JSON, ma con classi CSS stabili — una riga `<tr>` per corsa:

| Classe | Contenuto | Esempio |
|---|---|---|
| `numTreno` | numero treno (preceduto da `&nbsp;`) | `1117` |
| `categoria` | categoria | `DD`, `A` |
| `destinazione` | dentro un `<div>` | `SORRENTO` |
| `informazioni` | dentro un `<marquee>` | `SOPPRESSO - - VIA POMPEI` |
| `binario` | può essere vuoto | `9` |
| `orario` | `HH:MM` | `11:41` |
| `ritardo` | vuoto se in orario, `<marquee>` se no | `SOPPRESSO` |

Il campo `informazioni` porta sia l'instradamento (`VIA POMPEI`, `VIA SCAFATI`) sia lo
stato: sono concatenati con ` - - `. La soppressione compare **due volte**, in
`informazioni` e in `ritardo`.

---

## 2. GTFS

```
https://www.wimob.it/cfile/download.php?file=google-transit.zip
```

3,1 MB compressi, 22 MB estratti, aggiornamento dichiarato mensile. Il file scaricato
il 28/08/2026 è datato **29/07/2026**.

| File | Righe | Note |
|---|---|---|
| `agency.txt` | 2 | `NA0004` ferrovia, `EAVO` autolinee |
| `routes.txt` | 103 | 16 ferroviarie (`route_type=2`), 87 bus (`=3`) |
| `trips.txt` | 6.370 | **`trip_short_name` = numero del treno** |
| `stops.txt` | 3.863 | include le fermate bus; `zone_id` = codice ISTAT del comune |
| `stop_times.txt` | 228.884 | arrivo e partenza per ogni fermata |
| `calendar_dates.txt` | 2.219 | 37 `service_id`, dal **20260729 al 20270513** |
| `shapes.txt` | 115.953 | tracciati per la mappa |

**Non c'è `calendar.txt`**: il servizio è definito solo per date esplicite in
`calendar_dates`. E **non ci sono `fare_attributes.txt` / `fare_rules.txt`**: le tariffe
vanno prese altrove (§4).

Le 16 linee ferroviarie vanno oltre la Circumvesuviana: ci sono anche Cumana (9),
Circumflegrea (5), Piscinola–Aversa (2), Napoli–Benevento (3) e
Napoli–Caserta–Piedimonte Matese (7). Alcuni `route_id` usano il punto come suffisso di
variante: `1`, `1.`, `1..` sono Napoli–Sorrento, Napoli–Torre Annunziata e
Napoli–Torre del Greco.

**Copertura fino a maggio 2027**: è l'unica delle fonti dell'app che permette di
rispondere su date future. ViaggiaTreno risponde `204` oltre la giornata corrente.

---

## 3. La chiave di join

Due agganci, entrambi verificati:

**Stazioni: `gtfs_stop_id = codLoc + 6000`.**
Delle 126 stazioni distinte nel catalogo del sito, 102 trovano corrispondenza. Le 11
apparenti divergenze di nome sono solo `SAN`/`SANTA` contro l'abbreviazione `S.` e vanno
normalizzate prima del confronto.

Le 24 senza corrispondenza sono impianti chiusi, stagionali, bivi e posti di movimento —
`CAVALLI DI BRONZO`, `POZZANO`, `SCRAJO`, `BIVIO MADONNELLE`, `ACCADIA P.M.`… — più alcune
fermate urbane che il GTFS non espone. Vanno trattate come non pianificabili: il tabellone
le mostra, l'orario no.

La tabella completa è in `stazioni-mapping.csv`, con coordinate e codice ISTAT.

**Corse: `trip_short_name` = numero del treno del tabellone.**
Verificato sui numeri visti in diretta — 1117, 6117, 4119, 11157 — ciascuno presente
esattamente una volta in `trips.txt`. È l'aggancio che permette di prendere il numero dal
tabellone e ricostruire da GTFS il percorso completo, cosa che nessun endpoint EAV offre.

Il catalogo del sito contiene anche un campo `idMoova`: **è morto**, valorizzato su una
stazione su 184. Ignorarlo.

---

## 4. Tariffe

Non stanno in EAV: la Campania ha tariffa integrata **UnicoCampania**, a zone per comune.
Il calcolatore è un form Laravel:

```
POST https://www.unicocampania.it/tariffeList
_token=<csrf>&comune_partenza=NAPOLI&comune_arrivo=SORRENTO&ppn=true
```

Serve prima un `GET /` per raccogliere il cookie di sessione e il valore di `_token`: sono
accoppiati, un token senza il suo cookie viene rifiutato. `ppn` è il flag "passaggio per
Napoli", che cambia il prezzo.

Verificato Napoli→Sorrento: **corsa semplice aziendale € 4,60**, biglietto orario € 5,80,
più l'intera scala di abbonamenti (mensili e annuali, fino a € 756,00). Nella risposta il
prezzo sta in `<div class="price-tk">`, raggruppato per tipo di titolo.

**L'aggancio con il GTFS c'è già**: `stops.txt` porta `zone_id` = codice ISTAT del comune
(Napoli `63049`), e sono 50 comuni distinti. Da stazione a comune a tariffa il percorso è
completo senza dover indovinare nulla.

Da valutare: le tariffe cambiano di rado, quindi conviene una tabella comune→comune
calcolata una volta e imbarcata, invece di una chiamata a runtime a ogni ricerca. 50 comuni
significano al massimo 1.225 coppie.

---

## 5. Cosa manca

- **Nessun tempo reale sul percorso.** Il tabellone dà il ritardo alla stazione
  interrogata, non lungo la corsa. Per sapere dov'è un treno bisognerebbe interrogare il
  tabellone di ogni stazione della linea, che è insostenibile. Il ritardo lungo il percorso
  si può solo propagare dall'ultimo dato noto, come già si fa per ViaggiaTreno.
- **Nessun endpoint per numero di treno.** Il numero si risolve solo via GTFS, quindi il
  percorso di una corsa è quello teorico: il ritardo arriva unicamente dai tabelloni.
- **`api.eavsrl.it` è un gateway WSO2 attivo** ma espone solo i servizi interni di default
  (`echo`, `wso2carbon-sts`, `Version`). È quasi certamente il backend dell'app ufficiale
  Go EAV: i percorsi reali si scoprirebbero solo ispezionando l'APK o il suo traffico. Non
  è indispensabile — GTFS più tabellone coprono già orari, percorsi e tempo reale.
- **Viaggi misti** con gli altri operatori: fuori portata per ora, come da tua indicazione.

## 6. Com'è stata integrata

Questo documento è nato prima del codice; da qui in poi descrive quello che c'è, non quello
che si prevedeva. Le insidie operative stanno nei commenti del modulo `:data`, secondo la
convenzione del progetto: qui resta ciò che nel codice non troverebbe posto — la licenza
del feed, gli endpoint delle tariffe, la storia dei due registri.

| Classe | Cosa fa |
|---|---|
| `EavApi` | l'unico endpoint, il POST del tabellone |
| `EavBoardParser` | interpreta l'HTML del monitor, scartando le righe di riempimento |
| `EavStations` | le 150 fermate, generate incrociando i due registri |
| `EavOrario` · `EavGtfsParser` | l'orario compatto e il lettore del GTFS |
| `EavGtfsUpdater` | il rinfresco a bordo, quando l'orario supera i tre mesi |
| `EavRepository` | sceglie fra tabellone e orario, e dichiara quale ha risposto |

Due scelte che vale la pena ritrovare qui:

- **Il GTFS non si interroga a runtime.** Viene ridotto al solo ferroviario — 623 corse,
  7.693 passaggi — e imbarcato compresso in 34 KB. Lo stesso `EavGtfsParser` serve il task
  di build e l'aggiornamento sul telefono, così le due strade non possono divergere.
- **`realtime` è un campo, non una sfumatura.** Le righe che vengono dall'orario lo portano
  falso: il loro ritardo non è zero, è sconosciuto, e su una rete dove le soppressioni sono
  quotidiane spacciarlo per puntualità sarebbe la bugia più dannosa possibile.
