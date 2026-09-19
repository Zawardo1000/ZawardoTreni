package it.zawardo.treni

import it.zawardo.treni.data.mapper.toJourney
import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.remote.trenord.TrenordSolutionDto
import it.zawardo.treni.domain.model.Biglietto
import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.Price
import it.zawardo.treni.domain.model.prezzoTotale
import it.zawardo.treni.domain.model.tratteDaBiglietto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un viaggio che si compra con due biglietti.
 *
 * Varese-Brescia del 19/09/2026 alle 10:10, come la dava Trenord: a piedi fino a
 * Varese Nord, R22 10036 e RE51 2937 fino a Milano Centrale, poi l'EC 301 di
 * Trenitalia. Trenord la dichiara non vendibile, `OTHER_OPERATOR`; ciascuno
 * prezza la sua parte, 6,30 € e 23,50 €.
 */
class BigliettiTest {

    private fun treno(numero: String, categoria: String, operatore: String?, da: Pair<String, String>, a: Pair<String, String>) =
        """
        {"journey_type": "train",
         "train": {"train_id": "$numero", "train_category": "$categoria"${operatore?.let { ", \"train_operator\": \"$it\"" } ?: ""}},
         "pass_list": [
          {"station": {"station_id": "${da.first}", "station_ori_name": "VIA"}, "type": "start", "dep_time": "${da.second}:00", "is_journey": true},
          {"station": {"station_id": "${a.first}", "station_ori_name": "VIA"}, "type": "end", "arr_time": "${a.second}:00", "is_journey": true}]}
        """

    private fun soluzione(vararg tratte: String) = NetworkModule.json.decodeFromString<TrenordSolutionDto>(
        """
        {"date": "20260919", "dep_time": "10:10:00", "arr_time": "12:51:00",
         "saleability": {"status": false, "non_saleability_motivation": "OTHER_OPERATOR"},
         "journey_list": [
          {"journey_type": "walk", "train": {"train_category": "MXP"}, "walk": {"duration": "00:10:00"}},
          ${tratte.joinToString(",")}
         ]}
        """,
    ).toJourney()!!

    private val r22 = treno("10036", "REG", "TRENORD", "S01738" to "10:20", "S01642" to "11:13")
    private val re51 = treno("2937", "RE", "TRENORD\$:\$FNM3", "S01642" to "11:19", "S01700" to "11:37")
    private val ec = treno("301", "EC", "TRENITALIA", "S01700" to "12:05", "S09999" to "12:51")

    @Test
    fun `Varese-Brescia delle 10 e 10 sono due biglietti`() {
        val viaggio = soluzione(r22, re51, ec)
        val tratte = viaggio.tratteDaBiglietto()!!
        assertEquals(
            "sullo stesso biglietto Trenord si cambia fra R22 e RE51; il tratto a piedi non conta",
            listOf(listOf("10036", "2937"), listOf("301")),
            tratte.map { t -> t.map { it.trainNumber } },
        )
        assertEquals(listOf(DataSource.TRENORD, DataSource.TRENITALIA), tratte.map { it.first().venditore })
    }

    @Test
    fun `un venditore solo, un venditore sconosciuto o un prezzo gia' noto non fanno piu' biglietti`() {
        assertNull("tutto Trenord e' un biglietto", soluzione(r22, re51).tratteDaBiglietto())
        assertNull(
            "di un treno non si sa chi lo venda: non si prezza a pezzi",
            soluzione(r22, treno("301", "EC", null, "S01700" to "12:05", "S09999" to "12:51")).tratteDaBiglietto(),
        )
        assertNull(soluzione(r22, re51, ec).copy(price = Price("30.00")).tratteDaBiglietto())
    }

    @Test
    fun `il prezzo di due biglietti e' la somma`() {
        val due = listOf(Biglietto(DataSource.TRENORD, Price("6.30")), Biglietto(DataSource.TRENITALIA, Price("23.50")))
        val totale = due.prezzoTotale()!!
        assertEquals("29.80", totale.amount)
        assertTrue(totale.saleable)

        val esaurito = listOf(due[0], Biglietto(DataSource.TRENITALIA, Price("23.50", saleable = false, esaurito = true)))
        assertFalse(esaurito.prezzoTotale()!!.saleable)
        assertTrue(esaurito.prezzoTotale()!!.esaurito)

        assertNull("senza tutti i prezzi, nessun totale", listOf(Biglietto(DataSource.TRENORD, Price("n/d"))).prezzoTotale())
    }
}
