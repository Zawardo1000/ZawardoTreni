package it.zawardo.treni

import it.zawardo.treni.data.mapper.toJourney
import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.remote.trenord.TrenordSearchDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quale prezzo si mostra, fra i sei che Trenord manda.
 *
 * Segnalazione con lo screenshot davanti: Calolziocorte-Olginate - Milano
 * Centrale costava 2,60 euro sulla soluzione delle 08:46 e 5,20 su quelle delle
 * 09:16 e 09:46. Nessuna delle due cifre era sbagliata in se': erano prezzi di
 * biglietti diversi. Trenord risponde solo alle prime cinque soluzioni della
 * finestra, e da li' in poi i prezzi arrivano da Le Frecce, che pubblica un
 * totale unico — quello intero. La stessa tratta cambiava prezzo a meta' lista.
 *
 * Il payload qui sotto e' quello vero del 31/08/2026, ridotto alle parti che
 * contano: `ordinary` non e' un prezzo solo ma sei, tre tariffe per due classi,
 * e prendere il minimo significava vendere a tutti il ridotto per ragazzi.
 *
 * Il JSON serve anche a inchiodare i nomi dei campi: `tariff_type`, `class` e
 * `status` non sono deducibili dal modello, e sbagliarne uno non rompe niente —
 * fa solo tornare un prezzo diverso.
 */
class PrezzoTrenordTest {

    private fun prodotto(tariffa: String, classe: String, prezzo: Double) = """
        {"name":"Biglietto ferroviario","type":"ordinary","category":"tur",
         "tariff_type":"$tariffa","class":"$classe","price":$prezzo,
         "localized_name":"Corsa Singola"}
    """.trimIndent()

    private val tariffeIntere = listOf(
        prodotto("ragazzo", "2", 2.6),
        prodotto("ragazzo", "1", 4.0),
        prodotto("anziano", "2", 4.2),
        prodotto("adulto", "2", 5.2),
        prodotto("anziano", "1", 6.3),
        prodotto("adulto", "1", 7.9),
    )

    private fun payload(
        prodotti: List<String>,
        vendibile: Boolean = true,
        tratte: Int = 1,
    ): String {
        val routes = (1..tratte).joinToString(",") { i ->
            """{"route_index":$i,"products":[${prodotti.joinToString(",")}]}"""
        }
        return """
        {"solutions":[{
          "date":"20260831","dep_time":"08:46:00","arr_time":"09:40:00",
          "duration":"00:54:00","change":"1",
          "dep_station":{"station_id":"S01524","station_ori_name":"CALOLZIOCORTE OLGINATE"},
          "arr_station":{"station_id":"S01700","station_ori_name":"MILANO CENTRALE"},
          "journey_list":[{
            "train":{"train_id":"24831","train_category":"S","line":"S_8"},
            "pass_list":[
              {"station":{"station_id":"S01524","station_ori_name":"CALOLZIOCORTE OLGINATE"},
               "dep_time":"08:46:00","type":"start"},
              {"station":{"station_id":"S01700","station_ori_name":"MILANO CENTRALE"},
               "arr_time":"09:40:00","type":"end"}
            ]}],
          "ticket_routes":[$routes],
          "saleability":{"status":$vendibile}
        }]}
        """.trimIndent()
    }

    private fun viaggio(json: String) =
        NetworkModule.json.decodeFromString<TrenordSearchDto>(json)
            .solutions.first().toJourney()

    @Test
    fun `fra le sei corse singole si mostra l'adulto in seconda classe`() {
        val j = viaggio(payload(tariffeIntere))
        assertTrue("la soluzione non si e' costruita", j != null)
        assertEquals(
            "2,60 e' il biglietto ragazzo: il prezzo della tratta e' 5,20",
            "5.20",
            j!!.price?.amount,
        )
        assertEquals("5,20 €", j.price?.formatted)
    }

    @Test
    fun `il giornaliero resta fuori anche se e' l'unico titolo per adulti`() {
        // Sulla stessa tratta il titolo a tempo costa il triplo e vale un
        // giorno intero: accanto a una corsa singola non e' un prezzo, e senza
        // ordinari non c'e' niente da mostrare.
        val giornaliero = """
            {"name":"Giornaliero","type":"daily","tariff_type":"adulto",
             "class":"2","price":15.6}
        """.trimIndent()
        assertNull(viaggio(payload(listOf(giornaliero)))?.price)
    }

    @Test
    fun `senza una tariffa intera non si mostra un prezzo`() {
        // Il giorno in cui Trenord rinominasse `adulto` finiremmo qui: meglio
        // nessun prezzo — e i test live rossi — che il ridotto spacciato per
        // intero.
        val soloRidotti = tariffeIntere.filterNot { it.contains("\"adulto\"") }
        assertNull(
            "restano solo ragazzo e anziano: non e' il prezzo che paga chi cerca",
            viaggio(payload(soloRidotti))?.price,
        )
    }

    @Test
    fun `il biglietto a zone STIBM e' gia' a tariffa piena`() {
        /*
         * L'altra famiglia tariffaria: dentro l'area milanese Trenord non vende
         * una corsa singola per classe ed eta' ma un titolo a zone, un solo
         * `ordinary` con `tariff_type: standard` e nessuna classe. Milano Dateo
         * - Vignate sono 3,00 euro per chiunque, e scartare `standard` insieme
         * ai ridotti toglieva il prezzo a tutto il Passante.
         */
        val stibm = listOf(
            """{"name":"Biglietto","type":"ordinary","tariff_type":"standard",
                "price":3.0,"localized_name":"Ordinario - STIBM"}""".trimIndent(),
            """{"name":"Giornaliero","type":"daily","tariff_type":"standard",
                "price":10.5,"localized_name":"Giornaliero - STIBM"}""".trimIndent(),
        )
        assertEquals("3.00", viaggio(payload(stibm))?.price?.amount)
    }

    @Test
    fun `senza dichiarazione di tariffa si prende comunque la seconda classe`() {
        // Non tutte le tratte popolano `tariff_type`. Il campo assente non e'
        // uno sconto: quei titoli restano, e fra prima e seconda vince la
        // seconda, che e' il prezzo di riferimento.
        val senzaTariffa = listOf(
            """{"name":"Biglietto","type":"ordinary","class":"1","price":7.9}""",
            """{"name":"Biglietto","type":"ordinary","class":"2","price":5.2}""",
        )
        assertEquals("5.20", viaggio(payload(senzaTariffa))?.price?.amount)
    }

    @Test
    fun `due tratte da pagare fanno un prezzo solo, che e' la somma`() {
        val j = viaggio(payload(tariffeIntere, tratte = 2))
        assertEquals("due biglietti si pagano entrambi", "10.40", j?.price?.amount)
    }

    @Test
    fun `una soluzione che Trenord non vende lo dichiara`() {
        // `saleability.status`, non `saleable`: col nome sbagliato il campo
        // restava null e ogni prezzo passava per acquistabile.
        val j = viaggio(payload(tariffeIntere, vendibile = false))
        assertEquals("il prezzo resta, e serve a confrontare", "5.20", j?.price?.amount)
        assertTrue("non vendibile va detto", j?.price?.saleable == false)
    }
}
