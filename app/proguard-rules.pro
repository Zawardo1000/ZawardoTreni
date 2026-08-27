# =====================================================================
# kotlinx.serialization
#
# R8 rinomina le classi e cancella i serializer generati, e il guasto si
# manifesta SOLO in release: i DTO tornano vuoti e le rotte di navigazione
# smettono di risolversi. Le regole coprono l'intero package dell'app perche'
# @Serializable e' usato sia sui DTO di rete sia sulle rotte in ui/.
# =====================================================================
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, AnnotationDefault
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Serializer generati per le classi dell'app (DTO di rete e rotte di navigazione)
-keep,includedescriptorclasses class it.zawardo.treni.**$$serializer { *; }
-keepclassmembers class it.zawardo.treni.** {
    *** Companion;
}
-keepclasseswithmembers class it.zawardo.treni.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# I DTO sono popolati per riflessione dai nomi dei campi: rinominarli li svuota.
-keep class it.zawardo.treni.data.remote.**Dto { *; }
-keep class it.zawardo.treni.data.remote.lefrecce.** { *; }
-keep class it.zawardo.treni.data.remote.viaggiatreno.** { *; }

# =====================================================================
# Navigation Compose type-safe
# Le rotte sono @Serializable e vengono risolte per nome qualificato.
# =====================================================================
-keep class it.zawardo.treni.ui.SearchRoute { *; }
-keep class it.zawardo.treni.ui.TrainSearchRoute { *; }
-keep class it.zawardo.treni.ui.BoardRoute { *; }
-keep class it.zawardo.treni.ui.AboutRoute { *; }
-keep class it.zawardo.treni.ui.ResultsRoute { *; }
-keep class it.zawardo.treni.ui.TrainRoute { *; }

# =====================================================================
# Retrofit
# =====================================================================
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-if interface * { @retrofit2.http.* public *** *(...); }
-keep,allowoptimization,allowshrinking,allowobfuscation class <1>

# Le interfacce API sono implementate a runtime da un proxy dinamico.
-keep interface it.zawardo.treni.data.remote.**Api { *; }

# =====================================================================
# OkHttp
# =====================================================================
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# =====================================================================
# Room: le entita' sono mappate sui nomi delle colonne
# =====================================================================
-keep class it.zawardo.treni.data.local.** { *; }
