package it.zawardo.treni

import java.time.LocalTime
import java.time.ZoneId

/**
 * Di notte i tabelloni sono vuoti perche' non circola niente, e un test dal vivo
 * non puo' distinguerlo da un servizio che ha smesso di rispondere.
 *
 * Le sorgenti regionali chiudono: alle 00:45 del 20/09/2026 Ferrotramviaria
 * rispondeva `{"arrivi":[],"partenze":[]}` — anche interrogata a mano, fuori
 * dall'app — e l'orario di stazione di Trenord tornava senza righe. Sono i due
 * casi in cui un test rosso non dice niente di vero.
 *
 * Quindi: **il vuoto si accetta solo in queste ore**. Nelle altre resta un
 * fallimento, che e' il motivo per cui quei test esistono.
 */
internal fun notteFonda(): Boolean {
    val ora = LocalTime.now(ZoneId.of("Europe/Rome"))
    return ora.hour < PRIMA_CORSA || ora.hour >= ULTIMA_CORSA
}

/** Prima di quest'ora il servizio regionale non e' ancora cominciato. */
private const val PRIMA_CORSA = 5

/** E da quest'ora in poi e' finito quasi ovunque. */
private const val ULTIMA_CORSA = 24
