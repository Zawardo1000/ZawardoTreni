import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Layer dati: Kotlin/JVM puro, deliberatamente senza dipendenze Android.
 *
 * Questo isolamento non e' estetico. Serve a due cose:
 *  - i test di integrazione girano su JVM normale, senza Robolectric
 *    (OkHttp 5 in un unit test Android non riesce a caricare PublicSuffixDatabase);
 *  - il compilatore impedisce che un `android.util.Log` finisca qui dentro.
 */
kotlin {
    jvmToolchain(17)
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(libs.retrofit)
    api(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

/**
 * Il classpath con cui girano i rigeneratori di orario.
 *
 * **Non e' `sourceSets["test"].runtimeClasspath`, ed e' il punto delicato.**
 * Quei task producono `src/main/resources`, quindi `processResources` dipende
 * da loro; se a loro volta dipendessero da `classes` — come fa qualunque
 * classpath che includa `output` per intero, o il sorgente di test — si
 * chiuderebbe un cerchio: `classes` -> `processResources` -> rigenera ->
 * `classes`. Gradle lo rifiuta, e lo rifiuta **solo in release**, cioe' proprio
 * quando serve.
 *
 * Si prendono percio' le sole cartelle delle classi compilate, che dipendono da
 * `compileKotlin` e non dalle risorse, piu' le dipendenze di runtime. Per lo
 * stesso motivo i due strumenti vivono nel sorgente principale e non in quello
 * di test: R8 li toglie dall'APK di release, visto che a runtime nessuno li
 * chiama.
 */
val classpathStrumenti: FileCollection =
    files(sourceSets["main"].output.classesDirs, configurations["runtimeClasspath"])

/**
 * Riscarica il GTFS EAV e riscrive l'orario imbarcato.
 *
 * Usa la stessa classe che gira sul telefono quando l'orario scade. Se il feed
 * e' irraggiungibile il task non fallisce: stampa l'errore e lascia in piedi
 * l'orario precedente, che resta valido per mesi.
 */
val rigeneraOrarioEav = tasks.register<JavaExec>("rigeneraOrarioEav") {
    group = "orari"
    description = "Scarica il GTFS EAV e rigenera data/src/main/resources/eav-orario.gz"
    mainClass.set("it.zawardo.treni.tools.RigeneraOrarioEavKt")
    classpath = classpathStrumenti
    dependsOn(tasks.named("compileKotlin"))
    args(layout.projectDirectory.file("src/main/resources/eav-orario.gz").asFile.absolutePath)
    // Il feed cambia una volta al mese: rieseguirlo a ogni build non servirebbe
    // a niente, ma nemmeno saltarlo per "up-to-date" avrebbe senso, visto che
    // l'input sta su un altro server. Si esegue quando lo si chiede.
    outputs.upToDateWhen { false }
}

/**
 * Lo stesso, per il GTFS ARST.
 *
 * Qui il download e' molto piu' pesante — 19,7 MB contro 3,1 — perche'
 * l'archivio ARST e' per il 98% autolinee, e il ferroviario che ne estraiamo
 * sono venti KB. E' un buon motivo in piu' perche' giri solo in release e mai
 * a ogni compilazione.
 */
val rigeneraOrarioArst = tasks.register<JavaExec>("rigeneraOrarioArst") {
    group = "orari"
    description = "Scarica il GTFS ARST e rigenera data/src/main/resources/arst-orario.gz"
    mainClass.set("it.zawardo.treni.tools.RigeneraOrarioArstKt")
    classpath = classpathStrumenti
    dependsOn(tasks.named("compileKotlin"))
    args(layout.projectDirectory.file("src/main/resources/arst-orario.gz").asFile.absolutePath)
    outputs.upToDateWhen { false }
}

/*
 * Le release partono sempre da orari appena scaricati.
 *
 * Un orario imbarcato invecchia in silenzio: nessuno se ne accorge finche' un
 * utente non cerca un treno di un mese che il feed non copre piu'. Agganciarlo
 * alla release e' l'unico momento garantito in cui qualcuno sta guardando.
 * Le build di debug restano offline, altrimenti si scaricherebbero 23 MB a ogni
 * compilazione.
 */
val staFacendoUnaRelease = gradle.startParameter.taskNames.any {
    it.contains("Release") || it.contains("bundle")
}
tasks.named("processResources") {
    if (staFacendoUnaRelease) {
        dependsOn(rigeneraOrarioEav)
        dependsOn(rigeneraOrarioArst)
    }
}

tasks.withType<Test>().configureEach {
    useJUnit()

    /*
     * Questi test interrogano API reali: l'esito dipende dai treni che circolano
     * in questo momento, non dal codice. Lasciarli cacheare significherebbe
     * vedere "PASSED" restituito dalla cache mentre l'API e' gia' cambiata,
     * cioe' esattamente il caso che devono intercettare.
     */
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
