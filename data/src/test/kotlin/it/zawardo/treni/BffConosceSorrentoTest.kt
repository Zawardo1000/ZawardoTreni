package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.StationRepository
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Il BFF conosce Sorrento/Napoli-EAV? Con che codice? */
class BffConosceSorrentoTest {
    private val bff = StationRepository(NetworkModule.lefrecceApi)

    @Test
    fun `cosa da' il BFF per le stazioni fuori-RFI`() = runBlocking {
        for (q in listOf("Sorrento", "Pompei", "Ercolano", "Bari Aeroporto", "Mandas")) {
            val res = bff.search(q)
            println("\n=== BFF '$q': ${res.size} ===")
            res.take(4).forEach {
                println("  ${it.name}  rfi=${it.rfiCode}  id=${it.locationId}  lat=${it.latitude}")
            }
        }
    }
}
