# Treni — App Android orari & stato treni in tempo reale

> Piano tecnico. Verifiche API eseguite dal vivo il **2026-08-27**.

## 1. Sorgenti dati (verificate oggi, niente roba legacy)

Non esiste un'API ufficiale Trenitalia/RFI. Le uniche due sorgenti vive e **attualmente in produzione** sono:

### A. Lefrecce BFF — *ricerca A→B*
`https://app.lefrecce.it/Channels.Website.BFF.WEB/app`
È il backend che alimenta **oggi** www.lefrecce.it e l'app ufficiale Trenitalia. HTTPS, JSON.

| Endpoint | Uso |
|---|---|
| `GET /locations?name={q}&limit=10` | autocompletamento stazioni → `locationId` + `bdoCode` |
| `GET /locations/closest?lat=&lon=` | stazione più vicina (GPS) |
| `GET /search?startlocationid=&endlocationid=&arflag=A&departure_time={ISO}&adultno=1&childno=0&direction=A` | → `searchId` |
| `GET /search/{searchId}/solutions?offset=0&limit=10` | soluzioni di viaggio |

- **Ponte chiave**: `bdoCode` (`S01700`) è esattamente il codice stazione ViaggiaTreno. `locationId = 830000000 + numero(bdoCode)` (verificato su 8 stazioni; non vale per fermate bus/multistazione, che non hanno `bdoCode` e vanno filtrate).
- In `solutions[].solutionNodes[]` filtrare `type == "SOLUTION_SEGMENT"` (gli altri sono `SOLUTION_LOCATION`, punti di interscambio/bus). Ogni segmento = una tratta:
  `offeredTransportMeanDeparture.name` = **numero treno**, `.classification.acronym` = **FR/IC/REG/RE/EC…**
- Richiede **CookieJar** (`ASESSIONID`) + User-Agent realistico + gzip. C'è Akamai davanti (`_abck`, `bm_sz`) ma non blocca il traffico normale.
- `searchId` **scade in ~10 minuti** → rieseguire la search al refresh.
- ⚠️ **Morti (HTTP 410)**: `/solutions/{id}`, `/summaryview`, `/nodes/{x}/stops`, `/nodes/{x}/delay`. Non usarli — il realtime lo prendiamo da ViaggiaTreno.

### B. ViaggiaTreno — *tempo reale*
`http://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno`
Backend del portale ufficiale RFI/Trenitalia. È l'**unica** sorgente gratuita di ritardi/fermate live.

| Endpoint | Uso |
|---|---|
| `GET /cercaStazione/{prefisso}` | stazioni (JSON) |
| `GET /elencoStazioni/{codReg}` (0…22) | **dump completo stazioni** → DB offline in-app |
| `GET /cercaNumeroTrenoTrenoAutocomplete/{n}` | `n - ORIGINE - gg/mm/aa|n-Sxxxxx-millis` → risolve `(codOrigine, millisDataPartenza)` |
| `GET /andamentoTreno/{codOrigine}/{n}/{millis}` | **stato completo del treno** |
| `GET /partenze/{staz}/{data}` · `/arrivi/{staz}/{data}` | tabellone stazione |
| `GET /tratteCanvas/{codOrig}/{n}/{millis}` | tratte per la mappa |

⚠️ Due vincoli reali, misurati:
1. **Solo HTTP** — l'HTTPS fa 301 verso HTTP (il cert è di `www.rfi.it`). Serve `networkSecurityConfig` con cleartext **limitato al solo dominio `viaggiatreno.it`**.
2. **Solo giornata odierna** — `andamentoTreno` risponde `204 No Content` per ieri e per domani. Quindi: ricerca su data futura ⇒ **solo orario**, nessun ritardo. È il comportamento anche delle app esistenti.

### Mappatura campi → UI (da `andamentoTreno`)
- **Nome treno**: `compNumeroTreno` (es. `"REG 17801"`)
- **Ritardo**: `ritardo` (minuti, può essere negativo = anticipo)
- **Stato**: `provvedimento` (0 regolare, 1 soppresso, 2 variato), `circolante`, `nonPartito`, `fermateSoppresse[]`, `tipoTreno` (`ST` soppresso tot. / `PP` parz. / `PG` regolare), `subTitle`
- **Dov'è il treno**: `stazioneUltimoRilevamento` + `compOraUltimoRilevamento` (+ `oraUltimoRilevamento` in ms)
- **Fermate** (`fermate[]`): `progressivo`, `stazione`, `id`, `arrivo_teorico`/`arrivoReale`/`ritardoArrivo`, `partenza_teorica`/`partenzaReale`/`ritardoPartenza`, `binarioProgrammato*`→`binarioEffettivo*`, `tipoFermata` (P/F/A), `actualFermataType` (**0** futura · **1** effettuata · **2** in corso · **3** soppressa)

> Per le fermate future ViaggiaTreno **propaga già il ritardo stimato** in `ritardoArrivo`/`ritardoPartenza`: è esattamente lo "stimato" richiesto, non serve calcolarlo.

## 2. Prova end-to-end eseguita
`/locations "bologna c"` → `/search` → `/solutions` → estrazione tratte → `cercaNumeroTrenoTrenoAutocomplete` → `andamentoTreno`, con output reale:
```
REG 17801 BOLOGNA CENTRALE -> PRATO CENTRALE | ritardo 0' | ultimo rilev: PRATO CENTRALE @ 06:47
  ✓ 1 BOLOGNA CENTRALE   par 05:35/05:36 +1  bin -→IV-EST
  ✓ 9 PIANORO            arr 05:58/05:57 -1  par 05:59/06:00 +1  bin 2→2
  ✓ 14 VERNIO MONTEPIANO arr 06:26/06:30 +4  par 06:27/06:32 +6  bin 3→30
```
Anche le soluzioni **con cambio** funzionano (es. `RE 17801` Bologna→Prato + `RE 18657` Prato→Firenze).

## 3. Decisioni prese

| Voce | Scelta |
|---|---|
| Nome app | **ZawardoTreni** |
| Package id | **`it.zawardo.treni`** (definitivo: su Play non è più modificabile dopo la prima pubblicazione) |
| minSdk | **26** (Android 8.0) — copre >98% dei device, `java.time` nativo, niente desugaring |
| compileSdk / targetSdk | **36** |
| Output | **debug APK** (iterazione) + **release firmato** (consegna finale) |
| Toolchain | installazione **portabile** in `.tools/` — nessun admin, nessuna variabile globale |
| Feature v1 extra | tutte e quattro: ricerca per numero treno · tabellone partenze/arrivi · stazione vicina GPS · notifica ritardo su treno seguito |
| Consegna | APK installato a mano dall'utente (device Android 15) |

### Stack (versioni stabili risolte dai repository il 2026-08-27)
| | |
|---|---|
| JDK | Temurin **17** (minimo e default per AGP 9.3) |
| Gradle | **9.7.1** (AGP 9.3 richiede ≥ 9.5.0) |
| AGP | **9.3.2** |
| Kotlin | **2.4.10** |
| Compose BOM | **2026.08.00** |
| Room | **2.8.4** |
| Retrofit | **3.0.0** |
| OkHttp | **5.5.0** |
| kotlinx-serialization | **1.11.0** |
| Navigation Compose | **2.10.0** |
| WorkManager | **2.11.2** (notifiche ritardo) |
| DataStore | **1.2.1** |
| Android cmdline-tools | **23.0** (`16111833`) |

### Nota architetturale: notifica ritardo

Requisito: **polling 60 s**, avviso se il ritardo cambia di **oltre 3 minuti**.

`WorkManager` ha un intervallo periodico minimo di **15 minuti** imposto dal sistema: non è utilizzabile.
Unica via conforme → **Foreground Service** con notifica persistente.

- `foregroundServiceType="dataSync"` — su Android 15 ha un tetto di **6 h/giorno**; sufficiente per un viaggio, e a differenza di `specialUse` non è a rischio di rigetto in review su Play.
- Avvio **solo** su azione esplicita dell'utente ("Segui treno") → soddisfa il vincolo Android 14+ che vieta l'avvio da background.
- Notifica persistente aggiornata in-place con il ritardo corrente (è il canale di lettura principale, non un effetto collaterale).
- Canale notifiche separato per l'**alert** (suono/vibrazione) emesso solo quando `|ritardo_nuovo − ritardo_notificato| > 3 min`.
- **Auto-stop** all'arrivo del treno alla stazione di destinazione dell'utente, o su `arrivato == true`.
- Permessi: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS` (runtime, Android 13+).

## 4. Stack di dettaglio
- **Kotlin** + **Jetpack Compose** (Material 3), MVVM, Coroutines/Flow
- **Retrofit 2** + **OkHttp** (CookieJar, interceptor UA/gzip) + **kotlinx.serialization**
- **Room**: `stations` (seed offline), `search_history` (ultime 10, LRU), `saved_searches`
- **DataStore** per preferenze
- Gradle wrapper 8.x / AGP 8.x / **JDK 17**

Ogni dettaglio di rete sta dietro interfacce (`ItineraryDataSource`, `TrainStatusDataSource`) con base-URL in config: se un endpoint cambia si sostituisce l'implementazione senza toccare la UI.

## 5. Schermate
1. **Ricerca** — partenza/arrivo con autocompletamento (debounce 250 ms: prima Room offline, poi remoto), pulsante scambio, "vicino a me" (GPS opzionale), DatePicker + TimePicker **preimpostati su adesso**, `Cerca` + `Salva ricerca`.
2. **Home** — tab *Cronologia* (ultime 10, auto) e *Salvate* (manuali, rinominabili). Tap = ricerca rieseguita con orario corrente.
3. **Risultati** — per soluzione: `partenza→arrivo`, durata, n. cambi, chip treno (`FR 9505`), badge ritardo live e stato (Soppresso / Variato / In orario / +N′) se la data è oggi.
4. **Dettaglio treno** — header (numero, origine→destinazione, ritardo, "ultimo rilevamento a X alle HH:MM"), timeline fermate con effettivo/stimato + binario programmato→effettivo, fermate soppresse barrate; pull-to-refresh + auto-refresh 60 s.

## 6. Fasi
| # | Fase | Output |
|---|---|---|
| 0 | Toolchain (JDK 17 + Android SDK cmdline-tools + platform/build-tools 35) | `gradlew` funzionante |
| 1 | Layer rete + modelli + repository | test JVM che replicano la prova end-to-end |
| 2 | Seed DB stazioni (`elencoStazioni` 0..22 → asset) | autocompletamento offline |
| 3 | UI Ricerca + cronologia + salvate (Room) | schermata 1-2 |
| 4 | UI Risultati + arricchimento realtime | schermata 3 |
| 5 | UI Dettaglio treno | schermata 4 |
| 6 | Polish (tema chiaro/scuro, errori, offline) + build APK | APK installabile |

## 7. Rischi e mitigazioni
| Rischio | Mitigazione |
|---|---|
| ViaggiaTreno non documentato, può cambiare | interfacce + base-URL configurabile; degradazione a "solo orario" se il realtime fallisce |
| ViaggiaTreno in cleartext HTTP | `networkSecurityConfig` con eccezione **solo** per `viaggiatreno.it` |
| Akamai su Lefrecce | CookieJar persistente, UA realistico, retry con backoff |
| `searchId` scade a ~10 min | ricerca rieseguita al refresh |
| Realtime solo per oggi | UI mostra esplicitamente "orario previsto" per date future |
| API non ufficiali | uso personale, nessuna vendita biglietti, rate limit conservativo lato client |

## 8. Pubblicazione su Google Play (valutazione preliminare)

### Costi e account
- Account Google Play Developer: **25 USD una tantum**.
- ⚠️ Per i **nuovi account personali** Google richiede un **closed testing con almeno 12 tester per 14 giorni consecutivi** prima di poter richiedere l'accesso alla produzione. Gli account *organizzazione* ne sono esenti. È il vincolo con più impatto sulla tempistica: va avviato con largo anticipo.
- Verifica di identità obbligatoria (documento per privati, D-U-N-S per organizzazioni).

### Requisiti tecnici
| Requisito | Impatto sul progetto |
|---|---|
| Formato **AAB**, non APK | il build lo produce già: `bundleRelease` |
| **Play App Signing** | upload key locale + chiave di firma gestita da Google |
| `targetSdk` recente (entro 1 anno dalla major) | `targetSdk 36` è conforme |
| Supporto 64-bit | automatico con Kotlin/Compose |
| **Privacy policy** su URL pubblico | **obbligatoria**: l'app usa internet e posizione |
| **Data safety form** | dichiarare la posizione (solo locale, mai trasmessa) e l'assenza di raccolta dati |
| Content rating | questionario, esito atteso: PEGI 3 |
| Asset store | icona 512×512, feature graphic 1024×500, ≥2 screenshot |

### ⚠️ Rischio specifico di questa app
L'app si appoggia ad **API non ufficiali** di Trenitalia/RFI. Pubblicarla su Play espone più di un uso privato. Mitigazioni da applicare **dall'inizio**, non dopo:

1. **Nessun marchio altrui** nel nome, nell'icona o negli screenshot: mai "Trenitalia", "Frecciarossa", "ViaggiaTreno", "FS", né i loro loghi o la livrea colori. `ZawardoTreni` va bene.
2. **Disclaimer visibile** in app e nella scheda Play: *"App non ufficiale, non affiliata né approvata da Trenitalia S.p.A. o Ferrovie dello Stato."* Serve contro la policy Play di **impersonation**.
3. **Nessuna vendita di biglietti** — già escluso per scelta.
4. Rate limiting client-side e cache aggressiva: ridurre il carico sulle API riduce la probabilità di attirare attenzione o di essere bloccati.
5. Restano possibili una richiesta di rimozione da parte di Trenitalia o un blocco delle API. Nessuna mitigazione tecnica lo esclude. Va messo in conto come rischio accettato: app analoghe sono su Play da anni, ma non è una garanzia.

### Conseguenze sulle scelte di adesso
- Package id `it.zawardo.treni` fissato ora: dopo la prima pubblicazione è immutabile.
- Build configurato da subito per generare **sia APK sia AAB**.
- Keystore di upload generato e **da conservare**: se si perde, l'app su Play non è più aggiornabile.
- Schermata "Info" con disclaimer e crediti fonti dati già nella v1.
