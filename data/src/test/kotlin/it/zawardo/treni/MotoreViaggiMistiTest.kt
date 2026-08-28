package it.zawardo.treni

import it.zawardo.treni.data.misti.MotoreViaggiMisti
import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.Journey
import it.zawardo.treni.domain.model.Leg
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.TransportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime

/**
 * Il motore dei viaggi misti, con dati costruiti a mano.
 *
 * Non tocca la rete: prova la logica di concatenazione dove ogni caso limite —
 * cambio troppo stretto, attesa lunga, hub sbagliato, due AV in fila — si
 * costruisce apposta e si vede a colpo d'occhio. E' il collaudo del pezzo prima
 * di collegarci le API.
 */
class MotoreViaggiMistiTest {

    private val giorno = LocalDateTime.of(2026, 9, 1, 0, 0)

    private fun st(rfi: String, nome: String) = Station(rfiCode = rfi, locationId = 0, name = nome)

    /** Una gamba-treno da hh:mm a hh:mm fra due codici. */
    private fun leg(
        from: Station, to: Station, dep: String, arr: String,
        num: String, src: DataSource, cat: String? = null,
    ) = Leg(
        trainNumber = num,
        category = cat,
        from = from,
        to = to,
        departure = ora(dep),
        arrival = ora(arr),
        kind = TransportKind.TRAIN,
        source = src,
    )

    private fun ora(hhmm: String): LocalDateTime {
        val (h, m) = hhmm.split(":").map { it.toInt() }
        return giorno.withHour(h).withMinute(m)
    }

    private fun viaggio(vararg legs: Leg): Journey {
        val d = legs.first().departure
        val a = legs.last().arrival
        return Journey(departure = d, arrival = a, duration = Duration.between(d, a), legs = legs.toList())
    }

    // Stazioni ricorrenti
    private val sorrento = st("EAV62", "Sorrento")
    private val garibaldi = st("EAV3", "Napoli P. Garibaldi")
    private val napoliC = st("S09218", "Napoli Centrale")
    private val roma = st("S08409", "Roma Termini")
    private val milano = st("S01700", "Milano Centrale")
    private val bologna = st("S05043", "Bologna Centrale")

    // --------------------------------------------------------- casi felici

    @Test
    fun `Sorrento-Roma si compone via Napoli con trasferimento a piedi`() {
        // EAV Sorrento -> Garibaldi, poi a piedi a Centrale, poi Italo -> Roma
        val eav = viaggio(leg(sorrento, garibaldi, "08:00", "09:10", "EAV-A", DataSource.EAV))
        val italo = viaggio(leg(napoliC, roma, "09:40", "10:50", "9910", DataSource.ITALO))

        val out = MotoreViaggiMisti.assembla(prime = listOf(eav), seconde = listOf(italo))

        println("\n=== SORRENTO -> ROMA ===")
        out.forEach { j ->
            println("  ${j.departure.toLocalTime()} -> ${j.arrival.toLocalTime()}  " +
                j.legs.joinToString(" + ") { it.label + (it.source?.let { s -> "[$s]" } ?: "") })
        }

        assertEquals("dovrebbe esserci un solo misto", 1, out.size)
        val j = out.first()
        assertTrue("il viaggio deve risultare assemblato", j.assembled)
        assertTrue("deve attraversare due operatori", j.multiOperator)
        assertEquals("tre gambe: EAV, a piedi, Italo", 3, j.legs.size)
        assertEquals("la gamba di mezzo e' a piedi", TransportKind.WALK, j.legs[1].kind)
        assertTrue("il misto non porta prezzo", j.price == null)
    }

    @Test
    fun `feeder piu Italo nella stessa stazione, senza trasferimento`() {
        // Regionale Bologna -> Milano, poi Italo Milano -> (altrove): stessa stazione S01700
        val reg = viaggio(leg(bologna, milano, "08:00", "09:05", "RV-1", DataSource.TRENITALIA))
        val italo = viaggio(leg(milano, roma, "09:25", "12:20", "9980", DataSource.ITALO))

        val out = MotoreViaggiMisti.assembla(prime = listOf(reg), seconde = listOf(italo))
        assertEquals(1, out.size)
        assertEquals("nessuna gamba a piedi: stessi binari", 2, out.first().legs.size)
        assertTrue(out.first().legs.none { it.kind == TransportKind.WALK })
    }

    // ----------------------------------------------------- vincoli temporali

    @Test
    fun `un cambio troppo stretto viene scartato`() {
        // Arrivo 09:10, Italo parte 09:13: tre minuti, sotto gli 8+5 del trasferimento
        val eav = viaggio(leg(sorrento, garibaldi, "08:00", "09:10", "EAV-A", DataSource.EAV))
        val italo = viaggio(leg(napoliC, roma, "09:13", "10:20", "9910", DataSource.ITALO))
        val out = MotoreViaggiMisti.assembla(prime = listOf(eav), seconde = listOf(italo))
        assertTrue("un cambio impossibile non va proposto", out.isEmpty())
    }

    @Test
    fun `un'attesa troppo lunga viene scartata`() {
        // Arrivo 09:10, Italo parte 11:00: quasi due ore di attesa, oltre i 60 min
        val eav = viaggio(leg(sorrento, garibaldi, "08:00", "09:10", "EAV-A", DataSource.EAV))
        val italo = viaggio(leg(napoliC, roma, "11:00", "12:10", "9910", DataSource.ITALO))
        val out = MotoreViaggiMisti.assembla(prime = listOf(eav), seconde = listOf(italo))
        assertTrue("due ore di attesa non sono una coincidenza utile", out.isEmpty())
    }

    // -------------------------------------------------------- anti-assurdo

    @Test
    fun `due alta velocita in fila sulla stessa direttrice si scartano`() {
        // Trenitalia Roma->Bologna (lunga) + Italo Bologna->Milano (lunga): entrambe lunghe
        val tr = viaggio(leg(roma, bologna, "08:00", "10:00", "FR-1", DataSource.TRENITALIA, cat = "FR"))
        val italo = viaggio(leg(bologna, milano, "10:20", "11:30", "9990", DataSource.ITALO))
        val out = MotoreViaggiMisti.assembla(prime = listOf(tr), seconde = listOf(italo))
        assertTrue("due AV concorrenti in fila non hanno senso", out.isEmpty())
    }

    @Test
    fun `un misto troppo piu lento del diretto migliore si scarta`() {
        val eav = viaggio(leg(sorrento, garibaldi, "08:00", "09:10", "EAV-A", DataSource.EAV))
        val italo = viaggio(leg(napoliC, roma, "09:40", "10:50", "9910", DataSource.ITALO))
        // Un diretto ipotetico che fa Sorrento->Roma in molto meno
        val diretto = Duration.ofMinutes(90)
        val out = MotoreViaggiMisti.assembla(
            prime = listOf(eav), seconde = listOf(italo), direttoMigliore = diretto,
        )
        // Il misto dura 2h50: oltre i 90+30 del limite
        assertTrue("un misto molto piu' lento del diretto non serve", out.isEmpty())
    }

    // ------------------------------------------------------- nodi sbagliati

    @Test
    fun `una seconda meta che parte da un nodo scollegato non si aggancia`() {
        // Feeder arriva a Garibaldi (Napoli), ma l'Italo parte da Milano: nessun cambio
        val eav = viaggio(leg(sorrento, garibaldi, "08:00", "09:10", "EAV-A", DataSource.EAV))
        val italo = viaggio(leg(milano, roma, "09:40", "12:35", "9910", DataSource.ITALO))
        val out = MotoreViaggiMisti.assembla(prime = listOf(eav), seconde = listOf(italo))
        assertTrue("stazioni lontane non si concatenano", out.isEmpty())
    }

    @Test
    fun `fra piu coincidenze tiene la piu veloce e ordina`() {
        val eav = viaggio(leg(sorrento, garibaldi, "08:00", "09:10", "EAV-A", DataSource.EAV))
        val italoPresto = viaggio(leg(napoliC, roma, "09:40", "10:50", "9910", DataSource.ITALO))
        val italoTardi = viaggio(leg(napoliC, roma, "10:05", "11:30", "9912", DataSource.ITALO))
        val out = MotoreViaggiMisti.assembla(
            prime = listOf(eav), seconde = listOf(italoPresto, italoTardi),
        )
        assertEquals("entrambe valide", 2, out.size)
        assertTrue(
            "la prima in lista deve essere la piu' breve",
            out[0].duration <= out[1].duration,
        )
    }

    @Test
    fun `senza input non inventa niente`() {
        assertTrue(MotoreViaggiMisti.assembla(emptyList(), emptyList()).isEmpty())
        val eav = viaggio(leg(sorrento, garibaldi, "08:00", "09:10", "EAV-A", DataSource.EAV))
        assertTrue(MotoreViaggiMisti.assembla(listOf(eav), emptyList()).isEmpty())
    }
}
