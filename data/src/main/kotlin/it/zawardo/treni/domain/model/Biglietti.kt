package it.zawardo.treni.domain.model

import java.math.BigDecimal
import java.util.Locale

/** Un biglietto di un viaggio che ne richiede piu' d'uno: chi lo vende, e quanto costa. */
data class Biglietto(
    val venditore: DataSource,
    val prezzo: Price,
)

/**
 * Le tratte di un viaggio da comprare con biglietti separati, una per
 * biglietto; null se il viaggio si compra con uno solo, o se non si sa chi
 * venda una delle sue tratte.
 *
 * Il caso da cui nasce: Varese-Brescia del 19/09/2026 alle 10:10, R22 10036 e
 * RE51 2937 fino a Milano Centrale, poi l'EC 301. Trenord la propone ma la
 * dichiara non vendibile, `OTHER_OPERATOR`: l'EuroCity e' di Trenitalia. E
 * Trenitalia quella combinazione non la conosce, perche' passa da Varese Nord.
 * Nessuno la prezza intera; ciascuno prezza la sua parte — 6,30 € Trenord,
 * 23,50 € Trenitalia — e la somma e' quel che costa davvero, in due biglietti.
 *
 * Una tratta e' una fila di treni dello stesso venditore: sullo stesso
 * biglietto Trenord si cambia fra R22 e RE51. Il tratto a piedi non si compra e
 * non spezza la fila. Solo per le soluzioni Trenord senza prezzo: le altre o un
 * prezzo ce l'hanno, o non dicono chi venda cosa.
 */
fun Journey.tratteDaBiglietto(): List<List<Leg>>? {
    if (source != JourneySource.TRENORD || price != null) return null
    val treni = legs.filter { it.isTrain }
    if (treni.isEmpty() || treni.any { it.venditore == null }) return null
    val tratte = mutableListOf<MutableList<Leg>>()
    for (treno in treni) {
        val ultima = tratte.lastOrNull()
        if (ultima != null && ultima.last().venditore == treno.venditore) ultima += treno
        else tratte += mutableListOf(treno)
    }
    return tratte.takeIf { it.size > 1 }
}

/**
 * Il prezzo di piu' biglietti: la somma, vendibile se lo sono tutti, esaurita
 * se lo e' uno. Null se non si sa il prezzo di tutti: mezzo prezzo, senza dire
 * quale meta', sarebbe peggio di nessuno.
 */
fun List<Biglietto>.prezzoTotale(): Price? {
    if (isEmpty()) return null
    val cifre = map { it.prezzo.amount.toBigDecimalOrNull() ?: return null }
    return Price(
        amount = String.format(Locale.US, "%.2f", cifre.fold(BigDecimal.ZERO, BigDecimal::add)),
        currency = first().prezzo.currency,
        saleable = all { it.prezzo.saleable },
        esaurito = any { it.prezzo.esaurito },
    )
}
