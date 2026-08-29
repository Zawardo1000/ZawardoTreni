# ZawardoTreni

App Android per orari e stato in tempo reale dei treni italiani.

> **App non ufficiale**, non affiliata né approvata da Trenitalia S.p.A., Ferrovie dello
> Stato Italiane, Trenord S.r.l. o Italo – Nuovo Trasporto Viaggiatori S.p.A.
> Nessuna vendita di biglietti.

## Cosa fa

- Ricerca A→B con autocompletamento stazioni, data e ora (default: adesso)
- Salvataggio ricerche + cronologia delle ultime 10
- Risultati con numero treno, stato (soppresso, variato, in orario) e ritardo/anticipo
- Dettaglio corsa: elenco fermate, posizione attuale del treno, ritardi **effettivi** sulle
  fermate già effettuate e **stimati** su quelle future, binario programmato → effettivo
- Ricerca per numero treno
- Tabellone partenze/arrivi di stazione
- Stazione più vicina via GPS
- Prezzo del biglietto, dove Trenitalia e Trenord lo pubblicano
- Reti accendibili e spegnibili una per una, per non pagare chiamate che non servono
- Viaggi con più operatori (beta): combina reti che nessuno collega, es.
  Circumvesuviana + Italo per Sorrento → Roma. Vedi [`data/VIAGGI-MISTI.md`](data/VIAGGI-MISTI.md)
- "Segui treno": notifica quando il ritardo cambia di oltre 3 minuti

## Fonti dati

Otto servizi, sette reti. Nessuno di questi è documentato, e nessuno è ufficiale.

| Fonte | Uso | Tempo reale |
|---|---|---|
| [Le Frecce BFF](https://app.lefrecce.it) | ricerca stazioni, itinerari A→B, **prezzi** | — |
| [ViaggiaTreno](http://www.viaggiatreno.it) | ritardi, fermate, binari, posizione del treno | sì |
| [Trenord](https://www.trenord.it) | regionale lombardo, linee S del Passante, avvisi, **prezzi** | sì |
| [Italo in viaggio](https://italoinviaggio.italotreno.com) | le corse NTV, assenti da ogni altra fonte | sì |
| [EAV](https://orariotreni.eavsrl.it) | Circumvesuviana, Cumana, Circumflegrea | sì, dove c'è il monitor |
| [GTFS EAV](https://www.eavsrl.it/open-data/) | orario EAV: giorni futuri e linee senza monitor | no |
| [Ferrotramviaria](https://eticket.ferrovienordbarese.it) | Bari–Barletta e aeroporto di Bari | sì |
| [Orario svizzero](https://transport.opendata.ch) | Vigezzina–Centovalli e linee S del Ticino | sì |
| [GTFS ARST](https://www.arstspa.info) | ferrovie sarde a scartamento ridotto | no |

Sono API **non ufficiali e non documentate**, ricostruite dal traffico dei siti in
produzione: possono cambiare o smettere di funzionare senza preavviso. Fanno eccezione i
due GTFS, che sono open data pubblicati apposta, in Italian Open Data Licence.

**Le corse senza tempo reale sono dichiarate come tali.** Dove l'orario è l'unica fonte —
tutta ARST, EAV oltre oggi — la riga non porta un ritardo pari a zero: porta l'indicazione
che il ritardo *non si conosce*, che è un'informazione diversa. Il modello lo distingue con
`BoardEntry.realtime`.

**Sulla rete nazionale il realtime esiste solo per la giornata corrente**: `andamentoTreno`
risponde `204` per qualsiasi altra data. Per le date future l'app mostra il solo orario previsto.

Le insidie di queste API — e ce ne sono parecchie — stanno nei commenti del modulo
`:data`, accanto al codice che le aggira.

### Limiti dei dati

Nessuna fonte è completa, e l'app lo dichiara invece di far finta.

- **Italo**: solo la giornata corrente, e senza prezzo. L'orario dei giorni futuri e
  le tariffe, chi possiede quei dati sceglie di non renderli disponibili apertamente
  a un'app come questa: è una sua decisione legittima, e la si rispetta — non la si
  aggira. Per la coincidenza veloce sulle date future c'è la Freccia, che l'orario
  completo lo pubblica.
- **Prezzi**: presenti per Trenitalia (Le Frecce) e Trenord, non per Italo — stesso
  motivo. Su un viaggio con cambio è il prezzo dell'intera soluzione, dove il gestore
  lo espone, e mai della sola gamba Italo.
- **ARST**: solo orario previsto, mai il ritardo o il binario — quelle ferrovie un
  tempo reale non lo pubblicano. In cambio è l'unica che risponda per i giorni futuri.
- **Orari imbarcati (EAV, ARST)**: una fotografia, rinfrescata a soglie di qualche
  mese; un cambio d'orario appena entrato in vigore può non esserci ancora.

## Build

Il progetto non richiede Android Studio. La toolchain è portabile e vive in `.tools/`
(esclusa dal versionamento).

```bash
source .tools/env.sh            # JAVA_HOME, ANDROID_HOME, PATH
./gradlew :app:assembleDebug    # APK di debug, non minificato
./gradlew :app:assembleRelease  # APK firmato e minificato (~2,4 MB)
./gradlew :app:bundleRelease    # AAB per Play Store
```

Requisiti: JDK 17, Android SDK platform 37, build-tools 36.0.0.

La versione e' derivata da git: `versionCode` e' il numero di commit, `versionName`
e' `1.1.<commit>`, e nella schermata Info compare lo sha esatto della build.

### Firma di release

Le credenziali stanno in `keystore.properties`, **non versionato**. Senza quel file
la debug compila lo stesso e la release resta non firmata, cosi' chi clona il
repository puo' lavorare senza possedere la chiave.

R8 e' attivo in release: le regole in `app/proguard-rules.pro` proteggono i
serializer di `kotlinx.serialization` (DTO e rotte di navigazione), le interfacce
Retrofit e le entita' Room. Senza quelle regole l'app si rompe **solo** in release,
a runtime.

### Test di integrazione

`LiveApiTest` interroga le API reali: è il primo posto da guardare quando l'app smette
di funzionare, perché fallisce se i contratti sono cambiati.

```bash
./gradlew :app:testDebugUnitTest --tests '*LiveApiTest*' -i
```

## Privacy

L'app non ha account, non assegna identificativi, non contiene pubblicità né
analitiche, e non esiste alcun server dell'autore. Quello che salvi resta sul
telefono; quello che esce sono le domande sugli orari, rivolte direttamente ai
gestori ferroviari.

Il testo completo è in [`docs/privacy.html`](docs/privacy.html), pubblicato su
<https://zawardo1000.github.io/ZawardoTreni/privacy.html>. Descrive il
comportamento reale del codice: se cambia ciò che l'app invia, o a chi, va
aggiornato insieme al codice.

## Licenza

[GNU GPL v3](LICENSE) o successive — Copyright © 2026 Zawardo.

Il codice e' aperto e va usato, studiato e modificato. Chi lo ridistribuisce, in
qualunque forma, deve distribuire anche il sorgente completo dell'opera derivata
con questa stessa licenza: **non se ne possono fare versioni chiuse**. Ai sensi
della sezione 7 della GPL, ogni copia o derivato deve inoltre indicare in modo
visibile — documentazione, note di licenza e schermata Info — di essere basato
su **ZawardoTreni di Zawardo**, e non puo' spacciarsi per l'originale.

Le dipendenze (Retrofit, OkHttp, Compose, kotlinx) sono Apache-2.0, compatibile
in questa direzione.

## Stack

Kotlin · Jetpack Compose (Material 3) · Retrofit + OkHttp · kotlinx.serialization · Room ·
WorkManager · AGP 9 con Kotlin integrato.
