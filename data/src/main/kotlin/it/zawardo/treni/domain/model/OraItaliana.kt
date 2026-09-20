package it.zawardo.treni.domain.model

import it.zawardo.treni.data.mapper.ROME
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime

/*
 * «Adesso» per questa app e' sempre l'ora italiana.
 *
 * Gli orari dei treni sono in ora di Roma, sempre: li scrivono cosi' tutte le
 * fonti, compresa quella svizzera. L'orologio del telefono invece e' quello di
 * chi guarda, e i due coincidono solo finche' si resta in Italia. Da Londra —
 * o con un telefono che ha il fuso sbagliato, come l'emulatore, che nasce in
 * GMT — «adesso» arretrava di un'ora e la ricerca proponeva treni gia' partiti,
 * il tabellone ne nascondeva la prima ora, e a cavallo della mezzanotte il
 * confronto «e' oggi?» cadeva sul giorno sbagliato.
 *
 * Queste tre funzioni sono l'unico «adesso» che il codice deve usare quando il
 * confronto e' con un orario ferroviario. Restano fuori solo le misure di
 * durata (quanto e' passato fra due letture), che con i fusi non c'entrano.
 */

/** Il giorno in Italia. */
fun oggiInItalia(): LocalDate = LocalDate.now(ROME)

/** L'istante in Italia, come data e ora. */
fun adessoInItalia(): LocalDateTime = LocalDateTime.now(ROME)

/** L'ora in Italia, senza la data. */
fun oraInItalia(): LocalTime = LocalTime.now(ROME)

/** L'istante in Italia con la sua zona: serve dove si chiede un tabellone. */
fun istanteInItalia(): ZonedDateTime = ZonedDateTime.now(ROME)
