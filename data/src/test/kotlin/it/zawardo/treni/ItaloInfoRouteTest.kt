package it.zawardo.treni

import it.zawardo.treni.data.mapper.fermateDaInfoRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * Il percorso scritto dentro una riga del tabellone Italo (`InfoRoute`), come
 * arriva davvero: le stringhe sono quelle misurate il 19/09/2026 a Roma Termini.
 *
 * E' l'unico modo di sapere dove va un Italo che il loro servizio non sta
 * seguendo, e sono la maggior parte: `RicercaTrenoService` rispondeva `IsEmpty`
 * su quattro corse in viaggio su quattro.
 */
class ItaloInfoRouteTest {

    @Test
    fun `le fermate di una partenza, in ordine e con l'ora`() {
        val fermate = fermateDaInfoRoute(
            "Roma Tiburtina (18.48) - Firenze Santa Maria Novella (20.17) - Bologna centrale (21.03) " +
                "- Mediopadana R.Emilia (21.28) - Milano Rogoredo (22.09) - Milano Centrale (22.20)",
        )
        assertEquals(
            listOf(
                "Roma Tiburtina" to LocalTime.of(18, 48),
                "Firenze Santa Maria Novella" to LocalTime.of(20, 17),
                "Bologna centrale" to LocalTime.of(21, 3),
                "Mediopadana R.Emilia" to LocalTime.of(21, 28),
                "Milano Rogoredo" to LocalTime.of(22, 9),
                "Milano Centrale" to LocalTime.of(22, 20),
            ),
            fermate,
        )
    }

    /** Un nome col punto e uno col trattino: a delimitare e' la parentesi, non il separatore. */
    @Test
    fun `i nomi con punti e trattini restano interi`() {
        val fermate = fermateDaInfoRoute(
            "Reggio Calabria (13.15) - Villa S.Giovanni (13.34) - Rosarno (14.05) - Napoli (18.20)",
        )
        assertEquals(listOf("Reggio Calabria", "Villa S.Giovanni", "Rosarno", "Napoli"), fermate.map { it.first })
        assertEquals(LocalTime.of(13, 34), fermate[1].second)
    }

    @Test
    fun `l'ora si legge anche coi due punti`() {
        assertEquals(
            listOf("Firenze S.M.N." to LocalTime.of(9, 5)),
            fermateDaInfoRoute("Firenze S.M.N. (9:05)"),
        )
    }

    @Test
    fun `quel che non si riconosce non esce`() {
        assertTrue(fermateDaInfoRoute(null).isEmpty())
        assertTrue(fermateDaInfoRoute("").isEmpty())
        assertTrue(fermateDaInfoRoute("Napoli Centrale").isEmpty())
        // Un'ora impossibile non diventa una fermata.
        assertTrue(fermateDaInfoRoute("Napoli (25.99)").isEmpty())
        // E il resto della riga si legge lo stesso.
        assertEquals(listOf("Salerno"), fermateDaInfoRoute("Napoli (25.99) - Salerno (19.40)").map { it.first })
    }
}
