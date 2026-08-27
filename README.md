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
- "Segui treno": notifica quando il ritardo cambia di oltre 3 minuti

## Fonti dati

| Fonte | Uso |
|---|---|
| [Le Frecce BFF](https://app.lefrecce.it) | ricerca stazioni e itinerari A→B sulla rete nazionale |
| [ViaggiaTreno](http://www.viaggiatreno.it) | stato realtime: ritardi, fermate, binari, posizione |
| [Trenord](https://www.trenord.it) | regionale e suburbano lombardo, linee S del Passante, avvisi di servizio |
| [Italo in viaggio](https://italoinviaggio.italotreno.com) | le corse Italo, che nessun'altra fonte pubblica: tabellone di stazione con ritardo e binario |

Sono tutte API **non ufficiali e non documentate**, ricostruite dal traffico dei siti
in produzione. Possono cambiare o smettere di funzionare senza preavviso.

**Il realtime esiste solo per la giornata corrente**: `andamentoTreno` risponde `204` per
qualsiasi altra data. Per le date future l'app mostra il solo orario previsto.

Le insidie note di queste API sono documentate nei commenti del modulo `:data`,
accanto al codice che le aggira. Le principali:

- il BFF **pretende l'offset di fuso** nella `departure_time`: senza, ignora l'ora
  e riparte da mezzanotte;
- `andamentoTreno` **non proietta il ritardo** sulle fermate future, che restano a
  zero anche su un treno a +8: il ricalcolo lo fa l'app;
- gli endpoint di dettaglio del BFF rispondono `410` e non vanno usati;
- il `searchId` scade dopo circa 10 minuti;
- **di Italo ViaggiaTreno non sa nulla**: non una riga nei tabelloni, e
  `cercaNumeroTreno` sui suoi numeri non trova niente. Senza la fonte NTV, meta'
  dell'alta velocita' per l'app non circola;
- di Italo servono **tre** endpoint, e fanno cose diverse:
  `RicercaStazioneService` e' il tabellone di stazione ed e' sempre vivo;
  `RicercaTrattaService` da' le corse fra due stazioni **col percorso completo**
  ed e' l'unico modo di avere le fermate di un Italo; `RicercaTrenoService`,
  quello per numero, risponde `IsEmpty` quasi sempre — verificato sui cinque
  Italo in viaggio verso Napoli e su uno con quindici minuti di ritardo;
- il servizio "in viaggio" di Italo puo' **congelarsi**: il 27 agosto 2026 alle
  21:00 rispondeva ancora con la fotografia delle 08:11, e il loro stesso sito
  diceva "informazioni non disponibili" per un treno in corsa. Per questo il
  tabellone e' la fonte primaria, il percorso un di piu', e l'ora
  dell'aggiornamento viene sempre dichiarata a chi guarda;
- Italo usa sigle stazione proprie (`RMT`, `MC_`) e nessun endpoint le traduce
  in codici RFI: la tabella e' in `ItaloStations`, 64 voci prese dal loro
  catalogo (`/api/getStations`) e verificate una a una su `autocompletaStazione`.

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
