import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

/**
 * Versione derivata da git.
 *
 * Scritta a mano restava "1.0.0" per sempre e non permetteva di capire quale
 * build si avesse in mano: inutile proprio quando serve, cioe' segnalando un
 * problema. Il conteggio dei commit e' monotono crescente, requisito di Play
 * per il versionCode.
 *
 * `providers.exec` e non un ProcessBuilder diretto: quest'ultimo romperebbe la
 * configuration cache. I fallback coprono build da un archivio senza .git.
 */
fun git(vararg args: String, fallback: String): String = runCatching {
    providers.exec { commandLine(*args) }.standardOutput.asText.get().trim()
}.getOrDefault(fallback).ifBlank { fallback }

val gitCommitCount = git("git", "rev-list", "--count", "HEAD", fallback = "1").toIntOrNull() ?: 1
val gitSha = git("git", "rev-parse", "--short", "HEAD", fallback = "sconosciuto")
val gitDate = git(
    "git", "log", "-1", "--format=%cd", "--date=format:%d/%m/%Y",
    fallback = "data sconosciuta",
)

/**
 * Credenziali di firma lette da `keystore.properties`, fuori dal versionamento.
 *
 * Il file puo' mancare: chi clona il repo deve poter compilare la debug senza
 * possedere la chiave. In quel caso la release resta non firmata invece di far
 * fallire l'intera configurazione del progetto.
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "it.zawardo.treni"
    compileSdk = 37

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // v3 abilita la rotazione della chiave: se un domani il keystore
                // va sostituito, si puo' fare senza perdere gli aggiornamenti.
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "it.zawardo.treni"
        minSdk = 26
        targetSdk = 36
        versionCode = gitCommitCount
        versionName = "1.1.$gitCommitCount"

        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "BUILD_DATE", "\"$gitDate\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/**")
    }

    // AGP 9: il supporto Kotlin e' integrato, il blocco kotlin{} sta dentro android{}
    kotlin {
        jvmToolchain(17)
    }
}

ksp {
    // Schema versionato: senza, le migrazioni future si scrivono alla cieca
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    /*
     * Non usiamo i Fragment: li tira dentro play-services-location, che dipende
     * ancora dalla 1.1.0. Google la segnala come obsoleta in fase di
     * pubblicazione, e nessun'altra libreria del grafo chiede una versione piu'
     * alta, quindi Gradle si tiene quella. Il vincolo la rialza senza aggiungere
     * una dipendenza diretta da una libreria che non ci serve.
     */
    constraints {
        implementation(libs.androidx.fragment) {
            because("play-services-location porta la 1.1.0, dichiarata obsoleta")
        }
    }

    implementation(project(":data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.vm)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.androidx.datastore)
    implementation(libs.androidx.work)
    implementation(libs.play.services.location)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
