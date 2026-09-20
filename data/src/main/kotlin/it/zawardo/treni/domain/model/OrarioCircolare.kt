package it.zawardo.treni.domain.model

import java.time.Duration
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/*
 * L'orologio gira: fra le 23:55 e le 00:05 ci sono dieci minuti, non ventitre
 * ore e cinquanta.
 *
 * Questa regola serviva in quattro punti del dominio e stava scritta quattro
 * volte, due delle quali identiche riga per riga con i nomi tradotti
 * (`HALF_DAY`/`DAY` di qua, `MEZZA_GIORNATA`/`GIORNATA` di la'). Il giorno in
 * cui una delle copie fosse cambiata — una tolleranza diversa, un segno
 * invertito — le altre avrebbero continuato a rispondere come prima, e a
 * divergere in silenzio su un treno notturno.
 *
 * Qui non si sceglie una convenzione nuova: e' esattamente quel che facevano le
 * copie, in un posto solo.
 */

/** Mezza giornata in minuti: oltre questo scarto e' passata la mezzanotte. */
private const val MEZZA_GIORNATA_MIN = 720
private const val GIORNATA_MIN = 1440

/** Un giorno in secondi, per le distanze fra due ore del giorno. */
private const val GIORNATA_SEC = 86_400L

/**
 * I minuti da [da] a [a] lungo il quadrante, col segno: positivo se [a] viene
 * dopo, negativo se viene prima. Uno scarto oltre la mezza giornata e' la
 * mezzanotte in mezzo, non un treno con dodici ore di anticipo.
 */
fun minutiCircolari(da: LocalTime, a: LocalTime): Int =
    riportaNelGiorno(ChronoUnit.MINUTES.between(da, a).toInt())

/** Lo stesso riporto, per chi i minuti li ha gia' calcolati (ritardo compreso). */
fun riportaNelGiorno(minuti: Int): Int = when {
    minuti > MEZZA_GIORNATA_MIN -> minuti - GIORNATA_MIN
    minuti < -MEZZA_GIORNATA_MIN -> minuti + GIORNATA_MIN
    else -> minuti
}

/**
 * Quanto distano due ore del giorno, senza segno e per la via piu' corta: le
 * 23:58 e le 00:02 distano quattro minuti.
 */
fun secondiCircolari(uno: LocalTime, altro: LocalTime): Long {
    val secondi = abs(Duration.between(uno, altro).seconds)
    return minOf(secondi, GIORNATA_SEC - secondi)
}
