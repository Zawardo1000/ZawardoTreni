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
