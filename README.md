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

Dettagli completi degli endpoint e delle loro insidie: [`PLAN.md`](PLAN.md).

## Build

Il progetto non richiede Android Studio. La toolchain è portabile e vive in `.tools/`
(esclusa dal versionamento).

```bash
source .tools/env.sh          # JAVA_HOME, ANDROID_HOME, PATH
./gradlew :app:assembleDebug  # APK di debug
./gradlew :app:bundleRelease  # AAB per Play Store
```

Requisiti: JDK 17, Android SDK platform 36, build-tools 36.0.0.

### Test di integrazione

`LiveApiTest` interroga le API reali: è il primo posto da guardare quando l'app smette
di funzionare, perché fallisce se i contratti sono cambiati.

```bash
./gradlew :app:testDebugUnitTest --tests '*LiveApiTest*' -i
```

## Stack

Kotlin · Jetpack Compose (Material 3) · Retrofit + OkHttp · kotlinx.serialization · Room ·
WorkManager · AGP 9 con Kotlin integrato.
