# Viaggi misti multi-operatore

Stato: **implementato**, dietro il flag beta «Soluzioni con più operatori»,
spento di default. Questo documento resta come mappa del disegno; la sezione 11
in fondo dice cosa è vivo e cosa no.

## 1. Il problema, ridotto all'osso

L'app oggi trova viaggi con cambio, ma **dentro una sola rete alla volta**: il
BFF Le Frecce concatena Trenitalia e i regionali che conosce, Trenord fa il suo.
Quello che nessuno fa è un viaggio che **cambia operatore per strada**.

Due casi, di natura diversa:

- **feeder + Italo** — arrivo a un grande hub con un treno qualunque, proseguo
  con Italo. Il cambio è alla **stessa stazione fisica**: Milano Centrale è
  `S01700` per tutti. Match esatto sul codice RFI, che per Trenitalia,
  ViaggiaTreno, Trenord e Italo è già la chiave comune (`ItaloStations` tiene
  `italo → rfi`).
- **fuori-RFI + alta velocità** — Sorrento→Roma: EAV fino a Napoli, poi Italo o
  Trenitalia. Qui il cambio **non è alla stessa stazione**: si arriva a Napoli
  Garibaldi (EAV) e si riparte da Napoli Centrale (RFI), a qualche minuto a
  piedi. Le due stazioni hanno codici diversi e registri diversi. È il caso che
  serve davvero, ed è il più difficile.

Il secondo caso è la ragione per cui questo documento esiste: senza di lui
basterebbe estendere `merge()` di poco.

## 2. La decisione di fondo: interscambi come tabella, non come geometria

La tentazione è calcolare gli interscambi a runtime dalle coordinate: due
stazioni entro N metri = si può cambiare. **Non lo facciamo**, per tre motivi:

- **falsi positivi.** Due stazioni a 300 metri in linea d'aria possono avere in
  mezzo una ferrovia da scavalcare, o venti minuti di salita. La distanza
  euclidea non conosce i sottopassi.
- **il transfer va dichiarato.** «Napoli Garibaldi → Napoli Centrale, 10 minuti
  a piedi» è un'informazione che l'utente ha diritto di leggere prima di
  fidarsi. Un match geometrico non sa dire i minuti.
- **sono pochi e noti.** Gli interscambi fuori-RFI ↔ AV in Italia si contano
  sulle dita: Napoli (EAV ↔ Centrale/Afragola), Bari (FNB ↔ Centrale),
  Domodossola (Vigezzina ↔ RFI). Precalcolarli a mano costa un pomeriggio e li
  rende esatti.

Quindi: una **tabella di interscambi imbarcata**, dello stesso genere di
`ItaloStations`.

```
Interscambio(
    da: "EAV3",         // Napoli P. Garibaldi (EAV)
    a: "S09218",        // Napoli Centrale (RFI)
    minuti: 10,
    modo: A_PIEDI,      // oppure STESSA_STAZIONE
    nota: "uscita lato Garibaldi, ~600 m",
)
```

Gli interscambi **stessa stazione** (stesso `rfiCode`, cambio ~15 min) restano
impliciti e non stanno in tabella: si riconoscono dal codice uguale.

Le coordinate — che EAV, FNB e Svizzera già hanno — restano utili per una cosa
sola: **preselezionare quali hub provare** (§4), non per decidere dove si
cambia.

## 3. Il modello dati da cambiare

Tre modifiche, in ordine di invasività.

### 3a. La sorgente scende sulla gamba

Oggi `Journey.source` è unica. Un viaggio misto ha gambe di operatori diversi,
e ognuna va seguita in tempo reale dalla **sua** sorgente: la gamba EAV dal
tabellone EAV, la gamba Italo dal servizio Italo. Serve `Leg.source`.

`Journey.source` diventa derivata: `MIXED` se le gambe non concordano, altrimenti
quella comune. Il codice che oggi legge `Journey.source` per decidere dove
chiedere il realtime va spostato a livello di gamba.

### 3b. La gamba di trasferimento

Un cambio a piedi è una gamba a sé, senza treno:

```
Leg(kind = WALK, from = Garibaldi, to = Centrale, durata = 10 min)
```

`TransportKind` ha già `OTHER`; si aggiunge `WALK`, o si riusa `OTHER` con un
`kindLabel = "A piedi"`. La gamba a piedi **non è tracciabile** (nessun numero),
e nell'UI si mostra diversa: niente ritardo, niente binario, solo «~10 min a
piedi».

### 3c. Il viaggio misto sa di esserlo

Un flag o un tipo che distingua un viaggio misto da uno normale, per l'UI (il
badge «beta», l'avviso sul prezzo Italo mancante) e per il ranking (i misti non
devono soppiantare i diretti).

## 4. L'algoritmo: hub-and-spoke

```
cerca(A, C, quando):
  1. hub candidati:
       H = stazioni-AV ∪ estremi-di-interscambio
       tenute solo quelle "fra A e C":
         dist(A,H) + dist(H,C) ≤ 1.4 × dist(A,C)
       → tipicamente 1..3 hub
  2. per ogni H, in parallelo:
       gambaA = miglior viaggio A → (H o suo interscambio)   [rete di A]
       gambaB = miglior viaggio (H o suo interscambio) → C   [rete AV: Italo/Trenitalia]
       (e la simmetrica, per il verso opposto)
  3. concatena gambaA + [transfer] + gambaB con i vincoli §5
  4. dedup contro i diretti, ranking, taglio
```

**Un solo cambio di rete.** Non si costruiscono catene di tre operatori: feeder
+ long-haul e basta. Tre reti in fila moltiplicano le chiamate e producono quasi
solo mostri.

**La preselezione è locale e istantanea** — coordinate già in memoria — quindi
non costa rete. Le chiamate vere partono solo per gli hub sopravvissuti.

## 5. I vincoli (il cuore del "che abbia senso")

- **cambio minimo**: 15 min stessa stazione; per un interscambio a piedi, i suoi
  minuti dichiarati + un margine.
- **attesa massima al cambio**: 60 min. Oltre, la coincidenza è formalmente
  valida e praticamente inutile: si scarta.
- **durata totale**: se esiste un diretto (o una soluzione a rete singola), un
  misto si tiene solo se non è più lento di ~30 min. Un misto che fa risparmiare
  cinque minuti al prezzo di un cambio non vale.
- **anti-sovrapposizione**: la gamba feeder dev'essere corta rispetto alla
  long-haul. Combinare due AV concorrenti sulla stessa direttrice
  (Trenitalia Roma→Bologna + Italo Bologna→Milano) è quasi sempre assurdo: si
  scarta se entrambe le gambe superano una soglia di durata/distanza.
- **niente doppio interscambio a piedi**: al massimo un transfer pedonale per
  viaggio.

## 6. Le fonti, gamba per gamba, coi loro limiti

| Gamba | Fonte | Limite noto |
|---|---|---|
| feeder RFI | BFF Le Frecce | nessuno di nuovo |
| feeder Trenord | BFF Trenord | area lombarda |
| feeder EAV/FNB | tabellone / orario | fuori-RFI, codici sintetici |
| long-haul Italo | `RicercaTrattaService` | **solo corse monitorate**: su date future può dare poco |
| long-haul Trenitalia AV | BFF Le Frecce | nessuno |

Due limiti da dichiarare nell'UI:

- **Italo su date future è incompleto**: la tratta copre le corse che il loro
  sistema sta seguendo, non un orario pieno. Una ricerca mista per «fra tre
  giorni» può non trovare la gamba Italo pur esistendo il treno.
- **niente prezzo Italo**: un misto con gamba Italo mostra il prezzo solo della
  gamba Trenitalia/Trenord. Va detto, non lasciato intuire.

## 7. Performance e quando lanciarlo

- preselezione hub: locale, < 1 ms.
- chiamate extra: ~2–3 per hub sopravvissuto, tutte parallele. Con 3 hub, ~6–9
  richieste, concorrenti con la ricerca diretta.
- **non sempre**: i misti si calcolano solo quando la ricerca diretta rende
  poco (sotto N soluzioni) **oppure** su tocco esplicito dell'utente. Non devono
  rallentare la ricerca Milano→Roma di chi vuole solo la lista pulita.

## 8. UI e flag beta

- interruttore **«Soluzioni con più operatori (beta)»**, spento di default,
  nelle impostazioni accanto alle sorgenti.
- i viaggi misti compaiono marcati: un badge «beta» o «misto», il transfer a
  piedi reso esplicito con i minuti, e — se c'è una gamba Italo — l'avviso che
  il prezzo non è completo.
- restano **in coda** ai diretti nel ranking a parità di orario: sono un di più,
  non il default.

## 9. Piano di implementazione, a fasi

1. **Modello** — `Leg.source`, `TransportKind.WALK`, il tipo «viaggio misto»,
   la tabella `Interscambi` imbarcata (poche voci, verificate a mano).
   *Nessuna rete, tutto testabile a tavolino.*
2. **Motore di concatenazione** — funzione pura: date due liste di gambe e la
   tabella interscambi, produce i viaggi misti validi applicando i vincoli §5.
   *Pura, offline, coperta da test con dati finti prima ancora di toccare le
   sorgenti.* È il pezzo dove stanno gli errori sottili, e va isolato apposta.
3. **Preselezione hub + cablaggio sorgenti** — coordinate hub imbarcate, il
   filtro geografico, e l'orchestrazione delle chiamate per gamba.
4. **Tempo reale per gamba** — il dettaglio di un viaggio misto segue ogni gamba
   dalla sua fonte; la gamba a piedi non si segue.
5. **UI + flag beta** — l'interruttore, i badge, gli avvisi, il ranking in coda.

L'ordine non è negoziabile sui primi due: il motore (fase 2) va scritto e
verificato **prima** di collegare le sorgenti, perché è lì che un viaggio
"valido" può essere in realtà assurdo, ed è molto più facile vederlo con dati
controllati che con le API vere che cambiano sotto i piedi.

## 10. Cosa può andare storto, e come ce ne accorgiamo

- **coincidenze impossibili** — cambio troppo stretto, treno feeder in ritardo:
  i vincoli §5 sono conservativi apposta, ma vanno testati su casi reali.
- **hub sbagliato** — la preselezione tiene un hub fuori strada: il test
  geografico va verificato su tratte vere (Sorrento→Roma deve proporre Napoli,
  non Salerno).
- **doppioni mascherati** — lo stesso viaggio da fonti diverse con orari a un
  minuto di scarto: il dedup di `merge()` va esteso ai misti.
- **la gamba Italo che sparisce** — su date future: la UI deve degradare a «non
  trovato», non a una lista dimezzata senza spiegazione.

Ogni punto qui sopra è un test da scrivere nella fase corrispondente.

## 11. Stato dell'implementazione

Vivo e coperto da test:

| Pezzo | Dove | Test |
|---|---|---|
| modello (`Leg.source`, `WALK`, `Journey.assembled`) | `domain/model/Models.kt` | via i test motore |
| tabella interscambi | `data/misti/Interscambi.kt` | `MotoreViaggiMistiTest` |
| motore di concatenazione | `data/misti/MotoreViaggiMisti.kt` | `MotoreViaggiMistiTest` (10 casi) |
| preselezione hub | `data/misti/HubAV.kt` | `HubAVTest` |
| feeder EAV dall'orario | `EavRepository.itinerario` | `ViaggiMistiLiveTest` |
| gamba Italo | `ItaloRepository.itinerario` | `ViaggiMistiLiveTest` |
| orchestrazione | `ViaggiMistiRepository` | `ViaggiMistiLiveTest` |
| cablaggio + flag + UI | `ResultsViewModel`, `ResultsScreen`, `SearchScreen`, `SettingsStore` | — |

Misurato: Sorrento→Roma compone 3 soluzioni reali (EAV + a piedi + Italo) in
600 ms – 2 s, dominati dalla latenza Italo. Gira in una coroutine separata, dopo
i diretti: la ricerca normale non rallenta.

**Cosa NON è implementato, di proposito:**

- **feeder RFI + Italo.** Il BFF Le Frecce già connette A→C sulla rete RFI; il
  solo valore aggiunto sarebbe sostituire un Frecciarossa con un Italo senza
  prezzo. Marginale, lasciato fuori.
- **feeder FNB (Bari) + Italo.** Ferrotramviaria non ha un orario imbarcato, solo
  il tabellone in tempo reale, e da quello non si ricava un itinerario A→B. La
  tabella interscambi la voce Bari ce l'ha già; manca solo un
  `FnbRepository.itinerario`, che serve una fonte d'orario che oggi non c'è.
- **catene di tre operatori.** Un solo cambio di rete, per scelta (§4).

Il flag e l'interruttore ci sono comunque: quando FNB avrà un orario, il feeder
si aggiunge senza toccare il resto.
