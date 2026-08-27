# ZawardoTreni

App Android per orari e stato in tempo reale dei treni italiani.

> **App non ufficiale**, non affiliata né approvata da Trenitalia S.p.A. o Ferrovie dello Stato Italiane.
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
| [Le Frecce BFF](https://app.lefrecce.it) | ricerca stazioni e itinerari A→B |
| [ViaggiaTreno](http://www.viaggiatreno.it) | stato realtime: ritardi, fermate, binari, posizione |

Entrambe sono API **non ufficiali e non documentate**, ricostruite dal traffico dei siti
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
- il `searchId` scade dopo circa 10 minuti.

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

## Stack

Kotlin · Jetpack Compose (Material 3) · Retrofit + OkHttp · kotlinx.serialization · Room ·
WorkManager · AGP 9 con Kotlin integrato.
