package it.zawardo.treni.ui.common

import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Come si scrivono ore e date in tutta l'app.
 *
 * Erano costruiti dieci volte in nove file, con quattro nomi diversi per la
 * stessa cosa (`TIME`, `TIME_FMT`, `ORARIO`, `ORA`, `HHMM`). Sembra innocuo
 * finche' uno dei dieci non nasce diverso: `TrainNumberScreen` aveva
 * `ofPattern("d MMM")` **senza lingua**, e su un telefono in inglese la data
 * della corsa usciva «22 Sep» mentre tutto il resto scriveva «lun 22 set».
 *
 * La lingua e' fissata a quella italiana e non segue il telefono, come gia' il
 * fuso: i nomi delle stazioni e le parole dell'app sono in italiano, e una data
 * in un'altra lingua in mezzo si legge come un errore.
 */

/** «14:35». */
val ORA_DEL_GIORNO: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** «lun 22 set», per intestazioni e chip. */
val GIORNO_BREVE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ITALIAN)

/** «22 set», dove il giorno della settimana non serve. */
val GIORNO_CORTO: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ITALIAN)
