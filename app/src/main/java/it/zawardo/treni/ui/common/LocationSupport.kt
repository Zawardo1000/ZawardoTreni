package it.zawardo.treni.ui.common

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.ACCESS_FINE_LOCATION,
)

fun hasLocationPermission(context: Context): Boolean =
    LOCATION_PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

/**
 * Posizione corrente.
 *
 * Si usa `getCurrentLocation` e non `lastLocation`: l'ultima nota puo' essere
 * vecchia di ore e di centinaia di chilometri, il che per "la stazione piu'
 * vicina" e' esattamente l'errore peggiore. Precisione bilanciata: per scegliere
 * una stazione bastano poche centinaia di metri, e il GPS fine costa batteria.
 */
@SuppressLint("MissingPermission")
suspend fun currentLocation(context: Context): Location? {
    if (!hasLocationPermission(context)) return null
    val client = LocationServices.getFusedLocationProviderClient(context)
    val cancel = CancellationTokenSource()
    return suspendCancellableCoroutine { cont ->
        client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancel.token)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resume(null) }
        cont.invokeOnCancellation { cancel.cancel() }
    }
}

/**
 * Incapsula "chiedi il permesso se serve, poi fai la cosa".
 * Se l'utente nega, [onResult] riceve false e la schermata decide cosa dire.
 */
@Composable
fun rememberLocationRequester(onResult: (granted: Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants -> onResult(grants.values.any { it }) }

    return remember(context, launcher) {
        {
            if (hasLocationPermission(context)) onResult(true)
            else launcher.launch(LOCATION_PERMISSIONS)
        }
    }
}
